# CobblemonGUARD — Funcionamiento completo

Documento de referencia técnica · versión 1.2.0 (sin compilar) · 2026-09-12
Proyecto: `C:\Claude\CobblemonGUARD-master` · Fabric 1.21.1 · Kotlin 2.0.21 · `environment: server`

Describe cómo funciona el mod por dentro: qué guarda, dónde, cuándo, en qué hilo y con qué garantías. Refleja el estado del código después de la auditoría del 10/09 y sus cuatro tandas de correcciones.

---

## 1. Qué es y qué problema resuelve

CobblemonGUARD sincroniza los datos de un jugador contra **MySQL**, de modo que su inventario, su PC de Cobblemon, su equipo, sus mochilas, sus accesorios y su dinero sean los mismos en cualquier servidor Fabric de la red.

El problema que ataca: cada backend Fabric es un proceso independiente con su propio guardado en disco. Cuando el proxy mueve a un jugador de un servidor a otro, el origen ve un DISCONNECT y el destino un JOIN, y ninguno sabe nada del otro. Sin una fuente de verdad externa, cada backend queda con su propia copia — y de ahí salen los datos revertidos, los ítems duplicados y los Pokémon fantasma.

Es una alternativa propia a CobbledSync, con dos diferencias de fondo: usa MySQL en vez de MongoDB, y **no reemplaza el sistema de almacenamiento de Cobblemon** — convive con él, leyendo y escribiendo el estado vivo en los momentos justos (ver §9 para la única excepción).

---

## 2. Mapa del código

```
com.cobblemonguard
├── CobblemonGuard.kt          Punto de entrada. Orden de arranque, singletons, versión.
├── config/
│   └── GuardConfig.kt         data class + carga/guardado con Gson.
├── database/
│   ├── DatabaseManager.kt     Pool HikariCP, creación de tablas, migraciones.
│   ├── PlayerDataRepository.kt  Tabla principal: UPSERT, carga, identidad, log.
│   ├── BackupRepository.kt    Historial: inserción con dedup, consulta, limpieza.
│   └── BlobMapping.kt         Mapeo DataType ↔ columnas.
├── events/
│   ├── PlayerEventHandler.kt  El orquestador. Todo el ciclo de vida vive acá.
│   └── FreezeScreen.kt        Congelar al jugador y mostrarle el estado.
├── sync/
│   ├── DataType.kt            Los 7 tipos de dato + parseo para comandos.
│   ├── DataSyncer.kt          La interfaz que implementa cada tipo.
│   ├── SyncRegistry.kt        Registro y ORDEN de los syncers.
│   ├── SyncService.kt         Serializar/aplicar todos los tipos de una pasada.
│   ├── InventorySync.kt       Inventario + cofre del Ender.
│   ├── PokemonPCSync.kt       PC completa de Cobblemon.
│   ├── PokemonPartySync.kt    Equipo de 6.
│   ├── AccessorySync.kt       Slots de Accessories (mochila equipada).
│   ├── BackpackSync.kt        Contenido real de las mochilas de Sophisticated Backpacks.
│   ├── EconomySync.kt         Saldos de Impactor.
│   ├── CobblemonStorage.kt    Acceso de bajo nivel a Cobblemon por reflexión.
│   ├── ImpactorEconomy.kt     Acceso a Impactor por reflexión.
│   └── LivePastureSync.kt     Refresco de PC para pasturas (opcional).
├── network/
│   └── CrossServerCoordinator.kt  Locks por jugador en Redis.
├── discord/
│   └── DiscordWebhook.kt      Notificaciones, con cola y throttle.
├── commands/
│   └── GuardCommand.kt        /cobblemonguard y su alias /cg.
└── util/
    ├── ReflectCache.kt        Caché de Method/Constructor resueltos por reflexión.
    └── Permissions.kt         Nodos de LuckPerms con fallback a OP.
```

### Orden de arranque (`CobblemonGuard.onInitialize`)

1. Cargar `config/cobblemonguard.json` (se crea con valores por defecto si no existe).
2. Si `enabled: false`, se corta acá y el mod no hace nada más.
3. Conectar MySQL y crear/migrar tablas.
4. Instanciar los repositorios.
5. Conectar Redis (`CrossServerCoordinator.connect()`), si `crossServerSyncEnabled`.
6. `PlayerEventHandler.register()` — a partir de acá los eventos están vivos.
7. `LivePastureSync.register()` — solo si está activado.
8. `GuardCommand.register()`.
9. Aviso a Discord de "servidor listo".

Si MySQL falla en el paso 3, se registra el error, se avisa a Discord y **no se registran los eventos**: el servidor arranca y el mod queda inerte, sin tocar los datos de nadie. Es deliberado — más vale no sincronizar que sincronizar mal.

