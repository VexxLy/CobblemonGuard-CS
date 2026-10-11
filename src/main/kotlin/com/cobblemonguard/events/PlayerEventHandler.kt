package com.cobblemonguard.events

import com.cobblemonguard.CobblemonGuard
import com.cobblemonguard.api.GuardApi
import com.cobblemonguard.database.toBlobMap
import com.cobblemonguard.discord.DiscordWebhook
import com.cobblemonguard.network.CrossServerCoordinator
import com.cobblemonguard.sync.CobblemonStorage
import com.cobblemonguard.sync.DataType
import com.cobblemonguard.sync.EconomySync
import com.cobblemonguard.sync.Resultado
import com.cobblemonguard.sync.SyncService
import com.cobblemonguard.sync.SyncTypes
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Orquesta la sincronización de datos de jugador con MySQL: carga al entrar, guarda al
 * salir / apagar / auto-save, y expone la API para los comandos.
 *
 * La presentación (pantallas de "cargando") vive en [FreezeScreen] y la serialización de
 * cada tipo en [SyncService] — aquí solo va la orquestación y las reglas (guards).
 */
object PlayerEventHandler {

    // Antes: Executors.newCachedThreadPool — crea un hilo NUEVO por tarea si no hay uno
    // libre, SIN TOPE. Bajo alta rotación de jugadores (muchos joins/quits en poco tiempo:
    // reinicio del server con muchos conectados, o un pico de entradas/salidas) esto puede
    // disparar decenas de hilos de golpe. Son daemon y se liberan tras 60s de inactividad,
    // pero la creación/destrucción constante y el overhead de contexto de tantos hilos SÍ
    // se nota como pérdida gradual de TPS con volumen alto de jugadores — el resto del
    // trabajo (DB) ya está acotado por el pool de conexiones MySQL (poolSize); un hilo de
    // más solo espera una conexión libre, no acelera nada.
    //
    // Ahora: pool de tamaño FIJO (con margen para tareas que no tocan MySQL, como el
    // heartbeat de CrossServerCoordinator o la limpieza de backups) y cola ilimitada — el
    // trabajo se ENCOLA en vez de crear hilos sin límite. Mantiene el número de hilos
    // estable bajo carga y nunca descarta una tarea. `allowCoreThreadTimeOut` conserva el
    // mismo comportamiento elástico de antes (los hilos se liberan tras 60s sin uso).
    private val dbExecutor = newPool("DB", (CobblemonGuard.config.poolSize + 4).coerceIn(6, 32))

    /**
     * Pool SEPARADO para el trabajo de JOIN, que es el único que hace esperas largas:
     * [waitForDatabase] (hasta 30s) y [CrossServerCoordinator.awaitRelease] (hasta
     * `crossServerWaitTimeoutMs`, 8s por defecto).
     *
     * Cuando ambas cosas compartían el [dbExecutor] —que es de tamaño fijo y chico, atado
     * a `poolSize`— bastaba un pico de reconexiones (reiniciar el proxy, o un backend
     * volviendo con la BD lenta) para que TODOS los hilos quedaran dormidos esperando, y
     * el resto del trabajo real —guardados de disconnect, backups, heartbeat de locks,
     * refrescos de pasturas— se encolaba detrás de esas esperas.
     *
     * Este pool puede ser bastante más grande sin costar nada: sus hilos están DORMIDOS,
     * no compiten por CPU. El trabajo de MySQL que hagan después sigue estando acotado por
     * el pool de conexiones de Hikari, que es el límite que de verdad importa.
     */
    private val joinExecutor = newPool("Join", (CobblemonGuard.config.poolSize * 2).coerceIn(16, 64))

    private fun newPool(name: String, threads: Int): java.util.concurrent.ThreadPoolExecutor {
        val threadNumber = java.util.concurrent.atomic.AtomicInteger(1)
        return java.util.concurrent.ThreadPoolExecutor(
            threads, threads,
            60L, TimeUnit.SECONDS,
            java.util.concurrent.LinkedBlockingQueue()
        ) { r ->
            Thread(r, "CobblemonGUARD-$name-${threadNumber.getAndIncrement()}").also { it.isDaemon = true }
        }.apply { allowCoreThreadTimeOut(true) }
    }

    /**
     * Permite a otras partes del mod (ej. [com.cobblemonguard.sync.LivePastureSync]) usar
     * el MISMO pool de hilos de BD en vez de crear uno propio — evita volver a multiplicar
     * pools pequeños, que es justo lo que se acaba de consolidar acá arriba.
     */
    fun submitDbTask(task: () -> Unit) {
        dbExecutor.submit { task() }
    }

    // Jugadores esperando carga en el próximo tick
    private val pendingLoad = ConcurrentHashMap.newKeySet<UUID>()

    // Jugadores cuyos datos se cargaron correctamente desde MySQL.
    // SOLO estos son elegibles para guardar — evita sobreescribir datos buenos con vacío.
    private val dataLoaded = ConcurrentHashMap.newKeySet<UUID>()

    /**
     * Unico punto donde un jugador pasa a estar "cargado".
     *
     * Antes esto era un `dataLoaded.add(uuid)` suelto repetido en cuatro lugares. Se
     * centralizo para poder avisarle a los otros mods de la red (via [GuardApi]) en el
     * mismo instante: cobblemmo-server necesita saber cuando el equipo/PC del jugador ya
     * son los de MySQL y no los del disco local de este backend, y antes solo podia
     * adivinarlo con temporizadores.
     */
    private fun markLoaded(player: ServerPlayerEntity) {
        dataLoaded.add(player.uuid)
        GuardApi.fireDataLoaded(player)
    }

    /** Para [GuardApi.isPlayerDataLoaded]. */
    fun isDataLoaded(uuid: UUID): Boolean = uuid in dataLoaded

    private val autoSaveTicks = ConcurrentHashMap<UUID, Int>()
    private val backupTicks = ConcurrentHashMap<UUID, Int>()

    /**
     * Arranca los contadores de auto-save y backup de un jugador en un punto ALEATORIO
     * dentro de su intervalo, en vez de en cero.
     *
     * Sin esto, todos los que entran juntos quedan sincronizados: tras un reinicio, si 40
     * jugadores entran en el mismo minuto, sus backups caen todos en el MISMO segundo, y
     * eso son 40 serializaciones completas (PC entera + escaneo del NBT de las mochilas +
     * accesorios + economía) en un solo tick del hilo principal. Un stutter garantizado,
     * cada 5 minutos, para siempre. Repartiéndolos, el trabajo queda distribuido a lo
     * largo del intervalo.
     */
    private fun seedSchedules(uuid: UUID) {
        val backupInterval = CobblemonGuard.config.backupIntervalSeconds
        if (backupInterval > 1) {
            backupTicks[uuid] = java.util.concurrent.ThreadLocalRandom.current().nextInt(backupInterval)
        }
        val autoSaveInterval = CobblemonGuard.config.autoSaveIntervalSeconds
        if (autoSaveInterval > 1) {
            autoSaveTicks[uuid] = java.util.concurrent.ThreadLocalRandom.current().nextInt(autoSaveInterval)
        }
    }

    // CADENA de guardados por UUID. Cada guardado nuevo de un jugador se encola DESPUÉS
    // del anterior, y el mapa guarda siempre el último eslabón.
    //
    // Resuelve dos carreras distintas:
    //
    // 1) save → load (reconexión rápida). El LOAD del rejoin espera a que el SAVE anterior
    //    termine antes de leer de MySQL. Sin esto, el SELECT puede correr en otro hilo y
    //    ganarle al UPDATE, devolviendo el estado ANTERIOR a la última sesión — lo que se
    //    percibe como "se pierden o se duplican ítems" al entrar y salir varias veces:
    //    la reconexión restaura un snapshot viejo ENCIMA de un mundo que ya cambió.
    //
    // 2) save → save (lo que ANTES no estaba cubierto). Dos guardados del mismo jugador
    //    encolados con poca diferencia —auto-save + disconnect, o `/cg save all` seguido
    //    de una desconexión— corrían en hilos distintos del pool, y nada garantizaba en
    //    qué orden llegaba cada UPSERT a MySQL: el más VIEJO podía ganar y pisar al más
    //    nuevo. Encadenándolos, el orden de escritura es siempre el de encolado.
    private val saveChains = ConcurrentHashMap<UUID, CompletableFuture<Void>>()
    private const val PENDING_SAVE_WAIT_MS = 15_000L

    /**
     * Encola [task] como el siguiente eslabón de la cadena de guardados de [uuid].
     * @return el future de ESTE guardado (completa cuando la tarea terminó).
     */
    private fun chainSave(uuid: UUID, task: () -> Unit): CompletableFuture<Void> {
        val chained = saveChains.compute(uuid) { _, previous ->
            val base: CompletableFuture<Void> = previous ?: CompletableFuture.completedFuture(null)
            // handle() antes de encadenar: si el guardado anterior falló, el siguiente debe
            // ejecutarse igual (una excepción propagada cancelaría toda la cadena).
            base.handle<Void?> { _, _ -> null }.thenRunAsync(Runnable { task() }, dbExecutor)
        }!!
        // Limpieza: solo si seguimos siendo el último eslabón (si ya se encoló otro
        // guardado encima, el mapa debe conservar ESE).
        chained.whenComplete { _, _ -> saveChains.remove(uuid, chained) }
        return chained
    }

