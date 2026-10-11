package com.cobblemonguard.database

import com.cobblemonguard.CobblemonGuard
import com.cobblemonguard.config.GuardConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection

class DatabaseManager(private val config: GuardConfig) {

    private var dataSource: HikariDataSource? = null

    // Clase del driver MySQL después de la relocalización del Shadow plugin.
    // IMPORTANTE: esta cadena debe coincidir con la regla relocate() en build.gradle.kts:
    //   relocate("com.mysql", "com.cobblemonguard.libs.mysql")
    private val DRIVER_CLASS = "com.cobblemonguard.libs.mysql.cj.jdbc.Driver"

    fun connect() {
        // Cargamos el driver explícitamente para que JDBC lo registre
        // (necesario cuando el driver está en un classloader de mod, no en el system classloader)
        try {
            Class.forName(DRIVER_CLASS)
        } catch (e: ClassNotFoundException) {
            throw RuntimeException(
                "Driver MySQL no encontrado en clase '$DRIVER_CLASS'. " +
                "Verifica que el build.gradle.kts tenga la regla relocate correcta.", e
            )
        }

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = buildJdbcUrl()
            username = config.username
            password = config.password
            driverClassName = DRIVER_CLASS
            maximumPoolSize = config.poolSize
            // Pool de tamaño FIJO (minimumIdle == maximumPoolSize), que es la recomendación
            // de HikariCP para cargas con picos. Con minimumIdle=1 (lo de antes), justo en
            // el peor momento —un reinicio con 30 jugadores entrando a la vez— Hikari tenía
            // que abrir las conexiones una por una, con handshake TCP y autenticación cada
            // vez, sumando latencia a cada join. Mantener las conexiones abiertas cuesta
            // muy poco y elimina ese arranque en frío.
            minimumIdle = config.poolSize
            connectionTimeout = config.connectionTimeoutMs
            idleTimeout = 600_000
            maxLifetime = 1_800_000
            poolName = "CobblemonGUARD-Pool"
            // Sin connectionTestQuery a propósito: el driver de MySQL 8 es JDBC4, así que
            // Hikari usa Connection.isValid(), que es más eficiente que mandar un
            // "SELECT 1" por cada préstamo de conexión. Setearlo lo desactivaba.
        }

