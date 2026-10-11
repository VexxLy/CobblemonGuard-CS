package com.cobblemonguard.commands

import com.cobblemonguard.CobblemonGuard
import com.cobblemonguard.database.BackupRepository
import com.cobblemonguard.database.toBlobMap
import com.cobblemonguard.discord.DiscordWebhook
import com.cobblemonguard.events.PlayerEventHandler
import com.cobblemonguard.network.CrossServerCoordinator
import com.cobblemonguard.sync.DataType
import com.cobblemonguard.sync.SyncService
import com.cobblemonguard.sync.SyncTypes
import com.cobblemonguard.util.Permissions
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.command.CommandSource
import net.minecraft.command.argument.EntityArgumentType
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.UUID

/**
 * Comandos de administración.
 *
 * ── Dos cosas importantes sobre cómo está escrito esto ─────────────────────────
 *
 * 1. NINGUNA consulta a MySQL corre en el hilo del servidor. Brigadier ejecuta los
 *    comandos en el hilo principal, y antes `/cg info`, `/cg backups` y `/cg rollback`
 *    llamaban al repositorio ahí mismo. `getClosestBackup` trae todos los LONGBLOB de la
 *    fila (megabytes) y, por el respaldo por nombre, puede recorrer buena parte de la
 *    tabla de backups: un rollback podía congelar el servidor varios segundos. Ahora todo
 *    va por [PlayerEventHandler.submitDbTask] y la respuesta vuelve al hilo del servidor
 *    con `server.execute`.
 *
 * 2. Los permisos salen de [Permissions]: nodos `cobblemonguard.*` si hay LuckPerms, con
 *    fallback al nivel de OP de siempre. Así se le puede dar `/cg backups` a un moderador
 *    sin darle OP completo.
 */
object GuardCommand {

    private val ALL_NODES = arrayOf(
        "status", "save", "restore", "info", "backup", "backups", "rollback", "saving", "reload", "unlock", "limpiar",
        "restaurarfila"
    )

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->

            val root = literal("cobblemonguard")
                .requires { Permissions.hasAny(it, *ALL_NODES) }

                // /cobblemonguard status
                .then(literal("status")
                    .requires { Permissions.has(it, "status") }
                    .executes { ctx -> doStatus(ctx.source) }
                )

                // /cobblemonguard reload — recarga config/cobblemonguard.json en caliente
                .then(literal("reload")
                    .requires { Permissions.has(it, "reload") }
                    .executes { ctx -> doReload(ctx.source) }
                )

                // /cobblemonguard saving <start|stop>  — activa/pausa el guardado "serio"
                .then(literal("saving")
                    .requires { Permissions.has(it, "saving") }
                    .then(literal("start").executes { ctx ->
                        PlayerEventHandler.savingEnabled = true
                        ctx.source.sendFeedback({ prefix().append(Text.literal("✔ Guardado ACTIVADO. El mod ya guarda datos en MySQL.").formatted(Formatting.GREEN)) }, true)
                        CobblemonGuard.logger.info("[SAVE] Guardado activado por ${ctx.source.name}")
                        DiscordWebhook.sendSavingToggle(ctx.source.name, true)
                        Command.SINGLE_SUCCESS
                    })
                    .then(literal("stop").executes { ctx ->
                        PlayerEventHandler.savingEnabled = false
                        ctx.source.sendFeedback({ prefix().append(Text.literal("⏸ Guardado PAUSADO. El mod NO escribirá en MySQL (la carga sigue activa).").formatted(Formatting.YELLOW)) }, true)
                        CobblemonGuard.logger.info("[SAVE] Guardado pausado por ${ctx.source.name}")
                        DiscordWebhook.sendSavingToggle(ctx.source.name, false)
                        Command.SINGLE_SUCCESS
                    })
                )