    // Tiempo máximo que esperamos (congelado) a que la BD conecte antes de expulsar.
    private const val DB_WAIT_MS = 30_000L

    /**
     * Prórroga al apagar, después del timeout general, antes de cerrar el pool de MySQL.
     *
     * Existe porque cerrar la BD con guardados en vuelo les corta la conexión y esos
     * jugadores pierden la sesión. "No confirmó a tiempo" casi siempre quiere decir "la base
     * está lenta", no "está colgado": diez segundos más suelen alcanzar.
     */
    private const val GRACIA_CIERRE_MS = 10_000L

    /**
     * Guardado "serio" activo. Cuando es false, el mod NO escribe en la BD (útil durante
     * transferencia/testeo). La CARGA sí sigue. Se controla con /cg saving start|stop.
     */
    @Volatile
    var savingEnabled: Boolean = true

    fun register() {
        savingEnabled = CobblemonGuard.config.savingEnabled
        if (!savingEnabled) {
            CobblemonGuard.logger.warn("[SAVE] Guardado PAUSADO por config (savingEnabled=false). Actívalo con /cg saving start cuando estés listo.")
        }
        registerJoin()
        registerDisconnect()
        registerServerStop()
        // Lo que un jugador congelado no puede hacer mientras espera sus datos (interactuar,
        // romper, soltar ítems, recibir daño). Solo actúa sobre congelados: con
        // syncOnJoinLeave=false nadie se congela y esto no hace nada.
        FreezeGuard.register()
        registerTickForDelayedLoad()
        if (CobblemonGuard.config.syncOnJoinLeave && CobblemonGuard.config.autoSaveIntervalSeconds > 0) registerAutoSave()
        if (CobblemonGuard.config.backupEnabled) registerBackupScheduler()
        if (CobblemonGuard.config.crossServerSyncEnabled) registerCrossServerHeartbeat()

        if (!CobblemonGuard.config.syncOnJoinLeave) {
            CobblemonGuard.logger.info(
                "[MODO] Sincronización al entrar/salir DESACTIVADA (syncOnJoinLeave=false). " +
                "Minecraft maneja el guardado normal. Activos: BACKUPS + comandos manuales (save/restore/rollback/backup)."
            )
        }
        CobblemonGuard.logger.info("Eventos de jugador registrados.")
    }

    // ══════════════════════════════════════════════════════
    // Persistencia (mapa de tipos → columnas, en un solo lugar)
    // ══════════════════════════════════════════════════════

    /**
     * Vigilancia de la serialización de Pokémon: dos alarmas, porque hay dos formas de romperse.
     *
     * Uno suelto es un hipo. Varios seguidos significa que algo dejó de funcionar, y desde ese
     * momento esos Pokémon no se guardan. El COALESCE del repositorio evita que eso vacíe la
     * base, pero los datos quedan congelados en silencio, y "congelado" tarda días en notarse.
     *
     * - **Global** — falla para TODOS: la reflexión contra Cobblemon se rompió en una versión
     *   nueva (un nombre de clase o de método cambió). Se detecta con fallos seguidos de
     *   cualquier jugador.
     * - **Por jugador** — falla para ALGUNOS: un Pokémon con una forma o un dato raro que hace
     *   explotar la serialización de esa PC en particular.
     *
     * Antes había un único contador global y cualquier guardado bueno lo ponía en cero. Con el
     * segundo caso eso significaba que la alarma no sonaba NUNCA: alcanzaba con que los demás
     * jugadores siguieran guardando bien para tapar al que perdía todo.
     */
    private const val FALLOS_GLOBALES_PARA_ALERTAR = 5
    private const val FALLOS_POR_JUGADOR_PARA_ALERTAR = 3

    private val fallosPokemonSeguidos = java.util.concurrent.atomic.AtomicInteger(0)

    /** Solo contiene jugadores con fallos pendientes; un guardado bueno los saca. */
    private val fallosPokemonPorJugador = ConcurrentHashMap<UUID, Int>()

    private fun vigilarSerializacionPokemon(uuid: UUID, name: String, blob: Map<DataType, ByteArray?>) {
        val cfg = CobblemonGuard.config
        val esperaPc = cfg.syncCobblemonPC
        val esperaParty = cfg.syncCobblemonParty
        if (!esperaPc && !esperaParty) return

        // Sin Cobblemon instalado en ESTE backend (un lobby, un servidor de minijuegos), los
        // serializadores devuelven null SIEMPRE y con razon. Sin este chequeo la alarma saltaba
        // en todos esos servidores, y una alarma que grita cuando todo esta bien es una alarma
        // que se ignora el dia que grita de verdad.
        if (!CobblemonStorage.isLoaded) return

        val faltaPc = esperaPc && blob[DataType.PC] == null
        val faltaParty = esperaParty && blob[DataType.PARTY] == null
        if (!faltaPc && !faltaParty) {
            fallosPokemonSeguidos.set(0)
            fallosPokemonPorJugador.remove(uuid)
            return
        }
        val que = if (faltaPc && faltaParty) "PC ni equipo" else if (faltaPc) "la PC" else "el equipo"

        // Modulo y no igualdad: con "== N" la alarma sonaba UNA vez en toda la vida del proceso,
        // y si nadie miraba la consola en ese instante se perdia entre miles de lineas.
        val global = fallosPokemonSeguidos.incrementAndGet()
        if (global % FALLOS_GLOBALES_PARA_ALERTAR == 0) {
            CobblemonGuard.logger.error(
                "[GUARD] $global guardados seguidos sin poder serializar $que (ultimo: $name). Es casi " +
                "seguro que la reflexion contra Cobblemon se rompio en esta version. Los datos NO se estan " +
                "borrando (las columnas se conservan), pero TAMPOCO se estan actualizando: todo cambio de " +
                "Pokemon se pierde al salir. REVISAR CobblemonStorage."
            )
        }

        val propio = fallosPokemonPorJugador.merge(uuid, 1, Int::plus)!!
        if (propio % FALLOS_POR_JUGADOR_PARA_ALERTAR == 0) {
            CobblemonGuard.logger.error(
                "[GUARD] $name lleva $propio guardados seguidos sin poder serializar $que. Sus cambios de " +
                "Pokemon NO se estan guardando: su ultimo estado bueno en MySQL es el de antes de que empezara " +
                "a fallar. Si es solo el, lo mas probable es un Pokemon con datos que Cobblemon no puede " +
                "escribir: busca el error de [PC]/[Party] de $name mas arriba. Si les pasa a todos, mira la " +
                "alarma global."
            )
        }
    }

    private fun persist(uuid: UUID, name: String, blob: Map<DataType, ByteArray?>) {
        vigilarSerializacionPokemon(uuid, name, blob)
        escribir(uuid, name, blob)
    }

    /**
     * Esperas entre reintentos de un guardado que falló: 2 + 4 + 8 + 15 + 30 s, casi un minuto de
     * espera. A eso se suma lo que tarde cada intento en fallar: con MySQL colgado (no caído), cada
     * uno puede llegar al timeout de socket del pool, así que en el peor caso el jugador queda
     * reteniendo su lock varios minutos. Cubre lo que de verdad pasa en producción —una conexión
     * cortada, un deadlock de InnoDB, un MySQL que se reinicia— sin dejarlo trabado para siempre.
     */
    private val ESPERAS_REINTENTO_MS = longArrayOf(2_000, 4_000, 8_000, 15_000, 30_000)

    /** true desde que arranca el apagado: no tiene sentido seguir reintentando contra una BD que se va a cerrar. */
    @Volatile
    private var apagando = false

    /**
     * Se libera al arrancar el apagado. Los reintentos esperan sobre esto en vez de con
     * Thread.sleep: así el apagado los DESPIERTA. Antes solo se miraba [apagando] después de un
     * intento fallido, y un hilo durmiendo sus 30 s seguía durmiendo; con el pool lleno de esos,
     * los guardados de apagado de los jugadores conectados quedaban en la cola detrás, y la BD
     * se cerraba sin haberlos hecho.
     */
    private val finDeEsperas = java.util.concurrent.CountDownLatch(1)

    /** Un guardado que se decidió NO terminar (no un error de la base). */
    private class GuardadoAbandonado(message: String) : Exception(message)

