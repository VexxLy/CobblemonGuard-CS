# CobblemonGUARD — Guía de Instalación

Sincroniza con MySQL: **inventario**, **cofre del Ender**, **PC de Cobblemon**,
**equipo de 6 (party)**, **accesorios equipados**, el **contenido de las mochilas**
de Sophisticated Backpacks y los **saldos de Impactor** (CobbleDollars, ArceusCoin, etc.).
Alternativa a CobbledSync usando MySQL en lugar de MongoDB, con Redis opcional para
coordinar redes multi-backend.

---

## Requisitos

- Java 21+
- Minecraft Fabric 1.21.1
- Cobblemon 1.7.3
- Fabric API
- fabric-language-kotlin
- MySQL 5.7+ o MariaDB 10.5+ (provisto por tu hosting)

**Opcionales** (si están instalados, se sincronizan; si no, se ignoran sin error):
- Accessories (`io.wispforest.accessories`) — para accesorios/mochilas equipadas
- Sophisticated Backpacks + Sophisticated Core — para el contenido de las mochilas
- Impactor (+ CobbleDollars/cobbledollars-bridge, ArceusCoin, etc.) — para saldos de economía

---

## Paso 1 — Preparar la base de datos MySQL

En el panel de tu hosting (phpMyAdmin, Plesk, cPanel, etc.):

1. Crea una base de datos llamada `cobblemonguard` (o el nombre que prefieras)
2. Ejecuta el script `sql/setup.sql` para crear las tablas
3. Anota: **host**, **puerto** (usualmente 3306), **usuario** y **contraseña**

---

## Paso 2 — Compilar el mod

### Opción A — IntelliJ IDEA (recomendado)
1. Abre IntelliJ IDEA
2. `File → Open` → selecciona la carpeta `CobblemonGUARD`
3. IntelliJ detectará el proyecto Gradle automáticamente
4. Espera a que descargue las dependencias
5. En el panel Gradle → `Tasks → fabric → remapJar`
6. El JAR compilado aparece en `build/libs/cobblemonguard-1.0.0.jar`

### Opción B — Línea de comandos
```bash
# Primero obtén el Gradle wrapper (solo la primera vez)
gradle wrapper

# Compilar
./gradlew remapJar         # Linux/Mac
gradlew.bat remapJar       # Windows
```

El JAR final estará en: `build/libs/cobblemonguard-1.0.0.jar`

---

## Paso 3 — Instalar en el servidor

1. Copia `build/libs/cobblemonguard-1.0.0.jar` a la carpeta `mods/` de tu servidor
2. Inicia el servidor **una vez** para que se cree la config
3. Para el servidor
4. Edita `config/cobblemonguard.json` con tus credenciales MySQL

---

## Paso 4 — Configuración

Archivo: `config/cobblemonguard.json`

```json
{
  "enabled": true,
  "host": "tu-servidor-mysql.hosting.com",
  "port": 3306,
  "database": "cobblemonguard",
  "username": "tu_usuario",
  "password": "tu_password",
  "poolSize": 10,
  "connectionTimeoutMs": 30000,
  "identifyByName": false,
  "identifyByNameFallback": true,
  "syncInventory": true,
  "syncEnderChest": true,
  "syncCobblemonPC": true,
  "syncCobblemonParty": true,
  "syncAccessories": true,
  "syncBackpackContents": true,
  "syncEconomy": true,
  "economyCurrencyKeys": ["cobblemmo:arceuscoin"],
  "autoSaveIntervalSeconds": 0
}
```