---

## 3. Modelo de datos

Tres tablas, todas InnoDB / utf8mb4, creadas por el mod al arrancar.

### `cobblemonguard_players` — el estado actual

Una fila por jugador. Es la fuente de verdad.

| Columna | Tipo | Notas |
|---|---|---|
| `uuid` | VARCHAR(36) | **PRIMARY KEY** |
| `player_name` | VARCHAR(16) | Índice `idx_name` |
| `last_save` | TIMESTAMP | `ON UPDATE CURRENT_TIMESTAMP`. Índice `idx_last_save` |
| `inventory` | LONGBLOB | NBT gzip |
| `ender_chest` | LONGBLOB | NBT gzip |
| `cobblemon_pc` | LONGBLOB | NBT gzip |
| `cobblemon_party` | LONGBLOB | NBT gzip |
| `trinkets` | LONGBLOB | Accesorios equipados. El nombre quedó de la época de Trinkets |
| `backpacks` | LONGBLOB | Mapa uuid→contenido de mochilas |
| `economy` | LONGBLOB | Texto plano, no NBT |

**La escritura es un único `INSERT ... ON DUPLICATE KEY UPDATE` con las siete columnas.** Esto no es un detalle menor: hace estructuralmente imposible que la PC se guarde y el equipo no. Es una fila, una transacción, todo o nada.

### `cobblemonguard_backups` — el historial

| Columna | Tipo | Notas |
|---|---|---|
| `id` | BIGINT AUTO_INCREMENT | PRIMARY KEY |
| `uuid` | VARCHAR(36) | Índice `idx_uuid_time (uuid, saved_at)` |
| `player_name` | VARCHAR(16) | Índice `idx_name_time (player_name, saved_at)` |
| `saved_at` | TIMESTAMP | |
| *(las mismas 7 columnas de blobs)* | LONGBLOB | |

Las filas son históricas y **nunca se reasocian** a un UUID nuevo, a diferencia de la tabla principal. Por eso existe el índice por nombre: si a un jugador se le reasoció el UUID (ver §4), sus backups viejos quedaron con el UUID anterior y hay que poder encontrarlos por nombre.

### `cobblemonguard_sync_log` — auditoría

`id`, `uuid`, `player_name`, `action` (SAVE/LOAD), `success`, `error_msg`, `synced_at`. Índices por uuid y por fecha.

Por defecto solo se registran los **fallos** (`syncLogOnlyErrors: true`) y se limpia sola cada `syncLogRetentionHours` (168 h = 7 días).

### Migraciones

Al arrancar, el mod consulta `information_schema` y agrega solo lo que falte: las columnas `trinkets`, `cobblemon_party`, `backpacks`, `economy` y los índices `idx_name_time` e `idx_last_save`. Se usa `information_schema` y no `ADD COLUMN IF NOT EXISTS` porque esa sintaxis es de MariaDB: en MySQL siempre falla, y como estaba dentro de un catch vacío, las migraciones nunca corrían sin que nadie se enterara.

---

## 4. Identidad del jugador

Por defecto el jugador se identifica por **UUID** (`identifyByName: false`), que es lo correcto en un servidor online.

Con `identifyByNameFallback` (activo por defecto), si no hay ninguna fila para ese UUID, antes de asumir "jugador nuevo" se busca la fila más reciente con ese **nombre**. Si aparece, se la **reclama**: se le reescribe el `uuid` a la fila y de ahí en adelante matchea directo. Sirve para migraciones offline↔online y para cualquier caso donde el UUID cambió.

El riesgo está documentado: si alguien más tomó ese nombre, podría reclamar la fila equivocada. Es el mismo riesgo que ya asume `identifyByName: true`.

`identifyByName: true` cambia el modo por completo: la identidad pasa a ser el nombre, tanto para leer como para escribir (con un UPDATE por nombre y un INSERT si no existe). Solo para servidores offline.

---

## 5. Los siete tipos de dato

`DataType` define: `INVENTORY`, `ENDERCHEST`, `PC`, `PARTY`, `ACCESSORIES`, `BACKPACKS`, `ECONOMY`.

Cada uno implementa `DataSyncer` (serializar / deserializar / ¿está habilitado?) y se registra en `SyncRegistry`. Agregar un tipo nuevo es una entrada en ese registro.

