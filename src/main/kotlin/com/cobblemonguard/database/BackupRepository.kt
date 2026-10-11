package com.cobblemonguard.database

import com.cobblemonguard.CobblemonGuard
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

class BackupRepository(private val db: DatabaseManager) {

    // ──────────────────────────────────────────────
    // Deduplicación de backups (ver saveBackup)
    // ──────────────────────────────────────────────

    private data class LastBackup(val fingerprint: String, val insertedAtMillis: Long)

    private val lastBackupByPlayer = ConcurrentHashMap<UUID, LastBackup>()

    /** Aunque nada cambie, se fuerza un backup cada tanto para que el jugador nunca se quede sin ninguno. */
    private val FORCE_INTERVAL_MS = 60 * 60 * 1000L   // 1 hora

    /** Olvida el estado de dedup de un jugador (llamar en su disconnect). */
    fun forgetDedup(uuid: UUID) { lastBackupByPlayer.remove(uuid) }

    /**
     * Huella del estado completo del jugador.
     *
     * Se usa CRC32 + longitud por columna en vez de un hash criptográfico porque acá solo
     * se compara un estado contra el INMEDIATAMENTE anterior del MISMO jugador (no se
     * buscan colisiones en un universo grande), y CRC32 es aproximadamente un orden de
     * magnitud más barato sobre blobs de megabytes. Incluir la longitud exacta de cada
     * columna hace que una colisión requiera además coincidir en tamaño byte a byte.
     */
    private fun fingerprint(blobs: List<ByteArray?>): String {
        val sb = StringBuilder(blobs.size * 20)
        for (b in blobs) {
            if (b == null) { sb.append("-;"); continue }
            val crc = CRC32().apply { update(b) }.value
            sb.append(b.size).append(':').append(crc).append(';')
        }
        return sb.toString()
    }

    // ──────────────────────────────────────────────
    // Guardar backup
    // ──────────────────────────────────────────────

