package com.cobblemonguard.network

import com.cobblemonguard.CobblemonGuard
import com.cobblemonguard.config.GuardConfig
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.exceptions.JedisConnectionException
import com.cobblemonguard.util.Permissions
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.text.Text
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinación cross-server para redes multi-backend (varios servidores Fabric detrás
 * de un mismo proxy Velocity, ej. Velocity-CTD + FabricProxy-Lite).
 *
 * ── El problema que resuelve ────────────────────────────────────────────────
 * Cada backend Fabric es un proceso de Minecraft INDEPENDIENTE. Cuando el proxy mueve
 * a un jugador de un backend a otro, cada uno ve esto como un DISCONNECT (origen) y un
 * JOIN (destino) completamente separados — ninguno sabe nada del otro. Sin coordinación
 * externa, el backend destino podría cargar la PC/equipo del jugador (desde MySQL o
 * incluso desde su propio disco) MIENTRAS el jugador sigue técnicamente "vivo" en el
 * backend origen (guardado async todavía en curso). Eso son dos copias del mismo
 * Pokémon en memoria a la vez en dos procesos distintos — la clase exacta de bug que
 * causa dupeos y objetos "fantasma".
 *
 * ── Cómo lo resuelve ────────────────────────────────────────────────────────
 * Un lock por jugador en Redis (compartido por TODOS los backends, latencia sub-ms):
 *   - JOIN  en un backend → si el lock existe y pertenece a OTRO backend, se espera
 *            (el jugador ya está congelado por FreezeScreen) hasta que se libere o
 *            hasta [GuardConfig.crossServerWaitTimeoutMs]. Después, se reclama el lock.
 *   - DISCONNECT en un backend → tras confirmar el guardado en MySQL, se libera el
 *            lock (solo si es dueño) y punto: MySQL sigue siendo la fuente de verdad
 *            persistente; Redis solo coordina el "quién tiene la data en memoria ahora".
 *   - Heartbeat periódico mientras el jugador está online → renueva el TTL del lock,
 *            para que sobreviva sesiones largas sin expirar.
 *   - TTL de seguridad ([GuardConfig.crossServerLockTtlSeconds]) → si un backend se
 *            cae sin liberar el lock (crash, kill -9), expira solo y no deja al
 *            jugador varado sin poder entrar a ningún backend.
 *
 * NO reemplaza a MySQL: es una capa fina de coordinación, no de persistencia. Si Redis
 * no está disponible, el mod se degrada a comportamiento single-server (no bloquea a
 * nadie) — nunca preferimos "trancar al jugador" sobre "dejarlo jugar sin coordinar".
 *
 * Todas las llamadas de red (Jedis) deben hacerse FUERA del hilo del servidor —
 * [com.cobblemonguard.events.PlayerEventHandler] ya las despacha en su executor de BD.
 */
object CrossServerCoordinator {

    @Volatile private var pool: JedisPool? = null

    /** Servidor actual, para avisarle a los staff conectados. Lo llenan los eventos de ciclo de vida. */
    @Volatile private var servidor: MinecraftServer? = null
    private val eventosRegistrados = AtomicBoolean(false)

    /** Si Redis no estaba al arrancar, se reintenta conectar como mucho una vez por minuto. */
    private const val RECONECTAR_CADA_MS = 60_000L
    @Volatile private var ultimoIntentoConexion = 0L

    /** Los avisos de "Redis no responde" salen como mucho cada 5 minutos (consola y staff). */
    private const val AVISO_CADA_MS = 5 * 60_000L
    private val ultimoAviso = AtomicLong(0L)

    // Jugadores actualmente "reclamados" por ESTE backend — a estos se les hace heartbeat.
    private val claimedHere = ConcurrentHashMap.newKeySet<UUID>()

    private fun lockKey(uuid: UUID) = "cobblemonguard:lock:${uuid}"

    /** Pasadas seguidas de heartbeat sin el jugador antes de soltarle el lock. Ver heartbeatAll. */
    private const val AUSENCIAS_PARA_SOLTAR = 2

