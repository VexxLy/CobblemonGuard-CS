package com.cobblemonguard.config

import com.cobblemonguard.CobblemonGuard
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.nio.file.Paths

data class GuardConfig(
    val enabled: Boolean = true,

    // Conexión MySQL
    val host: String = "localhost",
    val port: Int = 3306,
    val database: String = "cobblemonguard",
    val username: String = "root",
    val password: String = "tu_password_aqui",

    // Pool de conexiones. En el apagado del servidor se guardan TODOS los jugadores
    // online EN PARALELO — si poolSize es menor que el pico de jugadores simultáneos,
    // los guardados se hacen cola esperando una conexión libre y el apagado puede tardar
    // más (o, con hosting que mata el proceso rápido, no llegar a terminar). Regla
    // aproximada: al menos tantas conexiones como jugadores simultáneos esperados en un
    // pico (o el máximo que tu hosting de MySQL permita, lo que sea menor).
    val poolSize: Int = 10,
    val connectionTimeoutMs: Long = 30_000,

    // Identificar a los jugadores por NOMBRE en vez de UUID.
    // Úsalo en servidores OFFLINE: al pasar de online→offline el UUID cambia, y con
    // esto los jugadores recuperan sus datos guardados por su nombre. (En offline los
    // nombres son la identidad; NO usar en online donde la gente cambia de nombre.)
    val identifyByName: Boolean = false,

    // Con identifyByName=false (modo UUID, el normal en online): si NO existe ninguna
    // fila para el UUID del jugador, antes de asumir "jugador nuevo" se intenta UNA
    // búsqueda de respaldo por player_name (la fila más reciente con ese nombre). Si la
    // encuentra, la "reclama" para el UUID actual (UPDATE del uuid de esa fila) — de ahí
    // en adelante ya matchea directo por UUID, sin necesitar el respaldo de nuevo.
    // Pensado para: migrar un server de offline→online (o viceversa), un jugador que
    // perdió su UUID original (reinstalación de un proxy/auth distinto), o cualquier
    // caso donde quedó una fila vieja asociada solo al nombre.
    // CUIDADO: si el nombre fue REUTILIZADO por otra persona (el dueño original lo dejó
    // libre y alguien más lo tomó), esto podría reclamar la fila equivocada — es el
    // mismo riesgo que ya asume `identifyByName=true`. No aplica cuando
    // identifyByName=true (ese modo ya busca por nombre directamente).
    //
    // Nullable A PROPÓSITO (no Boolean=true): Gson, al leer un config.json VIEJO (de
    // antes de este campo) que no tiene esta clave, la deja en `null` — NO en el default
    // de Kotlin. Si fuera un Boolean plano, Gson la pondría en `false` (el default de
    // Java) para cualquiera que actualice desde una versión anterior, invirtiendo el
    // comportamiento pretendido. Usa siempre `identifyByNameFallbackEffective` (abajo),
    // nunca este campo directo.
    val identifyByNameFallback: Boolean? = null,

    // Qué sincronizar
    val syncInventory: Boolean = true,
    val syncEnderChest: Boolean = true,
    val syncCobblemonPC: Boolean = true,
    val syncCobblemonParty: Boolean = true,
    val syncAccessories: Boolean = true,
    val syncBackpackContents: Boolean = true,

    // Sincronizar saldos de Impactor (economía). No hace nada si Impactor no está instalado.
    val syncEconomy: Boolean = true,

    // Keys de moneda ("namespace:value") a sincronizar ADEMÁS de las que se logren
    // detectar automáticamente en Impactor (la detección automática usa `registered()`/
    // `all()`, que el propio código marca como NO confirmada contra la API real — ver
    // ImpactorEconomy — así que estas keys explícitas son la vía que SIEMPRE funciona,
    // sin depender de esa detección).
    //
    // Verificadas contra el economy.conf REAL de Impactor de este server (2026-09):
    //   "impactor:dollars"     — el dólar (moneda PRIMARIA, "primary = true" en su config)
    //   "impactor:arceuscoin"  — ArceusCoin
    //   "impactor:eventpoints" — Event Points
    // (el default previo traía "cobblemmo:arceuscoin", que NO es la key real — ese
    // namespace no existe en el economy.conf de Impactor; era un respaldo que en la
    // práctica nunca podía matchear nada).
    val economyCurrencyKeys: List<String> = listOf(
        "impactor:dollars",
        "impactor:arceuscoin",
        "impactor:eventpoints"
    ),

    // Guardado "serio" activo. Ponlo en false durante la transferencia/testeo para que
    // el mod NO escriba nada en la BD hasta que actives con /cg saving start.
    // (La CARGA/restauración sí funciona; solo se pausan las ESCRITURAS.)
    val savingEnabled: Boolean = true,

    // Sincronizar al ENTRAR/SALIR (cargar del/guardar al MySQL en join/quit).
    // false = el mod NO toca el inventario/PC al entrar ni salir (lo maneja el guardado
    //         normal de Minecraft). Solo quedan activos los BACKUPS + comandos manuales
    //         (/cg save, /cg restore, /cg rollback, /cg backup). Útil si la sync en vivo
    //         está dando problemas y solo quieres el sistema de respaldos.
    val syncOnJoinLeave: Boolean = true,

    // Qué hacer si al ENTRAR un tipo de dato habilitado tenía datos en MySQL y NO se pudo
    // aplicar (la reflexión contra un mod se rompió con una actualización, un snapshot dañado).
    //   true (por defecto) = se expulsa al jugador sin marcarlo como cargado, así que no se
    //        guarda nada suyo y MySQL queda intacta. Si el problema es general, nadie entra a
    //        este backend: es ruidoso a propósito.
    //   false = se lo deja entrar igual. OJO: para ese tipo juega con los datos LOCALES de este
    //        backend, y si ese tipo sí se puede guardar, lo local termina pisando a MySQL. Con PC
    //        y equipo es peor: si uno se aplica y el otro no, un Pokémon que en otro backend pasó
    //        del equipo a la PC (o al revés) puede quedar en los DOS, y eso se guarda. Es una
    //        válvula de escape para salir del paso mientras se arregla, no un modo normal.
    // Para un solo jugador trabado es mejor /cg restaurarfila <jugador> <tiempo> <tipo>.
    // Nullable por la misma razón que identifyByNameFallback: un config viejo debe quedar en true.
    val expulsarSiFallaAlCargar: Boolean? = null,

    // Guardar cada X segundos mientras el jugador está online (0 = solo al salir).
    // Solo aplica si syncOnJoinLeave = true.
    val autoSaveIntervalSeconds: Int = 0,

    // Sistema de respaldos con historial
    val backupEnabled: Boolean = true,
    val backupIntervalSeconds: Int = 300,  // cada 5 minutos
    val backupRetentionHours: Int = 192,   // 8 días (se conservan ~7-8 días y recién ahí se borran)

    // No insertar un backup si el estado del jugador es IDÉNTICO al de su backup anterior.
    // Con el intervalo por defecto (5 min) la gran mayoría de los backups de un jugador son
    // copias exactas de la anterior —su PC casi nunca cambia entre dos backups seguidos— y
    // cada fila guarda el estado COMPLETO (inventario + ender + PC + party + accesorios +
    // mochilas). En una red con decenas de jugadores activos eso son decenas de GB de
    // duplicados. Con esto activado se compara un hash del estado con el del último backup
    // guardado y se omite la inserción si no cambió nada; el rollback sigue funcionando
    // igual (el backup existente ya representa ese estado).
    // Nullable a propósito, misma razón que identifyByNameFallback (ver su doc): un
    // config.json viejo sin esta clave debe quedar en TRUE, no en el false de Java.
    val backupDedupEnabled: Boolean? = null,

    // Escribir en cobblemonguard_sync_log SOLO los fallos, en vez de cada SAVE/LOAD.
    // El log de éxitos era una fila (y una conexión extra del pool) por cada guardado de
    // cada jugador — con auto-save activo son millones de filas al año y el doble de
    // tráfico a MySQL por guardado, para información que casi nunca se consulta.
    // Ponlo en false si estás depurando y querés el historial completo.
    val syncLogOnlyErrors: Boolean? = null,

    // Retención del log de sincronización, en horas (0 = no borrar nunca).
    // Antes esta tabla no se limpiaba NUNCA — solo había un DELETE manual comentado en
    // sql/setup.sql. Ahora se limpia en el mismo ciclo periódico que los backups.
    val syncLogRetentionHours: Int? = null,

    // Discord Webhook
    val discordEnabled: Boolean = false,
    val discordWebhookUrl: String = "",
    val discordNotifyFirstJoin: Boolean = true,
    val discordNotifySyncErrors: Boolean = true,
    val discordNotifyRollbacks: Boolean = true,

    // ── Cross-server sync (red multi-backend detrás de Velocity) ──────────
    // Cuando un jugador cambia de servidor Fabric (ej. overworld -> dimensión custom
    // en su propio backend), CADA backend ve esto como un DISCONNECT + JOIN normal e
    // independiente — no hay forma de saber, sin coordinación externa, si el otro
    // backend YA terminó de guardar. Sin esto, el nuevo backend podría cargar del PC
    // desde disco/MySQL mientras el jugador SIGUE vivo en el otro proceso: dos copias
    // del mismo Pokémon en memoria a la vez = dupeo. Redis resuelve esto con un lock
    // de bajísima latencia (compartido entre TODOS los backends), sin tocar el proxy
    // Velocity ni FabricProxy-Lite: cada backend congela al jugador en el JOIN
    // (reusa FreezeScreen) hasta confirmar que el backend anterior liberó el lock.
    val crossServerSyncEnabled: Boolean = false,

    // Identificador ÚNICO de ESTE backend (ej. "overworld", "dimension-void").
    // Debe ser distinto en cada servidor Fabric de la red. Solo para logs/diagnóstico.
    val serverId: String = "server-1",

    val redisHost: String = "localhost",
    val redisPort: Int = 6379,
    val redisPassword: String = "",
    // Usuario ACL de Redis. Vacío o null = el usuario "default" (lo normal: Upstash, Redis
    // Cloud y un Redis propio con requirepass usan solo la contraseña). Nullable porque Gson
    // deja en null los campos que no estaban en un config.json viejo.
    val redisUsername: String? = null,
    // Conexión cifrada (TLS). OBLIGATORIO en true con Upstash (no deja conectarse sin TLS) y
    // con cualquier Redis al que se llega por internet. En false solo para un Redis en la
    // misma máquina o en una red privada.
    val redisSsl: Boolean = false,

    // Cuánto espera (congelado) el backend DESTINO a que el backend ORIGEN libere el
    // lock antes de dejarlo entrar de todas formas (evita colgar al jugador si el otro
    // backend se cayó a mitad del guardado). En ms.
    val crossServerWaitTimeoutMs: Long = 8_000,

    // TTL del lock en Redis (segundos). Se auto-renueva mientras el jugador está
    // online (heartbeat); solo actúa como red de seguridad si un backend se cae sin
    // liberar el lock correctamente (crash, kill -9, corte de luz, etc).
    val crossServerLockTtlSeconds: Int = 45,

    // Si Redis NO responde (caído, sin conexión, o la cuota mensual de Upstash agotada), no
    // hay forma de saber si otro backend sigue guardando al jugador que entra. En vez de
    // cargarlo al instante, se espera esto (ms, congelado) para darle tiempo a ese guardado
    // y después se carga. Además se avisa en consola y a los staff con `cobblemonguard.status`.
    // 0 = no esperar (comportamiento viejo). Null = 5000. Máximo 30000.
    val esperaSinRedisMs: Long? = null,

    // ── Sync en vivo de PASTURAS (ranchos) en redes multi-backend ─────────
    // Las pasturas de Cobblemon acceden a la PC del dueño DIRECTO por UUID
    // (Cobblemon.storage.getPC(uuid, registryAccess)), sin pasar por join/disconnect —
    // así que si el dueño está online en OTRO backend, una pastura en ESTE backend puede
    // quedar leyendo una copia vieja de su PC hasta que el dueño vuelva a entrar acá (hoy
    // se autocorrige recién en ese próximo login, no en tiempo real).
    //
    // Con esto en true, se registra una factory propia (vía Proxy, sin compilar contra
    // Cobblemon) que SOLO actúa para jugadores OFFLINE en este backend: dispara un
    // refresco asíncrono desde MySQL hacia el store en vivo de Cobblemon en este backend
    // (mismo merge por UUID que usa el resto del mod). Nunca toca a un jugador online,
    // nunca hace I/O de MySQL en el hilo del servidor, y nunca sirve datos directo — en el
    // peor caso (falla, o Cobblemon cambió su API interna) simplemente queda inerte y todo
    // sigue exactamente como sin esta opción.
    //
    // Sin efecto si `crossServerSyncEnabled=false` (en single-server las pasturas ya son
    // consistentes — comparten el mismo proceso). Por eso arranca en false: es la pieza
    // más nueva/experimental del mod — actívala cuando ya tengas cross-server funcionando
    // bien y quieras cerrar este último gap.
    val livePastureSyncEnabled: Boolean = false
) {
    /** Ver doc de [expulsarSiFallaAlCargar] — úsalo SIEMPRE en vez del campo directo. */
    val expulsarSiFallaAlCargarEffective: Boolean get() = expulsarSiFallaAlCargar ?: true

    val esperaSinRedisMsEffective: Long get() = (esperaSinRedisMs ?: 5_000L).coerceIn(0L, 30_000L)

    /** Ver doc de [identifyByNameFallback] — úsalo SIEMPRE en vez del campo directo. */
    val identifyByNameFallbackEffective: Boolean get() = identifyByNameFallback ?: true

    /** Ver doc de [backupDedupEnabled] — úsalo SIEMPRE en vez del campo directo. */
    val backupDedupEnabledEffective: Boolean get() = backupDedupEnabled ?: true

    /** Ver doc de [syncLogOnlyErrors] — úsalo SIEMPRE en vez del campo directo. */
    val syncLogOnlyErrorsEffective: Boolean get() = syncLogOnlyErrors ?: true

    /** Ver doc de [syncLogRetentionHours] — úsalo SIEMPRE en vez del campo directo. */
    val syncLogRetentionHoursEffective: Int get() = syncLogRetentionHours ?: 168

    companion object {
        private val GSON: Gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
        private val CONFIG_FILE: File = Paths.get("config", "cobblemonguard.json").toFile()

        fun load(): GuardConfig {
            if (!CONFIG_FILE.exists()) {
                val default = GuardConfig()
                save(default)
                CobblemonGuard.logger.info("Config creada en: ${CONFIG_FILE.absolutePath}")
                CobblemonGuard.logger.info("¡Edita la config con tus credenciales MySQL antes de usar el mod!")
                return default
            }
            return try {
                GSON.fromJson(CONFIG_FILE.readText(), GuardConfig::class.java).also {
                    CobblemonGuard.logger.info("Config cargada desde: ${CONFIG_FILE.absolutePath}")
                }
            } catch (e: Exception) {
                CobblemonGuard.logger.error("Error leyendo config, usando valores por defecto: ${e.message}")
                GuardConfig()
            }
        }

        fun save(config: GuardConfig) {
            CONFIG_FILE.parentFile?.mkdirs()
            CONFIG_FILE.writeText(GSON.toJson(config))
        }
    }
}