| Tipo | Qué guarda | Cómo |
|---|---|---|
| INVENTORY | Inventario principal, armadura, offhand | `PlayerInventory.writeNbt` → NBT gzip |
| ENDERCHEST | Cofre del Ender | `toNbtList(registries)` → NBT gzip |
| PC | Todas las cajas de la PC | `PCStore.saveToNBT` vía reflexión → NBT gzip |
| PARTY | Equipo de 6 | `PartyStore.saveToNBT` vía reflexión → NBT gzip |
| ACCESSORIES | Slots de Accessories (normales y cosméticos) | `toNbtList` por contenedor → NBT gzip |
| BACKPACKS | Contenido real de las mochilas SB | Mapa uuid→NBT, con escaneo de anidadas → NBT gzip |
| ECONOMY | Saldos de Impactor | Texto plano: `namespace:value=monto`, una línea por moneda |

### El orden importa al restaurar

`SyncRegistry` define el orden, y **BACKPACKS va antes que ACCESSORIES**. El contenido de una mochila vive en el almacenamiento del mundo indexado por un UUID que lleva el ítem; hay que restaurar primero el contenido y después el ítem que lo referencia.

### Dos protecciones sobre PC y equipo

Estas dos son las que evitan la clase de bug más cara del mod:

**Protección anti-cero.** Antes de aplicar un snapshot de PC o equipo se comprueba `blobHasPokemon()`: una heurística barata que descomprime el blob y busca la etiqueta `Species`. Si el snapshot guardado está vacío, **no se toca nada**. Así un snapshot vacío no puede borrarle la PC a nadie.

**Merge por UUID de Pokémon, no reemplazo.** La restauración no vacía la PC para volver a llenarla. Se carga el snapshot en un store temporal (`newDetachedPC`) y se copian al store vivo **solo los slots que difieren**, comparando el UUID de cada Pokémon (`copyPCToLive` / `copyPartyToLive`). Si el slot ya tiene el mismo Pokémon, no se toca. Esto evita crear objetos Pokémon duplicados, que es lo que produce los fantasmas.

Se usa un store temporal y no `loadFromNBT` directo sobre el vivo porque `loadFromNBT` puebla los datos pero **no notifica al cliente**; los `set()` del merge sí disparan los observers y el jugador ve su PC actualizada.

### `preserveLivePokemon`: la diferencia entre single-server y red

Al entrar, PC y equipo se aplican de forma distinta según la topología:

- **Single-server** (`crossServerSyncEnabled: false`) → `preserveLivePokemon = true`. Si el jugador ya tiene Pokémon cargados del disco local, no se tocan. MySQL es un respaldo, no la fuente de verdad.
- **Red multi-backend** (`crossServerSyncEnabled: true`) → `preserveLivePokemon = false`. **Siempre se aplica MySQL.** Acá "ya tiene Pokémon" no significa "está al día": cada backend tiene su propio disco, y tener Pokémon solo significa que el jugador (o una pastura) tocó ese backend antes.

Esta distinción fue un bug real: con la lógica vieja, MySQL solo se aplicaba la *primera* vez que un jugador pisaba cada backend, y desde la segunda se ignoraba por completo.

---

## 6. Ciclo de vida

### 6.1 Al entrar

```
JOIN (evento de red)
 └─ FreezeScreen.freeze()        Slowness 128 (999999 ticks) + título "Verificando..."
 └─ pendingLoad.add(uuid)

END_SERVER_TICK (1 tick después, para que Cobblemon cargue su PC del disco primero)
 └─ loadPlayerDataAsync(player)  →  se va al joinExecutor

joinExecutor (fuera del hilo del servidor)
 ├─ esperar saveChains[uuid]           máx 15 s   (guardado anterior en vuelo)
 ├─ waitForDatabase()                  máx 30 s   (si falla → EXPULSA al jugador)
 ├─ CrossServerCoordinator.awaitRelease()  máx 8 s (lock del backend anterior)
 └─ repository.loadPlayerData(uuid, nombre)
        │
        ├── null  → PRIMER INGRESO
        │     hilo del servidor: título "Registrando" + serializar estado actual
        │     dbExecutor:        persist → log → Discord → dataLoaded → seedSchedules → claim
        │     hilo del servidor: unfreeze + "¡Bienvenido!"
        │
        └── datos → REINGRESO
              hilo del servidor: título "Cargando" + SyncService.apply(...)
                                 dataLoaded + seedSchedules + unfreeze + "¡Datos restaurados!"
              dbExecutor:        claim del lock + log del LOAD
```

Tres detalles que importan:

- El jugador queda **congelado** desde el primer instante, antes de poder moverse. El freeze es Slowness amplificador 127 sin partículas, más títulos de estado. **No abre ninguna pantalla ni ScreenHandler** (ver §14).
- Si la base de datos no responde en 30 s, el jugador es **expulsado** con un mensaje claro en vez de dejarlo jugar sin datos. Dejarlo entrar significaría que al salir se le guarda un estado incompleto.
- `dataLoaded` es el guard central: **solo los jugadores cuya carga terminó bien son elegibles para guardar**. Si la carga falló, el jugador juega (con lo que tenga en el disco local) pero no se escribe nada suyo en MySQL.