                // /cobblemonguard save <player>  |  /cobblemonguard save all
                .then(literal("save")
                    .requires { Permissions.has(it, "save") }
                    .then(literal("all").executes { ctx -> doSaveAll(ctx.source) })
                    .then(argument("player", EntityArgumentType.player())
                        .executes { ctx ->
                            val src = ctx.source
                            val player = EntityArgumentType.getPlayer(ctx, "player")
                            src.sendFeedback({ prefix().append(Text.literal("Guardando datos de ${player.name.string}...").formatted(Formatting.YELLOW)) }, false)
                            PlayerEventHandler.forceSave(player) { success ->
                                if (success) {
                                    src.sendFeedback({ prefix().append(Text.literal("✔ Datos de ${player.name.string} guardados.").formatted(Formatting.GREEN)) }, false)
                                    DiscordWebhook.sendAdminSave(src.name, player.name.string)
                                } else
                                    src.sendError(prefix().append(Text.literal("✘ Error al guardar datos de ${player.name.string}.").formatted(Formatting.RED)))
                            }
                            Command.SINGLE_SUCCESS
                        }
                    )
                )

                // /cobblemonguard restore <player> [tipo]
                //   tipo: all | inventario | ender | pc | party | accesorios | mochilas | economia
                .then(literal("restore")
                    .requires { Permissions.has(it, "restore") }
                    .then(argument("player", EntityArgumentType.player())
                        .executes { ctx ->
                            doRestore(ctx.source, EntityArgumentType.getPlayer(ctx, "player"), "all")
                        }
                        .then(argument("tipo", StringArgumentType.word())
                            .suggests { _, b -> CommandSource.suggestMatching(TIPOS_SUGERIDOS, b) }
                            .executes { ctx ->
                                doRestore(
                                    ctx.source,
                                    EntityArgumentType.getPlayer(ctx, "player"),
                                    StringArgumentType.getString(ctx, "tipo")
                                )
                            }
                        )
                    )
                )

                // /cobblemonguard info <player>
                .then(literal("info")
                    .requires { Permissions.has(it, "info") }
                    .then(argument("player", EntityArgumentType.player())
                        .executes { ctx -> doInfo(ctx.source, EntityArgumentType.getPlayer(ctx, "player")) }
                    )
                )

                // /cobblemonguard backup <player>  |  /cobblemonguard backup all
                .then(literal("backup")
                    .requires { Permissions.has(it, "backup") }
                    .then(literal("all").executes { ctx -> doBackupAll(ctx.source) })
                    .then(argument("player", EntityArgumentType.player())
                        .executes { ctx ->
                            val src = ctx.source
                            val player = EntityArgumentType.getPlayer(ctx, "player")
                            src.sendFeedback({ prefix().append(Text.literal("Creando backup de ${player.name.string}...").formatted(Formatting.YELLOW)) }, false)
                            // Ahora se espera el resultado real en vez de cantar victoria al toque.
                            PlayerEventHandler.saveBackupAsync(player) { inserted ->
                                if (inserted) {
                                    src.sendFeedback({ prefix().append(Text.literal("✔ Backup de ${player.name.string} creado.").formatted(Formatting.GREEN)) }, false)
                                    DiscordWebhook.sendAdminBackup(src.name, player.name.string)
                                } else {
                                    src.sendFeedback({ prefix().append(Text.literal("• Sin cambios desde el último backup de ${player.name.string} — no se creó uno nuevo.").formatted(Formatting.GRAY)) }, false)
                                }
                            }
                            Command.SINGLE_SUCCESS
                        }
                    )
                )

                // /cobblemonguard backups <player>
                .then(literal("backups")
                    .requires { Permissions.has(it, "backups") }
                    .then(argument("player", EntityArgumentType.player())
                        .executes { ctx -> doListBackups(ctx.source, EntityArgumentType.getPlayer(ctx, "player")) }
                    )
                )

                // /cobblemonguard rollback <player> <tiempo> [tipo]  (ej: 30m, 1h, 2h)
                .then(literal("rollback")
                    .requires { Permissions.has(it, "rollback") }
                    .then(argument("player", EntityArgumentType.player())
                        .then(argument("tiempo", StringArgumentType.word())
                            .executes { ctx ->
                                doRollback(
                                    ctx.source,
                                    EntityArgumentType.getPlayer(ctx, "player"),
                                    StringArgumentType.getString(ctx, "tiempo"),
                                    "all"
                                )
                            }
                            .then(argument("tipo", StringArgumentType.word())
                                .suggests { _, b -> CommandSource.suggestMatching(TIPOS_SUGERIDOS, b) }
                                .executes { ctx ->
                                    doRollback(
                                        ctx.source,
                                        EntityArgumentType.getPlayer(ctx, "player"),
                                        StringArgumentType.getString(ctx, "tiempo"),
                                        StringArgumentType.getString(ctx, "tipo")
                                    )
                                }
                            )
                        )
                    )
                )