    /** uuid -> pasadas seguidas de heartbeat en las que no estaba. Se limpia solo. */
    private val ausencias = ConcurrentHashMap<UUID, Int>()

    private val cfg: GuardConfig get() = CobblemonGuard.config

    fun isEnabled(): Boolean = cfg.crossServerSyncEnabled

    @Synchronized
    fun connect() {
        if (!isEnabled()) return
        if (eventosRegistrados.compareAndSet(false, true)) {
            ServerLifecycleEvents.SERVER_STARTED.register { servidor = it }
            ServerLifecycleEvents.SERVER_STOPPED.register { servidor = null }
        }
        // Dos hilos pueden pasar a la vez el filtro de tiempo de poolOReconectar y llegar acá
        // uno detrás del otro: el segundo no tiene que abrir otro pool encima del primero.
        if (pool != null) return
        ultimoIntentoConexion = System.currentTimeMillis()
        try {
            // Sin PING automáticos. Antes había testOnBorrow=true (un PING antes de CADA
            // operación) y, por defecto de JedisPoolConfig, testWhileIdle=true (un PING cada
            // 30 s a cada conexión ociosa). Con un Redis que cobra o limita por comando
            // (Upstash: 500 mil/mes en el plan gratis) eso duplicaba el consumo sin aportar
            // nada. Una conexión vieja que el servidor cerró se detecta al usarla y se
            // reintenta una vez con otra (ver [conRedis]). El evictor sigue cerrando las
            // conexiones ociosas de más de 60 s, sin PING, así que casi nunca queda una vieja.
            val poolConfig = JedisPoolConfig().apply {
                maxTotal = 8
                maxIdle = 4
                minIdle = 0
                testOnBorrow = false
                testWhileIdle = false
            }
            val usuario = cfg.redisUsername?.takeIf { it.isNotBlank() }
            val clave = cfg.redisPassword.takeIf { it.isNotBlank() }
            pool = JedisPool(poolConfig, cfg.redisHost, cfg.redisPort, 5_000, usuario, clave, cfg.redisSsl)
            // Ping de verificación inmediata.
            pool!!.resource.use { it.ping() }
            CobblemonGuard.logger.info(
                "[CrossServer] Conectado a Redis en ${cfg.redisHost}:${cfg.redisPort}" +
                (if (cfg.redisSsl) " (TLS)" else " (SIN cifrar)") + " — serverId='${cfg.serverId}'."
            )
        } catch (e: Exception) {
            CobblemonGuard.logger.error(
                "[CrossServer] No se pudo conectar a Redis (${cfg.redisHost}:${cfg.redisPort}): ${e.message}. " +
                "La coordinación cross-server queda DESACTIVADA — el mod sigue funcionando en modo single-server." +
                (if (!cfg.redisSsl) " Si el Redis exige TLS (Upstash siempre lo exige), pon \"redisSsl\": true." else "")
            )
            runCatching { pool?.close() }
            pool = null
        }
    }

    /**
     * Ejecuta [bloque] con una conexión del pool, y si falla por la CONEXIÓN (socket que el
     * servidor ya cerró, corte momentáneo) lo reintenta UNA vez con otra.
     *
     * Reemplaza al PING de testOnBorrow. Solo se reintenta lo que falla después de haber
     * conseguido la conexión: si no se puede ni conectar (Redis caído), el error sube al
     * instante, sin duplicar la espera de 5 s del timeout.
     *
     * Todas las operaciones de este archivo se pueden repetir sin daño: GET, los Lua de
     * claim/renovar/soltar (comparan el dueño antes de escribir, así que repetir uno que sí
     * llegó a ejecutarse da el mismo resultado) y DEL.
     */
    private fun <T> conRedis(p: JedisPool, bloque: (Jedis) -> T): T {
        val primera = p.resource
        try {
            return primera.use(bloque)
        } catch (e: JedisConnectionException) {
            // `use` ya devolvió la conexión al pool marcada como rota; se descarta sola.
        }
        return p.resource.use(bloque)
    }

    fun isConnected(): Boolean = pool?.isClosed == false

