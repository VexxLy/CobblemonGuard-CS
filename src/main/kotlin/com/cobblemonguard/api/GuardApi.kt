package com.cobblemonguard.api

import com.cobblemonguard.CobblemonGuard
import net.minecraft.server.network.ServerPlayerEntity
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/**
 * API PÚBLICA Y ESTABLE de CobblemonGUARD para otros mods de la red.
 *
 * Existe porque CobbleMMO (cobblemmo-server) necesita exactamente dos cosas que GUARD ya
 * tiene resueltas y que no tiene sentido duplicar:
 *
 *  1. **Una conexión a la MySQL compartida.** GUARD ya trae el driver de MySQL shadeado y
 *     un pool de HikariCP andando. Si cada mod trajera el suyo tendríamos dos drivers, dos
 *     pools, dos juegos de credenciales que se pueden desincronizar y un jar más pesado.
 *     [getConnection] presta una conexión de ESE pool.
 *
 *  2. **Saber cuándo los datos del jugador ya están cargados desde MySQL.** GUARD congela
 *     al jugador al entrar y aplica su equipo/PC/inventario un rato después, de forma
 *     asíncrona. Cualquier mod que lea o escriba datos del jugador antes de ese momento ve
 *     un estado viejo o vacío. [onPlayerDataLoaded] avisa en el instante exacto, en el hilo
 *     del servidor, y evita que los otros mods tengan que adivinar con temporizadores.
 *
 * ## Cómo consumirla desde otro mod (por reflexión, sin compilar contra GUARD)
 *
 * ```java
 * Class<?> api = Class.forName("com.cobblemonguard.api.GuardApi");
 * if ((Integer) api.getMethod("apiVersion").invoke(null) >= 1) {
 *     Connection c = (Connection) api.getMethod("getConnection").invoke(null);
 * }
 * ```
 *
 * Todos los métodos son `@JvmStatic`, así que por reflexión se invocan con receptor `null`
 * (no hace falta buscar el campo `INSTANCE` de Kotlin). Los tipos que cruzan la frontera
 * son del JDK o de Minecraft a propósito: nada de Hikari ni del driver de MySQL, que están
 * relocalizados a `com.cobblemonguard.libs.*` y no son visibles con su nombre original.
 *
 * ## Contrato
 *
 * - [apiVersion] solo sube cuando se agrega algo. Nunca se borra ni se cambia la firma de
 *   un método existente: los mods que dependen de esto se compilan aparte y no se
 *   recompilan cuando GUARD cambia.
 * - Los listeners se disparan SIEMPRE en el hilo del servidor.
 * - Una excepción de un listener se loguea y se ignora: un mod de terceros no puede romper
 *   el ingreso de un jugador.
 *
 * Los métodos `fire*` son internos de GUARD (los llama [com.cobblemonguard.events.PlayerEventHandler]).
 * Están públicos solo porque Kotlin le cambia el nombre a lo `internal` al compilarlo; no
 * los llames desde otro mod.
 */
object GuardApi {

    /** Sube de a uno cada vez que se AGREGA algo. Lo existente nunca cambia de firma. */
    const val API_VERSION = 1

    private val loadedListeners = CopyOnWriteArrayList<Consumer<ServerPlayerEntity>>()
    private val savingListeners = CopyOnWriteArrayList<Consumer<ServerPlayerEntity>>()

    @JvmStatic
    fun apiVersion(): Int = API_VERSION

    // ══════════════════════════════════════════════════════
    // Base de datos
    // ══════════════════════════════════════════════════════

    /**
     * true si el pool de MySQL está conectado y puede prestar conexiones. Conviene
     * chequearlo antes de [getConnection] en vez de atrapar la excepción.
     */
    @JvmStatic
    fun isDatabaseReady(): Boolean = runCatching {
        CobblemonGuard.databaseOrNull()?.isConnected() == true
    }.getOrDefault(false)