| Campo | Descripción |
|-------|-------------|
| `enabled` | Activa/desactiva el mod sin borrarlo |
| `host` | Dirección del servidor MySQL de tu hosting |
| `port` | Puerto MySQL (default: 3306) |
| `database` | Nombre de la base de datos |
| `username` / `password` | Credenciales MySQL |
| `poolSize` | Conexiones MySQL simultáneas. Regla aproximada: al menos tantas como jugadores simultáneos esperados en un pico (importa sobre todo al apagar el server, cuando se guarda a todos en paralelo) |
| `identifyByName` | `true` = identifica jugadores por NOMBRE en vez de UUID (solo para servers **offline**, donde el UUID cambia). Default `false` (online, UUID). |
| `identifyByNameFallback` | Con `identifyByName: false` (UUID): si no hay fila para el UUID del jugador, antes de asumir "nuevo" prueba encontrarla por nombre y la reasocia al UUID actual. Default `true`. Útil al migrar offline↔online o si el UUID cambió por otra razón. Cuidado: si alguien más reutilizó ese nombre, podría reclamar la fila equivocada (mismo riesgo que `identifyByName`). |
| `syncInventory` | Sincronizar inventario principal (incluye ítems de mochila cargados) |
| `syncEnderChest` | Sincronizar cofre del Ender |
| `syncCobblemonPC` | Sincronizar PC de Cobblemon (todas las cajas) |
| `syncCobblemonParty` | Sincronizar el equipo de 6 Pokémon (party) |
| `syncAccessories` | Sincronizar slots de accesorios equipados (mochila puesta) |
| `syncBackpackContents` | Volcar el contenido real de las mochilas SB a MySQL (portabilidad total) |
| `syncEconomy` | Sincronizar saldos de Impactor. No hace nada si Impactor no está instalado. |
| `economyCurrencyKeys` | Monedas ("namespace:value") a sincronizar además de las que se detecten solas. Ver sección "Economía (Impactor)" más abajo. |
| `autoSaveIntervalSeconds` | Guardado automático cada X segundos (0 = solo al salir) |
| `backupDedupEnabled` | No inserta un backup si el estado del jugador es idéntico al del backup anterior. Default `true`. Con backups cada 5 min la mayoría son copias exactas de la anterior, y cada fila guarda el estado completo — esto es lo que evita que la tabla de backups crezca a decenas de GB. Igual se fuerza uno por hora aunque no haya cambios. |
| `syncLogOnlyErrors` | Escribir en `cobblemonguard_sync_log` solo los fallos, no cada SAVE/LOAD exitoso. Default `true`. Ponlo en `false` si estás depurando y querés el historial completo. |
| `syncLogRetentionHours` | Horas que se conservan las entradas del log de sincronización (default 168 = 7 días, `0` = no borrar nunca). Se limpia en el mismo ciclo periódico que los backups. |

> Nota: las columnas e índices nuevos de la base de datos (`cobblemon_party`, `backpacks`,
> `idx_name_time`, etc.) se **crean automáticamente** al arrancar el mod, comprobando
> `information_schema`. No necesitas tocar MySQL a mano aunque vengas de una versión
> anterior. Si la tabla de backups ya es grande, la creación del índice puede tardar un
> rato la primera vez — queda avisado en el log.

---

## Paso 4.5 — Cross-server sync (solo si tienes varios backends detrás de un proxy)

**Sáltate esta sección si tienes un solo servidor Fabric.** Solo aplica a redes tipo
Velocity-CTD + FabricProxy-Lite con más de un backend Fabric (ej. overworld y una
dimensión custom como procesos separados).

Agrega estos campos a `config/cobblemonguard.json` (se crean con valores por defecto la
primera vez que arranca el mod si usas una versión ya actualizada):

```json
{
  "crossServerSyncEnabled": true,
  "serverId": "overworld",
  "redisHost": "127.0.0.1",
  "redisPort": 6379,
  "redisPassword": "",
  "crossServerWaitTimeoutMs": 8000,
  "crossServerLockTtlSeconds": 45,
  "livePastureSyncEnabled": false
}
```

| Campo | Descripción |
|-------|-------------|
| `crossServerSyncEnabled` | Activa la coordinación cross-server. `false` (default) = comportamiento idéntico a antes, sin tocar Redis. |
| `serverId` | Identificador único de **este** backend. Debe ser distinto en cada servidor Fabric de la red (ej. `overworld`, `dimension-void`). Solo para logs/diagnóstico. |
| `redisHost` / `redisPort` / `redisPassword` | Conexión al Redis **local** (misma máquina/red que tus backends — la latencia importa). No uses Redis cloud. |
| `crossServerWaitTimeoutMs` | Cuánto espera (congelado, con la misma pantalla de "cargando" de siempre) el backend destino a que el origen confirme el guardado, antes de dejarlo entrar igual (por si el otro backend se cayó). |
| `crossServerLockTtlSeconds` | Red de seguridad: si un backend se cae sin liberar el lock, expira solo tras este tiempo. Se renueva automáticamente cada 15s mientras el jugador está online. |
| `livePastureSyncEnabled` | Cierra el gap de Pastures en tiempo real (ver más abajo). Requiere `crossServerSyncEnabled: true`. `false` por defecto — es la pieza más nueva del mod. |

