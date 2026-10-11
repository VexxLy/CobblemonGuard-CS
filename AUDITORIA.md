# Auditoría completa — CobblemonGUARD v1.2.0 (sin compilar)

Fecha: 2026-09-10 · Alcance: los 23 archivos Kotlin del mod, `build.gradle.kts`, `gradle.properties`, `fabric.mod.json`, `sql/setup.sql` y el esquema real que crea `DatabaseManager`.

Cada hallazgo indica archivo y línea aproximada, qué pasa, cuándo se dispara y el arreglo propuesto.

> **Estado (2026-09-10): TODO corregido.** Las cuatro tandas del punto 7 están aplicadas en el código, salvo una excepción documentada: el punto **3.2** (cachear el escaneo recursivo de NBT de mochilas) se descartó a propósito — cualquier caché habría que indexarla por el contenido del NBT, y calcular esa clave cuesta el mismo recorrido que el escaneo que se quería evitar. El costo real medido es menor y solo aplica a jugadores que llevan mochilas encima, así que se dejó como está. Las líneas citadas abajo corresponden al código **anterior** a los arreglos.

---

## 1. Bugs de severidad alta

### 1.1 Redis en el hilo del servidor durante el reingreso
`PlayerEventHandler.kt:284` — `CrossServerCoordinator.claim(uuid)` se ejecuta dentro del `player.server.execute { ... }` del path de REINGRESO, o sea en el hilo principal. `claim()` abre una conexión Jedis y hace un `SET` por red.

El propio encabezado de `CrossServerCoordinator.kt:43-44` dice: *"Todas las llamadas de red (Jedis) deben hacerse FUERA del hilo del servidor — PlayerEventHandler ya las despacha en su executor de BD."* En el path de PRIMER INGRESO (`PlayerEventHandler.kt:248`) sí se respeta: ahí `claim()` corre dentro del `dbExecutor`. Solo el reingreso — es decir, el 99% de los joins — lo viola.

Con Redis local son sub-milisegundos, así que hoy no se nota; con Redis en otra máquina, bajo carga, o durante un `BGSAVE` de Redis, cada join congela el servidor completo lo que tarde ese round-trip.

**Arreglo:** mover ese `claim(uuid)` fuera del `execute`, al hilo de BD, igual que en el path de primer ingreso.

### 1.2 `awaitRelease` retiene una conexión Jedis durante toda la espera
`CrossServerCoordinator.kt:109-126` — el bucle de espera está *dentro* de `jedisPool.resource.use { ... }`, así que la conexión queda tomada mientras el hilo duerme, hasta `crossServerWaitTimeoutMs` (8 s por defecto). El pool está configurado con `maxTotal = 8` (`CrossServerCoordinator.kt:63`).

Consecuencia: a partir del noveno jugador que entra simultáneamente y tiene que esperar, no queda conexión libre — los siguientes se bloquean esperando recurso del pool o revientan con `JedisExhaustedPoolException`. Es exactamente el escenario de reiniciar el proxy o un backend y que 20-30 jugadores reconecten a la vez.

**Arreglo:** tomar y soltar la conexión en cada iteración del poll en vez de retenerla durante los `Thread.sleep`. Subir `maxTotal` es un parche; el problema real es retener durante el sleep.

### 1.3 Las esperas bloqueantes saturan el pool de hilos de BD (regresión de esta sesión)
`PlayerEventHandler.kt:44-54` — el `dbExecutor` pasó a ser de tamaño **fijo** (`poolSize + 4`, o sea 14 por defecto). Pero dentro de ese mismo pool corren dos esperas largas por cada join: `waitForDatabase()` hasta **30 s** (`:174-183`) y `awaitRelease()` hasta **8 s** (`:227`).

Con la BD sana no pasa nada. Con la BD lenta o caída, o con un pico de reconexiones en cross-server, 14 joins bastan para dejar el pool entero durmiendo — y todo lo demás (guardados de disconnect, backups, heartbeat de locks, refrescos de pasturas) queda encolado detrás de esas esperas. Antes del cambio de hoy esto no ocurría porque el `cachedThreadPool` creaba hilos nuevos; acotarlo arregló el problema de TPS pero introdujo este.

**Arreglo:** dos pools. Uno chico y fijo para trabajo real de BD (el actual, acotado por `poolSize`) y otro para esperas bloqueantes de join — esos hilos están dormidos, no compiten por CPU, así que pueden ser más y elásticos. Alternativa más limpia: reemplazar el `waitForDatabase` bloqueante por un reintento programado.