### 6.2 Al salir

```
DISCONNECT
 ├─ pendingLoad.remove / autoSaveTicks.remove / backupTicks.remove
 ├─ FreezeScreen.clear(player)        saca del set Y quita el Slowness
 ├─ backupRepository.forgetDedup()    el próximo backup de la sesión que viene se inserta sí o sí
 └─ en el hilo del servidor:
      savePlayerDataAsync(player)     serializa y encola
      dataLoaded.remove(uuid)
```

El guardado y la limpieza de `dataLoaded` van **en ese orden y en el hilo del servidor**. Si se quitara `dataLoaded` antes, el propio guard bloquearía el guardado y se perdería la sesión.

El `clear(player)` que quita el Slowness existe porque el efecto se persiste en el playerdata de Minecraft: un jugador que se desconecta congelado se guardaba inmóvil, y si alguien desactivaba el mod para investigar, nadie se lo sacaba nunca.

`savePlayerDataAsync` serializa en el hilo del servidor y encola la escritura. Cuando el `persist` confirma, y **solo entonces**, se libera el lock de Redis.

### 6.3 Auto-save y backups

Los dos funcionan igual: un contador por jugador que se revisa **una vez por segundo** (no en cada tick — el intervalo se configura en segundos, revisar 20 veces por segundo no agrega precisión y cuesta CPU proporcional a la cantidad de jugadores).

Los contadores arrancan en un punto **aleatorio** dentro de su intervalo (`seedSchedules`). Sin eso, todos los que entran juntos tras un reinicio quedan sincronizados y sus backups caen en el mismo tick: cuarenta serializaciones completas de golpe.

Los jugadores congelados se saltean.

Cada 5 minutos, además, corre una limpieza global: backups vencidos y entradas viejas del log, ambos **por lotes** (500 y 2000 filas por vuelta) para no bloquear InnoDB con un DELETE gigante.

### 6.4 Al apagar

```
SERVER_STOPPING
 ├─ filtrar jugadores con dataLoaded
 ├─ por cada uno: serializar (hilo del servidor) + encolar en su cadena
 ├─ esperar TODOS juntos, con un timeout único:
 │     15 s + 300 ms por jugador, con techo de 90 s
 ├─ si quedaron pendientes → error en el log con los NOMBRES + aviso a Discord,
 │     distinguiendo "timeout" de "falló al guardar"
 └─ cerrar MySQL y Redis
```

Los nombres se sacan de la lista de pendientes **solo cuando el guardado salió bien**. Antes eso estaba en un `finally` y el resumen decía "N/N confirmados" aunque hubiera habido fallos.

El lock de Redis se libera explícitamente: un apagado normal no debe depender del TTL para que el jugador pueda entrar a otro backend.

---

## 7. Concurrencia: quién corre dónde

Esta es la parte que más cuesta y la que más se paga si está mal.

### Dos pools de hilos

| Pool | Tamaño | Para qué |
|---|---|---|
| `dbExecutor` | `poolSize + 4`, entre 6 y 32 | Trabajo real: escrituras, lecturas, backups, heartbeat, limpieza |
| `joinExecutor` | `poolSize × 2`, entre 16 y 64 | Solo las **esperas largas** del join y del restore |

Están separados a propósito. El trabajo de join hace esperas de hasta 30 s (base de datos) y 8 s (lock). Si compartieran pool, un pico de reconexiones —reiniciar el proxy con treinta jugadores— dejaría todos los hilos dormidos y los guardados se encolarían detrás. Los hilos del `joinExecutor` están **dormidos**, no compiten por CPU, así que puede ser grande sin costo; el trabajo de MySQL que hagan después sigue acotado por el pool de conexiones de Hikari, que es el límite que importa.

### La cadena de guardados por UUID

`saveChains` guarda, por jugador, el último eslabón de una cadena de `CompletableFuture`. Cada guardado nuevo se encola **después** del anterior, nunca en paralelo.

Cubre dos carreras:

- **save → load**: el rejoin espera al guardado de la desconexión anterior antes de leer. Sin esto, una reconexión rápida podía leer MySQL antes de que el UPDATE terminara y restaurar el estado *anterior* a la última sesión. Eso es lo que se percibe como "se perdieron ítems" o "aparecieron ítems viejos".
- **save → save**: dos guardados del mismo jugador (auto-save + disconnect, o `/cg save all` seguido de una desconexión) corrían en hilos distintos y nada garantizaba el orden de llegada a MySQL. El más viejo podía ganar.

