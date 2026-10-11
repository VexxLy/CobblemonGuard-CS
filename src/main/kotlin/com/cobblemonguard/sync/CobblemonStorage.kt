package com.cobblemonguard.sync

import com.cobblemonguard.CobblemonGuard
import com.cobblemonguard.util.ReflectCache
import net.minecraft.nbt.NbtCompound
import net.minecraft.server.network.ServerPlayerEntity
import java.util.UUID

/**
 * Acceso de bajo nivel al almacenamiento de Cobblemon (party + PC) vía REFLEXIÓN.
 *
 * ¿Por qué reflexión?  Cobblemon 1.7.3 usa Kotlin 2.2.x y Loom/kotlinx-metadata-jvm
 * no puede remap JARs con metadata Kotlin 2.2+ (límite 2.1.0), así que no podemos
 * compilar contra Cobblemon. Los NOMBRES de los métodos de Cobblemon NO se ofuscan
 * (es un mod distribuido con nombres reales), por eso la reflexión por nombre es estable.
 *
 * Estrategia de serialización:
 *   En vez de iterar Pokémon slot por slot (frágil), usamos el MISMO mecanismo que
 *   Cobblemon usa para persistir a disco:  store.saveToNBT(nbt, registries) /
 *   store.loadFromNBT(nbt, registries).  Esto garantiza un round-trip correcto tanto
 *   para la party (equipo de 6) como para la PC completa.
 *
 * Firmas reales (verificadas con javap sobre Cobblemon-fabric-1.7.3+1.21.1):
 *   PokemonStoreManager.getParty(class_3222): PlayerPartyStore
 *   PokemonStoreManager.getPC(class_3222): PCStore
 *   PokemonStore.saveToNBT(class_2487 nbt, class_5455 registries): class_2487
 *   PokemonStore.loadFromNBT(class_2487 nbt, class_5455 registries): PokemonStore
 *   PartyStore.clearParty()   /   PCStore.clearPC()
 *   PokemonStore.sendTo(class_3222)
 *
 * NOTA DE RENDIMIENTO: los métodos de aquí abajo se resuelven vía [ReflectCache] en
 * vez de escanear `Class.methods` en cada llamada — varios de estos (getAtInt,
 * setAtInt, pokemonUuid, pcSetAt...) corren una vez POR SLOT de PC/party (hasta
 * cientos por restore) en el hilo del servidor durante cada join. Sin cachear, con
 * muchos jugadores conectándose cerca en el tiempo eso se nota como TPS drops.
 */
object CobblemonStorage {

    private const val SLOTS_PER_BOX = 30

    private val cobblemonClass by lazy {
        runCatching { Class.forName("com.cobblemon.mod.common.Cobblemon") }.getOrNull()
    }
    private val pokemonClass by lazy {
        runCatching { Class.forName("com.cobblemon.mod.common.pokemon.Pokemon") }.getOrNull()
    }
    private val playerPartyStoreClass by lazy {
        runCatching { Class.forName("com.cobblemon.mod.common.api.storage.party.PlayerPartyStore") }.getOrNull()
    }
    private val pcStoreClass by lazy {
        runCatching { Class.forName("com.cobblemon.mod.common.api.storage.pc.PCStore") }.getOrNull()
    }
    private val pcPositionClass by lazy {
        runCatching { Class.forName("com.cobblemon.mod.common.api.storage.pc.PCPosition") }.getOrNull()
    }

    // Constructores de stores desasociados / posiciones de PC — resueltos una sola vez.
    private val partyStoreCtor by lazy {
        playerPartyStoreClass?.let { ReflectCache.constructor(it, UUID::class.java) }
    }
    private val pcStoreCtor by lazy {
        pcStoreClass?.let { ReflectCache.constructor(it, UUID::class.java) }
    }
    private val pcPositionCtor by lazy {
        // java.lang.Integer.TYPE y no Int::class.javaPrimitiveType: este ultimo es Class<Int>?
        // (nullable por firma, aunque para Int nunca sea null) y ReflectCache.constructor pide
        // Class<*> no nulo, asi que Kotlin lo rechaza.
        pcPositionClass?.let { ReflectCache.constructor(it, java.lang.Integer.TYPE, java.lang.Integer.TYPE) }
    }