    /**
     * El pool, o null si no hay conexión. Si Redis no estaba disponible (al arrancar, o porque
     * se cerró), intenta conectar de nuevo como mucho una vez por minuto: así el backend se
     * recupera solo cuando Redis vuelve, sin tener que reiniciarlo.
     */
    private fun poolOReconectar(): JedisPool? {
        pool?.let { return it }
        if (System.currentTimeMillis() - ultimoIntentoConexion < RECONECTAR_CADA_MS) return null
        connect()
        return pool
    }

    /**
     * Avisa que Redis no responde: en consola (error) y a los staff conectados con
     * `cobblemonguard.status`. Como mucho una vez cada 5 minutos, para no inundar el log
     * con un aviso por cada jugador que entra.
     */
    private fun avisarFalla(motivo: String) {
        val ahora = System.currentTimeMillis()
        val anterior = ultimoAviso.get()
        if (ahora - anterior < AVISO_CADA_MS || !ultimoAviso.compareAndSet(anterior, ahora)) return

        val cuota = motivo.contains("max requests limit", ignoreCase = true) ||
            motivo.contains("limit exceeded", ignoreCase = true)
        val explicacion = if (cuota)
            "se agotó la cuota de comandos del mes de Upstash. Sube de plan o espera al mes siguiente."
        else
            "no responde ($motivo)."
        CobblemonGuard.logger.error(
            "[CrossServer] Redis $explicacion La protección entre servidores está DEGRADADA: cada jugador " +
            "que entra espera ${cfg.esperaSinRedisMsEffective / 1000}s antes de cargar sus datos, para darle " +
            "tiempo al otro backend de terminar de guardarlo. Mientras dure, un cambio de servidor muy rápido " +
            "puede traer datos atrasados. (Este aviso se repite cada 5 min mientras siga pasando.)"
        )
        val srv = servidor ?: return
        srv.execute {
            val texto = Text.literal(
                "§c[GUARD] §fRedis " + (if (cuota) "agotó la cuota del mes" else "no responde") +
                ". §7La protección entre servidores está degradada; revisa la consola."
            )
            for (p in srv.playerManager.playerList) {
                if (Permissions.has(p.commandSource, "status")) p.sendMessage(texto, false)
            }
        }
    }