`forceRestore` usa el mismo guard, desde el `joinExecutor` para no esperar dentro del pool que tiene que ejecutar aquello que espera.

### Reparto entre ticks

`enqueueStaggered` reparte trabajo pesado del hilo del servidor a razón de **2 tareas por tick**. Lo usan `/cg save all` y `/cg backup all`: recorrer la playerlist entera serializando en un solo tick es un tirón garantizado en un servidor lleno; a dos por tick, cuarenta jugadores se reparten en un segundo.

### La regla general

| Corre en el hilo del servidor | Corre fuera |
|---|---|
| Leer y escribir estado del jugador (serializar/aplicar) | Todo MySQL |
| Acceso a `BackpackStorage` de Sophisticated Backpacks | Todo Redis |
| Mostrar títulos, congelar, expulsar | Notificaciones a Discord |
| Los contadores de auto-save y backup | Las esperas de join |

Las consultas de los comandos (`/cg info`, `/cg backups`, `/cg rollback`, `/cg unlock`) también corren fuera y solo vuelven al hilo del servidor para responderle al staff. Brigadier ejecuta en el hilo principal, y `getClosestBackup` trae blobs de megabytes.

---

## 8. Cross-server

Activado con `crossServerSyncEnabled: true`. Redis **no** guarda datos: solo coordina *quién tiene al jugador en memoria ahora mismo*. La persistencia sigue siendo MySQL.

**Lock por jugador:** clave `cobblemonguard:lock:<uuid>`, valor `serverId`, TTL `crossServerLockTtlSeconds` (45 s).

| Momento | Qué pasa |
|---|---|
| JOIN | Si el lock existe y es de **otro** backend, se espera (con el jugador ya congelado) hasta `crossServerWaitTimeoutMs` (8 s). Después se entra igual, para no dejarlo trancado. |
| Carga terminada | `claim()` — el lock pasa a ser nuestro. |
| Cada 15 s | Heartbeat: renueva el TTL de todos los jugadores reclamados, **en un solo pipeline** con un script Lua que compara el dueño y renueva de forma atómica. Antes eran dos round-trips secuenciales por jugador. |
| DISCONNECT | `release()`, pero **solo después** de que MySQL confirmó el guardado. Si el guardado falla, el lock no se suelta: es preferible que el jugador espere el timeout en el otro backend a que cargue datos viejos. |
| Lock colgado | `/cg unlock <nombre>` lo libera a la fuerza. |

La espera del lock hace poll con backoff suave (100 ms → 500 ms) y **suelta la conexión de Jedis entre vuelta y vuelta**. Retenerla mientras el hilo duerme agotaba el pool de 8 conexiones con nueve joins simultáneos.

**Si Redis no está disponible, el mod se degrada a comportamiento single-server** y no bloquea a nadie. Nunca se prefiere "trancar al jugador" sobre "dejarlo jugar sin coordinar".

---

## 9. Pasturas (`livePastureSyncEnabled`, apagado por defecto)

Es el único lugar donde el mod se mete en el sistema de almacenamiento de Cobblemon, y está deliberadamente acotado.

**El problema:** `PokemonPastureBlockEntity` accede a la PC del dueño **directo por UUID** (`Cobblemon.storage.getPC(uuid, registryAccess)`), sin pasar por join ni disconnect. Si el dueño está jugando en otro backend, la pastura de este puede quedar operando sobre una copia vieja.

**La solución:** se registra una `PokemonStoreFactory` propia, implementada con `java.lang.reflect.Proxy` (no se compila contra la interfaz), con la prioridad más alta que se pueda determinar. Esa factory:

- Para cualquier jugador **online en este backend**: no hace nada, devuelve `null`. El join/quit normal ya lo cubre.
- Para un jugador **offline acá**: dispara un refresco **asíncrono** que trae el snapshot de MySQL y lo mezcla —con el mismo `copyPCToLive` que usa el resto del mod— en el store que la factory por defecto ya mantiene. Con cooldown de 20 s por UUID y guard de reentrada.
- **Siempre devuelve `null`.** Nunca sirve datos directamente. Es un *gatillo de refresco*, no un backend de almacenamiento.

El peor caso de un fallo es que la clase quede inerte y la pastura siga funcionando exactamente como hoy. No puede devolver datos incorrectos ni causar un dupeo: no escribe nada, no reemplaza nada, solo empuja MySQL hacia el store en vivo de este backend.

**Por qué no se reemplazó el backend entero** (que es lo que hace CobbledSync): implementar toda la interfaz como fuente de verdad significa estar en el camino de cada acceso a PC y equipo de todos los jugadores, todo el tiempo, sin red de seguridad de compilador (reflexión pura) y con el riesgo de resolver MySQL de forma síncrona en un hot path de gameplay normal. El costo y el riesgo no se justifican para cerrar un hueco acotado a pasturas.

