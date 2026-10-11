package com.cobblemonguard

import com.cobblemonguard.commands.GuardCommand
import com.cobblemonguard.config.GuardConfig
import com.cobblemonguard.database.BackupRepository
import com.cobblemonguard.database.DatabaseManager
import com.cobblemonguard.database.PlayerDataRepository
import com.cobblemonguard.discord.DiscordWebhook
import com.cobblemonguard.events.PlayerEventHandler
import com.cobblemonguard.network.CrossServerCoordinator
import com.cobblemonguard.sync.LivePastureSync
import com.cobblemonguard.sync.SyncRegistry
import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory

object CobblemonGuard : ModInitializer {

    const val MOD_ID = "cobblemonguard"
    val logger = LoggerFactory.getLogger(MOD_ID)!!

    /**
     * Versión real del mod, leída de los metadatos de Fabric (o sea, de
     * `gradle.properties` → `fabric.mod.json`). Antes había un "1.0.0" hardcodeado en
     * [com.cobblemonguard.discord.DiscordWebhook] que quedó dos versiones desactualizado.
     */
    val modVersion: String by lazy {
        runCatching {
            net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map { it.metadata.version.friendlyString }
                .orElse("desconocida")
        }.getOrDefault("desconocida")
    }

    lateinit var config: GuardConfig
        private set

    lateinit var database: DatabaseManager
        private set

    lateinit var repository: PlayerDataRepository
        private set

    lateinit var backupRepository: BackupRepository
        private set

    /**
     * Acceso seguro a [database] para [com.cobblemonguard.api.GuardApi]: devuelve null en
     * vez de tirar `UninitializedPropertyAccessException` si GUARD está desactivado en la
     * config o si MySQL no conectó. Otro mod puede preguntar en cualquier momento del
     * arranque, incluso antes de que este `onInitialize` haya corrido.
     */
    fun databaseOrNull(): DatabaseManager? = if (::database.isInitialized) database else null

    /** Igual que [databaseOrNull], para la config. */
    fun configOrNull(): GuardConfig? = if (::config.isInitialized) config else null

    /**
     * Recarga la config desde disco en caliente. Devuelve la nueva config.
     * OJO: se aplican al instante los flags de sync, Discord, retención de backups y
     * savingEnabled. NO se aplican sin reiniciar: credenciales MySQL (host/user/pass) y
     * los intervalos de auto-save/backup (los schedulers se registran al arrancar).
     */
    fun reloadConfig(): GuardConfig {
        config = GuardConfig.load()
        return config
    }

    override fun onInitialize() {
        logger.info("=== CobblemonGUARD iniciando ===")

        config = GuardConfig.load()

        if (!config.enabled) {
            logger.warn("CobblemonGUARD está desactivado en la config. Edita config/cobblemonguard.json")
            return
        }

        try {
            database = DatabaseManager(config)
            database.connect()
            database.createTables()
            repository = PlayerDataRepository(database)
            backupRepository = BackupRepository(database)
            CrossServerCoordinator.connect()
            PlayerEventHandler.register()
            LivePastureSync.register()
            GuardCommand.register()
            logger.info("CobblemonGUARD listo. Sincronizando con MySQL en ${config.host}:${config.port}/${config.database}")
            if (config.crossServerSyncEnabled) {
                logger.info(
                    "CrossServerSync ${if (CrossServerCoordinator.isConnected()) "ACTIVO" else "DEGRADADO (sin Redis)"} " +
                    "— serverId='${config.serverId}'."
                )
            }
            avisarTiposDesactivados()
            DiscordWebhook.sendServerReady(config.host, config.database)
        } catch (e: Exception) {
            logger.error("CobblemonGUARD no pudo conectar a MySQL: ${e.message}")
            logger.error("Revisa config/cobblemonguard.json y asegúrate que la base de datos existe.")
            DiscordWebhook.sendConnectionError(e.message ?: "Error desconocido")
        }
    }

    /**
     * Avisa en el arranque qué tipos de dato están DESACTIVADOS, porque volver a activarlos
     * no es una operación inocente.
     *
     * Las columnas se escriben con COALESCE (un null nunca las pisa), así que mientras un
     * `syncX` está en false su columna deja de actualizarse pero NO se borra: queda congelada
     * en el estado del día que se apagó. El día que alguien la vuelve a activar, ese estado
     * viejo se le aplica al jugador encima del que tiene ahora — y si en el medio gastó ítems
     * o movió Pokémon a un sistema compartido (GTS, trade), eso se duplica.
     *
     * No se arregla solo, y no se PUEDE arreglar solo: el mod no tiene forma de distinguir
     * "esta columna está vieja porque la apagaste" de "está vieja porque otro backend de la
     * red la maneja". Lo único correcto acá es que el admin lo sepa antes de tocar el flag.
     */
    private fun avisarTiposDesactivados() {
        val apagados = SyncRegistry.syncers.filterNot { it.isEnabled() }.map { it.type.name }
        if (apagados.isEmpty()) return
        logger.warn(
            "[GUARD] Tipos de dato DESACTIVADOS en este backend: ${apagados.joinToString(", ")}. " +
            "Sus columnas en MySQL NO se actualizan, pero tampoco se borran. Si alguna vez " +
            "reactivas uno, a cada jugador se le va a aplicar el estado que tenía el día que lo " +
            "apagaste (retroceso de datos, y duplicación de lo que haya movido en el medio). " +
            "Para reactivarlo sin riesgo hay que vaciar esa columna primero (UPDATE " +
            "cobblemonguard_players SET <columna> = NULL) con el servidor apagado."
        )
    }
}