    val isLoaded: Boolean by lazy {
        (cobblemonClass != null).also { loaded ->
            if (!loaded) CobblemonGuard.logger.error(
                "[Cobblemon] Clase 'com.cobblemon.mod.common.Cobblemon' no encontrada — ¿está Cobblemon instalado?"
            )
        }
    }

    // ── Acceso al gestor de almacenamiento ─────────────────────────────────

    // La instancia (Cobblemon.INSTANCE) y su storage manager son SIEMPRE el mismo
    // singleton durante toda la vida del server — se resuelven una sola vez.
    private val cobblemonInstance by lazy {
        runCatching { cobblemonClass?.getField("INSTANCE")?.get(null) }.getOrNull()
    }
    private val getStorageMethod by lazy {
        cobblemonInstance?.let { ReflectCache.method(it.javaClass, "getStorage", 0) }
    }

    private fun storageManager(): Any? {
        val instance = cobblemonInstance ?: return null
        return runCatching {
            getStorageMethod?.invoke(instance)
        }.onFailure { e ->
            CobblemonGuard.logger.error("[Cobblemon] No se pudo obtener el storage manager: ${e.cause?.message ?: e.message}")
        }.getOrNull()
    }

    fun getParty(player: ServerPlayerEntity): Any? = getStore("getParty", player)

    fun getPC(player: ServerPlayerEntity): Any? = getStore("getPC", player)

    /**
     * getPC(uuid, registryAccess) — la SOBRECARGA por UUID que usan internamente las
     * pasturas (`PokemonPastureBlockEntity`) para leer la PC del dueño sin que esté
     * online. Distinta de [getPC] (que toma un [ServerPlayerEntity]) — se resuelve por
     * separado porque tienen distinta cantidad/tipo de parámetros. Usada por
     * [LivePastureSync] para refrescar el store en vivo de un jugador offline en este
     * backend.
     */
    fun getPCByUuid(uuid: UUID, registries: Any): Any? {
        val manager = storageManager() ?: return null
        return runCatching {
            ReflectCache.method(manager.javaClass, "getPC", 2) { it.parameterTypes[0] == UUID::class.java }
                ?.invoke(manager, uuid, registries)
        }.onFailure { e ->
            CobblemonGuard.logger.error("[Cobblemon] Error en getPC(uuid): ${e.cause?.message ?: e.message}")
        }.getOrNull()
    }

    /**
     * Registra una [PokemonStoreFactory] propia (implementada por Proxy, ver
     * [LivePastureSync]) en el storage manager de Cobblemon (`registerFactory(priority,
     * factory)`), sin compilar contra ninguno de los dos tipos:
     *
     *   - El tipo EXACTO de la interfaz `PokemonStoreFactory` se saca del segundo
     *     parámetro del propio método `registerFactory` (así [makeProxy] arma el Proxy
     *     con la interfaz real, sin que nosotros tengamos que adivinar su nombre
     *     completo ni mantenerlo si Cobblemon la mueve de paquete).
     *   - El valor de prioridad se saca de los `enumConstants` del PRIMER parámetro,
     *     prefiriendo uno cuyo nombre contenga "HIGH" (para quedar ANTES que la factory
     *     default en la iteración — el manager se queda con el primer resultado no-nulo,
     *     así que si quedáramos después nunca se nos llamaría) y si no hay ninguno así,
     *     el primer valor del enum. Esto es una heurística (el enum real de prioridad no
     *     se pudo confirmar sin compilar contra Cobblemon) — si resulta ser la prioridad
     *     equivocada, el único efecto es que [LivePastureSync] queda inerte (nunca se
     *     invoca), NUNCA rompe nada: revisa el log de arranque por
     *     "[Pasturas] Factory registrada..." para confirmar que se enganchó.
     *
     * @return true si se pudo registrar.
     */
    fun registerLiveStoreFactory(makeProxy: (factoryInterface: Class<*>) -> Any): Boolean {
        val manager = storageManager() ?: return false
        return runCatching {
            val method = manager.javaClass.methods.firstOrNull { it.name == "registerFactory" && it.parameterCount == 2 }
            if (method == null) {
                CobblemonGuard.logger.warn(
                    "[Pasturas] No se encontró 'registerFactory' en el storage manager de Cobblemon " +
                    "(¿cambió de nombre en esta versión?) — sync en vivo de pasturas desactivado."
                )
                return false
            }
            val priorityType = method.parameterTypes[0]
            val factoryType = method.parameterTypes[1]
            val constants = priorityType.enumConstants
            val priority = constants?.firstOrNull { (it as? Enum<*>)?.name?.contains("HIGH", ignoreCase = true) == true }
                ?: constants?.firstOrNull()
            if (priority == null) {
                CobblemonGuard.logger.warn(
                    "[Pasturas] El parámetro de prioridad de 'registerFactory' no es un enum reconocible " +
                    "— sync en vivo de pasturas desactivado."
                )
                return false
            }
            val proxy = makeProxy(factoryType)
            method.invoke(manager, priority, proxy)
            CobblemonGuard.logger.info(
                "[Pasturas] Factory de sync en vivo registrada en Cobblemon con prioridad '${(priority as Enum<*>).name}'."
            )
            true
        }.onFailure { e ->
            CobblemonGuard.logger.error("[Pasturas] No se pudo registrar la factory de sync en vivo: ${e.cause?.message ?: e.message}")
        }.getOrDefault(false)
    }