**Requisitos:** Redis 7+ instalado localmente (no versiones cloud), un `serverId` distinto
por cada backend Fabric, y CobblemonGUARD apuntando a la **misma base de datos MySQL**
desde todos los backends (MySQL sigue siendo la única fuente de verdad persistente —
Redis solo coordina "quién tiene la data en memoria ahora mismo").

**No necesitas tocar Velocity-CTD ni FabricProxy-Lite.** Toda la coordinación pasa por
el JOIN/DISCONNECT normal de cada backend — reutiliza la pantalla de congelado
("cargando...") que ya existe, así que para el jugador se ve igual que una carga normal,
solo que puede tardar un poco más si el otro backend todavía está guardando.

**Importante — con `crossServerSyncEnabled: true` cambia CÓMO se aplica la PC/party al
entrar:** en single-server, si el jugador ya tiene pokémon en su PC/equipo (cargados del
disco de Cobblemon) el mod NO los toca — asume que el disco local ya está al día. En una
red multi-backend eso es peligroso: cada backend tiene su PROPIO disco local, así que
"ya tener pokémon" no significa "estar al día" (puede ser una copia vieja de la última
vez que jugó en ESE backend en particular). Por eso, con cross-server activado, SIEMPRE
se aplica MySQL al entrar (mezclando de forma segura por UUID de Pokémon — solo toca lo
que realmente cambió, no duplica nada), apoyándose en el lock de Redis para garantizar
que MySQL está fresco. Ver también la nota sobre **Pastures** más abajo.

**Pastures (ranchos) en redes multi-backend:** cuando una pastura de Cobblemon está en un
chunk cargado, Cobblemon accede a la PC de su dueño por UUID
(`Cobblemon.storage.getPC(uuid, ...)`) **directamente**, sin pasar por el JOIN/DISCONNECT
del jugador. Si un jugador tiene una pastura en un backend donde normalmente NO juega, ese
backend puede terminar con una copia local de su PC ligeramente desactualizada mientras la
pastura está activa (comportamiento que Cobblemon reserva a su almacenamiento local, no a
MySQL). Con el punto anterior (cross-server siempre aplica MySQL al entrar) esto se
autocorrige en cuanto el jugador vuelve a entrar a ese backend — no se queda pegado
indefinidamente — pero por defecto, mientras el jugador está ausente de ese backend, la
pastura sigue operando sobre la copia local, no sobre MySQL en tiempo real.

Para cerrar ese último tramo, existe `livePastureSyncEnabled` (default `false`, requiere
`crossServerSyncEnabled: true`): registra un gancho propio en el storage de Cobblemon que,
SOLO para jugadores offline en este backend, dispara en segundo plano un refresco desde
MySQL hacia la PC que Cobblemon tiene en memoria en este backend (nunca toca a un jugador
online, nunca bloquea el hilo del servidor con una consulta a MySQL, nunca sirve datos
directo — si algo falla simplemente queda inerte y todo sigue igual que sin la opción).
Revisa el log de arranque: si ves `[Pasturas] Factory de sync en vivo registrada...` quedó
activo; si ves un warning de `[Pasturas]` en su lugar, quedó inerte (Cobblemon pudo haber
cambiado internamente el nombre/forma de su API de storage) y el mod sigue funcionando
igual que con la opción en `false`. Sin esta opción (o en single-server, donde no aplica),
la reconciliación ocurre en el próximo ingreso a ese backend en vez de ser instantánea.

---

## Cómo funciona

### Al entrar el jugador
1. Se espera 1 tick para que Cobblemon cargue su PC desde disco
2. (Si `crossServerSyncEnabled`) se espera a que el backend anterior confirme su guardado
3. Se consulta MySQL buscando datos del UUID del jugador
4. Si existen datos → se aplican. Inventario/ender/mochilas/accesorios/economía siempre
   sobreescriben con lo de MySQL. La PC y el equipo (party) en single-server NO se tocan
   si el jugador ya tiene pokémon (se asume que el disco local ya está al día); en
   cross-server SIEMPRE se aplica MySQL, mezclando de forma segura por UUID de Pokémon
   (ver "Paso 4.5" arriba)