### 1.4 Carrera entre dos guardados del mismo jugador
`PlayerEventHandler.kt:355-382` — `pendingSaves` ordena correctamente *save → load* (el rejoin espera el save anterior), pero **no ordena save → save**. Dos guardados del mismo UUID encolados con poca diferencia (auto-save + disconnect, o `/cg save all` + disconnect) se ejecutan en hilos distintos del pool, y nada garantiza en qué orden llega cada `INSERT ... ON DUPLICATE KEY UPDATE` a MySQL. El más viejo puede ganar y pisar al más nuevo.

Además, cuando se solapan, `pendingSaves[uuid] = future` (`:356`) reemplaza el future anterior sin esperarlo, así que el guard de rejoin solo espera al último.

Hoy el riesgo es bajo porque `autoSaveIntervalSeconds` viene en 0 (desactivado), pero se dispara con auto-save activo o con `save all` seguido de una desconexión. Es la clase exacta de bug que se percibe como "se revirtieron ítems".

**Arreglo:** encadenar los guardados por UUID (cada save nuevo se encola *después* del future anterior en vez de reemplazarlo), o un lock por UUID.

### 1.5 Los comandos de staff hacen MySQL síncrono en el hilo del servidor
`GuardCommand.kt:112` (`/cg info`), `:147` (`/cg backups`), `:269` (`/cg rollback`) — todos llaman al repositorio directamente desde el ejecutor de Brigadier, que corre en el hilo principal.

`getClosestBackup` trae todos los LONGBLOB de la fila (fácilmente megabytes) y, si entra por el fallback por nombre, hace un escaneo completo de la tabla de backups (ver 2.1). Un `/cg rollback` sobre una tabla grande puede congelar el servidor varios segundos.

**Arreglo:** mover las tres consultas al `dbExecutor` y devolver el resultado al jugador con `server.execute` en el callback, como ya hacen `forceSave`/`forceRestore`.

---

## 2. Base de datos y escalabilidad

### 2.1 Falta índice sobre `player_name` en la tabla de backups
`DatabaseManager.kt:73-87` — `cobblemonguard_backups` solo tiene `INDEX idx_uuid_time (uuid, saved_at)`. Pero `BackupRepository.kt:56` y `:101` consultan por `player_name = ? AND uuid != ?` (el fallback por nombre agregado para no perder historial tras una reasociación de UUID). Sin índice, eso es un **full table scan** sobre la tabla más pesada del esquema.

**Arreglo:** `ALTER TABLE cobblemonguard_backups ADD INDEX idx_name_time (player_name, saved_at);`

### 2.2 Volumen de la tabla de backups
Con los valores por defecto (`backupIntervalSeconds = 300`, `backupRetentionHours = 192`) cada jugador acumula ~2.300 filas en 8 días, y cada fila guarda el estado **completo**: inventario, ender, PC entera, party, accesorios y contenido de mochilas. Con un blob conservador de 200 KB son ~460 MB por jugador; una PC de Cobblemon llena empuja eso bastante más arriba. Con 50 jugadores activos hablamos de decenas de GB, y crece con las horas jugadas, no con la cantidad de cuentas.

Lo caro es que el 95% de esas filas son idénticas a la anterior: la PC de un jugador casi nunca cambia entre dos backups separados por 5 minutos.

**Arreglos posibles, de menor a mayor esfuerzo:**
1. Deduplicar por hash: guardar el hash del blob del último backup por jugador en memoria y no insertar si no cambió nada.
2. Política escalonada: cada 5 min la última hora, cada hora el último día, uno diario la última semana.
3. Deduplicar por columna (la PC es la que más pesa y menos cambia), guardando `NULL` cuando la columna no cambió y resolviendo hacia atrás en el restore.

### 2.3 `cobblemonguard_sync_log` crece sin límite
`DatabaseManager.kt:101-114` crea la tabla; `PlayerDataRepository.logSync` (`:236`) escribe en ella en **cada** guardado (`savePlayerData` la llama internamente, `:56`) y `PlayerEventHandler.kt:289` escribe otra fila más por cada LOAD. Hay limpieza automática para backups pero **ninguna** para esta tabla — el `setup.sql:70` deja el `DELETE` solo como comentario para ejecutar a mano.