    private fun getStore(methodName: String, player: ServerPlayerEntity): Any? {
        val manager = storageManager() ?: return null
        return runCatching {
            ReflectCache.method(manager.javaClass, methodName, 1)?.invoke(manager, player)
        }.onFailure { e ->
            CobblemonGuard.logger.error("[Cobblemon] Error en $methodName: ${e.cause?.message ?: e.message}")
        }.getOrNull()
    }

    // ── Serializar / deserializar un store completo ────────────────────────

    /** Serializa un store (party o PC) a un NbtCompound usando saveToNBT. */
    fun saveStore(store: Any, registries: Any): NbtCompound? {
        return runCatching {
            val nbt = NbtCompound()
            val method = ReflectCache.method(store.javaClass, "saveToNBT", 2) {
                it.parameterTypes.any { p -> p.isAssignableFrom(NbtCompound::class.java) }
            } ?: return null
            val result = method.invoke(store, *orderArgs(method.parameterTypes, nbt, registries))
            (result as? NbtCompound) ?: nbt
        }.onFailure { e ->
            CobblemonGuard.logger.error("[Cobblemon] Error en saveToNBT: ${e.cause?.message ?: e.message}")
        }.getOrNull()
    }

    /** Carga un store (party o PC) desde un NbtCompound usando loadFromNBT. */
    fun loadStore(store: Any, nbt: NbtCompound, registries: Any): Boolean {
        return runCatching {
            val method = ReflectCache.method(store.javaClass, "loadFromNBT", 2) {
                it.parameterTypes.any { p -> p.isAssignableFrom(NbtCompound::class.java) }
            } ?: return false
            method.invoke(store, *orderArgs(method.parameterTypes, nbt, registries))
            true
        }.onFailure { e ->
            CobblemonGuard.logger.error("[Cobblemon] Error en loadFromNBT: ${e.cause?.message ?: e.message}")
        }.getOrElse { false }
    }

    /** clearParty() / clearPC() según el método disponible en el store. */
    fun clearStore(store: Any) {
        runCatching {
            val method = ReflectCache.method(store.javaClass, "clearParty", 0)
                ?: ReflectCache.method(store.javaClass, "clearPC", 0)
            method?.invoke(store)
        }
    }

    /** Reenvía el estado del store al cliente del jugador. */
    fun sendTo(store: Any, player: ServerPlayerEntity) {
        runCatching {
            ReflectCache.method(store.javaClass, "sendTo", 1)?.invoke(store, player)
        }
    }

    // ── Stores TEMPORALES + copia al store en vivo ─────────────────────────
    //
    // loadFromNBT sobre el store EN VIVO puebla los datos pero NO notifica al
    // cliente (Cobblemon solo lo usa en stores recién creados). Por eso cargamos
    // en un store temporal y copiamos Pokémon por Pokémon al vivo con set(),
    // que sí dispara los observers y sincroniza el equipo/PC en el cliente.

    fun newDetachedParty(uuid: UUID): Any? = runCatching {
        partyStoreCtor?.newInstance(uuid)
    }.getOrNull()

    fun newDetachedPC(uuid: UUID): Any? = runCatching {
        pcStoreCtor?.newInstance(uuid)
    }.getOrNull()

