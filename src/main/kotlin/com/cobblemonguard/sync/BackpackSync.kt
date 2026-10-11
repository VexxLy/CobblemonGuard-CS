package com.cobblemonguard.sync

import com.cobblemonguard.CobblemonGuard
import com.cobblemonguard.util.ReflectCache
import net.minecraft.component.ComponentType
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtElement
import net.minecraft.nbt.NbtHelper
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtSizeTracker
import net.minecraft.registry.Registries
import net.minecraft.server.network.ServerPlayerEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Vuelca a MySQL el CONTENIDO real de las mochilas de Sophisticated Backpacks.
 *
 * ¿Por qué hace falta?  SB NO guarda el contenido dentro del ítem: lo guarda en un
 * almacenamiento del mundo (BackpackStorage, un class_18/PersistentState) indexado
 * por un UUID (componente `sophisticatedcore:storage_uuid`) que lleva el propio ítem.
 *
 * Restaurar solo el ítem (con su UUID) funciona MIENTRAS el mundo tenga ese
 * BackpackStorage en disco. Pero si migras el mundo/servidor, el contenido no viaja.
 * Este sync copia {uuid -> contenidoNBT} a MySQL para portabilidad total.
 *
 * Estrategia:
 *  - Recolectamos los UUID de mochila leyendo el componente STORAGE_UUID de cada
 *    ItemStack que lleva el jugador (inventario + ender + accesorios equipados).
 *  - Para cada UUID pedimos su contenido a BackpackStorage y lo guardamos.
 *  - Escaneamos ese contenido en busca de mochilas ANIDADAS (mochila dentro de
 *    mochila) para incluirlas también.
 *
 * Toda la interacción con SB es vía reflexión (nombres NO ofuscados). Los objetos
 * de Minecraft (ItemStack, NbtCompound, ComponentType) sí se usan directo.
 */
object BackpackSync {

    private const val NBT_SIZE_LIMIT = 100L * 1024 * 1024

    // ── Reflexión perezosa de SB / sophisticatedcore ───────────────────────

