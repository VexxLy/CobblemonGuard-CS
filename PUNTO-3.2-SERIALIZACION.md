# Punto 3.2 — El escaneo de mochilas, y por qué el problema real es otro

Documento de diseño · CobblemonGUARD 1.2.0 · 2026-09-10

Este documento explica por qué el punto 3.2 de la auditoría estaba mal planteado, cuál es el costo que sí duele, y cómo resolverlo. Nada de esto está implementado todavía.

---

## 1. Qué decía el punto 3.2 original

> **3.2 `BackpackSync.scanUuids` recorre todo el NBT en cada guardado.** Recorre recursivamente el NBT completo de cada mochila buscando mochilas anidadas, en el hilo del servidor, en cada serialización (join, quit, auto-save, backup). Para un jugador con varias mochilas grandes anidadas son miles de nodos por pasada.
>
> **Arreglo:** cachear el resultado por UUID de mochila mientras el contenido no cambie.

El diagnóstico (el escaneo corre en el hilo del servidor, muchas veces) es correcto. El arreglo propuesto no funciona, y además el escaneo no es lo caro.

---

## 2. Por qué cachear el escaneo no funciona

El código relevante (`BackpackSync.kt`):

```kotlin
val contents = getContents(storage, uuid) ?: continue   // referencia VIVA de SB
collected[uuid] = contents
if (key != null) {
    val nested = HashSet<UUID>()
    scanUuids(contents, key, nested)                     // recorrido recursivo
    nested.forEach { if (!collected.containsKey(it)) pending.add(it) }
}
```

Para cachear el resultado de `scanUuids` hace falta una clave que cambie cuando cambia el contenido de la mochila. Y ahí está el problema:

**No sirve la identidad del objeto.** `getContents()` llama a `getOrCreateBackpackContents` de Sophisticated Backpacks, que devuelve la referencia viva que SB tiene guardada en su `BackpackStorage`. Si SB muta ese `NbtCompound` en el lugar cuando el jugador mete o saca un ítem, la identidad del objeto no cambia aunque el contenido sí. Una caché indexada por identidad devolvería resultados viejos y perderíamos mochilas anidadas, que es exactamente el bug que la función existe para evitar.

**No sirve una clave de contenido.** Cualquier huella del contenido —un hash, un CRC, `NbtCompound.hashCode()`— requiere recorrer el árbol completo para calcularse. Es decir: exactamente el mismo trabajo que el escaneo que queríamos evitar, más el costo de mantener la caché.

No hay una tercera opción sin depender de detalles internos de SB que no podemos verificar desde acá. Por eso quedó sin implementar en la tanda 4.

---

## 3. El costo real: no es el escaneo, es el gzip

Mirando el flujo completo de `BackpackSync.serialize`, el escaneo es la parte barata. Lo que viene después es lo caro:

```kotlin
val baos = ByteArrayOutputStream()
NbtIo.writeCompressed(root, baos)    // recorre el MISMO árbol + lo comprime con gzip
return baos.toByteArray()
```

`NbtIo.writeCompressed` hace dos cosas: serializa el árbol NBT a bytes (un recorrido equivalente al del escaneo) **y** lo pasa por un `GZIPOutputStream`. El `Deflater` de Java en nivel por defecto anda por el orden de las decenas de MB/s, así que un blob de 1 MB son fácilmente 20-30 ms. Un tick de Minecraft dura 50 ms.

Comparado con eso, el recorrido de `scanUuids` sobre unos pocos miles de nodos está en el orden de fracciones de milisegundo. Optimizarlo es optimizar el 2% del problema.

### Y esto no es solo de mochilas

El mismo patrón está en **todos** los syncers, y el peor no es el de mochilas: es la PC.

| Syncer | Qué construye en el hilo del servidor | Después |
|--------|----------------------------------------|---------|
| `PokemonPCSync` | `saveStore()` → NBT de la PC completa (cientos de Pokémon) | `NbtIo.writeCompressed` |
| `PokemonPartySync` | `saveStore()` → NBT del equipo de 6 | `NbtIo.writeCompressed` |
| `InventorySync` | `writeNbt()` + `toNbtList()` | `NbtIo.writeCompressed` (×2) |
| `AccessorySync` | `toNbtList()` por contenedor | `NbtIo.writeCompressed` |
| `BackpackSync` | `getContents()` + `scanUuids()` | `NbtIo.writeCompressed` |
| `EconomySync` | consultas a Impactor (bloqueantes, hasta 800 ms de tope) | armado de un string |

La PC de un jugador activo es el blob más grande del mod por lejos, y su gzip corre entero en el hilo principal.

### Cuánto se nota

Con el jitter que agregamos en la tanda 4, los backups quedan repartidos a lo largo del intervalo. Con 40 jugadores y backups cada 5 minutos, toca un jugador cada 7,5 segundos. Si serializar a ese jugador cuesta 30 ms, es un tirón que se come el 60% de un tick, cada 7,5 segundos. Con 200 jugadores es cada 1,5 segundos.