    /**
     * Qué hacer cuando no se puede preguntar a Redis si otro backend tiene al jugador.
     *
     * Antes se dejaba entrar al instante. Pero justo en un cambio de servidor el otro backend
     * está guardando en ese momento, y cargar sin esperar es leer la fila vieja. Sin Redis no
     * hay forma de saberlo, así que se le da un margen fijo ([GuardConfig.esperaSinRedisMs])
     * antes de cargar. No se expulsa: si Redis queda caído un mes, expulsar sería dejar a todos
     * sin jugar.
     */
    private fun esperarSinRedis(playerName: String, motivo: String): Boolean {
        avisarFalla(motivo)
        val ms = cfg.esperaSinRedisMsEffective
        CobblemonGuard.logger.warn("[CrossServer] Sin Redis para $playerName ($motivo): se espera ${ms}ms antes de cargar.")
        if (ms > 0) {
            try {
                Thread.sleep(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return true
    }

    fun close() {
        runCatching { pool?.close() }
        pool = null
        claimedHere.clear()
    }

    /**
     * Espera (bloqueante — llamar SIEMPRE fuera del hilo del servidor) a que el lock del
     * jugador quede libre, hasta [GuardConfig.crossServerWaitTimeoutMs]. Si ya está libre, o
     * la coordinación está desactivada, retorna al instante. Si Redis no responde, espera
     * [GuardConfig.esperaSinRedisMs] y deja seguir (ver [esperarSinRedis]).
     *
     * @return true si se puede proceder a cargar al jugador con seguridad.
     */
    fun awaitRelease(uuid: UUID, playerName: String): Boolean {
        if (!isEnabled()) return true
        val jedisPool = poolOReconectar() ?: return esperarSinRedis(playerName, "sin conexión")

        val deadline = System.currentTimeMillis() + cfg.crossServerWaitTimeoutMs
        var pollIntervalMs = 100L
        try {
            while (true) {
                // IMPORTANTE: la conexión se toma y se SUELTA en cada vuelta del poll.
                // Antes el `use { }` envolvía todo el bucle, así que la conexión quedaba
                // retenida mientras el hilo dormía (hasta crossServerWaitTimeoutMs, 8s por
                // defecto). Con maxTotal=8 en el pool de Jedis, bastaban 9 jugadores
                // entrando a la vez —justo lo que pasa al reiniciar el proxy o un backend—
                // para agotar el pool: los siguientes se bloqueaban esperando recurso o
                // reventaban con JedisExhaustedPoolException. Dormir NO requiere tener una
                // conexión tomada.
                val owner = conRedis(jedisPool) { jedis -> jedis.get(lockKey(uuid)) }
                if (owner == null || owner == cfg.serverId) return true

                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) {
                    CobblemonGuard.logger.warn(
                        "[CrossServer] Timeout esperando liberación del lock de $playerName " +
                        "(retenido por backend '$owner') tras ${cfg.crossServerWaitTimeoutMs}ms — " +
                        "se deja entrar de todas formas para no trancarlo."
                    )
                    return false
                }
                Thread.sleep(minOf(pollIntervalMs, remaining))
                pollIntervalMs = (pollIntervalMs * 2).coerceAtMost(500L) // backoff suave
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return true
        } catch (e: Exception) {
            return esperarSinRedis(playerName, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Reclama el lock para ESTE backend (llamar justo después de terminar de cargar al jugador). */
    /**
     * Toma el lock SOLO si está libre o ya es de este backend. Antes era un SET a secas, y un
     * claim que llegaba tarde (Redis lento, cola del hilo de Redis) podía pisar el lock que otro
     * backend acababa de tomar legítimamente: el jugador ya se había pasado a ese otro, el otro lo
     * había cargado y reclamado, y este SET atrasado lo dejaba a nombre de un backend donde el
     * jugador ya no estaba. Ninguno de los dos lo renovaba y el lock caducaba con el jugador
     * jugando: al pasar al siguiente backend, ese no esperaba su guardado.
     */
    private val CLAIM_IF_FREE_OR_MINE = """
        local actual = redis.call('GET', KEYS[1])
        if actual == false or actual == ARGV[1] then
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])
            return 1
        else
            return 0
        end
    """.trimIndent()

    fun claim(uuid: UUID) {
        if (!isEnabled()) return
        val jedisPool = poolOReconectar() ?: return
        try {
            val tomado = conRedis(jedisPool) { jedis ->
                jedis.eval(
                    CLAIM_IF_FREE_OR_MINE,
                    listOf(lockKey(uuid)),
                    listOf(cfg.serverId, cfg.crossServerLockTtlSeconds.toString())
                ) as? Long ?: 0L
            }
            if (tomado == 1L) {
                claimedHere.add(uuid)
            } else {
                CobblemonGuard.logger.warn(
                    "[CrossServer] No se reclama el lock de $uuid: ya lo tiene otro backend (este claim llegó tarde)."
                )
            }
        } catch (e: Exception) {
            CobblemonGuard.logger.warn("[CrossServer] No se pudo reclamar el lock de $uuid: ${e.message}")
            avisarFalla(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Qué backend tiene el lock de [uuid] ahora, o null si nadie (o si no se puede saber). */
    fun dueñoActual(uuid: UUID): String? {
        if (!isEnabled()) return null
        val jedisPool = pool ?: return null
        return runCatching { conRedis(jedisPool) { jedis -> jedis.get(lockKey(uuid)) } }.getOrNull()
    }

    // Renueva el TTL SOLO si seguimos siendo el dueño, de forma atómica (antes eran un GET
    // y un EXPIRE separados: entre ambos otro backend podía tomar el lock y le
    // renovábamos el TTL a un lock ajeno).
    //
    // Un solo script para TODOS los locks del backend: recibe todas las llaves y devuelve una
    // lista de 1/0 en el mismo orden. Antes era un EVAL por jugador (en pipeline): un solo viaje
    // de red, pero N comandos, y Upstash cobra por comando — con 20 jugadores conectados todo
    // el día eran más de 3 millones al mes. Ahora es 1 comando por backend cada 15 s, haya los
    // jugadores que haya.
    private val RENEW_ALL_IF_OWNER = """
        local res = {}
        for i, k in ipairs(KEYS) do
            if redis.call('GET', k) == ARGV[1] then
                res[i] = redis.call('EXPIRE', k, ARGV[2])
            else
                res[i] = 0
            end
        end
        return res
    """.trimIndent()

    /** Máximo de llaves por llamada al script de renovación (para no mandar un EVAL gigante). */
    private const val LLAVES_POR_RENOVACION = 500

    /**
     * Renueva el TTL de todos los jugadores reclamados por este backend. Llamar
     * periódicamente (heartbeat).
     *
     * Va todo en UN comando ([RENEW_ALL_IF_OWNER], de a [LLAVES_POR_RENOVACION] llaves): un
     * solo viaje de red y un solo comando facturable por pasada, sin importar cuántos
     * jugadores haya conectados.
     */
    fun heartbeatAll(sigueAqui: (UUID) -> Boolean = { true }) {
        if (!isEnabled() || claimedHere.isEmpty()) return
        val jedisPool = pool ?: return

        // Solo se le renueva el TTL a quien SIGUE vinculado a este backend. Un lock de un
        // jugador que ya no esta aca es un lock huerfano: paso por el camino de "el guardado
        // fallo, no soltamos el lock", y no hay nada en vuelo que lo vaya a soltar despues.
        // Renovarselo cada 15 s lo volvia eterno, y eso no es proteccion: es un baneo. El
        // jugador quedaba expulsado de TODOS los backends hasta que un admin corriera
        // /cg unlock, y nada se lo decia a nadie.
        //
        // Dejando de renovarlo, el lock caduca solo en crossServerLockTtlSeconds. La ventana
        // sigue protegiendo lo que tiene que proteger (que nadie cargue mientras el otro
        // backend escribe), pero es una ventana y no una condena.
        //
        // Hacen falta AUSENCIAS_PARA_SOLTAR pasadas seguidas, no una. [sigueAqui] se arma en
        // el hilo del servidor y esta tarea corre despues, en el dbExecutor, que tiene cola
        // ilimitada: tras un reinicio del proxy, con decenas de joins encolando claim() y
        // logSync(), esta tarea puede esperar segundos. En esa ventana un jugador entra y
        // claim() lo mete en claimedHere — y como no estaba en el snapshot, con una sola
        // pasada se lo declaraba huerfano y se le cortaba el heartbeat a un jugador que esta
        // jugando aca. El lock le caducaba en mitad de la sesion y nadie lo volvia a reclamar:
        // si despues el proxy lo movia, el otro backend leia MySQL sin esperar el guardado de
        // este. Un UUID que aparecio tarde se salva porque la proxima pasada ya lo ve.
        for (uuid in claimedHere) {
            if (sigueAqui(uuid)) ausencias.remove(uuid)
        }
        val huerfanos = claimedHere.filter {
            !sigueAqui(it) && ausencias.merge(it, 1, Int::plus)!! >= AUSENCIAS_PARA_SOLTAR
        }
        if (huerfanos.isNotEmpty()) {
            claimedHere.removeAll(huerfanos.toSet())
            huerfanos.forEach { ausencias.remove(it) }
            CobblemonGuard.logger.warn(
                "[CrossServer] ${huerfanos.size} lock(s) sin jugador online aca: ${huerfanos.joinToString(", ")}. " +
                "Se deja de renovarlos; caducan solos en ${cfg.crossServerLockTtlSeconds}s. " +
                "Casi siempre esto significa que su guardado en MySQL fallo: revisa los [SAVE] de arriba. " +
                "Si alguno necesita entrar YA, /cg unlock <jugador>."
            )
        }
        ausencias.keys.retainAll(claimedHere)

        val uuids = claimedHere.toList()   // snapshot estable para casar respuestas con uuids
        if (uuids.isEmpty()) return
        val ttl = cfg.crossServerLockTtlSeconds.toString()
        try {
            for (tanda in uuids.chunked(LLAVES_POR_RENOVACION)) {
                val respuesta = conRedis(jedisPool) { jedis ->
                    jedis.eval(RENEW_ALL_IF_OWNER, tanda.map { lockKey(it) }, listOf(cfg.serverId, ttl))
                } as? List<*>
                if (respuesta == null || respuesta.size != tanda.size) {
                    // Respuesta inesperada: no se saca a nadie de claimedHere por esto. Peor
                    // caso, la próxima pasada lo vuelve a intentar.
                    CobblemonGuard.logger.warn(
                        "[CrossServer] Heartbeat: respuesta inesperada de Redis (${respuesta?.size} de ${tanda.size})."
                    )
                    continue
                }
                tanda.forEachIndexed { i, uuid ->
                    // 0 = ya no somos dueños (otro backend lo tomó, o el lock expiró):
                    // dejamos de hacerle heartbeat.
                    val renovado = (respuesta[i] as? Long) ?: 0L
                    if (renovado == 0L) claimedHere.remove(uuid)
                }
            }
        } catch (e: Exception) {
            CobblemonGuard.logger.warn("[CrossServer] Error en heartbeat de locks: ${e.message}")
            avisarFalla(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * true si el lock de [uuid] lo tiene OTRO backend en este momento.
     *
     * Lo usan los reintentos de guardado antes de volver a escribir. Si mientras reintentaban
     * el lock de este backend se perdió (Redis cortado más que el TTL) y otro backend lo tomó,
     * ese otro ya cargó al jugador y puede haber guardado algo más nuevo; escribir encima el
     * blob viejo de acá sería retroceder su progreso.
     *
     * Ante la duda devuelve false (sin cross-server, sin Redis, sin lock, o error de red): en
     * esos casos no hay forma de saber si alguien más lo tiene, y abortar un guardado por no
     * poder preguntar convertiría cualquier corte de Redis en sesiones perdidas.
     */
    fun otroEsDueño(uuid: UUID): Boolean {
        if (!isEnabled()) return false
        val jedisPool = pool ?: return false
        return runCatching {
            val owner = conRedis(jedisPool) { jedis -> jedis.get(lockKey(uuid)) }
            owner != null && owner != cfg.serverId
        }.getOrDefault(false)
    }

    /**
     * Libera el lock de [uuid] — llamar SOLO después de confirmar que el guardado en
     * MySQL terminó exitosamente (si liberamos antes y el guardado falla, el otro
     * backend podría cargar datos viejos).
     */
    fun release(uuid: UUID) {
        claimedHere.remove(uuid)
        if (!isEnabled()) return
        val jedisPool = pool ?: return
        try {
            conRedis(jedisPool) { jedis ->
                // Solo borra si el dueño actual somos nosotros (CAS simple vía Lua para evitar
                // borrar el lock de un backend que ya nos ganó la carrera).
                val script = """
                    if redis.call('GET', KEYS[1]) == ARGV[1] then
                        return redis.call('DEL', KEYS[1])
                    else
                        return 0
                    end
                """.trimIndent()
                jedis.eval(script, 1, lockKey(uuid), cfg.serverId)
            }
        } catch (e: Exception) {
            CobblemonGuard.logger.warn("[CrossServer] No se pudo liberar el lock de $uuid: ${e.message}")
        }
    }

    /** Libera SIN chequear dueño — solo para /cg saving stop o limpieza administrativa. */
    fun forceRelease(uuid: UUID) {
        claimedHere.remove(uuid)
        if (!isEnabled()) return
        val jedisPool = pool ?: return
        try {
            conRedis(jedisPool) { jedis -> jedis.del(lockKey(uuid)) }
        } catch (e: Exception) {
            CobblemonGuard.logger.warn("[CrossServer] No se pudo forzar la liberación del lock de $uuid: ${e.message}")
        }
    }
}
