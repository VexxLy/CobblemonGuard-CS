package com.cobblemonguard.network

import com.cobblemonguard.CobblemonGuard
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.util.UUID

/**
 * Locks cross-server en la MySQL de la red (alternativa a Redis, `lockBackend: "mysql"`).
 *
 * Misma semántica que los scripts Lua de [CrossServerCoordinator]:
 *  - Un lock por jugador: fila (uuid, owner, expires_at) en `cobblemonguard_locks`.
 *  - Vencido = como si no existiera (Redis lo borraba con el TTL; acá se ignora y se pisa).
 *  - Todas las horas salen del RELOJ DE LA BASE (NOW(3)), no del de cada backend: dos servidores
 *    con la hora un poco corrida no pueden robarse un lock vigente.
 *  - claim / renovar / soltar comparan el dueño en la misma sentencia (atómico, se pueden repetir).
 *
 * Usa un pool PROPIO de 2 conexiones, separado del de guardados: con MySQL lento el pool principal
 * se llena de guardados y el heartbeat no puede quedar en la cola detrás de ellos (mismo motivo por
 * el que los locks de Redis tienen su propio hilo).
 */
object MySqlLocks {

    private const val TABLA = "cobblemonguard_locks"
    /** Milisegundos actuales según MySQL. */
    private const val AHORA = "ROUND(UNIX_TIMESTAMP(NOW(3)) * 1000)"
    private const val CONEXIONES = 2

    @Volatile private var ds: HikariDataSource? = null
    @Volatile private var ultimaLimpieza = 0L

    fun isConnected(): Boolean = ds?.isRunning == true

    @Synchronized
    fun connect(): Boolean {
        if (ds != null) return true
        return try {
            val nuevo = CobblemonGuard.database.crearPoolSecundario("CobblemonGUARD-Locks", CONEXIONES)
            nuevo.connection.use { c ->
                c.createStatement().use { st ->
                    st.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS $TABLA (
                            uuid       VARCHAR(36) NOT NULL,
                            owner      VARCHAR(64) NOT NULL,
                            expires_at BIGINT      NOT NULL,
                            PRIMARY KEY (uuid),
                            INDEX idx_owner (owner)
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """.trimIndent()
                    )
                }
            }
            ds = nuevo
            CobblemonGuard.logger.info("[CrossServer] Locks en MySQL (tabla $TABLA, pool propio de $CONEXIONES conexiones).")
            true
        } catch (e: Exception) {
            CobblemonGuard.logger.error("[CrossServer] No se pudieron preparar los locks en MySQL: ${e.message}")
            false
        }
    }

    fun close() {
        runCatching { ds?.close() }
        ds = null
    }

    private fun <T> conConexion(bloque: (Connection) -> T): T {
        val fuente = ds ?: throw IllegalStateException("Locks de MySQL sin conexión")
        return fuente.connection.use(bloque)
    }

    /** Dueño VIGENTE del lock de [uuid], o null si no hay (o venció). */
    fun dueño(uuid: UUID): String? = conConexion { c ->
        c.prepareStatement("SELECT owner FROM $TABLA WHERE uuid=? AND expires_at > $AHORA").use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    /**
     * Toma el lock si está libre, vencido o ya es de [servidor]. true si quedó a nombre de [servidor].
     *
     * En el ON DUPLICATE KEY UPDATE las asignaciones se evalúan en orden: después de cambiar
     * `owner`, la segunda condición ya ve el dueño nuevo, así que el vencimiento se renueva justo
     * en los casos en que el lock pasó (o ya era) de este backend.
     */
    fun claim(uuid: UUID, servidor: String, ttlSegundos: Int): Boolean = conConexion { c ->
        c.prepareStatement(
            "INSERT INTO $TABLA (uuid, owner, expires_at) VALUES (?, ?, $AHORA + ?) " +
            "ON DUPLICATE KEY UPDATE " +
            "owner = IF(owner = VALUES(owner) OR expires_at <= $AHORA, VALUES(owner), owner), " +
            "expires_at = IF(owner = VALUES(owner), VALUES(expires_at), expires_at)"
        ).use { ps ->
            ps.setString(1, uuid.toString())
            ps.setString(2, servidor)
            ps.setLong(3, ttlSegundos * 1000L)
            ps.executeUpdate()
        }
        c.prepareStatement("SELECT owner FROM $TABLA WHERE uuid=?").use { ps ->
            ps.setString(1, uuid.toString())
            ps.executeQuery().use { rs -> rs.next() && rs.getString(1) == servidor }
        }
    }

    /**
     * Renueva los locks VIGENTES de [servidor] entre [uuids]. Devuelve los que siguen siendo suyos.
     * Un lock ya vencido no se revive (igual que en Redis, donde la llave ya no existía).
     * Dos sentencias por llamada, sin importar cuántos jugadores haya.
     */
    fun renovar(uuids: List<UUID>, servidor: String, ttlSegundos: Int): Set<UUID> {
        if (uuids.isEmpty()) return emptySet()
        val marcas = uuids.joinToString(",") { "?" }
        return conConexion { c ->
            c.prepareStatement(
                "UPDATE $TABLA SET expires_at = $AHORA + ? WHERE owner = ? AND expires_at > $AHORA AND uuid IN ($marcas)"
            ).use { ps ->
                ps.setLong(1, ttlSegundos * 1000L)
                ps.setString(2, servidor)
                uuids.forEachIndexed { i, u -> ps.setString(3 + i, u.toString()) }
                ps.executeUpdate()
            }
            val siguen = HashSet<UUID>()
            c.prepareStatement(
                "SELECT uuid FROM $TABLA WHERE owner = ? AND expires_at > $AHORA AND uuid IN ($marcas)"
            ).use { ps ->
                ps.setString(1, servidor)
                uuids.forEachIndexed { i, u -> ps.setString(2 + i, u.toString()) }
                ps.executeQuery().use { rs -> while (rs.next()) siguen.add(UUID.fromString(rs.getString(1))) }
            }
            limpiarViejos(c)
            siguen
        }
    }

    /** Suelta el lock solo si es de [servidor]. */
    fun release(uuid: UUID, servidor: String) {
        conConexion { c ->
            c.prepareStatement("DELETE FROM $TABLA WHERE uuid = ? AND owner = ?").use { ps ->
                ps.setString(1, uuid.toString())
                ps.setString(2, servidor)
                ps.executeUpdate()
            }
        }
    }

    /** Suelta el lock sin mirar el dueño (/cg unlock, limpieza administrativa). */
    fun forceRelease(uuid: UUID) {
        conConexion { c ->
            c.prepareStatement("DELETE FROM $TABLA WHERE uuid = ?").use { ps ->
                ps.setString(1, uuid.toString())
                ps.executeUpdate()
            }
        }
    }

    /** Como mucho cada 10 min: borra filas vencidas hace más de 1 h (no afectan, solo ocupan). */
    private fun limpiarViejos(c: Connection) {
        val ahora = System.currentTimeMillis()
        if (ahora - ultimaLimpieza < 600_000L) return
        ultimaLimpieza = ahora
        runCatching {
            c.createStatement().use { st -> st.executeUpdate("DELETE FROM $TABLA WHERE expires_at < $AHORA - 3600000") }
        }
    }
}
