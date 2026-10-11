-- ============================================================================
-- CobblemonGUARD — RECUPERACIÓN tras el "evento de caos"
-- ============================================================================
-- Objetivo: devolver a cada jugador su inventario/ender/accesorios al estado
-- ANTERIOR al caos, SIN afectar a quienes no se conectaron ese día.
--
-- Cómo funciona: por cada jugador busca el snapshot MÁS RECIENTE que sea
--   (a) anterior o igual a la hora del evento (@EVENTO) y
--   (b) con inventario NO vacío (LENGTH > 100 bytes)
-- combinando la tabla actual + todos los backups. Luego copia ese snapshot a la
-- tabla principal. Los jugadores que no jugaron ese día tienen como snapshot más
-- reciente su propia fila anterior => quedan IGUAL (no pierden nada).
--
-- ORDEN DE EJECUCIÓN:  1) migracion.sql   2) ESTE script   3) arrancar servidor
-- Ejecútalo en phpMyAdmin (pestaña SQL). Cada bloque puede ir por separado.
-- ============================================================================

USE cobblemonguard;   -- <<< ajusta el nombre si es distinto

-- ────────────────────────────────────────────────────────────────────────────
-- PASO 0 — AJUSTA LA HORA DEL EVENTO
-- Pon la hora (server time / UTC, como se ve en las columnas) JUSTO ANTES de que
-- empezara el desastre. Se recuperará el último buen estado hasta ese momento.
-- ────────────────────────────────────────────────────────────────────────────
SET @EVENTO = '2026-07-19 20:00:00';   -- <<< CAMBIA ESTO

-- ────────────────────────────────────────────────────────────────────────────
-- PASO 1 — SEGURIDAD: respaldo de la tabla principal antes de tocar nada
-- ────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS cobblemonguard_players_backup_recuperacion
    AS SELECT * FROM cobblemonguard_players;

-- ────────────────────────────────────────────────────────────────────────────
-- PASO 2 — Candidatos pre-evento con inventario no vacío (de ambas tablas)
-- ────────────────────────────────────────────────────────────────────────────
DROP TEMPORARY TABLE IF EXISTS _cand;
CREATE TEMPORARY TABLE _cand AS
    SELECT uuid, last_save AS saved_at, inventory, ender_chest, trinkets
        FROM cobblemonguard_players
        WHERE last_save <= @EVENTO AND inventory IS NOT NULL AND LENGTH(inventory) > 100
    UNION ALL
    SELECT uuid, saved_at, inventory, ender_chest, trinkets
        FROM cobblemonguard_backups
        WHERE saved_at <= @EVENTO AND inventory IS NOT NULL AND LENGTH(inventory) > 100;

-- Mejor (más reciente) candidato por jugador
DROP TEMPORARY TABLE IF EXISTS _best;
CREATE TEMPORARY TABLE _best AS
    SELECT c.uuid, c.saved_at, c.inventory, c.ender_chest, c.trinkets
    FROM _cand c
    JOIN (SELECT uuid, MAX(saved_at) AS mx FROM _cand GROUP BY uuid) m
      ON m.uuid = c.uuid AND m.mx = c.saved_at;

-- ────────────────────────────────────────────────────────────────────────────
-- PASO 3 — VISTA PREVIA (DRY-RUN). Revisa ANTES de aplicar.
--   Compara el estado actual vs el que se restaurará.
-- ────────────────────────────────────────────────────────────────────────────
SELECT p.player_name                       AS jugador,
       p.last_save                         AS actual_fecha,
       LENGTH(p.inventory)                 AS actual_inv_bytes,
       b.saved_at                          AS restaurar_a_fecha,
       LENGTH(b.inventory)                 AS restaurar_inv_bytes,
       CASE
         WHEN b.uuid IS NULL                       THEN 'sin cambio (no hay snapshot pre-evento)'
         WHEN b.saved_at = p.last_save             THEN 'sin cambio (ya es el mejor)'
         ELSE 'SE RESTAURA'
       END                                 AS accion
FROM cobblemonguard_players p
LEFT JOIN _best b ON b.uuid = p.uuid
ORDER BY accion DESC, actual_inv_bytes ASC;

-- ────────────────────────────────────────────────────────────────────────────
-- PASO 4 — APLICAR. (Ejecuta SOLO cuando la vista previa te convenza.)
--   Restaura inventario + ender + accesorios. NO toca PC/party (viven en el
--   mundo de Cobblemon y se respaldarán solos con el mod nuevo).
-- ────────────────────────────────────────────────────────────────────────────
UPDATE cobblemonguard_players p
JOIN _best b ON b.uuid = p.uuid
SET p.inventory   = b.inventory,
    p.ender_chest = b.ender_chest,
    p.trinkets    = b.trinkets,
    p.last_save   = NOW();

-- ────────────────────────────────────────────────────────────────────────────
-- Si algo sale mal, deshacer todo con:
--   REPLACE INTO cobblemonguard_players
--   SELECT * FROM cobblemonguard_players_backup_recuperacion;
-- ============================================================================