    /**
     * Como [persist], pero si falla lo vuelve a intentar con el MISMO blob antes de rendirse.
     *
     * Antes un solo error de red era una sesión perdida: el guardado fallaba, el lock se
     * quedaba tomado y el siguiente backend terminaba cargando el último snapshot bueno, sin
     * lo que el jugador hizo en esa sesión.
     *
     * Reintentar acá es seguro por tres motivos:
     * - El UPSERT es idempotente: si el intento "fallido" en realidad llegó a escribirse y lo
     *   que se perdió fue la respuesta, repetirlo escribe lo mismo.
     * - Corre dentro de la cadena del jugador ([chainSave]), así que ningún guardado más nuevo
     *   suyo puede adelantarse y quedar pisado por este más viejo.
     * - Mientras reintenta, la cadena sigue en [saveChains], y el heartbeat le renueva el lock
     *   a todo UUID con un guardado en vuelo. Ningún otro backend lo carga hasta que esto
     *   termine, para bien o para mal.
     *
     * Duerme el hilo del pool entre intentos. Es deliberado: un reintento programado sin
     * bloquear obligaría a reescribir la cadena entera con futures compuestos, y eso fue lo que
     * más errores introdujo cada vez que se tocó. El heartbeat corre en su propio hilo
     * justamente para que estos hilos dormidos no puedan dejar caducar ningún lock.
     *
     * @throws Exception el último error, si se agotaron los reintentos o el servidor se apaga.
     */
    private fun persistConReintentos(
        uuid: UUID, name: String, blob: Map<DataType, ByteArray?>, verificarDueño: Boolean
    ) {
        vigilarSerializacionPokemon(uuid, name, blob)
        var intento = 0
        while (true) {
            try {
                escribir(uuid, name, blob)
                if (intento > 0) CobblemonGuard.logger.info("[SAVE] $name guardado al reintento $intento.")
                return
            } catch (e: Exception) {
                if (intento >= ESPERAS_REINTENTO_MS.size || apagando) throw e
                val espera = ESPERAS_REINTENTO_MS[intento]
                intento++
                CobblemonGuard.logger.warn(
                    "[SAVE] Falló el guardado de $name (${e.message}). " +
                    "Reintento $intento/${ESPERAS_REINTENTO_MS.size} en ${espera / 1000}s."
                )
                val despertadoPorApagado = try {
                    finDeEsperas.await(espera, TimeUnit.MILLISECONDS)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
                // El jugador ya se fue de acá: si mientras esperábamos otro backend tomó su lock,
                // ese otro ya lo cargó y puede haber guardado algo MÁS NUEVO. Escribir encima este
                // blob viejo le haría retroceder lo que hizo allá.
                if (verificarDueño && CrossServerCoordinator.otroEsDueño(uuid)) {
                    throw GuardadoAbandonado("otro backend ya tomó a $name mientras se reintentaba; no se escribe encima de su guardado")
                }
                if (despertadoPorApagado) {
                    // La BD sigue abierta unos segundos más. Un último intento, sin más esperas:
                    // para un jugador que YA se fue no hay guardado de apagado que lo cubra, así
                    // que rendirse sin intentarlo es perder su sesión seguro. Si este también
                    // falla, la excepción sale como cualquier fallo de guardado (con su alerta).
                    escribir(uuid, name, blob)
                    CobblemonGuard.logger.info("[SAVE] $name guardado en el último intento antes del apagado.")
                    return
                }
            }
        }
    }

    private fun escribir(uuid: UUID, name: String, blob: Map<DataType, ByteArray?>) {
        CobblemonGuard.repository.savePlayerData(
            uuid, name,
            blob[DataType.INVENTORY], blob[DataType.ENDERCHEST], blob[DataType.PC],
            blob[DataType.PARTY], blob[DataType.ACCESSORIES], blob[DataType.BACKPACKS],
            blob[DataType.ECONOMY]
        )
    }

    /** @return true si realmente se insertó una fila (false = sin cambios, ver dedup). */
    private fun persistBackup(uuid: UUID, name: String, blob: Map<DataType, ByteArray?>): Boolean {
        return CobblemonGuard.backupRepository.saveBackup(
            uuid, name,
            blob[DataType.INVENTORY], blob[DataType.ENDERCHEST], blob[DataType.PC],
            blob[DataType.PARTY], blob[DataType.ACCESSORIES], blob[DataType.BACKPACKS],
            blob[DataType.ECONOMY]
        )
    }

    // ══════════════════════════════════════════════════════
    // JOIN: congelar al instante y cargar 1 tick después
    // ══════════════════════════════════════════════════════

    private fun registerJoin() {
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player
            if (!CobblemonGuard.config.syncOnJoinLeave) {
                // Modo solo-backups: NO cargamos nada (lo maneja Minecraft). Solo marcamos
                // al jugador como elegible para que los backups y comandos manuales funcionen.
                markLoaded(player)
                return@register
            }
            // Congelamos INMEDIATAMENTE, antes de que pueda moverse.
            FreezeScreen.freeze(player)
            FreezeScreen.connecting(player)
            pendingLoad.add(player.uuid)
        }
    }

    // Cola de trabajo REPARTIDO entre ticks (ver [enqueueStaggered]).
    private val staggerQueue = java.util.concurrent.ConcurrentLinkedQueue<() -> Unit>()
    private const val STAGGER_PER_TICK = 2

    /**
     * Encola trabajo pesado del hilo del servidor para repartirlo entre ticks en vez de
     * hacerlo todo de golpe.
     *
     * Lo usan `/cg save all` y `/cg backup all`: recorrer la playerlist entera serializando
     * a cada jugador (PC completa + mochilas + accesorios + economía) dentro de un mismo
     * tick es un stutter garantizado en un servidor con muchos jugadores. A
     * [STAGGER_PER_TICK] por tick, 40 jugadores se reparten en un segundo y no se nota.
     */
    fun enqueueStaggered(task: () -> Unit) {
        staggerQueue.add(task)
    }

    // La carga se hace 1 tick después del JOIN para que Cobblemon cargue su PC del disco primero.
    private fun registerTickForDelayedLoad() {
        ServerTickEvents.END_SERVER_TICK.register { server ->
            // Trabajo repartido entre ticks (save all / backup all).
            var processed = 0
            while (processed < STAGGER_PER_TICK) {
                val task = staggerQueue.poll() ?: break
                runCatching { task() }
                    .onFailure { CobblemonGuard.logger.error("[Tick] Error en tarea repartida: ${it.message}") }
                processed++
            }

            if (pendingLoad.isEmpty()) return@register
            val toLoad = pendingLoad.toSet()
            pendingLoad.clear()
            for (uuid in toLoad) {
                val player = server.playerManager.getPlayer(uuid) ?: continue
                loadPlayerDataAsync(player)
            }
        }
    }

    /** Espera (en el hilo de BD, no el del servidor) a que la BD conecte, hasta [DB_WAIT_MS]. */
    /**
     * Saca al jugador del servidor sin tocar su data.
     *
     * Es la respuesta correcta a todo fallo de carga, y el motivo es que la alternativa —
     * dejarlo jugar— es una vía de duplicación directa: cuando la carga no termina, el jugador
     * NO queda sin nada, queda con el `playerdata` LOCAL de este backend (Minecraft y Cobblemon
     * ya lo cargaron del disco antes de que este mod intervenga). O sea que tiene una copia
     * real y jugable de su inventario y sus Pokémon. Y como `markLoaded` no se llamó, ninguno
     * de sus guardados se escribe: puede tirar todo en un cofre, venderlo o regalarlo, y nada
     * de eso llega a MySQL. Cuando entra al otro backend, ese backend le aplica el blob de
     * MySQL y los mismos objetos existen dos veces.
     *
     * Expulsarlo es molesto por diez segundos. Lo otro arruina la economía del servidor.
     */
    private fun expulsar(player: ServerPlayerEntity, titulo: String, detalle: String) {
        // Se corre INLINE si ya estamos en el hilo del servidor, como hace GuardApi.dispatch.
        // Con execute() a secas el kick no ocurria ahi mismo: MinecraftServer encola las
        // tareas anidadas (shouldExecuteAsync mira runningTasks), asi que llamar a expulsar()
        // desde dentro de otro server.execute lo dejaba para mas adelante en el drenaje de la
        // cola. Funcionaba, salvo en el caso que importa: si el tick loop ya se detuvo
        // (apagado en curso) esa tarea no corre nunca y el jugador no se expulsa.
        val trabajo = Runnable {
            // El freeze NO se saca acá. disconnect() solo cierra el canal; al jugador lo saca del
            // mundo recién el tick siguiente, cuando la red procesa el cierre y dispara DISCONNECT
            // —y ese evento ya llama a FreezeScreen.clear()—. Si se lo descongelaba acá, en ese
            // tick quedaba en el mundo sin FreezeGuard: los paquetes que ya tenía en cola (soltar
            // con Q) tiraban ítems de verdad, y un mob o la lava lo podían matar y hacerle soltar
            // todo el inventario LOCAL, mientras MySQL todavía tiene esas mismas cosas.
            if (player.isRemoved) {
                // No es un caso raro: el jugador pudo irse solo mientras esperabamos a la BD
                // o al otro backend. Se deja constancia para no buscar un kick que nunca ocurrio.
                //
                // Y NO se toca el freeze: su DISCONNECT ya lo limpió. Y como el set de congelados
                // es por UUID, limpiar acá podía descongelar a su sesión NUEVA si volvió a entrar
                // mientras esta tarea vieja esperaba —dejándola sin FreezeGuard en plena carga—.
                CobblemonGuard.logger.info("[JOIN] ${player.gameProfile.name} ya se habia desconectado; no hace falta expulsarlo.")
                return@Runnable
            }
            player.networkHandler.disconnect(
                Text.literal("§e[CobblemonGUARD]\n\n")
                    .append(Text.literal("$titulo\n").formatted(Formatting.WHITE))
                    .append(Text.literal("$detalle\n").formatted(Formatting.GRAY))
                    .append(Text.literal("(Tus datos están a salvo — no se guardó nada.)").formatted(Formatting.GRAY))
            )
        }
        if (player.server.isOnThread) trabajo.run() else player.server.execute(trabajo)
    }

    private fun waitForDatabase(): Boolean {
        if (CobblemonGuard.database.isConnected()) return true
        var waited = 0L
        while (waited < DB_WAIT_MS) {
            Thread.sleep(500)
            waited += 500
            if (CobblemonGuard.database.isConnected()) return true
        }
        return CobblemonGuard.database.isConnected()
    }