Si la prioridad detectada resultara ser la equivocada, el único efecto es que la factory nunca se invoca. El log de arranque lo dice: `[Pasturas] Factory de sync en vivo registrada... con prioridad 'X'`.

---

## 10. Economía (Impactor)

Acceso por reflexión, verificado contra el jar real. Si Impactor no está, esta parte no hace nada.

- **Descubrimiento:** se enumeran las monedas registradas en Impactor y se suman las de `economyCurrencyKeys`. Esa enumeración usa `registered()`/`all()`, cuya forma exacta **no está confirmada**, por eso las keys explícitas son la vía que siempre funciona.
- **Keys reales de este servidor** (verificadas contra su `economy.conf`): `impactor:dollars` (primaria), `impactor:arceuscoin`, `impactor:eventpoints`.
- **Formato guardado:** texto plano UTF-8, una línea por moneda, `namespace:value=monto`.
- **Al restaurar** el saldo se **fija** al valor guardado: se calcula la diferencia y se hace un `deposit` o un `withdraw`, porque Impactor no expone un "set" directo. No se suma ni se resta a lo que el jugador tenga.
- La economía **siempre** se aplica al entrar, sin la noción de "solo si está vacío": un saldo de 0 es un valor legítimo, no un "todavía no cargado".

**Nota de rendimiento:** `account()` de Impactor devuelve un future y se lo espera con un tope de 800 ms. Ese bloqueo corre en el hilo del servidor, porque la serialización entera corre ahí. El tope es una red de seguridad contra un Impactor colgado, no un tiempo esperado. Ver §15.

---

## 11. Backups y rollback

- Se crea un backup por jugador cada `backupIntervalSeconds` (300 s), repartidos con jitter.
- **Deduplicación:** si el estado es idéntico al del último backup insertado para ese jugador, **no se inserta**. La huella es CRC32 + longitud por columna (no un hash criptográfico: acá solo se compara contra el estado inmediatamente anterior del mismo jugador, no se buscan colisiones en un universo grande). Igual se fuerza una inserción **cada hora** aunque no haya cambios, para que nadie se quede sin backups cuando la retención borre el último.
- Retención: `backupRetentionHours` (192 h = 8 días), limpiada por lotes.
- `/cg backups <jugador>` lista hasta 48, combinando los del UUID actual con los del nombre (por si hubo reasociación).
- `/cg rollback <jugador> <tiempo> [tipo]` busca el backup **más cercano anterior** al momento pedido y aplica solo los tipos indicados. Formatos de tiempo: `30m`, `1h`, `2h`, `3d` y variantes en español.

Sin la deduplicación, cada jugador acumulaba unas 2.300 filas con el estado completo cada 8 días. La enorme mayoría eran copias exactas de la anterior.

---

## 12. Comandos y permisos

Alias: `/cg` = `/cobblemonguard`.

| Comando | Nodo |
|---|---|
| `/cg status` | `cobblemonguard.status` |
| `/cg save <jugador>` \| `save all` | `cobblemonguard.save` |
| `/cg restore <jugador> [tipo]` | `cobblemonguard.restore` |
| `/cg info <jugador>` | `cobblemonguard.info` |
| `/cg backup <jugador>` \| `backup all` | `cobblemonguard.backup` |
| `/cg backups <jugador>` | `cobblemonguard.backups` |
| `/cg rollback <jugador> <tiempo> [tipo]` | `cobblemonguard.rollback` |
| `/cg saving <start\|stop>` | `cobblemonguard.saving` |
| `/cg reload` | `cobblemonguard.reload` |
| `/cg unlock <nombre>` | `cobblemonguard.unlock` |

Tipos para `restore` y `rollback`: `all`, `inventario`, `ender`, `pc`, `party`, `accesorios`, `mochilas`, `economia` (cada uno con alias).

**Resolución de permisos** (`util/Permissions.kt`): si LuckPerms está instalado (detectado por reflexión), el nodo manda — `true` permite, `false` deniega incluso a un OP. Si el nodo está **sin definir**, si no hay LuckPerms, o si la consulta falla, se cae al chequeo de siempre: **nivel de permiso 2**. Nunca se concede más de lo que ya concedía el OP salvo que un admin lo haya otorgado a propósito. La consola y los bloques de comandos se resuelven siempre por nivel.

`/cg saving stop` pausa **solo las escrituras**; la carga sigue funcionando. Sirve para migraciones y pruebas.

---

## 13. Configuración completa