                // /cobblemonguard unlock <nombre> — libera a la fuerza un lock de Redis
                .then(literal("unlock")
                    .requires { Permissions.has(it, "unlock") }
                    .then(argument("nombre", StringArgumentType.word())
                        .executes { ctx -> doUnlock(ctx.source, StringArgumentType.getString(ctx, "nombre")) }
                    )
                )

            // /cobblemonguard restaurarfila <nombre> <tiempo> <tipo> — copia UN tipo de dato desde
            // un backup directo a la fila de MySQL. Funciona con el jugador DESCONECTADO.
            root.then(literal("restaurarfila")
                .requires { Permissions.has(it, "restaurarfila") }
                .then(argument("nombre", StringArgumentType.word())
                    .then(argument("tiempo", StringArgumentType.word())
                        .then(argument("tipo", StringArgumentType.word())
                            .executes { ctx ->
                                doRestaurarFila(ctx.source,
                                    StringArgumentType.getString(ctx, "nombre"),
                                    StringArgumentType.getString(ctx, "tiempo"),
                                    StringArgumentType.getString(ctx, "tipo"))
                            }
                        )
                    )
                )
            )

            // /cobblemonguard limpiar <nombre> <tipo> — respalda la fila y deja en NULL una columna.
            // Último recurso: deja entrar con lo LOCAL del backend. Ver doLimpiar.
            root.then(literal("limpiar")
                .requires { Permissions.has(it, "limpiar") }
                .then(argument("nombre", StringArgumentType.word())
                    .then(argument("tipo", StringArgumentType.word())
                        .executes { ctx ->
                            doLimpiar(ctx.source,
                                StringArgumentType.getString(ctx, "nombre"),
                                StringArgumentType.getString(ctx, "tipo"))
                        }
                    )
                )
            )

            dispatcher.register(root)