5. Si no existen datos → primer ingreso, no se hace nada

### Al salir el jugador
1. Se serializa inventario + ender chest + PC + party + accesorios + contenido de mochilas en NBT comprimido
2. Se guarda en MySQL de forma asíncrona (sin lag en el servidor)

### Al apagar el servidor
1. Se guardan TODOS los jugadores online EN PARALELO (una tarea por jugador contra el
   pool de conexiones MySQL) y se espera a que TODOS terminen, con un único timeout
   total acotado (15s + 300ms por jugador, tope 90s) — antes de cerrar el pool.
2. Si algún jugador no confirma guardado dentro de ese timeout (típicamente por un
   `poolSize` insuficiente para la cantidad de jugadores conectados, o MySQL lento), se
   loguea un error con su nombre y se avisa por Discord (si está configurado) en vez de
   cerrar el pool en silencio.

---

## Comandos

Alias: `/cg` = `/cobblemonguard`.

| Comando | Nodo LuckPerms | Descripción |
|---------|----------------|-------------|
| `/cg status` | `cobblemonguard.status` | Estado de MySQL, versión, permisos y cross-server |
| `/cg save <jugador>` \| `save all` | `cobblemonguard.save` | Guarda ahora todos los datos del jugador |
| `/cg restore <jugador> [tipo]` | `cobblemonguard.restore` | Restaura desde MySQL (todo, o solo un tipo) |
| `/cg info <jugador>` | `cobblemonguard.info` | Último guardado y UUID |
| `/cg backup <jugador>` \| `backup all` | `cobblemonguard.backup` | Crea un backup manual al instante |
| `/cg backups <jugador>` | `cobblemonguard.backups` | Lista los backups disponibles |
| `/cg rollback <jugador> <tiempo> [tipo]` | `cobblemonguard.rollback` | Restaura desde un backup (ej. `30m`, `1h`, `2h`) |
| `/cg saving <start\|stop>` | `cobblemonguard.saving` | Activa o pausa la escritura en MySQL |
| `/cg reload` | `cobblemonguard.reload` | Recarga `config/cobblemonguard.json` en caliente |
| `/cg unlock <nombre>` | `cobblemonguard.unlock` | Libera a la fuerza un lock de Redis colgado (cross-server) |

**Permisos:** si tenés **LuckPerms** instalado, cada subcomando se resuelve por su nodo
`cobblemonguard.*`, así podés darle `/cg backups` y `/cg rollback` a un moderador sin
darle OP completo. Sin LuckPerms (o con el nodo sin definir) se cae al chequeo de siempre:
nivel de permiso 2 (operador). Un nodo puesto explícitamente en `false` deniega incluso a
un OP. `/cg status` te dice qué modo está activo.

**`[tipo]` opcional** (por defecto `all` = todo). Valores válidos (con alias):

| Tipo | Alias | Qué restaura |
|------|-------|--------------|
| `all` | `todo`, `todos` | Todo |
| `inventario` | `inv`, `inventory` | Inventario principal |
| `ender` | `enderchest` | Cofre del Ender |
| `pc` | — | PC de Cobblemon |
| `party` | `equipo`, `team` | Equipo de 6 Pokémon |
| `accesorios` | `acc`, `trinkets` | Accesorios/mochila equipada |
| `mochilas` | `mochila`, `backpack`, `bp` | Contenido de las mochilas |
| `economia` | `eco`, `saldo`, `saldos` | Saldos de Impactor (CobbleDollars, ArceusCoin, etc.) |

Ejemplos:
```
/cg restore Steve pc            → solo la PC de Steve
/cg restore Steve party         → solo su equipo de 6
/cg rollback Steve 2h mochilas  → solo las mochilas, al estado de hace 2h
/cg rollback Steve 30m          → todo, al estado de hace 30 min
```

---

## Sophisticated Backpacks

Se cubren **dos** aspectos de las mochilas:

1. **El ítem de la mochila** — si está en el inventario o en un slot de accesorio equipado,
   se guarda junto con el inventario/accesorios.
2. **El contenido de la mochila** — SB NO guarda el contenido dentro del ítem, sino en un
   almacenamiento del mundo indexado por un UUID. CobblemonGUARD lee ese UUID y vuelca el
   contenido real a MySQL (columna `backpacks`), incluyendo mochilas anidadas. Así el
   contenido es 100% portable entre servidores/mundos. Controlado por `syncBackpackContents`.