    private val backpackStorageClass by lazy {
        runCatching { Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage") }.getOrNull()
    }

    /** ComponentType<UUID> de sophisticatedcore (STORAGE_UUID). */
    private val storageUuidComponent: ComponentType<*>? by lazy {
        runCatching {
            val cls = Class.forName("net.p3pp3rf1y.sophisticatedcore.init.ModCoreDataComponents")
            val supplier = cls.getField("STORAGE_UUID").get(null)
            supplier.javaClass.getMethod("get").invoke(supplier) as? ComponentType<*>
        }.getOrNull()
    }

    /** Id NBT del componente (ej. "sophisticatedcore:storage_uuid") para buscar anidadas. */
    private val storageUuidKey: String? by lazy {
        runCatching { storageUuidComponent?.let { Registries.DATA_COMPONENT_TYPE.getId(it)?.toString() } }.getOrNull()
    }

    val isLoaded: Boolean by lazy {
        (backpackStorageClass != null && storageUuidComponent != null).also { loaded ->
            if (loaded) CobblemonGuard.logger.info("[Mochilas] Volcado de contenido de Sophisticated Backpacks a MySQL activado.")
            else CobblemonGuard.logger.warn("[Mochilas] Sophisticated Backpacks no detectado — el contenido de mochilas no se vuelca a MySQL.")
        }
    }

    // ── Serializar ─────────────────────────────────────────────────────────

    fun serialize(player: ServerPlayerEntity): ByteArray? {
        if (!isLoaded) return null
        // BackpackStorage.get() SOLO es seguro en el hilo del servidor: fuera de él,
        // Sophisticated Backpacks cae a su rama cliente y toca net.minecraft.class_310
        // (MinecraftClient), que no existe en un server dedicado → crash/ClassNotFound.
        if (!player.server.isOnThread) {
            CobblemonGuard.logger.warn("[Mochilas] serialize() fuera del hilo del servidor — se omite el volcado de ${player.name.string}.")
            return null
        }
        return runCatching {
            val storage = backpackStorage() ?: return null

            // 1) UUIDs de mochilas que lleva el jugador (nivel superior)
            val uuids = LinkedHashSet<UUID>()
            for (stack in carriedStacks(player)) readUuid(stack)?.let { uuids.add(it) }
            if (uuids.isEmpty()) return null

            // 2) Contenido de cada UUID + escaneo de anidadas
            val collected = LinkedHashMap<UUID, NbtCompound>()
            val pending = ArrayDeque(uuids)
            val key = storageUuidKey
            while (pending.isNotEmpty()) {
                val uuid = pending.removeFirst()
                if (collected.containsKey(uuid)) continue
                val contents = getContents(storage, uuid) ?: continue
                collected[uuid] = contents
                if (key != null) {
                    val nested = HashSet<UUID>()
                    scanUuids(contents, key, nested)
                    nested.forEach { if (!collected.containsKey(it)) pending.add(it) }
                }
            }
            if (collected.isEmpty()) return null

            // 3) Serializar {uuid -> contenido}
            val root = NbtCompound()
            val list = NbtList()
            for ((uuid, contents) in collected) {
                val entry = NbtCompound()
                entry.put("uuid", NbtHelper.fromUuid(uuid))
                entry.put("data", contents)
                list.add(entry)
            }
            root.put("backpacks", list)

            val baos = ByteArrayOutputStream()
            NbtIo.writeCompressed(root, baos)
            CobblemonGuard.logger.debug("[Mochilas] Serializado ${player.name.string}: ${collected.size} mochilas.")
            baos.toByteArray()
        }.onFailure { e ->
            CobblemonGuard.logger.error("[Mochilas] Error serializando ${player.name.string}: ${e.message}")
        }.getOrNull()
    }

    // ── Deserializar ───────────────────────────────────────────────────────

    fun deserialize(player: ServerPlayerEntity, data: ByteArray): Resultado {
        if (!isLoaded) return Resultado.OMITIDO
        if (!player.server.isOnThread) {
            // Nadie deberia llamar esto fuera del hilo del servidor; si pasa, es un bug y las
            // mochilas quedan con su contenido local. Eso es un fallo, no una omision.
            CobblemonGuard.logger.error("[Mochilas] deserialize() fuera del hilo del servidor — no se restauran las de ${player.name.string}.")
            return Resultado.FALLO
        }
        return runCatching {
            val storage = backpackStorage() ?: run {
                // En algunos servidores BackpackStorage.get() cae en su rama de CLIENTE y no se
                // puede usar nunca (ver [disabled]). Ahi el contenido de las mochilas no se
                // sincroniza en este backend por diseño, y eso ya se aviso al desactivarlo.
                // Tratarlo como FALLO dejaria sin entrar a todo el que alguna vez tuvo una
                // mochila, porque esa columna nunca vuelve a NULL.
                //
                // Pero en una RED eso no alcanza: el ítem de la mochila llega por el inventario de
                // MySQL y su contenido sería el LOCAL de este backend. Si en otro backend la vació,
                // acá vuelve a estar llena, y lo que sacó también está en su inventario. Así que en
                // red es un fallo (con expulsarSiFallaAlCargar como salida), y en un solo servidor,
                // donde el contenido local es el único que hay, una omisión.
                if (disabled && !CobblemonGuard.config.crossServerSyncEnabled) return Resultado.OMITIDO
                CobblemonGuard.logger.error(
                    "[Mochilas] No se pudo obtener el BackpackStorage de Sophisticated Backpacks" +
                    (if (disabled) " (está desactivado en este servidor desde el primer error, ver arriba)." else ".")
                )
                return Resultado.FALLO
            }
            val root = NbtIo.readCompressed(ByteArrayInputStream(data), NbtSizeTracker.of(NBT_SIZE_LIMIT))
            val list = root.getList("backpacks", NbtList.COMPOUND_TYPE.toInt())
            var restored = 0
            var fallidas = 0
            for (i in 0 until list.size) {
                val entry = list.getCompound(i)
                val uuid = runCatching { NbtHelper.toUuid(entry.get("uuid")!!) }.getOrNull()
                if (uuid == null) {
                    // Una entrada sin uuid no se puede asociar a ninguna mochila: no hay nada
                    // que restaurar ni nada local que quede desactualizado por culpa de ella.
                    CobblemonGuard.logger.warn("[Mochilas] Entrada #$i de ${player.name.string} sin uuid valido — se ignora.")
                    continue
                }
                val contents = entry.getCompound("data")
                if (setContents(storage, uuid, contents)) restored++ else fallidas++
            }
            if (fallidas > 0) {
                CobblemonGuard.logger.error("[Mochilas] ${player.name.string}: $fallidas de ${restored + fallidas} mochilas no se pudieron restaurar.")
                return Resultado.FALLO
            }
            CobblemonGuard.logger.debug("[Mochilas] Restaurado ${player.name.string}: $restored mochilas.")
            Resultado.APLICADO
        }.getOrElse { e ->
            CobblemonGuard.logger.error("[Mochilas] Error deserializando ${player.name.string}: ${e.message}")
            Resultado.FALLO
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Todos los ItemStacks que lleva el jugador: inventario + ender + accesorios. */
    private fun carriedStacks(player: ServerPlayerEntity): List<net.minecraft.item.ItemStack> {
        val stacks = ArrayList<net.minecraft.item.ItemStack>()
        val inv = player.inventory
        for (i in 0 until inv.size()) inv.getStack(i).let { if (!it.isEmpty) stacks.add(it) }
        val ender = player.enderChestInventory
        for (i in 0 until ender.size()) ender.getStack(i).let { if (!it.isEmpty) stacks.add(it) }
        stacks.addAll(AccessorySync.equippedStacks(player))
        return stacks
    }

    /** Lee el UUID de contenido de un ItemStack (null si no es una mochila SB). */
    @Suppress("UNCHECKED_CAST")
    private fun readUuid(stack: net.minecraft.item.ItemStack): UUID? {
        val comp = (storageUuidComponent as? ComponentType<UUID>) ?: return null
        return runCatching { stack.get(comp) }.getOrNull()
    }

    /** Busca recursivamente valores de UUID bajo la clave [key] dentro de un NBT. */
    private fun scanUuids(nbt: NbtElement, key: String, out: MutableSet<UUID>) {
        when (nbt) {
            is NbtCompound -> {
                if (nbt.contains(key)) runCatching { out.add(NbtHelper.toUuid(nbt.get(key)!!)) }
                for (k in nbt.keys) nbt.get(k)?.let { scanUuids(it, key, out) }
            }
            is NbtList -> for (e in nbt) scanUuids(e, key, out)
            else -> {}
        }
    }

    // Si BackpackStorage.get() falla (en algunos servers cae a su rama de cliente y
    // toca net.minecraft.class_310), auto-desactivamos el volcado para no spamear el log.
    // El ítem de la mochila igual se guarda vía inventario/accesorios, y su contenido
    // persiste en el guardado del mundo — así que no se pierde nada en un solo servidor.
    @Volatile
    private var disabled = false

    private fun backpackStorage(): Any? {
        if (disabled) return null
        val cls = backpackStorageClass ?: return null
        return runCatching {
            ReflectCache.method(cls, "get", 0)?.invoke(null)
        }.onFailure { e ->
            disabled = true
            CobblemonGuard.logger.warn(
                "[Mochilas] BackpackStorage no accesible en este servidor (${e.cause?.message ?: e.message}). " +
                "Se DESACTIVA el volcado de contenido de mochilas a MySQL (el ítem sí se guarda; " +
                "el contenido persiste en el guardado del mundo). No afecta al resto de la sincronización."
            )
        }.getOrNull()
    }

    private fun getContents(storage: Any, uuid: UUID): NbtCompound? {
        return runCatching {
            ReflectCache.method(storage.javaClass, "getOrCreateBackpackContents", 1)
                ?.invoke(storage, uuid) as? NbtCompound
        }.getOrNull()
    }

    private fun setContents(storage: Any, uuid: UUID, contents: NbtCompound): Boolean {
        return runCatching {
            // Antes era "?.invoke(...); true": si el metodo no existia (Sophisticated Backpacks
            // lo renombro) el ?. lo salteaba y igual devolvia true. Cada mochila contaba como
            // restaurada sin que se hubiera escrito nada.
            val m = ReflectCache.method(storage.javaClass, "setBackpackContents", 2) ?: return false
            m.invoke(storage, uuid, contents)
            true
        }.getOrElse { false }
    }
}