    /**
     * Copia el equipo (6 slots) del store temporal al store en vivo, tocando SOLO
     * lo que cambió. Si el slot ya tiene el MISMO Pokémon (mismo UUID) se deja
     * intacto — así evitamos crear objetos Pokémon duplicados que causan fantasmas.
     */
    fun copyPartyToLive(detached: Any, live: Any): Boolean {
        val size = intMethod(live, "size") ?: 6
        var changed = false
        for (i in 0 until size) {
            val newP = getAtInt(detached, i)
            val curP = getAtInt(live, i)
            when {
                newP == null && curP != null -> { removePokemon(live, curP); changed = true }
                newP != null && !sameUuid(curP, newP) -> { setAtInt(live, i, newP); changed = true }
                // mismo UUID (o ambos vacíos) → no tocar
            }
        }
        return changed
    }

    /** Copia todas las cajas de la PC (solo lo que cambió). Devuelve true si hubo cambios. */
    fun copyPCToLive(detached: Any, live: Any): Boolean {
        val liveBoxes = boxesOf(live) ?: return false
        val detBoxes = boxesOf(detached) ?: return false
        var changed = false
        for (b in liveBoxes.indices) {
            val lb = liveBoxes[b] ?: continue
            val db = detBoxes.getOrNull(b)
            for (s in 0 until SLOTS_PER_BOX) {
                val newP = if (db != null) getAtInt(db, s) else null
                val curP = getAtInt(lb, s)
                when {
                    newP == null && curP != null -> { removePokemon(live, curP); changed = true }
                    newP != null && !sameUuid(curP, newP) -> { pcSetAt(live, b, s, newP); changed = true }
                }
            }
        }
        return changed
    }

    // ── Mezcla estricta, con sincronización de CONTENIDO ──────────────────
    //
    // copyPartyToLive / copyPCToLive (arriba) comparan solo por UUID: si el slot ya tiene el
    // mismo Pokémon, no lo tocan. En una red eso es un agujero grande. El Pokémon vivo en este
    // backend es la copia de SU disco local, que puede tener días: si en otro backend el
    // jugador lo subió de nivel, le cambió los movimientos o le sacó el objeto equipado, acá no
    // se aplicaba nada. Peor: el objeto que le sacó estaba ahora en su inventario (que sí viaja
    // por MySQL) Y seguía equipado en la copia vieja de este backend — un duplicado sin ningún
    // truco. Y al salir, este backend escribía la copia vieja encima del progreso del otro.
    //
    // Estas versiones comparan también el contenido (el NBT completo de cada uno), y si difiere:
    //
    // - Lo normal: REEMPLAZAN el objeto en ese slot por el de la BD, igual que cuando el UUID es
    //   distinto. Es la única forma de que quede idéntico: Pokemon.copyFrom de Cobblemon 1.8.0 no
    //   copia varios campos persistidos (rideBoosts, rideStamina, currentFullness,
    //   interactionCooldowns, isAlpha, heldItemVisible, originalTrainerFriendship) y además pisa
    //   potentialMarks con las marks del otro, que es un bug suyo.
    // - Si hay algo VIVO apuntando a ese objeto —está en el hombro, o en una pastura (tiene
    //   tetheringId: la entidad de la pastura guarda una referencia a este mismo objeto)—
    //   reemplazarlo dejaría a esa referencia colgando de un objeto que ya no está en el store:
    //   los "fantasmas" que motivaron comparar solo por UUID. Ahí se copian los datos SOBRE el
    //   mismo objeto con copyFrom, se le devuelve su estado (copyFrom lo pisa) y se corrige
    //   potentialMarks. Los campos que copyFrom no copia quedan con el valor local: es lo mejor
    //   que se puede hacer sin reemplazarlo, y son pocos Pokémon por jugador.
    //
    // Además son ESTRICTAS: si no pueden leer la estructura del store o escribir un slot,
    // lanzan. Las de arriba devolvían "sin cambios" y el llamador no podía distinguir eso de
    // "no se pudo hacer nada". Las de arriba se conservan tal cual para LivePastureSync.