Archivo: `config/cobblemonguard.json`, creado con valores por defecto en el primer arranque.

| Clave | Default | Qué hace |
|---|---|---|
| `enabled` | `true` | Apaga el mod entero sin desinstalarlo |
| `host` / `port` / `database` / `username` / `password` | localhost:3306 | Conexión MySQL |
| `poolSize` | `10` | Conexiones simultáneas. Regla: al menos tantas como el pico de jugadores |
| `connectionTimeoutMs` | `30000` | Espera por una conexión del pool |
| `identifyByName` | `false` | Identidad por nombre (solo servidores offline) |
| `identifyByNameFallback` | `true` | Respaldo por nombre cuando no hay fila por UUID |
| `syncInventory` | `true` | |
| `syncEnderChest` | `true` | |
| `syncCobblemonPC` | `true` | |
| `syncCobblemonParty` | `true` | |
| `syncAccessories` | `true` | |
| `syncBackpackContents` | `true` | |
| `syncEconomy` | `true` | |
| `economyCurrencyKeys` | las tres de Impactor | Monedas explícitas, además de las autodetectadas |
| `savingEnabled` | `true` | Arrancar con las escrituras pausadas |
| `syncOnJoinLeave` | `true` | `false` = solo backups y comandos manuales |
| `autoSaveIntervalSeconds` | `0` | `0` = solo al salir |
| `backupEnabled` | `true` | |
| `backupIntervalSeconds` | `300` | |
| `backupRetentionHours` | `192` | |
| `backupDedupEnabled` | `true` | No insertar backups idénticos al anterior |
| `syncLogOnlyErrors` | `true` | Registrar solo los fallos |
| `syncLogRetentionHours` | `168` | `0` = no borrar nunca |
| `discordEnabled` | `false` | |
| `discordWebhookUrl` | `""` | |
| `discordNotifyFirstJoin` / `discordNotifySyncErrors` / `discordNotifyRollbacks` | `true` | |
| `crossServerSyncEnabled` | `false` | Coordinación por Redis |
| `serverId` | `"server-1"` | **Debe ser distinto en cada backend** |
| `redisHost` / `redisPort` / `redisPassword` | localhost:6379 | Redis **local**, la latencia importa |
| `crossServerWaitTimeoutMs` | `8000` | Espera máxima por el lock del otro backend |
| `crossServerLockTtlSeconds` | `45` | Red de seguridad si un backend se cae sin liberar |
| `livePastureSyncEnabled` | `false` | Refresco de PC para pasturas (requiere cross-server) |

### Un detalle de Gson que hay que conocer

Cuatro claves están declaradas como **nullable** en el código (`identifyByNameFallback`, `backupDedupEnabled`, `syncLogOnlyErrors`, `syncLogRetentionHours`) y se leen a través de getters `...Effective`.

El motivo: cuando Gson lee un `config.json` viejo al que le falta una clave, **no** usa el valor por defecto de Kotlin — usa el de Java, que para un `Boolean` es `false`. Si el default deseado es `true`, declararla como `Boolean = true` haría que todo el que actualice desde una versión anterior reciba `false` en silencio, invirtiendo el comportamiento. Declararla nullable y resolver el default en un getter evita la trampa.

**Corolario importante:** cambiar un default en el código **no** cambia lo que ya está escrito en el `config.json` de un servidor existente. Gson lee el archivo. Para adoptar un default nuevo hay que editar el archivo a mano.

---

## 14. Integraciones externas

Todas por reflexión, ninguna compilada. El motivo original es técnico: Loom no puede remapear jars con metadata de Kotlin 2.2+, así que no se puede compilar contra Cobblemon. La consecuencia buena es que el mod tolera versiones distintas y la ausencia de cualquier dependencia opcional.

| Mod | Para qué | Si no está |
|---|---|---|
| **Cobblemon** | PC y equipo | Dependencia dura: el mod no arranca sin él |
| **Accessories** | Slots de accesorios / mochila equipada | Esa parte se desactiva sola, con aviso en el log |
| **Sophisticated Backpacks** | Contenido de las mochilas | Ídem. Si `BackpackStorage.get()` falla, se **auto-desactiva** para no llenar el log |
| **Impactor** | Saldos | Ídem |
| **LuckPerms** | Nodos de permiso | Se cae a OP nivel 2 |

`ReflectCache` cachea los `Method` y `Constructor` resueltos. Sin eso, cada llamada reescaneaba el array completo de métodos públicos de la clase — y varias de esas llamadas corren por slot de PC, por Pokémon, por moneda y por mochila, en el hilo del servidor.

