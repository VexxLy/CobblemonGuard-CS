package com.cobblemonguard.database

import com.cobblemonguard.CobblemonGuard
import java.util.UUID

class PlayerDataRepository(private val db: DatabaseManager) {

    // Guardar datos de un jugador

    /**
     * Guarda el estado del jugador.
     *
     * <h2>Un null NUNCA pisa una columna</h2>
     *
     * Cada columna se escribe con {@code COALESCE(valor_nuevo, valor_actual)}: si el
     * serializador devolvió null, la columna se queda como estaba en vez de ponerse en NULL.
     *
     * No es paranoia. El null que devuelve un serializador significa DOS cosas incompatibles: "este tipo está desactivado
     * en la config" y "la serialización falló" (todos atrapan su excepción y devuelven null).
     * El caso feo: todo el acceso a Cobblemon es por reflexión por nombre, y el mod acepta
     * cobblemon >= 1.8.0. Si en alguna versión cambia getStorage o saveToNBT, PokemonPCSync
     * devuelve null y, si el UPSERT escribiera las nueve columnas siempre, CADA guardado de CADA
     * jugador pondría cobblemon_pc = NULL. La PC de todo el
     * servidor se vaciaría en horas, y el backup periódico usa el mismo serializador, así que
     * copiaría el mismo NULL. Con COALESCE, el peor caso pasa a ser "los datos se congelan en el
     * último estado bueno", que se nota y se arregla.
     *
     * Un null nunca es un dato legítimo que haya que escribir:
     * - PC y equipo producen NBT válido aunque estén vacíos.
     * - Las mochilas solo guardan CONTENIDOS por UUID; una columna vieja no resucita nada,
     *   porque el item de la mochila viaja en el inventario, que si se sincroniza.
     * - Un saldo viejo es mejor que ningún saldo.
     */
    fun savePlayerData(
        uuid: UUID,
        playerName: String,
        inventory: ByteArray?,
        enderChest: ByteArray?,
        cobblemonPc: ByteArray?,
        cobblemonParty: ByteArray?,
        trinkets: ByteArray?,
        backpacks: ByteArray?,
        economy: ByteArray? = null,
        ftbQuests: ByteArray? = null,
        cobblemonPokedex: ByteArray? = null,
        cobblemmoMochila: ByteArray? = null,
        rctProgreso: ByteArray? = null
    ) {
        if (CobblemonGuard.config.identifyByName) {
            saveByName(uuid, playerName, inventory, enderChest, cobblemonPc, cobblemonParty, trinkets, backpacks, economy, ftbQuests, cobblemonPokedex, cobblemmoMochila, rctProgreso)
            logSync(uuid, playerName, "SAVE", true, null)
            return
        }
        db.getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO cobblemonguard_players
                    (uuid, player_name, inventory, ender_chest, cobblemon_pc, cobblemon_party, trinkets, backpacks, economy, ftb_quests, cobblemon_pokedex, cobblemmo_mochila, rct_progreso)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                -- VALUES(col) y no el alias de fila (INSERT ... AS n) que MySQL 8.0.20
                -- recomienda: ese alias NO existe en MariaDB, y este mod se instala tanto en
                -- una como en la otra. VALUES() esta deprecado pero funciona en las dos; el
                -- alias funcionaria en una sola. Un warning de deprecacion es mas barato que
                -- un mod que no arranca en la mitad de los hostings.
                ON DUPLICATE KEY UPDATE
                    player_name     = VALUES(player_name),
                    inventory       = COALESCE(VALUES(inventory),       inventory),
                    ender_chest     = COALESCE(VALUES(ender_chest),     ender_chest),
                    cobblemon_pc    = COALESCE(VALUES(cobblemon_pc),    cobblemon_pc),
                    cobblemon_party = COALESCE(VALUES(cobblemon_party), cobblemon_party),
                    trinkets        = COALESCE(VALUES(trinkets),        trinkets),
                    backpacks       = COALESCE(VALUES(backpacks),       backpacks),
                    economy         = COALESCE(VALUES(economy),         economy),
                    ftb_quests      = COALESCE(VALUES(ftb_quests),      ftb_quests),
                    cobblemon_pokedex = COALESCE(VALUES(cobblemon_pokedex), cobblemon_pokedex),
                    cobblemmo_mochila = COALESCE(VALUES(cobblemmo_mochila), cobblemmo_mochila),
                    rct_progreso    = COALESCE(VALUES(rct_progreso),    rct_progreso),
                    last_save       = CURRENT_TIMESTAMP
            """.trimIndent()).use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.setString(2, playerName)
                stmt.setBytes(3, inventory)
                stmt.setBytes(4, enderChest)
                stmt.setBytes(5, cobblemonPc)
                stmt.setBytes(6, cobblemonParty)
                stmt.setBytes(7, trinkets)
                stmt.setBytes(8, backpacks)
                stmt.setBytes(9, economy)
                stmt.setBytes(10, ftbQuests)
                stmt.setBytes(11, cobblemonPokedex)
                stmt.setBytes(12, cobblemmoMochila)
                stmt.setBytes(13, rctProgreso)
                stmt.executeUpdate()
            }
        }
        logSync(uuid, playerName, "SAVE", true, null)
    }

    /**
     * Upsert por NOMBRE (modo offline): actualiza la fila del jugador buscándola por
     * player_name; si no existe, la inserta. Así conserva su data aunque el UUID cambie.
     */
    private fun saveByName(
        uuid: UUID, playerName: String,
        inventory: ByteArray?, enderChest: ByteArray?, cobblemonPc: ByteArray?,
        cobblemonParty: ByteArray?, trinkets: ByteArray?, backpacks: ByteArray?, economy: ByteArray?,
        ftbQuests: ByteArray?, cobblemonPokedex: ByteArray?, cobblemmoMochila: ByteArray?, rctProgreso: ByteArray?
    ) {
        db.getConnection().use { conn ->
            val updated = conn.prepareStatement("""
                UPDATE cobblemonguard_players
                SET inventory       = COALESCE(?, inventory),
                    ender_chest     = COALESCE(?, ender_chest),
                    cobblemon_pc    = COALESCE(?, cobblemon_pc),
                    cobblemon_party = COALESCE(?, cobblemon_party),
                    trinkets        = COALESCE(?, trinkets),
                    backpacks       = COALESCE(?, backpacks),
                    economy         = COALESCE(?, economy),
                    ftb_quests      = COALESCE(?, ftb_quests),
                    cobblemon_pokedex = COALESCE(?, cobblemon_pokedex),
                    cobblemmo_mochila = COALESCE(?, cobblemmo_mochila),
                    rct_progreso    = COALESCE(?, rct_progreso),
                    last_save       = CURRENT_TIMESTAMP
                WHERE player_name = ?
            """.trimIndent()).use { stmt ->
                stmt.setBytes(1, inventory)
                stmt.setBytes(2, enderChest)
                stmt.setBytes(3, cobblemonPc)
                stmt.setBytes(4, cobblemonParty)
                stmt.setBytes(5, trinkets)
                stmt.setBytes(6, backpacks)
                stmt.setBytes(7, economy)
                stmt.setBytes(8, ftbQuests)
                stmt.setBytes(9, cobblemonPokedex)
                stmt.setBytes(10, cobblemmoMochila)
                stmt.setBytes(11, rctProgreso)
                stmt.setString(12, playerName)
                stmt.executeUpdate()
            }
            if (updated == 0) {
                conn.prepareStatement("""
                    INSERT INTO cobblemonguard_players
                        (uuid, player_name, inventory, ender_chest, cobblemon_pc, cobblemon_party, trinkets, backpacks, economy, ftb_quests, cobblemon_pokedex, cobblemmo_mochila, rct_progreso)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { stmt ->
                    stmt.setString(1, uuid.toString())
                    stmt.setString(2, playerName)
                    stmt.setBytes(3, inventory)
                    stmt.setBytes(4, enderChest)
                    stmt.setBytes(5, cobblemonPc)
                    stmt.setBytes(6, cobblemonParty)
                    stmt.setBytes(7, trinkets)
                    stmt.setBytes(8, backpacks)
                    stmt.setBytes(9, economy)
                    stmt.setBytes(10, ftbQuests)
                    stmt.setBytes(11, cobblemonPokedex)
                    stmt.setBytes(12, cobblemmoMochila)
                    stmt.setBytes(13, rctProgreso)
                    stmt.executeUpdate()
                }
            }
        }
    }

    // Cargar datos de un jugador

    data class PlayerData(
        val inventory: ByteArray?,
        val enderChest: ByteArray?,
        val cobblemonPc: ByteArray?,
        val cobblemonParty: ByteArray?,
        val trinkets: ByteArray?,
        val backpacks: ByteArray?,
        val economy: ByteArray? = null,
        val ftbQuests: ByteArray? = null,
        val cobblemonPokedex: ByteArray? = null,
        val cobblemmoMochila: ByteArray? = null,
        val rctProgreso: ByteArray? = null
    )

    fun loadPlayerData(uuid: UUID, playerName: String): PlayerData? {
        val byName = CobblemonGuard.config.identifyByName
        if (byName) return queryPlayerData("player_name = ? ORDER BY last_save DESC LIMIT 1", playerName)

        // Modo UUID (default): el UUID sigue siendo la fuente de verdad.
        queryPlayerData("uuid = ?", uuid.toString())?.let { return it }

        // No hay fila con este UUID. Antes de asumir "jugador nuevo", probamos un
        // respaldo por nombre (ver doc de GuardConfig.identifyByNameFallback).
        if (!CobblemonGuard.config.identifyByNameFallbackEffective) return null
        val byNameData = queryPlayerData("player_name = ? ORDER BY last_save DESC LIMIT 1", playerName) ?: return null

        claimRowForUuid(uuid, playerName)
        CobblemonGuard.logger.info(
            "[Identidad] '$playerName' no tenía fila por UUID ($uuid) — se encontró y reclamó una fila " +
            "existente por NOMBRE. De ahora en adelante matcheará directo por UUID."
        )
        return byNameData
    }

    private fun queryPlayerData(where: String, param: String): PlayerData? {
        db.getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT inventory, ender_chest, cobblemon_pc, cobblemon_party, trinkets, backpacks, economy, ftb_quests, cobblemon_pokedex, cobblemmo_mochila, rct_progreso
                FROM cobblemonguard_players
                WHERE $where
            """.trimIndent()).use { stmt ->
                stmt.setString(1, param)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    return PlayerData(
                        inventory      = rs.getBytes("inventory"),
                        enderChest     = rs.getBytes("ender_chest"),
                        cobblemonPc    = rs.getBytes("cobblemon_pc"),
                        cobblemonParty = rs.getBytes("cobblemon_party"),
                        trinkets       = rs.getBytes("trinkets"),
                        backpacks      = rs.getBytes("backpacks"),
                        economy        = rs.getBytes("economy"),
                        ftbQuests      = rs.getBytes("ftb_quests"),
                        cobblemonPokedex = rs.getBytes("cobblemon_pokedex"),
                        cobblemmoMochila = rs.getBytes("cobblemmo_mochila"),
                        rctProgreso    = rs.getBytes("rct_progreso")
                    )
                }
            }
        }
        return null
    }

    /**
     * Reasocia la fila más reciente de [playerName] (que tenía OTRO UUID, o ninguno
     * resoluble) al [uuid] actual. Solo se llama tras confirmar (en [loadPlayerData])
     * que NO existe ya una fila con ese UUID, así que no puede chocar con la PK.
     */
    private fun claimRowForUuid(uuid: UUID, playerName: String) {
        db.getConnection().use { conn ->
            conn.prepareStatement("""
                UPDATE cobblemonguard_players
                SET uuid = ?
                WHERE player_name = ?
                ORDER BY last_save DESC
                LIMIT 1
            """.trimIndent()).use { stmt ->
                stmt.setString(1, uuid.toString())
                stmt.setString(2, playerName)
                stmt.executeUpdate()
            }
        }
    }

    // Info del jugador (para /cobblemonguard info)

    data class PlayerInfo(val uuid: UUID, val playerName: String, val lastSave: String)

    fun getPlayerInfo(uuid: UUID, playerName: String): PlayerInfo? {
        val byName = CobblemonGuard.config.identifyByName
        if (byName) return queryPlayerInfo(uuid, "player_name = ? ORDER BY last_save DESC LIMIT 1", playerName)

        queryPlayerInfo(uuid, "uuid = ?", uuid.toString())?.let { return it }
        if (!CobblemonGuard.config.identifyByNameFallbackEffective) return null
        // Solo lectura: no reclama la fila (eso ya lo hace loadPlayerData en el join).
        return queryPlayerInfo(uuid, "player_name = ? ORDER BY last_save DESC LIMIT 1", playerName)
    }

    private fun queryPlayerInfo(uuid: UUID, where: String, param: String): PlayerInfo? {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT player_name, last_save FROM cobblemonguard_players WHERE $where"
            ).use { stmt ->
                stmt.setString(1, param)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    return PlayerInfo(
                        uuid       = uuid,
                        playerName = rs.getString("player_name"),
                        lastSave   = rs.getTimestamp("last_save").toString()
                    )
                }
            }
        }
        return null
    }

    /**
     * Lee SOLO el blob de PC de un jugador por UUID exacto, sin el fallback por nombre
     * de [loadPlayerData] (no hace falta: quien llama ya tiene el UUID real) y sin
     * reclamar ninguna fila. Usado por [com.cobblemonguard.sync.LivePastureSync] para
     * refrescar en segundo plano la PC de un jugador offline en este backend cuando una
     * pastura accede a ella.
     */
    fun loadPcBlobOnly(uuid: UUID): ByteArray? {
        db.getConnection().use { conn ->
            conn.prepareStatement("SELECT cobblemon_pc FROM cobblemonguard_players WHERE uuid = ?").use { stmt ->
                stmt.setString(1, uuid.toString())
                val rs = stmt.executeQuery()
                if (rs.next()) return rs.getBytes("cobblemon_pc")
            }
        }
        return null
    }

    /**
     * Busca el UUID guardado de un jugador por su nombre (la fila más reciente si hubiera
     * varias). Se resuelve contra NUESTRA tabla, no contra la caché de perfiles de
     * Minecraft, así que funciona igual para un jugador que ahora mismo está en otro
     * backend. Lo usa `/cg unlock`.
     */
    /**
     * Copia la fila completa del jugador a la tabla de backups y reemplaza UNA columna por
     * [valor], en una sola transacción. Para `/cg restaurarfila` (valor = el de un backup) y
     * `/cg limpiar` (valor = null).
     *
     * Es la salida para un jugador que no puede entrar porque un tipo de dato suyo no se puede
     * aplicar (ver Resultado.FALLO): esos dos comandos necesitan funcionar sin él conectado.
     * La versión que había no se pierde en el momento: queda como un backup más, con fecha. Ojo
     * que ese backup sigue la misma retención que los demás (backupRetentionHours).
     *
     * @param columna SOLO un nombre de la lista blanca de abajo; nunca texto del usuario.
     * @return false si no existe fila para ese uuid.
     */
    fun respaldarYReemplazar(uuid: UUID, columna: String, valor: ByteArray?): Boolean {
        require(columna in COLUMNAS_DE_DATOS) { "columna no permitida: $columna" }
        db.getConnection().use { conn ->
            val autoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val copiadas = conn.prepareStatement("""
                    INSERT INTO cobblemonguard_backups
                        (uuid, player_name, inventory, ender_chest, cobblemon_pc, cobblemon_party, trinkets, backpacks, economy, ftb_quests, cobblemon_pokedex, cobblemmo_mochila, rct_progreso)
                    SELECT uuid, player_name, inventory, ender_chest, cobblemon_pc, cobblemon_party, trinkets, backpacks, economy, ftb_quests, cobblemon_pokedex, cobblemmo_mochila, rct_progreso
                    FROM cobblemonguard_players WHERE uuid = ?
                """.trimIndent()).use { stmt ->
                    stmt.setString(1, uuid.toString())
                    stmt.executeUpdate()
                }
                if (copiadas == 0) {
                    conn.rollback()
                    return false
                }
                conn.prepareStatement("UPDATE cobblemonguard_players SET $columna = ? WHERE uuid = ?").use { stmt ->
                    stmt.setBytes(1, valor)
                    stmt.setString(2, uuid.toString())
                    stmt.executeUpdate()
                }
                conn.commit()
                return true
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            } finally {
                runCatching { conn.autoCommit = autoCommit }
            }
        }
    }

    fun findUuidByName(playerName: String): UUID? {
        db.getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT uuid FROM cobblemonguard_players
                WHERE player_name = ?
                ORDER BY last_save DESC
                LIMIT 1
            """.trimIndent()).use { stmt ->
                stmt.setString(1, playerName)
                val rs = stmt.executeQuery()
                if (rs.next()) return runCatching { UUID.fromString(rs.getString("uuid")) }.getOrNull()
            }
        }
        return null
    }

    fun hasData(uuid: UUID): Boolean {
        db.getConnection().use { conn ->
            conn.prepareStatement(
                "SELECT 1 FROM cobblemonguard_players WHERE uuid = ? LIMIT 1"
            ).use { stmt ->
                stmt.setString(1, uuid.toString())
                return stmt.executeQuery().next()
            }
        }
    }

    // Log de sincronizaciones

    /**
     * Registra una operación en `cobblemonguard_sync_log`.
     *
     * Por defecto (`syncLogOnlyErrors`) solo se escriben los FALLOS. Una fila por cada SAVE y
     * cada LOAD de cada jugador son millones de filas al año con auto-save activo, y además
     * cada fila toma su PROPIA conexión del pool: cada guardado costaría el doble de tráfico
     * a MySQL para registrar un "salió bien" que casi nunca se consulta.
     */
    fun logSync(uuid: UUID, playerName: String, action: String, success: Boolean, errorMsg: String?) {
        if (success && CobblemonGuard.config.syncLogOnlyErrorsEffective) return
        try {
            db.getConnection().use { conn ->
                conn.prepareStatement("""
                    INSERT INTO cobblemonguard_sync_log (uuid, player_name, action, success, error_msg)
                    VALUES (?, ?, ?, ?, ?)
                """.trimIndent()).use { stmt ->
                    stmt.setString(1, uuid.toString())
                    stmt.setString(2, playerName)
                    stmt.setString(3, action)
                    stmt.setBoolean(4, success)
                    stmt.setString(5, errorMsg)
                    stmt.executeUpdate()
                }
            }
        } catch (e: Exception) {
            CobblemonGuard.logger.warn("No se pudo escribir en sync_log: ${e.message}")
        }
    }

    /**
     * Borra entradas del log más viejas que [retentionHours] (0 = no borrar nada).
     *
     * `sql/setup.sql` solo trae el DELETE como comentario para ejecutar a mano; esto lo hace
     * solo. Se borra en lotes por el mismo motivo que los
     * backups (ver [BackupRepository.cleanOldBackups]).
     */
    fun cleanOldSyncLog(retentionHours: Int) {
        if (retentionHours <= 0) return
        try {
            var totalDeleted = 0
            db.getConnection().use { conn ->
                conn.prepareStatement("""
                    DELETE FROM cobblemonguard_sync_log
                    WHERE synced_at < DATE_SUB(NOW(), INTERVAL ? HOUR)
                    LIMIT 2000
                """.trimIndent()).use { stmt ->
                    stmt.setInt(1, retentionHours)
                    while (true) {
                        val deleted = stmt.executeUpdate()
                        totalDeleted += deleted
                        if (deleted < 2000) break
                    }
                }
            }
            if (totalDeleted > 0)
                CobblemonGuard.logger.debug("[SyncLog] Eliminadas $totalDeleted entradas antiguas (>${retentionHours}h).")
        } catch (e: Exception) {
            CobblemonGuard.logger.warn("[SyncLog] Error limpiando el log de sincronización: ${e.message}")
        }
    }
}

/** Columnas de datos de jugador que `respaldarYReemplazar` acepta. Lista blanca: se interpolan en SQL. */
private val COLUMNAS_DE_DATOS = setOf(
    "inventory", "ender_chest", "cobblemon_pc", "cobblemon_party", "trinkets", "backpacks", "economy", "ftb_quests", "cobblemon_pokedex", "cobblemmo_mochila", "rct_progreso"
)
