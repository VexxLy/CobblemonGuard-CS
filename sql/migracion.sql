-- ============================================================================
-- CobblemonGUARD — MIGRACIÓN de la base de datos VIEJA a la NUEVA versión
-- ============================================================================
-- Ejecuta esto UNA vez en tu hosting (phpMyAdmin → pestaña SQL) ANTES de
-- arrancar el servidor con el mod nuevo.
--
-- Buenas noticias: el esquema es COMPATIBLE. El mod nuevo agrega solo columnas
-- nuevas (cobblemon_party, backpacks) automáticamente al arrancar. NO se pierde
-- inventario ni ender chest.
--
-- PERO hay un detalle CRÍTICO: en la versión vieja el guardado del PC de
-- Cobblemon estaba roto y guardó SIEMPRE una PC VACÍA (34 bytes). Si dejamos
-- esos datos, cuando un jugador entre, el mod nuevo leería esa "PC vacía" y
-- BORRARÍA los Pokémon que tiene el jugador en el mundo. Para evitarlo, ponemos
-- esa columna en NULL: así el mod nuevo la ignora y conserva la PC real del
-- mundo, y a partir del próximo guardado la empezará a respaldar bien.
-- ============================================================================

USE cobblemonguard;   -- <<< cambia si tu base de datos tiene otro nombre

-- 1) VERIFICACIÓN (opcional): confirma que el PC viejo está vacío.
--    Si MAX(LENGTH) es un número pequeño (~30-40) => está vacío, seguro de borrar.
SELECT COUNT(*)                         AS jugadores,
       MIN(LENGTH(cobblemon_pc))        AS pc_min_bytes,
       MAX(LENGTH(cobblemon_pc))        AS pc_max_bytes
FROM cobblemonguard_players;

-- 2) SEGURIDAD: copia de respaldo de la tabla antes de tocar nada.
CREATE TABLE IF NOT EXISTS cobblemonguard_players_backup_migracion
    AS SELECT * FROM cobblemonguard_players;

-- 3) Neutraliza el PC viejo roto (evita que el mod nuevo borre PCs reales).
UPDATE cobblemonguard_players  SET cobblemon_pc = NULL;
UPDATE cobblemonguard_backups  SET cobblemon_pc = NULL;

-- Las columnas cobblemon_party y backpacks se crean solas al arrancar el mod
-- (ALTER TABLE ... ADD COLUMN IF NOT EXISTS). No hace falta tocarlas aquí.
-- ============================================================================
-- Tras ejecutar esto: arranca el servidor con el mod nuevo apuntando a esta
-- misma base de datos. El inventario y ender chest de todos se conservan.
-- ============================================================================