    private val saveToNbtMethod by lazy {
        pokemonClass?.methods?.firstOrNull { it.name == "saveToNBT" && it.parameterCount == 2 }
    }
    private val copyFromMethod by lazy {
        pokemonClass?.methods?.firstOrNull { it.name == "copyFrom" && it.parameterCount == 1 }
    }
    private val getStateMethod by lazy {
        pokemonClass?.methods?.firstOrNull { it.name == "getState" && it.parameterCount == 0 }
    }
    private val setStateMethod by lazy {
        pokemonClass?.methods?.firstOrNull { it.name == "setState" && it.parameterCount == 1 }
    }
    private val getTetheringIdMethod by lazy {
        pokemonClass?.methods?.firstOrNull { it.name == "getTetheringId" && it.parameterCount == 0 }
    }
    private val getPotentialMarksMethod by lazy {
        pokemonClass?.methods?.firstOrNull { it.name == "getPotentialMarks" && it.parameterCount == 0 }
    }

    /** Qué hacer con un Pokémon vivo que tiene el mismo UUID que el de la BD. */
    private enum class Accion { NADA, REEMPLAZAR, COPIAR_EN_LUGAR }

    private fun nbtDe(pokemon: Any, registries: Any): NbtCompound? = runCatching {
        saveToNbtMethod?.invoke(pokemon, registries, NbtCompound()) as? NbtCompound
    }.getOrNull()

    /**
     * Compara [vivo] con [nuevo] (mismo UUID) y decide cómo dejarlo igual al de la BD.
     * @throws IllegalStateException si no se pudo comparar, o si el Pokémon está afuera.
     */
    private fun decidir(vivo: Any, nuevo: Any, registries: Any): Accion {
        val a = nbtDe(vivo, registries) ?: error("no se pudo serializar el Pokémon en vivo para compararlo")
        val b = nbtDe(nuevo, registries) ?: error("no se pudo serializar el Pokémon de la BD para compararlo")
        if (a == b) return Accion.NADA
        val leerEstado = getStateMethod ?: error("Pokemon.getState no existe en esta versión de Cobblemon")
        val estado = leerEstado.invoke(vivo)?.javaClass?.simpleName
        // Al entrar no puede haber Pokémon afuera salvo que el jugador lo haya sacado durante la
        // carga, y un /cg restore o un rollback sí pueden encontrarse uno peleando. Tocarle los datos
        // a una entidad viva (vida, nivel, especie) es buscarse problemas con la batalla y el cliente.
        if (estado == "SentOutState") error("el Pokémon está afuera; guárdalo en su Poké Ball y repite")
        val enPastura = runCatching { getTetheringIdMethod?.invoke(vivo) }.getOrNull() != null
        return if (estado == "ShoulderedState" || enPastura) Accion.COPIAR_EN_LUGAR else Accion.REEMPLAZAR
    }

    /** Copia los datos de [nuevo] sobre [vivo] sin reemplazar el objeto. Ver el bloque de arriba. */
    private fun copiarEnLugar(vivo: Any, nuevo: Any) {
        val copiar = copyFromMethod ?: error("Pokemon.copyFrom no existe en esta versión de Cobblemon")
        val leerEstado = getStateMethod ?: error("Pokemon.getState no existe en esta versión de Cobblemon")
        val fijarEstado = setStateMethod ?: error("Pokemon.setState no existe en esta versión de Cobblemon")
        val estado = leerEstado.invoke(vivo)
        copiar.invoke(vivo, nuevo)
        if (estado != null) fijarEstado.invoke(vivo, estado)
        // copyFrom de 1.8.0 hace potentialMarks.clear(); potentialMarks.addAll(other.marks):
        // llena las marcas POTENCIALES con las marcas del otro. Se corrige con las de verdad.
        runCatching {
            val leerMarcas = getPotentialMarksMethod ?: return@runCatching
            @Suppress("UNCHECKED_CAST")
            val destino = leerMarcas.invoke(vivo) as? MutableSet<Any?> ?: return@runCatching
            val origen = leerMarcas.invoke(nuevo) as? Set<*> ?: return@runCatching
            destino.clear()
            destino.addAll(origen)
        }.onFailure { e ->
            CobblemonGuard.logger.warn("[Pokémon] No se pudieron corregir las marcas potenciales tras copyFrom: ${e.message}")
        }
    }

