# CobblemonGUARD

Mod **server-side** para Fabric 1.21.1 (probado contra Cobblemon 1.8.0) que sincroniza los
datos de cada jugador con una base de datos **MySQL**: inventario, cofre del Ender, PC y
equipo de Cobblemon, accesorios (Accessories), el contenido de las mochilas de
Sophisticated Backpacks y los saldos de economía (Impactor). Pensado como alternativa a
CobbledSync usando MySQL en vez de MongoDB, con una capa de coordinación por **Redis**
para redes multi-backend (opcional).

## Qué hace (y qué NO hace)

**Hace:**
- Guarda/restaura por jugador en join/quit, auto-save y al apagar el servidor.
- Sistema de backups con historial para `rollback` (retención configurable).
- Comandos de admin (`/cg save|restore|rollback|backup|status|saving ...`), con notificaciones a Discord.
- Toda la escritura a MySQL es asíncrona (no bloquea el hilo del servidor).
- **Cross-server sync (opcional, `crossServerSyncEnabled`):** en redes con varios backends
  Fabric detrás de un proxy (Velocity-CTD, FabricProxy-Lite), coordina por Redis quién
  "tiene" al jugador en cada momento, para que el backend destino espere a que el origen
  termine de guardar antes de cargarlo — evita el dupeo por doble-carga entre procesos.
  Ver `network/CrossServerCoordinator.kt` y la sección correspondiente en `INSTALACION.md`.

**NO hace (relevante para debugging de crasheos):**
- No registra ni modifica **recetas** (`recipes`), bloques, ítems ni datapacks.
- No define paquetes de red personalizados ni toca el `update_recipes` / `chat_session`.
- No tiene mixins ni transforma clases de otros mods.
- Solo interactúa con Cobblemon/Accessories/SB/Impactor **por reflexión en tiempo de
  ejecución**, siempre en el hilo del servidor (excepto las llamadas de red a Redis/MySQL,
  que corren en el executor de BD, nunca en el hilo del servidor).

Por diseño, este mod **no puede** causar errores de sincronización de recetas
(`update_recipes`) ni de sesión de chat (`chat_session_update`) — esos son de otros
mods/datapacks del modpack.

## Estructura

- `src/main/kotlin/com/cobblemonguard/` — código Kotlin del mod
  - `events/PlayerEventHandler.kt` — join/quit, freeze, save/load, guards anti-pérdida de datos
  - `sync/` — `InventorySync`, `PokemonPCSync`, `PokemonPartySync`, `AccessorySync`, `BackpackSync`, `EconomySync`, `CobblemonStorage`, `ImpactorEconomy`
  - `database/` — `DatabaseManager` (HikariCP), `PlayerDataRepository`, `BackupRepository`
  - `network/CrossServerCoordinator.kt` — lock + heartbeat por Redis para sync cross-server (opcional)
  - `commands/GuardCommand.kt` — comandos `/cg`
  - `discord/DiscordWebhook.kt` — notificaciones
- `sql/` — scripts de creación/migración/recuperación de tablas
- `INSTALACION.md` — guía de instalación y configuración

## Build

```bash
./gradlew build
# JAR final en build/libs/cobblemonguard-1.2.0.jar
```

Requiere: Fabric 1.21.1, Cobblemon 1.8.0+, Fabric API, fabric-language-kotlin, MySQL/MariaDB.
Redis es **opcional** — solo necesario si activas `crossServerSyncEnabled`.

## Nota sobre dependencias

Cobblemon y Trinkets se acceden **por reflexión** (no se compila contra ellos) porque
Loom no puede remap JARs con metadata Kotlin 2.2+. Impactor también se accede por
reflexión — no por el problema de Kotlin (Impactor es Java puro), sino para que la
integración de economía siga siendo completamente opcional (sin jar de referencia, sin
coordenada de maven, sin declarar una versión mínima) — la API real usada está
documentada y verificada en `sync/ImpactorEconomy.kt`, contrastada contra el proyecto
hermano `cobblemmo-server` (que sí compila contra Impactor directamente). Por eso `libs/`
(JARs de referencia) y las carpetas de mods descompilados no están en el repo — no son
parte del código fuente.

La configuración con credenciales (`config/cobblemonguard.json`) está excluida del repo.