Además cada `logSync` abre su **propia conexión** del pool: cada guardado consume dos conexiones y dos round-trips en vez de uno.

**Arreglo:** loguear solo los fallos (o hacerlo configurable), y agregar la limpieza de esta tabla al mismo ciclo periódico que ya limpia backups.

### 2.4 Las migraciones de columnas no funcionan en MySQL
`DatabaseManager.kt:92-98` usa `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`, envuelto en un `catch` silencioso. `IF NOT EXISTS` en `ADD COLUMN` es una extensión de **MariaDB**; MySQL (Oracle) no lo soporta en ninguna versión, incluida la 8. En MySQL esa sentencia lanza siempre error de sintaxis, se traga en el catch, y la migración **nunca ocurre**.

Hoy no se nota porque el `CREATE TABLE IF NOT EXISTS` de arriba ya trae todas las columnas en instalaciones nuevas. Pero es una bomba para la próxima columna que agregues: en MySQL nadie la va a recibir y va a fallar con "Unknown column".

**Arreglo:** consultar `information_schema.COLUMNS` y emitir el `ALTER` solo si falta la columna.

### 2.5 `cleanOldBackups` borra sin lotes
`BackupRepository.kt:143-159` — un único `DELETE` sin `LIMIT`. Si alguien baja la retención, o si el mod estuvo días sin limpiar, ese DELETE puede tocar cientos de miles de filas con BLOBs en una sola transacción: undo log enorme, locks largos en InnoDB y el resto de las consultas esperando.

**Arreglo:** borrar en lotes (`DELETE ... LIMIT 1000` en bucle hasta que no borre nada). A futuro, particionar la tabla por rango de fecha convierte la limpieza en un `DROP PARTITION` instantáneo.

### 2.6 Configuración de HikariCP
`DatabaseManager.kt:30-43`:
- `minimumIdle = 1` con `maximumPoolSize = poolSize`. Justo en el pico (reinicio, 30 jugadores entrando) Hikari tiene que abrir conexiones nuevas una por una, con handshake TCP y autenticación cada vez. La recomendación de HikariCP para carga en picos es pool de tamaño fijo: `minimumIdle == maximumPoolSize`. El propio `economy.conf` de Impactor de tu servidor dice literalmente eso en sus comentarios.
- `connectionTestQuery = "SELECT 1"` desactiva el mecanismo eficiente (`Connection.isValid()`); con un driver JDBC4 como el de MySQL 8 conviene no setearlo.
- Faltan `cachePrepStmts`/`useServerPrepStmts`/`prepStmtCacheSize` en la URL. Para un workload de UPSERTs repetidos idénticos es una de las optimizaciones con mejor relación esfuerzo/beneficio.
- `autoReconnect=true` es un parámetro legacy de Connector/J desaconsejado cuando hay pool; Hikari ya valida y renueva.

### 2.7 Heartbeat de Redis sin pipelining
`CrossServerCoordinator.kt:148-166` — `heartbeatAll()` hace un `GET` y un `EXPIRE` **secuenciales por cada jugador** reclamado, cada 15 segundos, sobre una sola conexión. Con 100 jugadores son 200 round-trips en serie cada 15 s.

**Arreglo:** pipeline de Jedis, o directamente un script Lua que renueve solo si el dueño coincide (además elimina la carrera entre el GET y el EXPIRE).

---

## 3. Rendimiento y TPS

### 3.1 Picos de serialización sincronizados
`PlayerEventHandler.kt:483-494` y `:510-521` — los contadores de auto-save y backup son por jugador, pero **arrancan todos en cero al entrar**. Tras un reinicio, cuando 40 jugadores entran en el mismo minuto, sus backups caen todos en el mismo segundo: 40 serializaciones completas (PC entera + escaneo recursivo del NBT de las mochilas + accesorios + economía) en un solo tick del hilo principal. Eso es un stutter garantizado cada 5 minutos.

Lo mismo con `/cg save all` (`GuardCommand.kt:212`) y `/cg backup all` (`:228`), que recorren toda la playerlist serializando en el mismo tick.

**Arreglo:** jitter inicial — arrancar el contador de cada jugador en un valor aleatorio dentro del intervalo, o derivarlo del hash de su UUID. Para los comandos `all`, repartir en varios ticks.