    /** Equipo: devuelve cuántos slots cambiaron. Lanza si algo no se pudo hacer. */
    /**
     * @param esperaPokemon true si se sabe que el snapshot TIENE Pokémon (el llamador ya lo
     *   comprobó). Si aun así no se lee ninguno del store temporal, es que leer slots dejó de
     *   funcionar —y como el mismo método lee el store vivo, los dos "parecen vacíos" y la mezcla
     *   no haría nada sin avisar—.
     */
    fun mezclarParty(detached: Any, live: Any, registries: Any, esperaPokemon: Boolean): Int {
        val size = intMethod(live, "size") ?: error("no se pudo leer el tamaño del equipo")
        var cambios = 0
        var leidos = 0
        for (i in 0 until size) {
            val newP = getAtInt(detached, i)
            val curP = getAtInt(live, i)
            if (newP != null) leidos++
            when {
                newP == null && curP != null -> {
                    if (!removePokemon(live, curP)) error("no se pudo quitar el Pokémon del slot ${i + 1} del equipo")
                    cambios++
                }
                newP != null && !sameUuid(curP, newP) -> {
                    if (!setAtInt(live, i, newP)) error("no se pudo poner el Pokémon en el slot ${i + 1} del equipo")
                    cambios++
                }
                newP != null && curP != null -> when (decidir(curP, newP, registries)) {
                    Accion.NADA -> {}
                    Accion.REEMPLAZAR -> {
                        if (!setAtInt(live, i, newP)) error("no se pudo actualizar el Pokémon del slot ${i + 1} del equipo")
                        cambios++
                    }
                    Accion.COPIAR_EN_LUGAR -> { copiarEnLugar(curP, newP); cambios++ }
                }
            }
        }
        if (esperaPokemon && leidos == 0) error("el equipo guardado tiene Pokémon pero no se pudo leer ninguno")
        return cambios
    }

    /** PC: devuelve cuántos slots cambiaron. Lanza si algo no se pudo hacer. */
    fun mezclarPC(detached: Any, live: Any, registries: Any, esperaPokemon: Boolean): Int {
        val liveBoxes = boxesOf(live) ?: error("no se pudieron leer las cajas de la PC en vivo")
        val detBoxes = boxesOf(detached) ?: error("no se pudieron leer las cajas de la PC de la BD")
        // Si la BD tiene más cajas que este backend y hay Pokémon en las que sobran, aplicar solo
        // las que entran los dejaría afuera, y el próximo guardado (con menos cajas) los borraría
        // de la red. Es una diferencia de configuración entre backends: mejor que se note.
        for (b in liveBoxes.size until detBoxes.size) {
            val caja = detBoxes[b] ?: continue
            for (s in 0 until SLOTS_PER_BOX) {
                if (getAtInt(caja, s) != null) {
                    error("la PC guardada tiene Pokémon en la caja ${b + 1}, pero este backend tiene ${liveBoxes.size} " +
                        "cajas. Los backends tienen distinta cantidad de cajas de PC en la config de Cobblemon: igualala")
                }
            }
        }
        var cambios = 0
        var leidos = 0
        for (b in liveBoxes.indices) {
            val lb = liveBoxes[b] ?: continue
            val db = detBoxes.getOrNull(b)
            for (s in 0 until SLOTS_PER_BOX) {
                val newP = if (db != null) getAtInt(db, s) else null
                val curP = getAtInt(lb, s)
                if (newP != null) leidos++
                when {
                    newP == null && curP != null -> {
                        if (!removePokemon(live, curP)) error("no se pudo quitar el Pokémon de la caja ${b + 1}, slot ${s + 1}")
                        cambios++
                    }
                    newP != null && !sameUuid(curP, newP) -> {
                        if (!pcSetAt(live, b, s, newP)) error("no se pudo poner el Pokémon en la caja ${b + 1}, slot ${s + 1}")
                        cambios++
                    }
                    newP != null && curP != null -> when (decidir(curP, newP, registries)) {
                        Accion.NADA -> {}
                        Accion.REEMPLAZAR -> {
                            if (!pcSetAt(live, b, s, newP)) error("no se pudo actualizar el Pokémon de la caja ${b + 1}, slot ${s + 1}")
                            cambios++
                        }
                        Accion.COPIAR_EN_LUGAR -> { copiarEnLugar(curP, newP); cambios++ }
                    }
                }
            }
        }
        if (esperaPokemon && leidos == 0) error("la PC guardada tiene Pokémon pero no se pudo leer ninguno")
        return cambios
    }