Eso encaja con la descripción original que diste del problema: *"pérdida mínima o gradual con alto volumen de jugadores"*.

**Aviso honesto sobre estos números:** son estimaciones basadas en el rendimiento típico de `Deflater` y en el tamaño esperable de una PC de Cobblemon. No están medidos en tu servidor. Ver la sección 6.

---

## 4. La solución: partir la serialización en dos fases

### La idea

Hoy `DataSyncer.serialize()` hace todo junto y todo en el hilo del servidor:

```kotlin
interface DataSyncer {
    fun serialize(player: ServerPlayerEntity): ByteArray?
}
```

La propuesta es separar lo que **obligatoriamente** necesita el hilo principal (leer estado vivo del juego) de lo que no (armar bytes y comprimir):

```kotlin
interface DataSyncer {
    /**
     * FASE 1 — hilo del servidor. Lee el estado vivo y devuelve un cierre que ya NO
     * depende de él. Debe ser lo más barato posible.
     * @return null si este tipo no aplica para este jugador.
     */
    fun snapshot(player: ServerPlayerEntity): (() -> ByteArray?)?

    fun deserialize(player: ServerPlayerEntity, data: ByteArray, onlyIfEmpty: Boolean)
}
```

Y `SyncService` pasa de una función a dos:

```kotlin
object SyncService {
    /** Hilo del servidor. Barato. */
    fun snapshot(player: ServerPlayerEntity): Map<DataType, (() -> ByteArray?)?>

    /** Fuera del hilo del servidor. Acá cae el gzip. */
    fun encode(snapshots: Map<DataType, (() -> ByteArray?)?>): Map<DataType, ByteArray?>
}
```

### La regla que hay que respetar

> Todo lo que el cierre capture tiene que ser **suyo**: objetos recién creados que nadie más va a leer ni escribir mientras el cierre corre en otro hilo.

Los `NbtCompound` y `NbtList` de Minecraft no son thread-safe, pero eso solo importa si alguien más los toca. Si son estructuras nuevas que solo nosotros vemos, leerlas desde el pool de BD es seguro.

### Qué cambia en cada syncer

**`PokemonPCSync` y `PokemonPartySync` — cambio trivial.** `CobblemonStorage.saveStore()` ya construye un `NbtCompound` nuevo (crea el `NbtCompound()` y se lo pasa a `saveToNBT` de Cobblemon). Ese objeto **ya es un snapshot válido**: no es una referencia viva a nada. Solo hay que mover el `writeCompressed` al cierre.

```kotlin
override fun snapshot(player: ServerPlayerEntity): (() -> ByteArray?)? {
    if (!CobblemonStorage.isLoaded) return null
    val pc = CobblemonStorage.getPC(player) ?: return null
    val nbt = CobblemonStorage.saveStore(pc, player.server.registryManager) ?: return null
    // A partir de acá `nbt` es nuestro y de nadie más.
    return {
        val baos = ByteArrayOutputStream()
        NbtIo.writeCompressed(nbt, baos)
        baos.toByteArray()
    }
}
```

**`InventorySync` — cambio trivial.** `player.inventory.writeNbt(NbtList())` y `player.enderChestInventory.toNbtList(registries)` construyen estructuras nuevas. Mismo patrón.

**`AccessorySync` — cambio trivial.** El `NbtCompound` raíz se arma con `toNbtList(registries)` de cada contenedor, que también genera estructura nueva. El bucle sobre contenedores queda en la fase 1 (toca la capability de Accessories), el `writeCompressed` se va a la fase 2.

**`BackpackSync` — el único con trabajo real.** Acá `getContents()` devuelve la referencia viva de SB, así que hay que copiarla:

```kotlin
val contents = getContents(storage, uuid)?.copy() ?: continue
```

`NbtCompound.copy()` es un recorrido del árbol —el mismo costo que el escaneo que queríamos cachear— pero a cambio se van a la fase 2 **el escaneo de anidadas y el gzip**. El saldo es claramente favorable.

Hay un detalle: para saber qué mochilas anidadas hay que pedirle a `BackpackStorage`, primero hay que escanear, y `BackpackStorage.get()` solo es seguro en el hilo del servidor. Dos opciones:

- **Copiar y escanear en fase 1, comprimir en fase 2.** Simple, conserva el comportamiento exacto de hoy, y ya se lleva el gzip (lo caro) fuera del hilo.
- **Copiar en fase 1, escanear en fase 2 y volver al hilo del servidor solo si aparecieron anidadas.** Más eficiente, pero introduce un rebote entre hilos. Las mochilas anidadas son poco frecuentes, así que el rebote sería raro, pero complica el flujo.

Recomiendo la primera. La segunda solo vale si la medición muestra que el escaneo pesa, que es justamente lo que no creo.

**`EconomySync` — el mejor caso.** Este no necesita el hilo del servidor **para nada**: `ImpactorEconomy` habla con el servicio de Impactor, no con el estado del mundo. Se va entero a la fase 2:

```kotlin
override fun snapshot(player: ServerPlayerEntity): (() -> ByteArray?)? {
    if (!ImpactorEconomy.isLoaded) return null
    val uuid = player.uuid                      // lo único que necesitamos del jugador
    val extra = CobblemonGuard.config.economyCurrencyKeys
    return { /* consulta a Impactor + armado del string, en el pool de BD */ }
}
```

Y con eso **desaparece el último bloqueo en el hilo del servidor**: el `future.get(800ms)` de `ImpactorEconomy.account()` que dejamos como red de seguridad en la auditoría anterior deja de poder frenar el tick, porque ya no corre ahí. El tope se puede volver a subir sin riesgo.

### Qué cambia en los llamadores

Son seis sitios, todos con la misma forma. Hoy:

```kotlin
val blob = SyncService.serialize(player)              // hilo del servidor: TODO
dbExecutor.submit { persist(uuid, name, blob) }
```

Después:

```kotlin
val snap = SyncService.snapshot(player)               // hilo del servidor: solo leer estado
dbExecutor.submit { persist(uuid, name, SyncService.encode(snap)) }
```

Ubicación de los seis (`PlayerEventHandler.kt`, numeración actual):

| Línea | Contexto |
|-------|----------|
| 331 | primer ingreso |
| 468 | `savePlayerDataAsync` (disconnect / auto-save) |
| 534 | apagado del servidor |
| 688 | `saveBackupAsync` |
| 721 | `forceSave` |

`SyncRegistry` también cambia: el helper `syncer(...)` que arma los `DataSyncer` anónimos pasa a recibir una función `snapshot` en vez de `serialize`. Es un cambio mecánico de una línea por entrada.

`SyncService.apply()` y todo el camino de **deserialización** no se tocan.

---

## 5. Riesgos y cómo acotarlos

**El riesgo real es uno solo: que algún snapshot conserve, sin querer, una referencia a estado vivo del juego.** Si eso pasa, se estaría leyendo estado del mundo desde otro hilo, que es la clase de bug que produce corrupción intermitente e irreproducible.

Mitigación: para cuatro de los cinco syncers ya se cumple por construcción (los métodos de Minecraft y Cobblemon que usamos *construyen* estructuras nuevas, no devuelven las internas). El único que necesita atención es `BackpackSync`, y ahí el `.copy()` es explícito y está en una sola línea. Vale dejar comentada esa línea con el motivo, porque es el tipo de cosa que alguien "limpia" seis meses después sin saber por qué estaba.

**Riesgo secundario: el snapshot queda desactualizado.** Entre la fase 1 y la fase 2 pasan milisegundos, y el jugador podría cambiar algo en el medio. Pero esto ya pasa hoy: entre `SyncService.serialize()` y el `INSERT` en MySQL también hay una ventana. No es un problema nuevo ni se agranda de forma relevante.

**Lo que NO cambia:** el orden de escritura a MySQL sigue garantizado por la cadena de guardados por UUID que se implementó en la tanda 2. Dos snapshots del mismo jugador se codifican en paralelo, pero se persisten en orden.

---

## 6. Antes de escribir una línea: medir

Todo lo de arriba se apoya en una estimación, no en una medición de tu servidor. El paso barato y correcto es instrumentar primero:

- Un `System.nanoTime()` alrededor de cada `serialize` en `SyncService`, acumulando por `DataType`.
- Un histograma simple en memoria (contador, total, máximo, y p95 aproximado).
- Un `/cg stats` que lo muestre, con su nodo `cobblemonguard.stats`.

Son unas 60 líneas y no tocan nada del flujo de datos. Con eso vas a saber, con números de tu servidor real:

- si la PC está costando 30 ms o 3 ms,
- qué tipo domina de verdad (mi apuesta es PC, después mochilas),
- y si el refactor vale la pena o si estamos por optimizar algo que no duele.

Si resulta que todo está en el orden de 1-3 ms, lo correcto es **no hacer el refactor** y cerrar el punto 3.2 como "medido, no es un problema". Esa también es una respuesta válida y hay que estar dispuesto a aceptarla.

---

## 7. Resumen

| | |
|---|---|
| **El punto 3.2 como estaba escrito** | No tiene solución razonable: cualquier caché cuesta lo mismo que el trabajo que evita. |
| **El problema que sí existe** | El gzip de la serialización corre en el hilo del servidor, para todos los tipos, y la PC es el blob más grande. |
| **La solución** | Partir `DataSyncer.serialize` en `snapshot()` (hilo del servidor, barato) y un cierre de codificación (pool de BD, se lleva el gzip). |
| **Alcance** | 5 syncers + `SyncRegistry` + `SyncService` + 6 call sites en `PlayerEventHandler`. Cambio mecánico en 4 de los 5 syncers. |
| **Beneficio extra** | `EconomySync` se va entero fuera del hilo del servidor y desaparece el último bloqueo del tick. |
| **Riesgo** | Uno, identificable y acotado a una línea: el `.copy()` de `BackpackSync`. |
| **Paso previo recomendado** | Instrumentar y medir. Puede que la conclusión sea que no hace falta. |