        dataSource = HikariDataSource(hikariConfig)
        CobblemonGuard.logger.info("MySQL conectado: ${config.host}:${config.port}/${config.database}")
    }

    fun createTables() {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                // Tabla principal de datos de jugadores
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS cobblemonguard_players (
                        uuid         VARCHAR(36)  NOT NULL,
                        player_name  VARCHAR(16)  NOT NULL,
                        last_save    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                          ON UPDATE CURRENT_TIMESTAMP,
                        inventory      LONGBLOB,
                        ender_chest    LONGBLOB,
                        cobblemon_pc   LONGBLOB,
                        cobblemon_party LONGBLOB COMMENT 'Equipo de 6 Pokémon (party) de Cobblemon',
                        trinkets       LONGBLOB COMMENT 'Slots de Trinkets/Accessories — mochilas equipadas de Sophisticated Backpacks',
                        backpacks      LONGBLOB COMMENT 'Contenido real de las mochilas SB (mapa uuid->contenido)',
                        economy        LONGBLOB COMMENT 'Saldos de Impactor (texto plano, una moneda por línea)',
                        PRIMARY KEY (uuid),
                        INDEX idx_name (player_name)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                """.trimIndent())

                // Tabla de respaldos con historial (para rollback)
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS cobblemonguard_backups (
                        id           BIGINT       NOT NULL AUTO_INCREMENT,
                        uuid         VARCHAR(36)  NOT NULL,
                        player_name  VARCHAR(16)  NOT NULL,
                        saved_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        inventory      LONGBLOB,
                        ender_chest    LONGBLOB,
                        cobblemon_pc   LONGBLOB,
                        cobblemon_party LONGBLOB,
                        trinkets       LONGBLOB,
                        backpacks      LONGBLOB,
                        economy        LONGBLOB,
                        PRIMARY KEY (id),
                        INDEX idx_uuid_time (uuid, saved_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                """.trimIndent())


                // Tabla de log de sincronizaciones (útil para debugging)
                stmt.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS cobblemonguard_sync_log (
                        id          BIGINT       NOT NULL AUTO_INCREMENT,
                        uuid        VARCHAR(36)  NOT NULL,
                        player_name VARCHAR(16)  NOT NULL,
                        action      VARCHAR(10)  NOT NULL COMMENT 'SAVE o LOAD',
                        success     BOOLEAN      NOT NULL DEFAULT TRUE,
                        error_msg   TEXT,
                        synced_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (id),
                        INDEX idx_uuid (uuid),
                        INDEX idx_time (synced_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
                """.trimIndent())
            }

            // ── Migraciones ────────────────────────────────────────────────
            //
            // ANTES esto se hacía con "ALTER TABLE ... ADD COLUMN IF NOT EXISTS", dentro de
            // un catch vacío. Pero `IF NOT EXISTS` en ADD COLUMN es una extensión de
            // MARIADB: MySQL (Oracle) no la soporta en NINGUNA versión, ni la 8. O sea que
            // en MySQL la sentencia siempre lanzaba error de sintaxis, se la tragaba el
            // catch, y la migración NUNCA ocurría — silenciosamente. En instalaciones
            // nuevas no se notaba porque el CREATE TABLE de arriba ya trae las columnas,
            // pero cualquier servidor actualizando desde una versión vieja del mod se
            // quedaba sin ellas y fallaba con "Unknown column".
            //
            // Ahora se consulta information_schema (estándar, funciona igual en MySQL y
            // MariaDB) y el ALTER se emite solo si realmente falta.
            for (table in listOf("cobblemonguard_players", "cobblemonguard_backups")) {
                for (col in listOf("trinkets", "cobblemon_party", "backpacks", "economy")) {
                    ensureColumn(conn, table, col, "LONGBLOB")
                }
            }

            // Índice para el respaldo por NOMBRE de BackupRepository (listBackups /
            // getClosestBackup consultan "player_name = ? AND uuid != ?"). Sin él, esas
            // consultas hacían un escaneo COMPLETO de la tabla más pesada del esquema.
            ensureIndex(conn, "cobblemonguard_backups", "idx_name_time", "player_name, saved_at")
            // Presente en sql/setup.sql pero no acá — el esquema quedaba distinto según
            // si instalaste con el script o dejaste que el mod creara las tablas.
            ensureIndex(conn, "cobblemonguard_players", "idx_last_save", "last_save")
        }
        CobblemonGuard.logger.info("Tablas MySQL verificadas/creadas.")
    }

    /** Agrega [column] a [table] si no existe (comprobado contra information_schema). */
    private fun ensureColumn(conn: Connection, table: String, column: String, definition: String) {
        try {
            val exists = conn.prepareStatement("""
                SELECT 1 FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                LIMIT 1
            """.trimIndent()).use { stmt ->
                stmt.setString(1, table)
                stmt.setString(2, column)
                stmt.executeQuery().next()
            }
            if (exists) return
            conn.createStatement().use { it.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition") }
            CobblemonGuard.logger.info("[Migración] Columna '$column' agregada a $table.")
        } catch (e: Exception) {
            CobblemonGuard.logger.error("[Migración] No se pudo verificar/agregar '$column' en $table: ${e.message}")
        }
    }

    /** Crea el índice [indexName] sobre [columns] si no existe. */
    private fun ensureIndex(conn: Connection, table: String, indexName: String, columns: String) {
        try {
            val exists = conn.prepareStatement("""
                SELECT 1 FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?
                LIMIT 1
            """.trimIndent()).use { stmt ->
                stmt.setString(1, table)
                stmt.setString(2, indexName)
                stmt.executeQuery().next()
            }
            if (exists) return
            CobblemonGuard.logger.info("[Migración] Creando índice '$indexName' en $table ($columns)... puede tardar si la tabla ya es grande.")
            conn.createStatement().use { it.executeUpdate("ALTER TABLE $table ADD INDEX $indexName ($columns)") }
            CobblemonGuard.logger.info("[Migración] Índice '$indexName' creado en $table.")
        } catch (e: Exception) {
            CobblemonGuard.logger.error("[Migración] No se pudo verificar/crear el índice '$indexName' en $table: ${e.message}")
        }
    }

    fun getConnection(): Connection {
        return dataSource?.connection
            ?: throw IllegalStateException(
                "Base de datos no conectada. CobblemonGUARD no pudo inicializarse correctamente."
            )
    }

    fun isConnected(): Boolean = dataSource?.isRunning == true

    fun close() {
        dataSource?.close()
        CobblemonGuard.logger.info("Pool de conexiones MySQL cerrado.")
    }

    private fun buildJdbcUrl(): String {
        return "jdbc:mysql://${config.host}:${config.port}/${config.database}" +
            "?useSSL=false" +
            "&allowPublicKeyRetrieval=true" +
            "&serverTimezone=UTC" +
            "&characterEncoding=utf8" +
            // Caché de PreparedStatements: este mod repite SIEMPRE las mismas 6-7
            // sentencias (el UPSERT del jugador, el SELECT por uuid, el INSERT de backup…),
            // así que cachearlas evita reparsearlas en cada llamada.
            "&cachePrepStmts=true" +
            "&prepStmtCacheSize=250" +
            "&prepStmtCacheSqlLimit=2048" +
            // NO se activa useServerPrepStmts a propósito: acá se mandan LONGBLOB que
            // pueden pesar megabytes (PC completa + mochilas), y con prepared statements
            // del lado servidor esos blobs quedan retenidos en memoria de MySQL. El
            // beneficio no compensa en este workload.
            //
            // socketTimeout: red de seguridad para que un MySQL colgado no deje un hilo
            // esperando para siempre (con el pool de hilos acotado, un hilo trabado es un
            // hilo menos para todos los guardados).
            "&connectTimeout=10000" +
            "&socketTimeout=60000"
            // autoReconnect=true quitado: es un parámetro legacy de Connector/J
            // desaconsejado cuando hay un pool de conexiones — Hikari ya valida cada
            // conexión antes de prestarla y las recicla por maxLifetime.
    }
}