### 3.2 `BackpackSync.scanUuids` recorre todo el NBT en cada guardado
`BackpackSync.kt:171-180` — recorre recursivamente el NBT completo de cada mochila buscando mochilas anidadas, en el hilo del servidor, en **cada** serialización (join, quit, auto-save, backup). Para un jugador con varias mochilas grandes anidadas son miles de nodos por pasada.

**Arreglo:** cachear el resultado por UUID de mochila mientras el contenido no cambie, o al menos saltear el escaneo recursivo si el jugador no lleva mochilas.

### 3.3 Se serializa y escribe todo aunque nada haya cambiado
`SyncService.kt:14-17` serializa siempre los siete tipos, y `savePlayerData` escribe siempre las siete columnas. Un jugador parado en un lobby genera exactamente el mismo tráfico que uno que acaba de reorganizar su PC entera.

**Arreglo:** comparar el hash del blob con el del último guardado y saltear la escritura a MySQL si es idéntico. No ahorra la CPU de serializar, pero sí el I/O de red y disco de MySQL, que es lo que escala mal. Combinado con 2.2 resuelve buena parte del problema de volumen.

---

## 4. Inconsistencias

| # | Dónde | Qué |
|---|-------|-----|
| 4.1 | `gradle.properties:11` | Dice `mod_version=1.1.0`, pero el `README.md:51` documenta `cobblemonguard-1.2.0.jar` y toda la documentación habla de 1.2.0. El bump de versión no llegó a la PC — el build de hoy produciría un jar 1.1.0. |
| 4.2 | `fabric.mod.json:24` | Declara `fabric-language-kotlin: >=1.10.0+kotlin.1.9.0`, pero el mod se compila con Kotlin **2.0.21** (`build.gradle.kts:8`) y `gradle.properties:17` documenta FLK `1.12.3+kotlin.2.0.21`. Con un FLK viejo el mod arrancaría y fallaría raro en runtime en vez de dar un error claro de dependencia. |
| 4.3 | `fabric.mod.json:28-31` | `suggests` menciona Sophisticated Backpacks e Impactor, pero no **Accessories**, que el mod sí usa (`AccessorySync.kt`). |
| 4.4 | `sql/setup.sql` | No crea `cobblemonguard_backups` (el mod la crea al arrancar, así que funciona, pero el script queda incompleto), y sí crea `INDEX idx_last_save` que `DatabaseManager` **no** crea. El esquema termina siendo distinto según si instalaste con el script o dejaste que el mod creara las tablas. |
| 4.5 | `PlayerEventHandler.kt:424-434` | En el guardado de apagado, `pendingNames.remove(name)` está en el `finally`, así que se ejecuta también cuando `persist` lanzó excepción. Resultado: si un guardado **falla**, el resumen final igual dice "N/N confirmados" y no se dispara el webhook de error. Solo queda la línea de error suelta en el log. |
| 4.6 | `DiscordWebhook.kt:167` | Reporta `field("Versión", "1.0.0")` hardcodeado. |
| 4.7 | `PlayerEventHandler.kt:289` | El `logSync(..., "LOAD", true, null)` se escribe fuera del `server.execute`, o sea antes de que los datos se hayan aplicado realmente. Si el `apply` falla, el log dice que el LOAD salió bien. |
| 4.8 | Varios | Código muerto: `CobblemonGuard.reloadConfig()` (no existe `/cg reload` que lo llame), `CrossServerCoordinator.forceRelease()` y `DiscordWebhook.sendConnected()` no se invocan desde ningún lado. |

---

## 5. Riesgos menores

### 5.1 Slowness 128 puede quedar pegado
`FreezeScreen.kt:30-42` — `freeze()` aplica Slowness 128 por 999.999 ticks, y `clear()` (que es lo que corre en el disconnect) solo saca al jugador del set, sin quitar el efecto. El efecto persiste en el playerdata de Minecraft.

Normalmente se auto-corrige: al reconectar, `freeze()` + `unfreeze()` lo limpia. Pero si el jugador queda congelado (expulsión por BD caída, crash del servidor durante el freeze) y luego alguien desactiva el mod para investigar — que es justo lo que uno hace cuando el mod falla — esos jugadores quedan **inmóviles y nadie les quita el efecto**.

**Arreglo:** quitar el efecto en `clear()`, y limpiar cualquier Slowness residual al entrar antes de congelar.