    /**
     * Presta una conexión del pool de GUARD. **El que la pide la cierra**
     * (`try (Connection c = ...)`), que es lo que la devuelve al pool.
     *
     * No llamar desde el hilo del servidor: pedir una conexión puede esperar si el pool
     * está lleno, y cualquier consulta viaja por red.
     *
     * @throws IllegalStateException si la base no está conectada.
     */
    @JvmStatic
    fun getConnection(): Connection {
        val db = CobblemonGuard.databaseOrNull()
            ?: throw IllegalStateException("CobblemonGUARD no inicializó su base de datos.")
        return db.getConnection()
    }

    /** Nombre de este backend según la config de GUARD (`serverId`). Cadena vacía si no hay config. */
    @JvmStatic
    fun serverId(): String = runCatching {
        CobblemonGuard.configOrNull()?.serverId ?: ""
    }.getOrDefault("")

    /** true si GUARD está operando en modo red (varios backends contra la misma MySQL). */
    @JvmStatic
    fun isCrossServerEnabled(): Boolean = runCatching {
        CobblemonGuard.configOrNull()?.crossServerSyncEnabled == true
    }.getOrDefault(false)

    // ══════════════════════════════════════════════════════
    // Ciclo de vida de los datos del jugador
    // ══════════════════════════════════════════════════════

    /**
     * Se dispara (en el hilo del servidor) cuando GUARD terminó de aplicarle al jugador sus
     * datos de MySQL y lo descongeló. A partir de acá su equipo, su PC y su inventario son
     * los de la red, no los del disco local de este backend.
     *
     * Es el momento correcto para que otro mod cargue lo suyo del jugador.
     *
     * Registrar en `onInitialize()`. Los listeners no se pueden quitar a propósito: se
     * registran una vez al arrancar y viven lo que vive el servidor.
     */
    @JvmStatic
    fun onPlayerDataLoaded(listener: Consumer<ServerPlayerEntity>) {
        loadedListeners.add(listener)
    }

    /**
     * Se dispara (en el hilo del servidor) justo ANTES de que GUARD serialice y guarde al
     * jugador al desconectarse. Es la última ventana para volcar datos propios mientras el
     * jugador todavía existe.
     *
     * Lo que se haga acá tiene que ser corto: corre en el hilo del servidor y demora la
     * desconexión. Si hay que escribir a la base, encolarlo en un hilo propio.
     */
    @JvmStatic
    fun onPlayerDataSaving(listener: Consumer<ServerPlayerEntity>) {
        savingListeners.add(listener)
    }

    /** true si GUARD ya cargó los datos de este jugador desde MySQL en este backend. */
    @JvmStatic
    fun isPlayerDataLoaded(uuid: UUID): Boolean =
        com.cobblemonguard.events.PlayerEventHandler.isDataLoaded(uuid)

    // ── Uso interno de GUARD ──────────────────────────────────────────────

    /** Interno de GUARD. Puede llamarse desde cualquier hilo; despacha en el del servidor. */
    @JvmStatic
    fun fireDataLoaded(player: ServerPlayerEntity) =
        dispatch(loadedListeners, player, "cargado", omitirSiSalio = true)

    /**
     * Interno de GUARD. Se dispara durante la DESCONEXION, donde el jugador puede estar ya
     * marcado como removido de la entidad-lista; por eso acá NO se omite por `isRemoved`
     * (si se omitiera, el evento no llegaría nunca y los otros mods perderían su última
     * chance de volcar datos).
     */
    @JvmStatic
    fun fireDataSaving(player: ServerPlayerEntity) =
        dispatch(savingListeners, player, "guardando", omitirSiSalio = false)

    private fun dispatch(
        listeners: List<Consumer<ServerPlayerEntity>>,
        player: ServerPlayerEntity,
        evento: String,
        omitirSiSalio: Boolean,
    ) {
        if (listeners.isEmpty()) return
        val server = player.server
        val run = Runnable {
            if (omitirSiSalio && player.isRemoved) return@Runnable
            for (l in listeners) {
                try {
                    l.accept(player)
                } catch (e: Throwable) {
                    // Un mod de terceros no puede romper el ingreso ni la salida de un jugador.
                    CobblemonGuard.logger.error(
                        "[API] Un listener de '$evento' falló para ${player.name.string}: ${e.message}", e
                    )
                }
            }
        }
        if (server.isOnThread) run.run() else server.execute(run)
    }
}
