-- ============================================================
-- CobblemonGUARD - Script de configuración MySQL
-- Ejecuta este script en tu panel de hosting (phpMyAdmin, etc.)
-- ============================================================

-- 1. Crear la base de datos (si no existe ya)
CREATE DATABASE IF NOT EXISTS cobblemonguard
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE cobblemonguard;

-- 2. Tabla principal de datos de jugadores
CREATE TABLE IF NOT EXISTS cobblemonguard_players (
    uuid         VARCHAR(36)  NOT NULL COMMENT 'UUID del jugador (formato: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx)',
    player_name  VARCHAR(16)  NOT NULL COMMENT 'Nombre del jugador (puede cambiar)',
    last_save    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
                                       ON UPDATE CURRENT_TIMESTAMP COMMENT 'Última sincronización',
    inventory       LONGBLOB           COMMENT 'Inventario completo serializado en NBT comprimido (incluye SB items en inventario)',
    ender_chest     LONGBLOB           COMMENT 'Cofre del Ender serializado en NBT comprimido',
    cobblemon_pc    LONGBLOB           COMMENT 'PC completa de Cobblemon serializada en NBT comprimido',
    cobblemon_party LONGBLOB           COMMENT 'Equipo de 6 Pokémon (party) de Cobblemon en NBT comprimido',
    trinkets        LONGBLOB           COMMENT 'Slots de Accessories/Trinkets — mochilas Sophisticated Backpacks EQUIPADAS',
    backpacks       LONGBLOB           COMMENT 'Contenido real de mochilas SB (mapa uuid->contenido) para portabilidad total',
    economy         LONGBLOB           COMMENT 'Saldos de Impactor (texto plano "namespace:value=monto", una moneda por línea)',
    PRIMARY KEY (uuid),
    INDEX idx_name (player_name),
    INDEX idx_last_save (last_save)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Datos de jugadores sincronizados por CobblemonGUARD';

-- 3. Tabla de respaldos con historial (para /cg rollback)
--    FALTABA en versiones anteriores de este script: el mod la creaba solo al arrancar,
--    así que "funcionaba", pero el esquema terminaba distinto según cómo hubieras
--    instalado. Los dos índices son los que el mod espera:
--      idx_uuid_time  → listado y búsqueda normales, por UUID
--      idx_name_time  → respaldo por NOMBRE (backups viejos hechos con otro UUID, tras
--                       una reasociación por identifyByNameFallback). Sin este índice esa
--                       consulta hace un escaneo COMPLETO de esta tabla, que es la más
--                       pesada del esquema.
CREATE TABLE IF NOT EXISTS cobblemonguard_backups (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    uuid         VARCHAR(36)  NOT NULL,
    player_name  VARCHAR(16)  NOT NULL,
    saved_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    inventory       LONGBLOB,
    ender_chest     LONGBLOB,
    cobblemon_pc    LONGBLOB,
    cobblemon_party LONGBLOB,
    trinkets        LONGBLOB,
    backpacks       LONGBLOB,
    economy         LONGBLOB,
    PRIMARY KEY (id),
    INDEX idx_uuid_time (uuid, saved_at),
    INDEX idx_name_time (player_name, saved_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Historial de respaldos para rollback';

-- 4. Tabla de log de sincronizaciones (para debugging y auditoría)
CREATE TABLE IF NOT EXISTS cobblemonguard_sync_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    uuid        VARCHAR(36)  NOT NULL,
    player_name VARCHAR(16)  NOT NULL,
    action      VARCHAR(10)  NOT NULL COMMENT 'SAVE o LOAD',
    success     BOOLEAN      NOT NULL DEFAULT TRUE,
    error_msg   TEXT                  COMMENT 'Mensaje de error si success=FALSE',
    synced_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_uuid (uuid),
    INDEX idx_time (synced_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Historial de sincronizaciones CobblemonGUARD';

-- 5. Usuario recomendado (opcional, ajusta el password)
-- Descomenta si quieres crear un usuario dedicado:
-- CREATE USER IF NOT EXISTS 'cobblemonguard'@'%' IDENTIFIED BY 'TU_PASSWORD_SEGURO';
-- GRANT SELECT, INSERT, UPDATE, DELETE ON cobblemonguard.* TO 'cobblemonguard'@'%';
-- FLUSH PRIVILEGES;

-- ============================================================
-- Consultas útiles para administración:
-- ============================================================

-- Ver todos los jugadores guardados:
-- SELECT uuid, player_name, last_save FROM cobblemonguard_players ORDER BY last_save DESC;

-- Ver log de sincronizaciones recientes:
-- SELECT * FROM cobblemonguard_sync_log ORDER BY synced_at DESC LIMIT 50;

-- Ver errores recientes:
-- SELECT * FROM cobblemonguard_sync_log WHERE success = FALSE ORDER BY synced_at DESC LIMIT 20;

-- Borrar datos de un jugador específico (usar con cuidado):
-- DELETE FROM cobblemonguard_players WHERE uuid = 'xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx';

-- Limpiar logs viejos a mano (normalmente NO hace falta: desde la 1.2.0 el mod limpia
-- esta tabla solo, según 'syncLogRetentionHours', y por defecto solo registra los fallos):
-- DELETE FROM cobblemonguard_sync_log WHERE synced_at < DATE_SUB(NOW(), INTERVAL 30 DAY) LIMIT 2000;

-- Ver cuánto ocupa cada tabla (útil para vigilar el crecimiento de los backups):
-- SELECT table_name,
--        ROUND(((data_length + index_length) / 1024 / 1024), 1) AS mb,
--        table_rows
-- FROM information_schema.TABLES
-- WHERE table_schema = DATABASE() AND table_name LIKE 'cobblemonguard%'
-- ORDER BY (data_length + index_length) DESC;