Las librerías propias (MySQL, HikariCP, Jedis) van **shadeadas y relocalizadas** a `com.cobblemonguard.libs.*` para no chocar con otros mods. Por eso otro mod **no puede reutilizar el pool de GUARD**: necesita su propio driver relocalizado a su propio paquete.

> **Aclaración para quien integre otro mod con GUARD:** el freeze de GUARD **no abre ningún `ScreenHandler`**. Es un efecto de Slowness 128 más paquetes de título. Cualquier diseño que intente detectar "GUARD está cargando" comparando `player.currentScreenHandler != player.playerScreenHandler` **no va a funcionar**. Si hace falta una señal de "ya terminé de cargar", lo correcto es pedirla y exponerla explícitamente, no inferirla.

---

## 15. Límites conocidos

**Lo que el mod NO sincroniza:** efectos de poción, estadísticas, avances, posición, salud, hambre, experiencia. No están en `DataType`. Cada backend tiene los suyos en su propio playerdata.

**El gzip corre en el hilo del servidor.** Toda la serialización, incluida la compresión de la PC (el blob más grande del mod), pasa por el hilo principal. Está analizado en detalle en `PUNTO-3.2-SERIALIZACION.md`, junto con la solución propuesta (partir `DataSyncer.serialize` en una fase de snapshot y otra de codificación). No está implementado, y la recomendación es medirlo antes de decidir.

**Pasturas en tiempo real solo con la opción activada**, y esa opción todavía no fue probada en producción.

**La ventana entre serializar y escribir.** Entre que se toma el snapshot y que llega a MySQL pasan milisegundos en los que el jugador puede cambiar algo. Es inherente al diseño y no se agranda de forma relevante.

**La reasociación por nombre puede equivocarse** si un nombre fue reutilizado por otra persona.

**Sin compilar.** La versión 1.2.0 acumula la auditoría completa y sus cuatro tandas de correcciones, pero todavía no pasó por `gradlew remapJar`.

---

## 16. Diagnóstico

### Qué mirar en el log de arranque

```
CobblemonGUARD listo. Sincronizando con MySQL en <host>/<base>
[Migración] Columna 'x' agregada a ...        solo si faltaba
[Migración] Índice 'idx_name_time' creado ... puede tardar si la tabla ya es grande
[Permisos] LuckPerms detectado — se usan nodos 'cobblemonguard.*'
[CrossServer] Conectado a Redis en ... — serverId='...'
[Pasturas] Factory de sync en vivo registrada ... con prioridad 'X'
```

### Prefijos de log por área

`[JOIN]` `[SAVE]` `[RESTORE]` `[ROLLBACK]` `[Backup]` `[SyncLog]` `[STOP]` `[CrossServer]` `[Pasturas]` `[Permisos]` `[Migración]` `[Economy]` `[Mochilas]` `[Accesorios]` `[PC]` `[Party]` `[Inventario]` `[Identidad]` `[Discord]`

### Consultas útiles

```sql
-- Errores recientes
SELECT * FROM cobblemonguard_sync_log WHERE success = FALSE ORDER BY synced_at DESC LIMIT 20;

-- Tamaño de las tablas (vigilar el crecimiento de los backups)
SELECT table_name,
       ROUND(((data_length + index_length) / 1024 / 1024), 1) AS mb,
       table_rows
FROM information_schema.TABLES
WHERE table_schema = DATABASE() AND table_name LIKE 'cobblemonguard%'
ORDER BY (data_length + index_length) DESC;
```

### Señales de que algo va mal

| Síntoma | Dónde mirar |
|---|---|
| `[STOP] ¡ATENCIÓN! N jugador(es)...` al apagar | `poolSize` bajo o MySQL lento |
| `[JOIN] <jugador>: el guardado anterior no confirmó en 15s` | Mismo problema, visto desde el join |
| Jugadores "cargando" varios segundos al cambiar de backend | Normal hasta `crossServerWaitTimeoutMs`. Si es siempre, revisar que `serverId` sea distinto en cada backend |
| `[Mochilas] BackpackStorage no accesible` | El volcado de mochilas se auto-desactivó; el ítem igual se guarda |
| Nada de `[Pasturas]` en el arranque | La opción está apagada, o Cobblemon cambió su API interna |

---

## 17. Documentos relacionados

- `AUDITORIA.md` — auditoría completa del 10/09: bugs, escalabilidad e inconsistencias, con el estado de cada punto.
- `PUNTO-3.2-SERIALIZACION.md` — por qué el gzip en el hilo del servidor es el costo real y cómo resolverlo.
- `INSTALACION.md` — guía de instalación, configuración y troubleshooting.
- `GUIA_STAFF.md` — uso de los comandos para el equipo.
- `sql/setup.sql` — esquema completo y consultas de administración.