    /**
     * Inserta un backup del estado del jugador.
     *
     * Con `backupDedupEnabled` (default) NO inserta si el estado es idéntico al del último
     * backup insertado para ese jugador. Con el intervalo por defecto (5 min) la enorme
     * mayoría de los backups son copias exactas del anterior —la PC de un jugador casi
     * nunca cambia entre dos backups seguidos— y cada fila guarda el estado COMPLETO
     * (inventario + ender + PC + party + accesorios + mochilas), así que en una red con
     * decenas de jugadores activos son decenas de GB de duplicados. El rollback no pierde
     * nada: el backup que ya está en la tabla representa exactamente ese mismo estado.
     *
     * Igual se fuerza una inserción cada [FORCE_INTERVAL_MS] aunque no haya cambios, para
     * que un jugador que estuvo horas quieto no se quede sin backups cuando la retención
     * borre el último.
     *
     * @return true si realmente se insertó una fila.
     */
    fun saveBackup(
        uuid: UUID,
        playerName: String,
        inventory: ByteArray?,
        enderChest: ByteArray?,
        cobblemonPc: ByteArray?,
        cobblemonParty: ByteArray?,
        trinkets: ByteArray?,
        backpacks: ByteArray?,
        economy: ByteArray? = null
    ): Boolean {
        val now = System.currentTimeMillis()
        var fp: String? = null
        if (CobblemonGuard.config.backupDedupEnabledEffective) {
            fp = fingerprint(listOf(inventory, enderChest, cobblemonPc, cobblemonParty, trinkets, backpacks, economy))
            val last = lastBackupByPlayer[uuid]
            if (last != null && last.fingerprint == fp && now - last.insertedAtMillis < FORCE_INTERVAL_MS) {
                CobblemonGuard.logger.debug("[Backup] $playerName sin cambios desde el último backup — no se inserta.")
                return false
            }
        }

        db.getConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO cobblemonguard_backups (uuid, player_name, inventory, ender_chest, cobblemon_pc, cobblemon_party, trinkets, backpacks, economy)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                stmt.executeUpdate()
            }
        }
        if (fp != null) lastBackupByPlayer[uuid] = LastBackup(fp, now)
        return true
    }

    // ──────────────────────────────────────────────
    // Listar backups de un jugador
    // ──────────────────────────────────────────────

    fun listBackups(uuid: UUID, playerName: String? = null): List<Timestamp> {
        val byUuid = queryBackupTimestamps("uuid = ?", uuid.toString())
        // Los backups son filas HISTÓRICAS (nunca se reasocian a un UUID nuevo, a
        // diferencia de cobblemonguard_players): si el jugador tuvo un UUID distinto
        // antes de una reasociación por nombre (ver PlayerDataRepository), sus backups
        // viejos quedaron con el UUID anterior. Completamos con esos si hay nombre.
        if (playerName == null || !CobblemonGuard.config.identifyByNameFallbackEffective || CobblemonGuard.config.identifyByName) {
            return byUuid
        }
        val byName = queryBackupTimestamps("player_name = ? AND uuid != ?", playerName, uuid.toString())
        if (byName.isEmpty()) return byUuid
        return (byUuid + byName).sortedDescending().take(48)
    }

    private fun queryBackupTimestamps(where: String, vararg params: String): List<Timestamp> {
        val result = mutableListOf<Timestamp>()
        db.getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT saved_at FROM cobblemonguard_backups
                WHERE $where
                ORDER BY saved_at DESC
                LIMIT 48
            """.trimIndent()).use { stmt ->
                params.forEachIndexed { i, p -> stmt.setString(i + 1, p) }
                val rs = stmt.executeQuery()
                while (rs.next()) result.add(rs.getTimestamp("saved_at"))
            }
        }
        return result
    }

    // ──────────────────────────────────────────────
    // Obtener el backup más cercano a una fecha
    // ──────────────────────────────────────────────

    data class BackupEntry(
        val savedAt: Timestamp,
        val inventory: ByteArray?,
        val enderChest: ByteArray?,
        val cobblemonPc: ByteArray?,
        val cobblemonParty: ByteArray?,
        val trinkets: ByteArray?,
        val backpacks: ByteArray?,
        val economy: ByteArray? = null
    )

    fun getClosestBackup(uuid: UUID, targetTimeMillis: Long, playerName: String? = null): BackupEntry? {
        queryClosestBackup("uuid = ? AND saved_at <= ?", uuid.toString(), Timestamp(targetTimeMillis))?.let { return it }
        if (playerName == null || !CobblemonGuard.config.identifyByNameFallbackEffective || CobblemonGuard.config.identifyByName) {
            return null
        }
        // Mismo respaldo que en listBackups: el momento pedido puede caer ANTES de que
        // este UUID existiera (backups viejos hechos bajo el UUID anterior del jugador).
        return queryClosestBackup(
            "player_name = ? AND uuid != ? AND saved_at <= ?",
            playerName, uuid.toString(), Timestamp(targetTimeMillis)
        )
    }

    private fun queryClosestBackup(where: String, vararg params: Any): BackupEntry? {
        db.getConnection().use { conn ->
            conn.prepareStatement("""
                SELECT saved_at, inventory, ender_chest, cobblemon_pc, cobblemon_party, trinkets, backpacks, economy
                FROM cobblemonguard_backups
                WHERE $where
                ORDER BY saved_at DESC
                LIMIT 1
            """.trimIndent()).use { stmt ->
                params.forEachIndexed { i, p ->
                    when (p) {
                        is Timestamp -> stmt.setTimestamp(i + 1, p)
                        else -> stmt.setString(i + 1, p.toString())
                    }
                }
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    return BackupEntry(
                        savedAt        = rs.getTimestamp("saved_at"),
                        inventory      = rs.getBytes("inventory"),
                        enderChest     = rs.getBytes("ender_chest"),
                        cobblemonPc    = rs.getBytes("cobblemon_pc"),
                        cobblemonParty = rs.getBytes("cobblemon_party"),
                        trinkets       = rs.getBytes("trinkets"),
                        backpacks      = rs.getBytes("backpacks"),
                        economy        = rs.getBytes("economy")
                    )
                }
            }
        }
        return null
    }

    // ──────────────────────────────────────────────
    // Limpiar backups viejos
    // ──────────────────────────────────────────────

    /**
     * Borra backups más viejos que [retentionHours], EN LOTES.
     *
     * Antes era un único DELETE sin LIMIT. Si alguien bajaba la retención, o si el mod
     * estuvo días sin limpiar, ese DELETE podía tocar cientos de miles de filas con
     * LONGBLOBs en una sola transacción: undo log enorme, locks largos en InnoDB y el
     * resto de las consultas del servidor esperando detrás. En lotes de 500 cada
     * transacción es corta y MySQL puede intercalar el resto del trabajo.
     */
    fun cleanOldBackups(retentionHours: Int) {
        if (retentionHours <= 0) return
        try {
            var totalDeleted = 0
            db.getConnection().use { conn ->
                conn.prepareStatement("""
                    DELETE FROM cobblemonguard_backups
                    WHERE saved_at < DATE_SUB(NOW(), INTERVAL ? HOUR)
                    LIMIT 500
                """.trimIndent()).use { stmt ->
                    stmt.setInt(1, retentionHours)
                    while (true) {
                        val deleted = stmt.executeUpdate()
                        totalDeleted += deleted
                        if (deleted < 500) break
                    }
                }
            }
            if (totalDeleted > 0)
                CobblemonGuard.logger.debug("[Backup] Eliminados $totalDeleted backups antiguos (>${retentionHours}h).")
        } catch (e: Exception) {
            CobblemonGuard.logger.warn("[Backup] Error limpiando backups: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // Parsear tiempo ("30m", "1h", "2h", "3d", "10min", "2dias")
    // ──────────────────────────────────────────────

    companion object {
        fun parseTimeAgo(input: String): Long? {
            val regex = Regex("^(\\d+)\\s*(m|min|minutos?|h|horas?|d|d[ií]as?)$", RegexOption.IGNORE_CASE)
            val match = regex.matchEntire(input.trim()) ?: return null
            val value = match.groupValues[1].toLong()
            val unit = match.groupValues[2].lowercase()
            val millis = when {
                unit.startsWith("d") -> value * 86_400_000L   // días
                unit.startsWith("h") -> value * 3_600_000L    // horas
                else                 -> value * 60_000L       // minutos
            }
            return System.currentTimeMillis() - millis
        }

        fun formatTimestamp(ts: java.sql.Timestamp): String {
            val ago = System.currentTimeMillis() - ts.time
            val mins = ago / 60_000
            return when {
                mins < 60   -> "hace ${mins}min"
                mins < 1440 -> "hace ${mins / 60}h ${mins % 60}min"
                else        -> "hace ${mins / 1440}d"
            }
        }
    }
}