No se necesita configuración adicional más allá de tener SB + Sophisticated Core instalados.

---

## Economía (Impactor)

Sincroniza los saldos de las monedas de [Impactor](https://impactordev.net/) por jugador,
igual que el resto de los datos (join/quit/auto-save/backups/rollback). Acceso por
reflexión (igual que Cobblemon) — si Impactor no está instalado, esta parte simplemente
no hace nada, sin errores.

- **Dólar (primaria), ArceusCoin y Event Points** se detectan automáticamente (se
  enumeran las monedas registradas en Impactor) Y además tienen key explícita de respaldo
  en `economyCurrencyKeys` (verificadas contra el `economy.conf` real de Impactor):
  `impactor:dollars`, `impactor:arceuscoin`, `impactor:eventpoints`. No hace daño tener
  ambas cosas (se deduplica).
- **⚠️ Si tu `config/cobblemonguard.json` ya existe de una versión anterior**, este
  default NUEVO no se aplica solo — Gson lee el valor que ya está escrito en el archivo.
  Versiones previas traían `cobblemmo:arceuscoin` de fábrica, que **no es una key real**
  (ese namespace no existe en Impactor, nunca pudo matchear nada). Edita a mano tu
  `economyCurrencyKeys` en el JSON y reemplázalo por las tres keys de arriba.
- **Otras monedas custom** — si no aparecen solas en los logs (`/cg status` no lo muestra
  directamente; revisa el log de guardado en consola con `syncEconomy` activo), agrega su
  key completa a `economyCurrencyKeys` (formato `"namespace:value"`, el mismo que usa
  Impactor internamente para registrarlas — revisa tu `economy.conf`, sección
  `currencies { "namespace:value" { ... } }`).
- Al restaurar (`/cg restore`, `/cg rollback`), el saldo se **fija** al valor guardado
  (deposita o retira la diferencia) — no se suma ni se resta a lo que el jugador tenga en
  ese momento.
- Tipo para comandos: `economia` (alias `eco`, `saldo`, `saldos`) — ver tabla de comandos.

---

## Troubleshooting

**"MySQL driver not found"**
→ El archivo JAR no fue compilado correctamente con el shadow plugin.
   Recompila con `gradlew remapJar`.

**"No se pudo conectar a MySQL"**
→ Verifica host, port, usuario y password en la config.
→ Asegúrate que MySQL acepta conexiones remotas desde la IP del servidor.

**Los datos no se cargan al entrar**
→ Revisa los logs del servidor buscando `[CobblemonGUARD]`.
→ Verifica que la tabla `cobblemonguard_players` existe y tiene datos.
→ Consulta `cobblemonguard_sync_log` para ver errores recientes.

**Pokémon de la PC no aparecen**
→ Cobblemon debe estar instalado y funcionando antes que CobblemonGUARD.
→ El orden de carga: Cobblemon carga → 1 tick → CobblemonGUARD sobreescribe con MySQL.

**"CrossServer: No se pudo conectar a Redis" (con `crossServerSyncEnabled: true`)**
→ El mod sigue funcionando en modo single-server (degradado, sin bloquear a nadie) —
   no es un error fatal, pero SIN Redis no hay protección anti-dupeo entre backends.
→ Verifica que Redis esté corriendo en `redisHost:redisPort` y acepte conexiones desde
   la IP de este backend.
→ Revisa `redisPassword` si tu Redis tiene `requirepass` configurado.

**Los saldos de Impactor no se guardan/restauran**
→ Revisa que Impactor (+ CobbleDollars/cobbledollars-bridge o la moneda que uses) esté
   instalado y `syncEconomy: true` en la config.
→ Si es una moneda que no sea CobbleDollars/ArceusCoin, agrégala a `economyCurrencyKeys`
   con su key exacta (ver sección "Economía (Impactor)" arriba).

**Un jugador se queda "cargando..." varios segundos al cambiar de backend**
→ Es esperado hasta `crossServerWaitTimeoutMs` (8s por defecto) si el backend anterior
   todavía está guardando sus datos. Si pasa siempre, revisa que `serverId` sea distinto
   en cada backend y que todos apunten al mismo Redis y a la misma base MySQL.

**Al reiniciar con muchos jugadores conectados, el guardado/backup de algunos falla**
→ Causa típica: `poolSize` (conexiones MySQL simultáneas) menor que la cantidad de
   jugadores online en el momento del apagado — todos se guardan en paralelo, así que si
   hay más jugadores que conexiones, los últimos hacen cola esperando una libre.
→ Revisa el log al apagar: si aparece `[STOP] ¡ATENCIÓN! N jugador(es) NO confirmaron
   guardado a tiempo...`, ahí están listados por nombre los que no llegaron a confirmar
   dentro del timeout — sube `poolSize` a al menos la cantidad de jugadores simultáneos
   esperados en un pico (ej. 20-30 en un server grande) y reinicia.
→ Desde la versión 1.2.0 el mod espera a TODOS los guardados en paralelo con un timeout
   total (no uno por uno como antes) y avisa explícitamente si alguno no llegó — antes
   este fallo podía pasar en silencio.

**Ítems/mochilas/PC "duplicados" o desactualizados al entrar y salir varias veces seguido**
→ Causa típica (corregida en 1.2.0): si el jugador se reconecta muy rápido, la carga del
   rejoin podía leer MySQL ANTES de que el guardado de la desconexión anterior terminara
   de escribirse, restaurando un snapshot viejo encima de un mundo que ya había cambiado
   (se ve como que "reaparecen" ítems que ya no deberían estar, o falta lo último hecho).
   A partir de 1.2.0 el rejoin espera automáticamente a que ese guardado en vuelo termine
   antes de leer — si ves en consola `[JOIN] <jugador>: el guardado anterior no confirmó
   en 15s`, es que el guardado tardó demasiado (mismo problema de `poolSize`/latencia de
   MySQL de arriba) y conviene revisar eso.
→ Si el problema persiste tras actualizar, revisa `cobblemonguard_sync_log` (ver sección
   siguiente) buscando `SAVE`/`LOAD` con `success=0` alrededor de la hora del incidente.

**Un jugador "perdió" sus datos tras migrar el server de offline↔online (o el UUID cambió)**
→ Con `identifyByNameFallback: true` (default desde esta versión) esto se resuelve solo:
   al no encontrar fila por el UUID nuevo, busca por nombre y la reasocia automáticamente.
   Revisa el log buscando `[Identidad] '<nombre>' no tenía fila por UUID... se reclamó`.
→ Si no aparece ese log y el jugador igual entra como "nuevo", confirma que
   `identifyByNameFallback` no esté en `false` explícito en tu config, y que
   `identifyByName` esté en `false` (el fallback no aplica en modo nombre puro).

**Pérdida gradual/mínima de TPS con muchos jugadores conectados**
→ Optimizado en 1.2.0, en tres frentes:
   1. Los accesos por reflexión a Cobblemon, Impactor, Sophisticated Backpacks y
      Accessories (varios de ellos en el hilo del servidor durante joins/restores) ahora
      cachean el `Method`/`Constructor` resuelto en vez de volver a escanear la lista
      completa de métodos de la clase en cada llamada.
   2. El pool de hilos de BD (`dbExecutor`) pasó de "un hilo nuevo por tarea, sin tope"
      a un pool de tamaño fijo (acorde a `poolSize`) — evita picos de creación/destrucción
      de hilos cuando muchos jugadores entran/salen en poco tiempo (ej. tras un reinicio).
   3. Los contadores de auto-save y backup por jugador ya no recorren la lista de
      jugadores online en CADA tick (20 veces por segundo) — se revisan una vez por
      segundo, que es toda la precisión que necesitan (los intervalos se configuran en
      segundos).
→ Si tras actualizar a 1.2.0 sigues notando caídas de TPS achacables al mod, revisa
   `/cg` (o los logs) para descartar que sea Sophisticated Backpacks/Accessories/Impactor
   fallando en cada llamada (mensajes repetidos de `[Mochilas]`/`[Accesorios]`/`[Economy]`
   en el log indican que el mod está reintentando reflexión fallida constantemente, lo
   cual sí puede costar CPU) — en ese caso el problema es la integración de terceros, no
   el cacheo en sí.

---

## Ver logs de sincronización

```sql
-- Últimas 20 operaciones
SELECT player_name, action, success, error_msg, synced_at
FROM cobblemonguard_sync_log
ORDER BY synced_at DESC
LIMIT 20;
```