            // Alias /cg
            dispatcher.register(
                literal("cg").requires { Permissions.hasAny(it, *ALL_NODES) }
                    .redirect(dispatcher.root.getChild("cobblemonguard"))
            )
        }

        // Toca el lazy para que el modo de permisos quede reportado en el log de arranque
        // y no la primera vez que alguien escribe un comando.
        Permissions.isLuckPermsPresent
        CobblemonGuard.logger.info("Comandos registrados: /cobblemonguard (/cg)")
    }

    // ──────────────────────────────────────────────
    // Helpers de ejecución asíncrona
    // ──────────────────────────────────────────────

    /** Corre [work] en el pool de BD y su resultado vuelve al hilo del servidor. */
    private fun <T> async(src: ServerCommandSource, work: () -> T, then: (T?) -> Unit) {
        PlayerEventHandler.submitDbTask {
            val result = runCatching { work() }
                .onFailure { CobblemonGuard.logger.error("[Comando] Error consultando MySQL: ${it.message}") }
                .getOrNull()
            src.server.execute { then(result) }
        }
    }

    // ──────────────────────────────────────────────
    // Subcomandos
    // ──────────────────────────────────────────────

    private fun doStatus(src: ServerCommandSource): Int {
        val cfg = CobblemonGuard.config
        if (CobblemonGuard.database.isConnected()) {
            src.sendFeedback({ prefix().append(Text.literal("✔ Conectado a ${cfg.host}:${cfg.port}/${cfg.database}").formatted(Formatting.GREEN)) }, false)
        } else {
            src.sendError(prefix().append(Text.literal("✘ MySQL desconectado. Revisa la config.").formatted(Formatting.RED)))
        }
        if (PlayerEventHandler.savingEnabled)
            src.sendFeedback({ Text.literal("  Guardado: ").formatted(Formatting.GRAY).append(Text.literal("ACTIVO").formatted(Formatting.GREEN)) }, false)
        else
            src.sendFeedback({ Text.literal("  Guardado: ").formatted(Formatting.GRAY).append(Text.literal("PAUSADO (usa /cg saving start)").formatted(Formatting.YELLOW)) }, false)

        src.sendFeedback({ Text.literal("  Versión: ").formatted(Formatting.GRAY).append(Text.literal(CobblemonGuard.modVersion).formatted(Formatting.WHITE)) }, false)
        src.sendFeedback({
            Text.literal("  Permisos: ").formatted(Formatting.GRAY).append(
                Text.literal(if (Permissions.isLuckPermsPresent) "nodos cobblemonguard.* (LuckPerms)" else "OP nivel 2").formatted(Formatting.WHITE)
            )
        }, false)
        if (cfg.crossServerSyncEnabled) {
            val redis = if (CrossServerCoordinator.isConnected()) "conectado" else "SIN CONEXIÓN (modo degradado)"
            src.sendFeedback({
                Text.literal("  Cross-server: ").formatted(Formatting.GRAY)
                    .append(Text.literal("serverId='${cfg.serverId}', Redis $redis").formatted(Formatting.WHITE))
            }, false)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun doReload(src: ServerCommandSource): Int {
        return try {
            CobblemonGuard.reloadConfig()
            src.sendFeedback({ prefix().append(Text.literal("✔ Config recargada desde disco.").formatted(Formatting.GREEN)) }, true)
            src.sendFeedback({
                Text.literal("  Ojo: credenciales de MySQL/Redis y los intervalos de auto-save y backup necesitan reinicio.")
                    .formatted(Formatting.GRAY)
            }, false)
            CobblemonGuard.logger.info("[CONFIG] Config recargada por ${src.name}")
            Command.SINGLE_SUCCESS
        } catch (e: Exception) {
            src.sendError(prefix().append(Text.literal("✘ Error recargando la config: ${e.message}").formatted(Formatting.RED)))
            Command.SINGLE_SUCCESS
        }
    }

    private fun doInfo(src: ServerCommandSource, player: ServerPlayerEntity): Int {
        val name = player.name.string
        async(src, { CobblemonGuard.repository.getPlayerInfo(player.uuid, name) }) { info ->
            if (info == null) {
                src.sendError(prefix().append(Text.literal("$name no tiene datos guardados en MySQL.").formatted(Formatting.RED)))
            } else {
                src.sendFeedback({ prefix().append(Text.literal("── $name ──").formatted(Formatting.AQUA)) }, false)
                src.sendFeedback({ Text.literal("  Último guardado: ").formatted(Formatting.GRAY).append(Text.literal(info.lastSave).formatted(Formatting.WHITE)) }, false)
                src.sendFeedback({ Text.literal("  UUID: ").formatted(Formatting.GRAY).append(Text.literal(info.uuid.toString()).formatted(Formatting.WHITE)) }, false)
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun doListBackups(src: ServerCommandSource, player: ServerPlayerEntity): Int {
        val name = player.name.string
        async(src, { CobblemonGuard.backupRepository.listBackups(player.uuid, name) }) { backups ->
            if (backups.isNullOrEmpty()) {
                src.sendError(prefix().append(Text.literal("$name no tiene backups disponibles.").formatted(Formatting.RED)))
            } else {
                src.sendFeedback({ prefix().append(Text.literal("Backups de $name:").formatted(Formatting.AQUA)) }, false)
                backups.forEachIndexed { i, ts ->
                    val ago = BackupRepository.formatTimestamp(ts)
                    src.sendFeedback({ Text.literal("  ${i + 1}. $ago  ($ts)").formatted(Formatting.GRAY) }, false)
                }
                src.sendFeedback({ Text.literal("  Usa /cg rollback $name <tiempo>").formatted(Formatting.YELLOW) }, false)
            }
        }
        return Command.SINGLE_SUCCESS
    }

    /** Guarda a TODOS los jugadores online, repartido entre ticks. */
    private fun doSaveAll(src: ServerCommandSource): Int {
        val players = src.server.playerManager.playerList.toList()
        if (players.isEmpty()) {
            src.sendFeedback({ prefix().append(Text.literal("No hay jugadores online.").formatted(Formatting.YELLOW)) }, false)
            return Command.SINGLE_SUCCESS
        }
        src.sendFeedback({ prefix().append(Text.literal("Guardando ${players.size} jugadores...").formatted(Formatting.YELLOW)) }, true)
        val names = players.map { it.name.string }
        // Repartido: serializar a todos en el mismo tick es un stutter en un servidor lleno.
        for (player in players) {
            PlayerEventHandler.enqueueStaggered { if (!player.isRemoved) PlayerEventHandler.forceSave(player) }
        }
        src.sendFeedback({ prefix().append(Text.literal("✔ Guardado disparado para ${players.size} jugadores.").formatted(Formatting.GREEN)) }, true)
        CobblemonGuard.logger.info("[SAVE] Guardado masivo de ${players.size} jugadores por ${src.name}")
        DiscordWebhook.sendAdminSaveAll(src.name, names)
        return Command.SINGLE_SUCCESS
    }

    /** Crea un backup de TODOS los jugadores online, repartido entre ticks. */
    private fun doBackupAll(src: ServerCommandSource): Int {
        val players = src.server.playerManager.playerList.toList()
        if (players.isEmpty()) {
            src.sendFeedback({ prefix().append(Text.literal("No hay jugadores online.").formatted(Formatting.YELLOW)) }, false)
            return Command.SINGLE_SUCCESS
        }
        src.sendFeedback({ prefix().append(Text.literal("Creando backup de ${players.size} jugadores...").formatted(Formatting.YELLOW)) }, true)
        val names = players.map { it.name.string }
        for (player in players) {
            PlayerEventHandler.enqueueStaggered { if (!player.isRemoved) PlayerEventHandler.saveBackupAsync(player) }
        }
        src.sendFeedback({ prefix().append(Text.literal("✔ Backup disparado para ${players.size} jugadores.").formatted(Formatting.GREEN)) }, true)
        CobblemonGuard.logger.info("[Backup] Backup masivo de ${players.size} jugadores por ${src.name}")
        DiscordWebhook.sendAdminBackupAll(src.name, names)
        return Command.SINGLE_SUCCESS
    }

    private const val TIPOS_VALIDOS = "all, inventario, ender, pc, party, accesorios, mochilas, economia"
    private val TIPOS_SUGERIDOS = listOf("all", "inventario", "ender", "pc", "party", "accesorios", "mochilas", "economia")

    /** Restaura desde los datos actuales de MySQL, solo los tipos indicados. */
    private fun doRestore(src: ServerCommandSource, player: ServerPlayerEntity, tipo: String): Int {
        val types = SyncTypes.parse(tipo)
        if (types == null) {
            src.sendError(prefix().append(Text.literal("Tipo inválido '$tipo'. Usa: $TIPOS_VALIDOS").formatted(Formatting.RED)))
            return Command.SINGLE_SUCCESS
        }
        val etiqueta = if (types == SyncTypes.ALL) "todos los datos" else tipo.lowercase()
        src.sendFeedback({ prefix().append(Text.literal("Restaurando $etiqueta de ${player.name.string} desde MySQL...").formatted(Formatting.YELLOW)) }, false)
        PlayerEventHandler.forceRestore(player, types) { fallidos ->
            when {
                fallidos == null ->
                    src.sendError(prefix().append(Text.literal("✘ ${player.name.string} no tiene datos en MySQL o hubo un error.").formatted(Formatting.RED)))
                fallidos.isEmpty() -> {
                    src.sendFeedback({ prefix().append(Text.literal("✔ Restaurado ($etiqueta) para ${player.name.string}.").formatted(Formatting.GREEN)) }, false)
                    DiscordWebhook.sendAdminRestore(src.name, player.name.string, etiqueta)
                }
                else ->
                    src.sendError(prefix().append(Text.literal(
                        "✘ Restauración INCOMPLETA para ${player.name.string}: no se pudo aplicar " +
                        "${fallidos.joinToString(", ")}. Revisa la consola. Si ya estaba cargado, sus guardados " +
                        "siguen activos y van a escribir este estado mezclado."
                    ).formatted(Formatting.RED)))
            }
        }
        return Command.SINGLE_SUCCESS
    }

    /** Restaura desde un backup histórico, solo los tipos indicados. */
    private fun doRollback(src: ServerCommandSource, player: ServerPlayerEntity, timeStr: String, tipo: String): Int {
        val types = SyncTypes.parse(tipo)
        if (types == null) {
            src.sendError(prefix().append(Text.literal("Tipo inválido '$tipo'. Usa: $TIPOS_VALIDOS").formatted(Formatting.RED)))
            return Command.SINGLE_SUCCESS
        }
        val targetTime = BackupRepository.parseTimeAgo(timeStr)
        if (targetTime == null) {
            src.sendError(prefix().append(Text.literal("Formato de tiempo inválido. Usa: 30m, 1h, 2h, etc.").formatted(Formatting.RED)))
            return Command.SINGLE_SUCCESS
        }
        val name = player.name.string
        val etiqueta = if (types == SyncTypes.ALL) "todos los datos" else tipo.lowercase()
        src.sendFeedback({ prefix().append(Text.literal("Buscando backup de $name de hace $timeStr...").formatted(Formatting.YELLOW)) }, false)

        // La búsqueda del backup (que trae LONGBLOBs y puede recorrer bastante tabla) va al
        // pool de BD; solo la aplicación vuelve al hilo del servidor.
        async(src, { CobblemonGuard.backupRepository.getClosestBackup(player.uuid, targetTime, name) }) { backup ->
            if (backup == null) {
                src.sendError(prefix().append(Text.literal("No hay backup disponible para hace $timeStr.").formatted(Formatting.RED)))
                return@async
            }
            if (player.isRemoved) {
                src.sendError(prefix().append(Text.literal("$name se desconectó antes de aplicar el rollback.").formatted(Formatting.RED)))
                return@async
            }
            val ago = BackupRepository.formatTimestamp(backup.savedAt)
            val fallidos = SyncService.fallidos(SyncService.apply(player, backup.toBlobMap(), types))
            if (fallidos.isNotEmpty()) {
                // El rollback no toca markLoaded, asi que un fallo aca no habilita guardados
                // nuevos. Pero tampoco puede decir "completado": el admin cree que el jugador
                // quedo en el estado del backup y no es asi.
                src.sendError(prefix().append(Text.literal(
                    "✘ Rollback INCOMPLETO ($etiqueta): no se pudo aplicar ${fallidos.joinToString(", ")}. Revisa la consola."
                ).formatted(Formatting.RED)))
                CobblemonGuard.logger.error("[ROLLBACK] $name: no se pudo aplicar ${fallidos.joinToString(", ")} desde el backup de $ago.")
                return@async
            }
            src.sendFeedback({ prefix().append(Text.literal("✔ Rollback completado ($etiqueta). Estado restaurado a $ago.").formatted(Formatting.GREEN)) }, false)
            CobblemonGuard.logger.info("[ROLLBACK] $name restaurado ($etiqueta) al estado de $ago por ${src.name}")
            DiscordWebhook.sendRollback(name, "$ago ($etiqueta)", src.name)
        }
        return Command.SINGLE_SUCCESS
    }

    /**
     * Libera a la fuerza el lock de Redis de un jugador.
     *
     * Para el caso en que un backend se cayó de forma sucia y dejó un lock colgado: el
     * jugador queda esperando el timeout en cada join hasta que expire el TTL. El nombre se
     * resuelve contra NUESTRA tabla, así que funciona aunque el jugador no esté online acá.
     */
    private fun doUnlock(src: ServerCommandSource, playerName: String): Int {
        if (!CobblemonGuard.config.crossServerSyncEnabled) {
            src.sendError(prefix().append(Text.literal("La coordinación cross-server está desactivada — no hay locks que liberar.").formatted(Formatting.RED)))
            return Command.SINGLE_SUCCESS
        }
        async(src, {
            val uuid = CobblemonGuard.repository.findUuidByName(playerName)
            if (uuid != null) CrossServerCoordinator.forceRelease(uuid)
            uuid
        }) { uuid ->
            if (uuid == null) {
                src.sendError(prefix().append(Text.literal("No encontré ningún jugador guardado con el nombre '$playerName'.").formatted(Formatting.RED)))
            } else {
                src.sendFeedback({ prefix().append(Text.literal("✔ Lock liberado para $playerName ($uuid).").formatted(Formatting.GREEN)) }, true)
                CobblemonGuard.logger.info("[CrossServer] Lock de $playerName liberado a mano por ${src.name}")
            }
        }
        return Command.SINGLE_SUCCESS
    }

    /** Columna de MySQL de cada tipo de dato. */
    private fun columnaDe(tipo: DataType): String = when (tipo) {
        DataType.INVENTORY -> "inventory"
        DataType.ENDERCHEST -> "ender_chest"
        DataType.PC -> "cobblemon_pc"
        DataType.PARTY -> "cobblemon_party"
        DataType.ACCESSORIES -> "trinkets"
        DataType.BACKPACKS -> "backpacks"
        DataType.ECONOMY -> "economy"
    }

    private fun blobDe(backup: BackupRepository.BackupEntry, tipo: DataType): ByteArray? = when (tipo) {
        DataType.INVENTORY -> backup.inventory
        DataType.ENDERCHEST -> backup.enderChest
        DataType.PC -> backup.cobblemonPc
        DataType.PARTY -> backup.cobblemonParty
        DataType.ACCESSORIES -> backup.trinkets
        DataType.BACKPACKS -> backup.backpacks
        DataType.ECONOMY -> backup.economy
    }

    /** Un tipo solo, o null después de avisar al que escribió el comando. */
    private fun unTipo(src: ServerCommandSource, tipo: String): DataType? {
        val types = SyncTypes.parse(tipo)
        if (types == null || types.size != 1) {
            src.sendError(prefix().append(Text.literal(
                "Tipo inválido. Usa UNO solo: inv, ender, pc, party, acc, bp o eco."
            ).formatted(Formatting.RED)))
            return null
        }
        return types.single()
    }

    /**
     * Resultado de la parte de BD de los comandos que tocan la fila de un jugador desconectado.
     * [ocupadoPor] no nulo = no se tocó nada porque el jugador está en ese backend.
     */
    private data class Fila(val uuid: UUID?, val hecho: Boolean, val ocupadoPor: String? = null, val detalle: String = "")

    /**
     * Si el jugador está conectado en algún backend, tocar su fila no sirve —su próximo guardado
     * la vuelve a escribir— y además confunde: el admin ve "listo" y nada cambió. Con cross-server
     * se pregunta a Redis quién tiene su lock; sin cross-server, solo se puede mirar este servidor.
     */
    private fun ocupado(uuid: UUID, conectadoAqui: Boolean): String? {
        CrossServerCoordinator.dueñoActual(uuid)?.let { return it }
        return if (conectadoAqui) CobblemonGuard.config.serverId else null
    }

    /**
     * Para un jugador que no puede entrar porque un tipo de dato suyo no se puede aplicar.
     *
     * `/cg restore` y `/cg rollback` no sirven en ese caso: los dos necesitan al jugador conectado y
     * cargado, y a este se lo expulsa en el mismo instante en que falla la carga. Esto trabaja
     * directo sobre MySQL: guarda la fila actual como backup y copia ESE tipo desde el backup más
     * cercano anterior a [timeStr]. Elegí un momento anterior a que el dato se dañara.
     */
    private fun doRestaurarFila(src: ServerCommandSource, playerName: String, timeStr: String, tipo: String): Int {
        val dataType = unTipo(src, tipo) ?: return Command.SINGLE_SUCCESS
        val targetTime = BackupRepository.parseTimeAgo(timeStr)
        if (targetTime == null) {
            src.sendError(prefix().append(Text.literal("Formato de tiempo inválido. Usa: 30m, 1h, 2h, etc.").formatted(Formatting.RED)))
            return Command.SINGLE_SUCCESS
        }
        val columna = columnaDe(dataType)
        // Se mira acá, en el hilo del servidor: la lista de jugadores no se lee desde el pool.
        val conectadoAqui = src.server.playerManager.getPlayer(playerName) != null
        async(src, {
            val uuid = CobblemonGuard.repository.findUuidByName(playerName) ?: return@async Fila(null, false)
            ocupado(uuid, conectadoAqui)?.let { return@async Fila(uuid, false, ocupadoPor = it) }
            // SIN nombre: getClosestBackup con nombre cae, si no encuentra nada propio, a backups de
            // OTRO uuid con el mismo nombre. Para una restauración que se escribe directo en la fila,
            // eso podía copiarle a este jugador los datos de otra cuenta.
            val backup = CobblemonGuard.backupRepository.getClosestBackup(uuid, targetTime, null)
                ?: return@async Fila(uuid, false, detalle = "No hay ningún backup de $playerName de hace $timeStr o antes.")
            val blob = blobDe(backup, dataType)
                ?: return@async Fila(uuid, false, detalle = "El backup de ${BackupRepository.formatTimestamp(backup.savedAt)} no tiene ese tipo de dato.")
            val ok = CobblemonGuard.repository.respaldarYReemplazar(uuid, columna, blob)
            Fila(uuid, ok, detalle = BackupRepository.formatTimestamp(backup.savedAt))
        }) { fila -> informarFila(src, playerName, fila, columna) { cuando ->
            "✔ $playerName: '$columna' restaurado desde el backup de $cuando, directo en MySQL. Su fila anterior " +
            "quedó como backup. Lo va a recibir en su próximo ingreso. Es un rollback de ese dato: lo que gastó, " +
            "cambió o intercambió después de esa fecha vuelve a estar como entonces."
        } }
        return Command.SINGLE_SUCCESS
    }

    /**
     * Último recurso, cuando no hay ningún backup bueno: guarda la fila como backup y deja en NULL
     * una columna. Al volver a entrar, ese tipo se saltea y el jugador se queda con lo que tenga
     * EN LOCAL el backend donde entre — que puede ser una copia de hace semanas (recupera cosas que
     * ya gastó: duplicado) o directamente nada si nunca estuvo en ese backend. Por eso no es la
     * primera opción.
     */
    private fun doLimpiar(src: ServerCommandSource, playerName: String, tipo: String): Int {
        val dataType = unTipo(src, tipo) ?: return Command.SINGLE_SUCCESS
        val columna = columnaDe(dataType)
        // Se mira acá, en el hilo del servidor: la lista de jugadores no se lee desde el pool.
        val conectadoAqui = src.server.playerManager.getPlayer(playerName) != null
        async(src, {
            val uuid = CobblemonGuard.repository.findUuidByName(playerName) ?: return@async Fila(null, false)
            ocupado(uuid, conectadoAqui)?.let { return@async Fila(uuid, false, ocupadoPor = it) }
            Fila(uuid, CobblemonGuard.repository.respaldarYReemplazar(uuid, columna, null))
        }) { fila -> informarFila(src, playerName, fila, columna) { _ ->
            "✔ $playerName: se guardó un backup de su fila y se vació '$columna'. En su próximo ingreso ese dato " +
            "se saltea y se queda con la copia LOCAL del servidor donde entre, que puede estar vieja o vacía."
        } }
        return Command.SINGLE_SUCCESS
    }

    private fun informarFila(src: ServerCommandSource, playerName: String, fila: Fila?, columna: String, exito: (String) -> String) {
        when {
            fila == null -> src.sendError(prefix().append(Text.literal(
                "Falló la consulta a MySQL; mira la consola.").formatted(Formatting.RED)))
            fila.uuid == null -> src.sendError(prefix().append(Text.literal(
                "No encontré ningún jugador guardado con el nombre '$playerName'.").formatted(Formatting.RED)))
            fila.ocupadoPor != null -> src.sendError(prefix().append(Text.literal(
                "$playerName está conectado en '${fila.ocupadoPor}'. No se tocó nada: su próximo guardado lo " +
                "volvería a escribir. Hazlo con él desconectado.").formatted(Formatting.RED)))
            !fila.hecho -> src.sendError(prefix().append(Text.literal(
                fila.detalle.ifEmpty { "No se pudo modificar la fila de $playerName." }).formatted(Formatting.RED)))
            else -> {
                src.sendFeedback({ prefix().append(Text.literal(exito(fila.detalle)).formatted(Formatting.GREEN)) }, true)
                CobblemonGuard.logger.warn("[Fila] ${src.name} modificó '$columna' de $playerName (${fila.uuid}). La versión anterior quedó en cobblemonguard_backups.")
            }
        }
    }

    private fun prefix(): MutableText =
        Text.literal("[CobblemonGUARD] ").formatted(Formatting.GOLD)
}