### 5.2 `DiscordWebhook` sin throttle y con un hilo por notificación
`DiscordWebhook.kt:203-225` — cada notificación crea un `Thread` nuevo. Si MySQL se cae con 40 jugadores online, `sendSyncError` se dispara por cada guardado fallido: 40 hilos y 40 requests HTTP casi simultáneos contra un endpoint que limita a ~5/s. El resultado es 429 y notificaciones perdidas, justo en el incidente donde más las necesitás.

**Arreglo:** una cola con un solo hilo consumidor y agregación (un mensaje con los N jugadores afectados en vez de N mensajes).

### 5.3 Escapado de JSON incompleto
`DiscordWebhook.kt:227-231` — `escape()` cubre `\`, `"`, `\n` y `\r`, pero no los demás caracteres de control (`\t`, `\b`, ` `-``). Un mensaje de error de MySQL con un tabulador genera un JSON inválido y Discord rechaza el webhook.

### 5.4 `/cg backup <player>` confirma antes de tiempo
`GuardCommand.kt:133-134` — llama a `saveBackupAsync` (asíncrono) y responde "✔ Backup creado" en la línea siguiente, sin esperar el resultado. Si falla, el staff ya vio un tick verde.

### 5.5 Posición de slots en accesorios
`AccessorySync.kt:178-184` — se castea el contenedor de Accessories a `SimpleInventory` y se usan `toNbtList`/`readNbtList`. La implementación base de `SimpleInventory` no guarda el índice de slot y restaura con `addStack()`, que compacta hacia el primer hueco libre. `EnderChestInventory` sobreescribe ambos métodos para preservar el slot, pero no sé si `ExpandedSimpleContainer` de Accessories hace lo mismo — no pude verificarlo desde acá.

Si no lo sobreescribe, los accesorios se restauran compactados hacia los primeros slots de cada contenedor. Con un solo slot de mochila da igual; con varios anillos o slots cosméticos, cambian de posición. **Vale una prueba manual**: equipar accesorios dejando huecos, relogear, y ver si vuelven a la misma posición.

### 5.6 Sin nodos de LuckPerms
`GuardCommand.kt:29` — todo el comando está detrás de `hasPermissionLevel(2)`, es decir, OP. No hay nodos de permiso, así que no podés darle `/cg backups` y `/cg rollback` a un moderador sin darle OP completo (con todo lo que eso habilita en el resto del servidor). Teniendo LuckPerms como dependencia dura en `cobblemmo-server`, acá sería coherente hacer lo mismo: nodos por subcomando (`cobblemonguard.rollback`, `cobblemonguard.saving`, etc.) con fallback a nivel 2.

---

## 6. Lo que está bien resuelto

Para contexto, y para no tocar lo que no conviene tocar:

- El UPSERT de una sola fila con las siete columnas (`PlayerDataRepository.kt:29-55`) hace estructuralmente imposible la clase de desync "la PC se guardó pero la party no".
- El guard `dataLoaded` evita el peor bug posible de un mod así: sobrescribir datos buenos con un snapshot vacío cuando la carga falló.
- La protección anti-cero (`blobHasPokemon`) más el merge por UUID de Pokémon (`copyPCToLive`/`copyPartyToLive`, que solo tocan los slots que difieren) es un diseño correcto y conservador para no generar fantasmas.
- El orden de los syncers en `SyncRegistry` (mochilas antes que accesorios) está pensado y documentado.
- La degradación cuando falta una dependencia opcional es consistente en todo el mod: si Impactor, Accessories o Sophisticated Backpacks no están, esa parte se desactiva sola sin romper el resto.
- `LivePastureSync` está bien acotado: siempre devuelve `null`, nunca toca jugadores online y nunca hace I/O de MySQL en el hilo del servidor.

---

## 7. Orden sugerido de trabajo

**Tanda 1 — correcciones puntuales, bajo riesgo, alto retorno**
1.1 (Redis fuera del hilo del servidor) · 1.2 (conexión Jedis en el poll) · 4.1 (versión) · 4.5 (resumen de apagado) · 2.1 (índice faltante)

**Tanda 2 — concurrencia**
1.3 (separar pools) · 1.4 (encadenar saves por UUID) · 1.5 (comandos fuera del hilo principal)

**Tanda 3 — escalabilidad de datos**
2.2 (dedup de backups) · 2.3 (retención del sync_log) · 2.5 (borrado por lotes) · 2.6 (Hikari) · 3.3 (saltear escrituras idénticas)

**Tanda 4 — pulido**
3.1 (jitter) · 2.4 (migraciones) · 5.x (Discord, freeze, LuckPerms)