    private fun loadPlayerDataAsync(player: ServerPlayerEntity) {
        val uuid = player.uuid
        val name = player.name.string

        // joinExecutor, NO dbExecutor: acá abajo hay esperas de hasta 30s (BD) + 8s
        // (lock cross-server) y no deben ocupar los hilos que hacen el trabajo real.
        joinExecutor.submit {
            try {
                // Si hay un SAVE en vuelo para este UUID (típicamente el disconnect de la
                // sesión anterior, en una reconexión rápida), lo esperamos ANTES de tocar
                // la BD. Sin esto, este LOAD podría ejecutarse en un hilo distinto y leer
                // MySQL antes de que el UPDATE anterior termine de commitear.
                saveChains[uuid]?.let { previousSave ->
                    CobblemonGuard.logger.debug("[JOIN] $name: esperando guardado anterior en vuelo antes de cargar...")
                    // Solo un TIMEOUT significa "todavía se está guardando". Si el guardado
                    // anterior terminó con error, ya no hay nada que esperar: la fila queda como
                    // quedó y cargarla es lo mejor que se puede hacer.
                    val sigueGuardando = try {
                        previousSave.get(PENDING_SAVE_WAIT_MS, TimeUnit.MILLISECONDS)
                        false
                    } catch (_: java.util.concurrent.TimeoutException) {
                        true
                    } catch (_: Exception) {
                        false
                    }
                    if (sigueGuardando) {
                        // Antes se seguía con la carga "de todas formas". Pero si el guardado de
                        // su sesión anterior todavía no llegó a MySQL, lo que hay en la fila es el
                        // estado de ANTES de esa sesión: cargarlo es devolverle cosas que ya gastó,
                        // y con crossServerSync el merge de la PC borra los Pokémon que consiguió
                        // después. Y ahora que los guardados reintentan hasta casi un minuto, este
                        // caso deja de ser raro justo cuando la BD anda mal.
                        expulsar(player,
                            "§fTu sesión anterior todavía se está guardando.",
                            "§7Vuelve a entrar en unos segundos.")
                        CobblemonGuard.logger.warn(
                            "[JOIN] $name expulsado: su guardado anterior sigue en curso tras " +
                            "${PENDING_SAVE_WAIT_MS / 1000}s. No se carga una fila que todavía no lo refleja."
                        )
                        return@submit
                    }
                }

                // Esperamos (congelado) a que la BD conecte. Si no, EXPULSAMOS en vez de
                // dejarlo jugar sin datos (evita perder loot al guardar vacío).
                if (!waitForDatabase()) {
                    // expulsar() y no un disconnect a mano: además de sacarlo, le saca el freeze y
                    // le devuelve al inventario lo que tenga en el cursor o la grilla de crafteo,
                    // que si no vanilla tira al piso al desconectarlo.
                    expulsar(player,
                        "§fLa base de datos aún no está disponible.",
                        "§7Vuelve a entrar en unos segundos.")
                    CobblemonGuard.logger.warn("[JOIN] $name expulsado: BD no disponible tras ${DB_WAIT_MS / 1000}s. No se toca su data.")
                    return@submit
                }

                // Cross-server: si el jugador viene de OTRO backend, esperamos (congelado,
                // en este hilo del joinExecutor — no bloquea el servidor ni los guardados) a
                // que ese backend confirme que terminó de guardarlo antes de tocar su data.
                // Evita cargar mientras sigue "vivo" en el otro proceso (dupeo). No-op si
                // está desactivado.
                //
                // Y se MIRA el resultado. Antes se descartaba: el método devuelve false cuando
                // vence el timeout y se cargaba igual, que es justo lo que no hay que hacer. El
                // otro backend puede seguir escribiendo, asi que ese snapshot es viejo por
                // definicion — y como con crossServerSync el merge de la PC borra todo Pokemon
                // vivo que no este en el snapshot, aplicar uno viejo no solo trae datos
                // atrasados: BORRA lo que el jugador consiguio despues. Al salir, esa perdida
                // se escribe a MySQL y ya no se recupera.
                if (!CrossServerCoordinator.awaitRelease(uuid, name)) {
                    expulsar(player,
                        "§fOtro servidor todavía está guardando tus datos.",
                        "§7Vuelve a entrar en unos segundos.")
                    CobblemonGuard.logger.warn(
                        "[JOIN] $name expulsado: el otro backend no confirmó el guardado a tiempo. " +
                        "No se carga un snapshot que puede estar viejo. Si se repite en cada intento, " +
                        "el guardado de ese backend falló y su lock quedó colgado: mira los [SAVE] de ese " +
                        "servidor y, si hace falta que entre ya, /cg unlock $name."
                    )
                    return@submit
                }

                val data = CobblemonGuard.repository.loadPlayerData(uuid, name)

                if (data == null) {
                    // ── PRIMER INGRESO: guardamos su estado inicial ──
                    player.server.execute {
                        if (player.isRemoved) return@execute
                        // Mismo motivo que en el reingreso: este bloque corre fuera del alcance
                        // del catch de abajo. Si serialize() tira aca, el jugador nuevo se queda
                        // congelado y sin fila en MySQL, y nada lo expulsa ni lo registra.
                        val blob = try {
                            FreezeScreen.registering(player)
                            SyncService.serialize(player)   // en el hilo del servidor
                        } catch (e: Exception) {
                            CobblemonGuard.logger.error("[JOIN] Error serializando el perfil inicial de $name: ${e.message}", e)
                            expulsar(player,
                                "§fNo se pudo crear tu perfil.",
                                "§7Vuelve a entrar en unos segundos. Si sigue pasando, avisa a un admin.")
                            dbExecutor.submit {
                                runCatching { CobblemonGuard.repository.logSync(uuid, name, "SAVE", false, e.message) }
                            }
                            return@execute
                        }
                        // Por chainSave, como TODA otra escritura de este UUID. Antes iba
                        // directo a dbExecutor: era la unica escritura del mod que podia correr
                        // en paralelo con otra del mismo jugador.
                        chainSave(uuid) {
                            // Y respeta savingEnabled: con /cg saving stop puesto para una
                            // migracion, un jugador nuevo escribia su fila igual.
                            if (!savingEnabled) {
                                CobblemonGuard.logger.warn(
                                    "[JOIN] Primer ingreso de $name: guardado PAUSADO (/cg saving), no se crea su fila."
                                )
                            } else {
                                try {
                                    persist(uuid, name, blob)
                                    CobblemonGuard.repository.logSync(uuid, name, "SAVE", true, null)
                                    CobblemonGuard.logger.info("[JOIN] Primer ingreso de $name — perfil guardado en MySQL.")
                                    DiscordWebhook.sendFirstJoin(name, uuid.toString())
                                } catch (e: Exception) {
                                    CobblemonGuard.logger.error("[JOIN] Error guardando perfil inicial de $name: ${e.message}")
                                    CobblemonGuard.repository.logSync(uuid, name, "SAVE", false, e.message)
                                }
                            }
                            // markLoaded SOLO si el jugador sigue conectado. Sin este chequeo, un
                            // jugador que se iba mientras corria el persist quedaba metido en
                            // dataLoaded PARA SIEMPRE (el DISCONNECT ya lo habia sacado, y esta
                            // linea lo volvia a agregar). En su siguiente sesion eso desarmaba el
                            // guard que impide guardar cuando la carga fallo: escribia el estado
                            // del disco local encima de MySQL.
                            if (player.isRemoved) {
                                CobblemonGuard.logger.warn("[JOIN] $name se fue durante su primer ingreso — no se marca como cargado.")
                                return@chainSave
                            }
                            markLoaded(player)   // a partir de ahora SÍ se guarda
                            seedSchedules(uuid)
                            CrossServerCoordinator.claim(uuid)   // ya estamos en el hilo de BD
                            player.server.execute {
                                if (!player.isRemoved) {
                                    FreezeScreen.unfreeze(player)
                                    FreezeScreen.welcome(player)
                                }
                            }
                        }
                    }
                } else {
                    // ── REINGRESO: aplicamos los datos de MySQL ──
                    player.server.execute {
                        if (player.isRemoved) return@execute
                        // Try/catch PROPIO. El catch grande del joinExecutor no cubre nada de
                        // lo que pasa aca adentro: esto corre despues, en el hilo del servidor.
                        // Y aca adentro es justo donde se APLICAN los datos, o sea el paso con
                        // mas chance de fallar (toda la lectura de Cobblemon es por reflexion).
                        // Si SyncService.apply tiraba, la excepcion se iba al tick del servidor:
                        // el jugador quedaba congelado para siempre, sin markLoaded y sin que
                        // nadie lo expulsara, con el playerdata local de este backend intacto.
                        try {
                        FreezeScreen.loading(player)
                        // preserveLivePokemon: en single-server SÍ se preserva (true) — no pisar
                        // pokémon vivos que Cobblemon ya cargó de SU disco local (evita fantasmas
                        // cuando MySQL es solo un respaldo/migración, no la fuente de verdad).
                        //
                        // En redes cross-server (crossServerSyncEnabled) esto NO puede ser true:
                        // cada backend tiene su PROPIO disco local independiente, así que "ya tiene
                        // pokémon" NO significa "está al día" — solo significa que el jugador (o una
                        // pastura) tocó la PC/party de ESTE backend antes. Con onlyIfEmpty=true,
                        // MySQL solo se aplicaba la PRIMERA vez que el jugador pisaba cada backend;
                        // desde la segunda vez en adelante se ignoraba por completo, dejando que
                        // cada backend siguiera con su copia local (potencialmente vieja frente al
                        // backend donde jugó por última vez, o frente a una pastura que le modificó
                        // la PC en ESTE backend mientras estaba desconectado/jugando en otro lado —
                        // exactamente el escenario de "pastures" que causa desyncs). Por eso en
                        // cross-server SIEMPRE se aplica MySQL (fuente de verdad, garantizada fresca
                        // por el lock de CrossServerCoordinator.awaitRelease ya esperado arriba): la
                        // mezcla (CobblemonStorage.mezclarPC / mezclarParty) da de alta y de baja por
                        // UUID, y a los Pokémon que ya estaban les copia el CONTENIDO de la BD sobre el
                        // mismo objeto, sin crear duplicados. blobHasPokemon sigue protegiendo contra
                        // pisar con un snapshot vacío.
                        val preserveLivePokemon = !CobblemonGuard.config.crossServerSyncEnabled
                        val resultados = SyncService.apply(player, data.toBlobMap(), SyncTypes.ALL, preserveLivePokemon = preserveLivePokemon)
                        // Lo que haya en el cursor o en la grilla 2x2 salió del inventario LOCAL
                        // durante la ventana de carga, y readNbt no lo toca. Si el inventario de
                        // MySQL se aplicó, esas copias sobran y se descartan (en el mismo tick: no
                        // hay nada en el medio). Si NO se aplicó, el inventario sigue siendo el local
                        // y descartarlas sería perderlas.
                        if (resultados[DataType.INVENTORY] == Resultado.APLICADO) {
                            FreezeScreen.descartarItemsSueltos(player)
                        }

                        // Si algun tipo habilitado tenia datos y NO se pudo aplicar, el jugador no
                        // tiene sus datos de MySQL: tiene los locales de este backend (la PC vieja
                        // del disco, el inventario de la ultima vez que paso por aca). Dejarlo jugar
                        // asi y despues guardarlo es escribir ese estado viejo encima del bueno, y
                        // si en el medio vende o intercambia algo, es duplicarlo.
                        //
                        // Se lo expulsa SIN markLoaded, asi que su desconexion no escribe nada y
                        // MySQL queda intacta. Esto es deliberadamente estricto: si un mod cambia su
                        // API y el tipo falla para todos, el backend deja de dejar entrar gente. Es
                        // ruidoso a proposito — un servidor que no deja entrar se nota en un minuto;
                        // uno que pisa PCs en silencio se nota cuando ya no hay backup que alcance.
                        val fallidos = SyncService.fallidos(resultados)
                        if (fallidos.isNotEmpty()) {
                            val lista = fallidos.joinToString(", ")
                            val tipoEjemplo = when (fallidos.first()) {
                                DataType.INVENTORY -> "inv"; DataType.ENDERCHEST -> "ender"; DataType.PC -> "pc"
                                DataType.PARTY -> "party"; DataType.ACCESSORIES -> "acc"; DataType.BACKPACKS -> "bp"
                                DataType.ECONOMY -> "eco"
                            }
                            dbExecutor.submit {
                                runCatching { CobblemonGuard.repository.logSync(uuid, name, "LOAD", false, "No se pudo aplicar: $lista") }
                                runCatching { DiscordWebhook.sendSyncError(name, "LOAD", "No se pudo aplicar: $lista") }
                            }
                            if (CobblemonGuard.config.expulsarSiFallaAlCargarEffective) {
                                // /cg rollback y /cg restore NO sirven acá: los dos necesitan al
                                // jugador conectado y cargado, y a este se lo expulsa ahora mismo.
                                CobblemonGuard.logger.error(
                                    "[JOIN] $name: no se pudo aplicar $lista desde MySQL. No se marca como cargado " +
                                    "(no se va a guardar nada suyo) y se lo expulsa. Si le pasa SOLO a el, lo mas " +
                                    "probable es un dato suyo dañado: /cg restaurarfila $name <tiempo> $tipoEjemplo " +
                                    "copia ese dato desde un backup anterior al daño (sin que tenga que estar conectado). " +
                                    "Solo si no hay ningun backup bueno, /cg limpiar como ultimo recurso. Si les " +
                                    "pasa a TODOS con el mismo tipo, casi seguro cambio la version del mod de ese tipo " +
                                    "(revisa el log de arriba); para dejarlos entrar mientras tanto existe " +
                                    "expulsarSiFallaAlCargar=false en la config, leyendo antes lo que implica."
                                )
                                expulsar(player,
                                    "§fNo se pudieron cargar todos tus datos.",
                                    "§7Si se repite, avisa a un admin.")
                                return@execute
                            }
                            CobblemonGuard.logger.error(
                                "[JOIN] $name: no se pudo aplicar $lista desde MySQL, pero entra igual porque " +
                                "expulsarSiFallaAlCargar=false. Para $lista juega con los datos LOCALES de este " +
                                "servidor, y su proximo guardado puede escribirlos encima de los de MySQL."
                            )
                        }

                        markLoaded(player)
                        seedSchedules(uuid)
                        FreezeScreen.unfreeze(player)
                        FreezeScreen.restored(player)
                        CobblemonGuard.logger.info("[JOIN] Datos restaurados para $name desde MySQL.")

                        // claim() abre una conexión a Redis y hace un SET por RED. Antes se
                        // llamaba acá mismo, es decir EN EL HILO DEL SERVIDOR, violando lo
                        // que documenta el propio CrossServerCoordinator ("todas las
                        // llamadas de red (Jedis) deben hacerse FUERA del hilo del
                        // servidor"). Con Redis local son sub-milisegundos y no se notaba;
                        // con Redis en otra máquina, bajo carga, o durante un BGSAVE, cada
                        // join congelaba el servidor entero lo que tardara ese round-trip.
                        // El path de PRIMER ingreso siempre lo hizo bien; solo el reingreso
                        // —o sea, prácticamente todos los joins— lo hacía mal.
                        //
                        // El logSync del LOAD también se movió acá adentro: antes se
                        // escribía antes de que los datos se hubieran aplicado, así que si
                        // el apply fallaba el log igual decía que el LOAD había salido bien.
                        //
                        // El claim va al hilo de Redis y no al dbExecutor: con MySQL lento, el pool de
                        // BD se llena de guardados que reintentan, y un claim encolado detrás dejaba al
                        // jugador jugando acá SIN lock. Si en ese rato se pasaba a otro backend, ese
                        // otro no veía lock, no esperaba, y cargaba MySQL antes de que este guardara.
                        redisExecutor.submit {
                            // Si mientras esperaba en la cola ya se fue, reclamar ahora sería tomar un
                            // lock para un jugador que no está acá (y que quizás ya está en otro lado).
                            if (!player.isRemoved && uuid in dataLoaded) {
                                runCatching { CrossServerCoordinator.claim(uuid) }
                            }
                        }
                        dbExecutor.submit {
                            runCatching { CobblemonGuard.repository.logSync(uuid, name, "LOAD", true, null) }
                        }
                        } catch (e: Exception) {
                            CobblemonGuard.logger.error("[JOIN] Error aplicando datos de $name: ${e.message}", e)
                            expulsar(player,
                                "§fNo se pudieron aplicar tus datos.",
                                "§7Vuelve a entrar en unos segundos. Si sigue pasando, avisa a un admin.")
                            dbExecutor.submit {
                                runCatching { CobblemonGuard.repository.logSync(uuid, name, "LOAD", false, e.message) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                CobblemonGuard.logger.error("[JOIN] Error procesando datos de $name: ${e.message}", e)
                // Se EXPULSA, no se descongela. Ver el comentario de expulsar(): descongelarlo
                // aca lo dejaba jugando con el playerdata local de este backend y sin guardar
                // nada, que es la via de duplicacion mas barata del mod. El camino de "BD no
                // disponible" de mas arriba ya hacia lo correcto; este hacia lo contrario.
                expulsar(player,
                    "§fNo se pudieron cargar tus datos.",
                    "§7Vuelve a entrar en unos segundos. Si sigue pasando, avisa a un admin.")
                try { CobblemonGuard.repository.logSync(uuid, name, "LOAD", false, e.message) } catch (_: Exception) {}
            }
        }
    }

    // ══════════════════════════════════════════════════════
    // DISCONNECT: guardar datos
    // ══════════════════════════════════════════════════════

    private fun registerDisconnect() {
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val player = handler.player
            pendingLoad.remove(player.uuid)
            // clear(player), no clear(uuid): además de sacarlo del set QUITA el Slowness,
            // para que no quede guardado en su playerdata si se desconectó congelado.
            FreezeScreen.clear(player)
            autoSaveTicks.remove(player.uuid)
            backupTicks.remove(player.uuid)
            // El dedup de backups es por sesión: al volver, que su primer backup se
            // inserte sí o sí.
            CobblemonGuard.backupRepository.forgetDedup(player.uuid)

            if (!CobblemonGuard.config.syncOnJoinLeave) {
                // Modo solo-backups: no guardamos al salir (lo maneja Minecraft). Solo limpiamos.
                dataLoaded.remove(player.uuid)
                EconomySync.olvidar(player.uuid)
                return@register
            }

            // El guardado y la limpieza de dataLoaded deben ir EN ORDEN y en el hilo del
            // servidor. El DISCONNECT puede dispararse en un hilo de red; si quitáramos
            // dataLoaded antes del guardado, el guard lo bloquearía (pérdida de datos).
            val saveThenCleanup = Runnable {
                // Ultima ventana para que otros mods vuelquen lo suyo mientras el jugador
                // todavia existe (ver GuardApi.onPlayerDataSaving).
                GuardApi.fireDataSaving(player)
                savePlayerDataAsync(player, porDesconexion = true)
                dataLoaded.remove(player.uuid)
                // Ya se serializó su último guardado (savePlayerDataAsync lo hace acá mismo, en
                // este hilo): las líneas de monedas ajenas que se conservaban para él ya no hacen falta.
                EconomySync.olvidar(player.uuid)
            }
            if (player.server.isOnThread) saveThenCleanup.run()
            else player.server.execute(saveThenCleanup)
        }
    }

    /**
     * @param porDesconexion true solo cuando viene del DISCONNECT o del apagado, es decir
     *   cuando el jugador SE VA de este backend. Decide si las salidas tempranas sueltan el
     *   lock cross-server; ver abajo.
     */
    private fun savePlayerDataAsync(player: ServerPlayerEntity, porDesconexion: Boolean = false) {
        val uuid = player.uuid
        val name = player.name.string

        // Las dos salidas tempranas sueltan el lock, pero SOLO si el jugador se esta yendo.
        //
        // Por que hay que soltarlo: si no, queda reclamado por este backend para siempre. El
        // heartbeat le renueva el TTL sin mirar si sigue conectado, asi que nunca expira, y a
        // partir de ahi cada join suyo a CUALQUIER backend espera el timeout completo. La
        // unica salida era que un admin corriera /cg unlock, y nada en el log lo decia.
        //
        // Por que SOLO en la desconexion: a este mismo metodo lo llama el AUTO-SAVE, para
        // jugadores que estan online y jugando aca. Con /cg saving stop puesto —que es justo
        // el modo que se usa para una migracion, donde el lock importa mas que nunca— cada
        // ronda de auto-save entraba por "!savingEnabled" y soltaba el lock de todos los
        // conectados. Cualquier otro backend podia entonces cargarlos sin esperar a nadie.
        //
        // Y se suelta por chainSave, no por dbExecutor suelto. chainSave serializa por UUID,
        // asi que el release no puede adelantarse ni atrasarse respecto de otras escrituras
        // del mismo jugador. Con dbExecutor pelado, una reconexion rapida (el kick dice
        // "vuelve a entrar en unos segundos") podia ejecutar el claim del JOIN nuevo ANTES
        // que el release de la sesion vieja: el release borraba el lock recien tomado —el CAS
        // pasa, porque el serverId es el mismo backend— y el jugador quedaba jugando aca sin
        // lock y sin heartbeat el resto de la sesion. Ademas saca el viaje a Redis del hilo
        // del servidor, que es donde entra este metodo (release() son hasta dos round-trips
        // con timeout de socket de 5 s).
        fun soltarLockSiSeVa() {
            if (porDesconexion) chainSave(uuid) { CrossServerCoordinator.release(uuid) }
        }
        if (!savingEnabled) {
            soltarLockSiSeVa()
            return
        }
        if (uuid !in dataLoaded) {
            CobblemonGuard.logger.warn("[SAVE] $name NO se guarda: sus datos no se habían cargado desde MySQL.")
            soltarLockSiSeVa()
            return
        }
        // La serialización debe correr en el hilo del servidor (acceso seguro a datos y a
        // BackpackStorage de SB). El disconnect por caída de red puede ser off-thread.
        if (!player.server.isOnThread) {
            player.server.execute { savePlayerDataAsync(player, porDesconexion) }
            return
        }

        val blob = SyncService.serialize(player)

        // La cadena se publica en el mapa ANTES de que el trabajo real arranque (compute()
        // es atómico), así que un JOIN de reconexión que llegue en el medio igual lo ve y
        // espera. Y si ya había un guardado de este jugador en vuelo, este se ejecuta
        // DESPUÉS, nunca en paralelo (ver doc de saveChains).
        chainSave(uuid) {
            try {
                if (!CobblemonGuard.database.isConnected()) {
                    CobblemonGuard.logger.error("[SAVE] BD no conectada — datos de $name NO guardados.")
                    DiscordWebhook.sendSyncError(name, "SAVE", "Base de datos no conectada")
                    // Acá tampoco se escribió nada, así que el lock se suelta: dejarlo tomado
                    // solo trancaría al jugador en los otros backends sin proteger nada.
                    CrossServerCoordinator.release(uuid)
                    return@chainSave
                }
                persistConReintentos(uuid, name, blob, verificarDueño = porDesconexion)
                CobblemonGuard.logger.info("[SAVE] Datos guardados en MySQL para $name.")
                // Recién AHORA que el guardado en MySQL está confirmado liberamos el lock —
                // si liberáramos antes y el guardado fallara, el próximo backend cargaría
                // datos viejos creyendo que están al día.
                CrossServerCoordinator.release(uuid)
            } catch (e: Exception) {
                if (e is GuardadoAbandonado) {
                    // No escribir encima de otro backend es lo correcto, pero la sesión de ESTE
                    // backend igual se pierde: tiene que verse, no quedar como un WARN más.
                    CobblemonGuard.logger.error(
                        "[SAVE] No se termina el guardado de $name: ${e.message}. Lo que hizo en esta sesión " +
                        "acá no llegó a MySQL; quedó en el playerdata de ESTE servidor."
                    )
                    if (porDesconexion) {
                        runCatching { DiscordWebhook.sendSyncError(name, "SAVE", "Guardado abandonado: ${e.message}") }
                    }
                } else if (porDesconexion) {
                    // El caso caro: se fue, su sesión no llegó a MySQL y no hay más
                    // oportunidades. Su estado más reciente sigue en el playerdata LOCAL de este
                    // backend, que es de donde un admin puede recuperarlo si hace falta.
                    CobblemonGuard.logger.error(
                        "[SAVE] ¡$name se desconectó y su guardado no se pudo hacer, ni siquiera reintentando! " +
                        "Error: ${e.message}. Su lock NO se suelta: caduca solo en unos segundos y, a partir de " +
                        "ahí, cualquier backend le va a cargar el último guardado bueno, SIN esta sesión. Lo más " +
                        "reciente quedó en el playerdata de ESTE servidor. Revisa la conexión a MySQL."
                    )
                    runCatching { DiscordWebhook.sendSyncError(name, "SAVE", "Guardado al salir falló tras reintentos: ${e.message}") }
                } else {
                    // Auto-save de alguien que sigue conectado: el próximo guardado lo vuelve a
                    // intentar con datos más nuevos. Se registra, pero no es una pérdida todavía.
                    CobblemonGuard.logger.error("[SAVE] Error guardando datos de $name (sigue conectado, se reintentará en el próximo guardado): ${e.message}")
                }
                try { CobblemonGuard.repository.logSync(uuid, name, "SAVE", false, e.message) } catch (_: Exception) {}
                // NO liberamos el lock si el guardado falló — mejor dejar al jugador esperando
                // en el otro backend (hasta el timeout) que arriesgar una carga con datos viejos.
            }
        }
    }

    // ══════════════════════════════════════════════════════
    // SERVER STOP: guardar todos los jugadores online
    // ══════════════════════════════════════════════════════

    /** Tope de lo que el apagado espera a los guardados que ya estaban en curso. */
    private const val ESPERA_EN_VUELO_AL_APAGAR_MS = 10_000L

    /**
     * Espera, con tope, a que terminen los guardados que ya estaban en curso antes de cerrar la BD.
     *
     * El apagado solo esperaba a los guardados de los jugadores CONECTADOS. Los de jugadores que ya
     * se habían ido —por ejemplo, reintentando porque MySQL tuvo un corte— quedaban afuera: el
     * apagado los despierta para un último intento, pero cerraba la BD sin esperarlos, y ese último
     * intento chocaba contra un pool cerrado. Con el servidor vacío pasaba siempre, que es justo
     * el reinicio típico después de un corte.
     */
    private fun esperarGuardadosEnVuelo() {
        val enVuelo = saveChains.values.toList()
        if (enVuelo.isEmpty()) return
        CobblemonGuard.logger.info("[STOP] Esperando ${enVuelo.size} guardado(s) que ya estaban en curso antes de cerrar la BD...")
        runCatching {
            CompletableFuture.allOf(*enVuelo.toTypedArray()).get(ESPERA_EN_VUELO_AL_APAGAR_MS, TimeUnit.MILLISECONDS)
        }.onFailure {
            CobblemonGuard.logger.warn("[STOP] Quedaron guardados sin terminar tras ${ESPERA_EN_VUELO_AL_APAGAR_MS / 1000}s; se cierra la BD igual.")
        }
    }

    private fun registerServerStop() {
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            // Los guardados que estén reintentando dejan de dormir y fallan en su próximo
            // intento: la BD se va a cerrar en segundos y sus hilos hacen falta para el
            // guardado de apagado, que lleva datos más nuevos de esos mismos jugadores.
            apagando = true
            finDeEsperas.countDown()
            if (!CobblemonGuard.config.syncOnJoinLeave || !savingEnabled) {
                // Modo solo-backups (o pausado): Minecraft ya guarda a los jugadores; solo cerramos la BD.
                esperarGuardadosEnVuelo()
                CobblemonGuard.database.close()
                CrossServerCoordinator.close()
                return@register
            }
            val players = server.playerManager.playerList.filter { it.uuid in dataLoaded }
            if (players.isEmpty()) {
                esperarGuardadosEnVuelo()
                CobblemonGuard.database.close()
                CrossServerCoordinator.close()
                return@register
            }
            CobblemonGuard.logger.info("[STOP] Guardando datos de ${players.size} jugadores (pool de conexiones MySQL: ${CobblemonGuard.config.poolSize})...")

            // ANTES: se esperaba cada future en SECUENCIA con "it.get(10, SECONDS)" — con
            // un pool de conexiones chico (default 5) y muchos jugadores online, eso podía
            // sumar hasta jugadores × 10s de espera (varios minutos), y encima el resultado
            // del timeout se descartaba en silencio: si el proceso era matado (SIGKILL) por
            // el panel/host antes de terminar esa espera secuencial, o si simplemente
            // seguíamos adelante tras el timeout, database.close() se llamaba igual y
            // cortaba conexiones con guardados aún en curso. Eso era el "el backup falla al
            // reiniciar con muchos jugadores conectados".
            //
            // AHORA: todos los guardados se lanzan en paralelo (ya lo hacían) pero se
            // esperan TODOS JUNTOS con un único timeout total acotado y escalado con la
            // cantidad de jugadores, y si alguno no llega a tiempo se loguea explícitamente
            // (con nombre) en vez de cerrar el pool en silencio.
            val pendingNames = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
            // mapNotNull y con la serializacion dentro de un try: si serialize() tira con un
            // jugador, antes se llevaba puesto el handler entero de SERVER_STOPPING. Los
            // jugadores que faltaban no se guardaban, y —peor— el database.close() y el
            // CrossServerCoordinator.close() del final no corrian: conexiones cortadas de
            // golpe y locks de Redis colgados hasta que venciera el TTL. Un jugador problematico
            // ahora se pierde solo a si mismo, y queda en pendingNames para que salga en la alarma.
            val futures: List<CompletableFuture<Void>> = players.mapNotNull { player ->
                val uuid = player.uuid
                val name = player.name.string
                pendingNames.add(name)
                val blob = try {
                    SyncService.serialize(player)   // hilo del servidor (evento STOPPING)
                } catch (e: Exception) {
                    CobblemonGuard.logger.error("[STOP] Error serializando a $name — no se guarda: ${e.message}", e)
                    return@mapNotNull null
                }
                // chainSave y no runAsync suelto: si este jugador tenía un auto-save en
                // vuelo, el de apagado se encola DESPUÉS y no compite con él.
                chainSave(uuid) {
                    try {
                        persist(uuid, name, blob)
                        // Liberamos el lock explícitamente: un apagado normal (no un crash) NO
                        // debe depender del TTL para que el jugador pueda entrar a otro backend.
                        CrossServerCoordinator.release(uuid)
                        // Se saca de pendientes SOLO si el guardado salió bien. Antes esto
                        // estaba en un `finally`, así que también se sacaba cuando persist()
                        // lanzaba excepción: el resumen final decía "N/N confirmados" y no se
                        // mandaba el webhook aunque hubiera habido fallos reales.
                        pendingNames.remove(name)
                    } catch (e: Exception) {
                        CobblemonGuard.logger.error("[STOP] Error guardando $name: ${e.message}")
                    }
                }
            }

            // pendingNames es un synchronizedSet, pero su ITERADOR no se sincroniza solo:
            // recorrerlo (joinToString) mientras una tarea del pool hace remove() lanza
            // ConcurrentModificationException. Y aca abajo se lo recorre por definicion con
            // tareas en vuelo (justo despues de un timeout). Esa excepcion escapaba del
            // handler del evento y se llevaba puesto el database.close() y el
            // CrossServerCoordinator.close() de mas abajo: conexiones cortadas de golpe y
            // locks de Redis colgados hasta que venciera el TTL. Se saca una copia bajo el
            // monitor y se trabaja sobre la copia.
            fun pendientes(): List<String> = synchronized(pendingNames) { pendingNames.toList() }

            // Base generosa + margen por jugador, con un techo para no colgar el apagado
            // indefinidamente si la BD está caída (mejor cortar y avisar que trabar el stop).
            val timeoutMs = (15_000L + players.size * 300L).coerceAtMost(90_000L)
            // Solo un TimeoutException cuenta como "timeout". Antes cualquier excepcion lo
            // marcaba: un ExecutionException (fallo real de persist) o un
            // RejectedExecutionException (dbExecutor ya cerrado, que falla al instante) se
            // reportaban al admin como "la BD esta lenta, sube poolSize" — mandandolo a
            // perseguir un problema de rendimiento que no existe mientras el error de verdad
            // seguia ahi.
            val timedOut = try {
                CompletableFuture.allOf(*futures.toTypedArray()).get(timeoutMs, TimeUnit.MILLISECONDS)
                false
            } catch (e: java.util.concurrent.TimeoutException) {
                true
            } catch (e: Exception) {
                CobblemonGuard.logger.error("[STOP] Fallo esperando los guardados de apagado: ${e.message}", e)
                false
            }

            // La PRORROGA va ANTES de la alarma, no despues.
            //
            // Cerrar la BD con guardados corriendo les arranca la conexion de las manos, y
            // esos jugadores pierden la sesion entera. El timeout de arriba existe para no
            // colgar el apagado indefinidamente, pero "no confirmo en 15 s" casi nunca
            // significa "esta colgado para siempre": significa que la BD esta lenta.
            //
            // Cuando esta espera extra estaba DESPUES del bloque de alarma, un guardado que
            // terminaba durante la prorroga ya habia disparado el error rojo y el webhook de
            // Discord: el admin recibia un aviso de perdida de datos por jugadores que se
            // guardaron bien. Se avisa una sola vez, y recien cuando se sabe el resultado final.
            if (timedOut && pendingNames.isNotEmpty()) {
                CobblemonGuard.logger.warn("[STOP] Esperando ${GRACIA_CIERRE_MS / 1000}s más antes de cerrar la BD, para no cortar los guardados en vuelo...")
                runCatching {
                    CompletableFuture.allOf(*futures.toTypedArray()).get(GRACIA_CIERRE_MS, TimeUnit.MILLISECONDS)
                }
            }

            val sinConfirmar = pendientes()
            if (sinConfirmar.isNotEmpty()) {
                val names = sinConfirmar.joinToString(", ")
                val causa = if (timedOut) "no confirmaron guardado a tiempo (timeout ${timeoutMs}ms + ${GRACIA_CIERRE_MS / 1000}s de prórroga)"
                            else "fallaron al guardar (ver los errores [STOP] de arriba)"
                CobblemonGuard.logger.error(
                    "[STOP] ¡ATENCIÓN! ${sinConfirmar.size} jugador(es) $causa: $names. Si tu hosting " +
                    "mata el proceso poco después del stop, esos jugadores pueden quedar con el último " +
                    "auto-save/backup periódico en vez del estado más reciente. Sube 'poolSize' en la " +
                    "config (regla aproximada: al menos tantas conexiones como jugadores simultáneos " +
                    "esperados en un pico) o revisa la latencia/salud de tu MySQL."
                )
                DiscordWebhook.sendSyncError(
                    names, "STOP",
                    if (timedOut) "Timeout guardando al apagar el servidor (pool de conexiones insuficiente o BD lenta)"
                    else "Error guardando al apagar el servidor"
                )
            }

            esperarGuardadosEnVuelo()
            CobblemonGuard.database.close()
            CrossServerCoordinator.close()
            CobblemonGuard.logger.info("[STOP] Guardado de apagado: ${players.size - sinConfirmar.size}/${players.size} confirmados. BD cerrada.")
        }
    }

    // ══════════════════════════════════════════════════════
    // AUTO-SAVE + BACKUP periódicos
    // ══════════════════════════════════════════════════════

    private fun registerAutoSave() {
        val intervalSeconds = CobblemonGuard.config.autoSaveIntervalSeconds
        CobblemonGuard.logger.info("Auto-save activado cada ${intervalSeconds}s")
        // OPTIMIZACIÓN DE TPS: antes este recorrido de TODOS los jugadores online corría
        // en CADA TICK (20 veces por segundo), aunque el intervalo se configura en
        // SEGUNDOS — esa precisión extra no sirve para nada, solo cuesta CPU del hilo
        // del servidor proporcional al número de jugadores conectados, 20x más de lo
        // necesario. Con muchos jugadores esto es justamente el tipo de "pérdida
        // gradual de TPS que escala con el volumen de jugadores". Ahora se revisa una
        // sola vez por segundo — mismo comportamiento, 1/20 del costo.
        var secondTicks = 0
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (++secondTicks < 20) return@register
            secondTicks = 0
            for (player in server.playerManager.playerList) {
                if (FreezeScreen.isFrozen(player.uuid)) continue
                val secs = autoSaveTicks.merge(player.uuid, 1, Int::plus) ?: 1
                if (secs >= intervalSeconds) {
                    autoSaveTicks[player.uuid] = 0
                    savePlayerDataAsync(player)
                }
            }
        }
    }

    // Limpieza de backups viejos: GLOBAL y periódica (no por jugador en cada backup, para
    // no spamear DELETEs cuando el intervalo de backup es corto, ej. cada minuto).
    private const val CLEANUP_INTERVAL_TICKS = 5 * 60 * 20   // cada 5 minutos
    private var cleanupTicks = 0

    private fun registerBackupScheduler() {
        val intervalSeconds = CobblemonGuard.config.backupIntervalSeconds
        CobblemonGuard.logger.info(
            "Backups automáticos activados cada ${intervalSeconds}s, retención ${CobblemonGuard.config.backupRetentionHours}h (${CobblemonGuard.config.backupRetentionHours / 24}d)"
        )
        // Mismo motivo que en registerAutoSave(): el recorrido de jugadores se revisa
        // una vez por segundo, no en cada tick — el intervalo ya se configura en segundos.
        var secondTicks = 0
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (++secondTicks >= 20) {
                secondTicks = 0
                for (player in server.playerManager.playerList) {
                    if (FreezeScreen.isFrozen(player.uuid)) continue
                    val secs = backupTicks.merge(player.uuid, 1, Int::plus) ?: 1
                    if (secs >= intervalSeconds) {
                        backupTicks[player.uuid] = 0
                        saveBackupAsync(player)
                    }
                }
            }
            // Limpieza global periódica (una sola vez cada CLEANUP_INTERVAL_TICKS).
            if (++cleanupTicks >= CLEANUP_INTERVAL_TICKS) {
                cleanupTicks = 0
                val backupHours = CobblemonGuard.config.backupRetentionHours
                val logHours = CobblemonGuard.config.syncLogRetentionHoursEffective
                dbExecutor.submit {
                    runCatching { CobblemonGuard.backupRepository.cleanOldBackups(backupHours) }
                    // El log de sincronización no se limpiaba NUNCA — solo existía un DELETE
                    // comentado en sql/setup.sql para correr a mano. Ahora entra en el mismo
                    // ciclo, también por lotes.
                    runCatching { CobblemonGuard.repository.cleanOldSyncLog(logHours) }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════
    // CROSS-SERVER: heartbeat periódico de los locks reclamados por este backend
    // ══════════════════════════════════════════════════════

    private const val CROSS_SERVER_HEARTBEAT_TICKS = 15 * 20   // cada 15 segundos
    private var heartbeatTicks = 0

    /**
     * Hilo PROPIO para hablar con Redis: el heartbeat y el claim del ingreso. Antes iban al
     * [dbExecutor], el mismo pool que hace los
     * guardados: con MySQL lento o caído, ese pool se llena de guardados esperando (y ahora de
     * guardados reintentando), y el heartbeat quedaba en la cola detrás de ellos. Si esa espera
     * pasaba el TTL del lock, los locks de los jugadores que están JUGANDO acá caducaban, y otro
     * backend podía cargarlos mientras este todavía los tiene. Redis no depende de MySQL: que uno
     * esté caído no tiene por qué frenar al otro.
     */
    private val redisExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "CobblemonGUARD-Redis").also { it.isDaemon = true }
    }

    /** Un heartbeat en curso. Si Redis anda lento, no se encolan más encima: se saltea el turno. */
    private val latiendo = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun registerCrossServerHeartbeat() {
        CobblemonGuard.logger.info("[CrossServer] Heartbeat de locks activado cada ${CROSS_SERVER_HEARTBEAT_TICKS / 20}s.")
        ServerTickEvents.END_SERVER_TICK.register { server ->
            if (++heartbeatTicks < CROSS_SERVER_HEARTBEAT_TICKS) return@register
            heartbeatTicks = 0
            // La lista de conectados se saca ACA, en el hilo del servidor, y se le pasa al
            // heartbeat ya congelada. Consultar la PlayerManager desde el dbExecutor seria
            // leer estado del servidor desde otro hilo.
            val aqui = server.playerManager.playerList.mapTo(HashSet()) { it.uuid }
            // "Sigue aqui" incluye a quien ya se desconecto pero TIENE UN GUARDADO EN VUELO.
            // Sin esa parte, el lock de un jugador que se va deja de renovarse en el instante
            // en que sale de la playerlist, y con un persist lento —MySQL cargado, PC grande,
            // cola del dbExecutor llena, o sea exactamente un reinicio masivo— caducaba
            // MIENTRAS lo estabamos escribiendo. El otro backend leia la fila a mitad de
            // escritura, que es justo lo que el lock existe para impedir.
            if (!latiendo.compareAndSet(false, true)) return@register
            redisExecutor.submit {
                try {
                    runCatching {
                        CrossServerCoordinator.heartbeatAll { it in aqui || saveChains.containsKey(it) }
                    }
                } finally {
                    latiendo.set(false)
                }
            }
        }
    }

    /**
     * @param callback recibe true si se insertó un backup nuevo, false si se omitió por no
     *   haber cambios (dedup) o si falló. Se invoca en el hilo del servidor.
     *   Antes `/cg backup` respondía "✔ Backup creado" en la línea siguiente a disparar el
     *   trabajo asíncrono, sin esperar el resultado: si fallaba, el staff igual veía el tick verde.
     */
    fun saveBackupAsync(player: ServerPlayerEntity, callback: ((Boolean) -> Unit)? = null) {
        val uuid = player.uuid
        val name = player.name.string
        if (!savingEnabled || uuid !in dataLoaded) {
            if (callback != null) player.server.execute { callback(false) }
            return
        }
        val blob = SyncService.serialize(player)
        dbExecutor.submit {
            var inserted = false
            try {
                inserted = persistBackup(uuid, name, blob)
                if (inserted) CobblemonGuard.logger.debug("[Backup] Backup guardado para $name.")
            } catch (e: Exception) {
                CobblemonGuard.logger.error("[Backup] Error guardando backup de $name: ${e.message}")
            }
            if (callback != null) {
                val result = inserted
                player.server.execute { callback(result) }
            }
        }
    }

    // ══════════════════════════════════════════════════════
    // API para comandos: force save / force restore
    // ══════════════════════════════════════════════════════

    fun forceSave(player: ServerPlayerEntity, callback: ((Boolean) -> Unit)? = null) {
        val uuid = player.uuid
        val name = player.name.string
        if (!savingEnabled) {
            CobblemonGuard.logger.warn("[SAVE] Force-save de $name cancelado: el guardado está pausado (/cg saving start para activar).")
            player.server.execute { callback?.invoke(false) }
            return
        }
        if (uuid !in dataLoaded) {
            CobblemonGuard.logger.warn("[SAVE] Force-save de $name cancelado: sus datos no se cargaron desde MySQL.")
            player.server.execute { callback?.invoke(false) }
            return
        }
        val blob = SyncService.serialize(player)
        // También por la cadena del UUID: un `/cg save` disparado justo cuando el jugador
        // se desconecta no debe competir con el guardado del disconnect.
        chainSave(uuid) {
            try {
                persist(uuid, name, blob)
                CobblemonGuard.logger.info("[SAVE] Force-save completado para $name.")
                player.server.execute { callback?.invoke(true) }
            } catch (e: Exception) {
                CobblemonGuard.logger.error("[SAVE] Error en force-save de $name: ${e.message}")
                player.server.execute { callback?.invoke(false) }
            }
        }
    }

    /** Restaura desde MySQL solo los [types] indicados (por defecto todos). */
    /**
     * @param callback recibe la lista de tipos que NO se pudieron aplicar (vacia = todo bien),
     *   o null si no habia datos o la carga desde MySQL fallo.
     */
    fun forceRestore(
        player: ServerPlayerEntity,
        types: Set<DataType> = SyncTypes.ALL,
        callback: ((List<DataType>?) -> Unit)? = null
    ) {
        val uuid = player.uuid
        val name = player.name.string
        // joinExecutor y no dbExecutor: esta tarea ESPERA a un guardado que corre en
        // dbExecutor. Si esperara desde el propio dbExecutor, con varios restores
        // simultáneos los hilos que esperan podrían estar ocupando justamente los hilos que
        // tienen que ejecutar esos guardados.
        joinExecutor.submit {
            try {
                // Mismo guard que en el JOIN: si hay un guardado de este jugador en vuelo,
                // esperarlo antes de leer, o el restore traería el estado anterior.
                saveChains[uuid]?.let { pending ->
                    runCatching { pending.get(PENDING_SAVE_WAIT_MS, TimeUnit.MILLISECONDS) }
                }
                val data = CobblemonGuard.repository.loadPlayerData(uuid, name)
                if (data == null) {
                    player.server.execute { callback?.invoke(null) }
                    return@submit
                }
                player.server.execute {
                    val fallidos = SyncService.fallidos(SyncService.apply(player, data.toBlobMap(), types))
                    if (fallidos.isNotEmpty()) {
                        // Antes el restore decia "completado" pasara lo que pasara. Y en una
                        // restauracion completa ademas marcaba al jugador como cargado: con un tipo
                        // a medio aplicar, eso habilitaba a guardar su estado mezclado sobre MySQL.
                        CobblemonGuard.logger.error("[RESTORE] Force-restore de $name: no se pudo aplicar ${fallidos.joinToString(", ")}.")
                        callback?.invoke(fallidos)
                        return@execute
                    }
                    // Restauración COMPLETA → el jugador queda en sync y vuelve a ser elegible para guardar.
                    if (types == SyncTypes.ALL) markLoaded(player)
                    CobblemonGuard.logger.info("[RESTORE] Force-restore ($types) completado para $name.")
                    callback?.invoke(emptyList())
                }
            } catch (e: Exception) {
                CobblemonGuard.logger.error("[RESTORE] Error en force-restore de $name: ${e.message}")
                player.server.execute { callback?.invoke(null) }
            }
        }
    }
}