    /** ¿El store en vivo tiene al menos un Pokémon? (PokemonStore es Iterable<Pokemon>) */
    fun storeHasAnyPokemon(store: Any): Boolean = runCatching {
        (store as? Iterable<*>)?.iterator()?.hasNext() == true
    }.getOrDefault(false)

    /** True si ambos Pokémon existen y tienen el mismo UUID. */
    private fun sameUuid(a: Any?, b: Any?): Boolean {
        if (a == null || b == null) return false
        val ua = pokemonUuid(a) ?: return false
        val ub = pokemonUuid(b) ?: return false
        return ua == ub
    }

    private fun pokemonUuid(pokemon: Any): Any? = runCatching {
        ReflectCache.method(pokemon.javaClass, "getUuid", 0)?.invoke(pokemon)
    }.getOrNull()

    private fun boxesOf(store: Any): List<*>? = runCatching {
        ReflectCache.method(store.javaClass, "getBoxes", 0)?.invoke(store) as? List<*>
    }.getOrNull()

    private fun getAtInt(target: Any, i: Int): Any? = runCatching {
        val method = ReflectCache.method(target.javaClass, "get", 1) {
            it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        method?.invoke(target, i)
    }.getOrNull()

    // Los tres devuelven si de verdad hicieron algo. Antes devolvían Unit y se tragaban todo:
    // si Cobblemon renombraba "set" o "remove", el ?. salteaba la llamada en silencio y la
    // mezcla informaba que había aplicado la PC sin haber tocado nada.

    private fun setAtInt(target: Any, i: Int, pokemon: Any): Boolean = runCatching {
        val method = ReflectCache.method(target.javaClass, "set", 2) {
            it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: return false
        method.invoke(target, i, pokemon)
        true
    }.getOrDefault(false)

    private fun removePokemon(store: Any, pokemon: Any): Boolean = runCatching {
        val method = ReflectCache.method(store.javaClass, "remove", 1) {
            it.parameterTypes[0] == pokemonClass
        } ?: return false
        // remove() devuelve si lo encontró y lo sacó; un false es que no se quitó nada.
        method.invoke(store, pokemon) as? Boolean ?: false
    }.getOrDefault(false)

    private fun pcSetAt(store: Any, box: Int, slot: Int, pokemon: Any): Boolean = runCatching {
        val posCls = pcPositionClass ?: return false
        val pos = pcPositionCtor?.newInstance(box, slot) ?: return false
        val method = ReflectCache.method(store.javaClass, "set", 2) {
            it.parameterTypes[0] == posCls
        } ?: return false
        method.invoke(store, pos, pokemon)
        true
    }.getOrDefault(false)

    private fun intMethod(target: Any, name: String): Int? = runCatching {
        ReflectCache.method(target.javaClass, name, 0)?.invoke(target) as? Int
    }.getOrNull()

    /**
     * Heurística barata: ¿el blob (NBT gzip de un store) contiene al menos un Pokémon?
     *
     * El NBT de cada Pokémon de Cobblemon incluye la etiqueta "Species". Si no aparece,
     * el store guardado está VACÍO. Se usa para NO pisar un PC/party con pokémon vivos
     * cuando la BD tiene un snapshot vacío (típico de datos migrados de la versión vieja).
     *
     * Ante cualquier error de lectura devuelve true (no bloquear el flujo normal).
     */
    fun blobHasPokemon(data: ByteArray): Boolean {
        return runCatching {
            val bytes = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(data)).use { it.readBytes() }
            val needle = "Species".toByteArray(Charsets.US_ASCII)
            indexOfSub(bytes, needle) >= 0
        }.getOrDefault(true)
    }

    private fun indexOfSub(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /**
     * Ordena [nbt] y [registries] según el orden real de parámetros del método.
     * Necesario porque el orden difiere entre clases (unas usan (nbt, registries)).
     */
    private fun orderArgs(paramTypes: Array<Class<*>>, nbt: NbtCompound, registries: Any): Array<Any> {
        return paramTypes.map { p ->
            if (p.isAssignableFrom(NbtCompound::class.java)) nbt else registries
        }.toTypedArray()
    }
}
