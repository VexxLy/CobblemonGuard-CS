package com.cobblemonguard.sync

import com.cobblemonguard.CobblemonGuard
import com.cobblemonguard.util.ReflectCache
import net.minecraft.entity.LivingEntity
import net.minecraft.inventory.SimpleInventory
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtSizeTracker
import net.minecraft.server.network.ServerPlayerEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Sincroniza los slots de accesorios equipados — donde viven las MOCHILAS de
 * Sophisticated Backpacks cuando el jugador las lleva puestas.
 *
 * El servidor COBBLEVERSE usa el mod **Accessories** (io.wispforest.accessories)
 * como proveedor de slots (con un compat layer para mods basados en Trinkets).
 *
 * ── POR QUÉ EL CÓDIGO ANTERIOR (TrinketsSync) NO FUNCIONABA ──────────────────
 * Llamaba por reflexión a métodos de Minecraft (getStack/setStack/size/encode…)
 * usando sus nombres Yarn. En un servidor de producción esos métodos tienen
 * nombres "intermediary" (method_5438, method_5447…), así que la reflexión por
 * nombre Yarn lanzaba NoSuchMethodException y NADA se guardaba.
 *
 * ── TÉCNICA CORRECTA ─────────────────────────────────────────────────────────
 * Usamos reflexión SOLO para cruzar la frontera de Accessories (cuyos nombres NO
 * se ofuscan). El objeto devuelto por getAccessories() es un SimpleInventory
 * (class_1277) de Minecraft, así que lo casteamos a la interfaz de Minecraft y
 * llamamos toNbtList/readNbtList NORMALMENTE — Loom las remapea a los nombres
 * intermediary correctos en producción.
 *
 * Nota sobre contenidos: SB guarda el CONTENIDO de la mochila en un almacenamiento
 * del mundo (BackpackStorage) indexado por el UUID que lleva el propio ítem. Al
 * restaurar el ítem de la mochila (con su UUID) el contenido se reencuentra solo.
 */
object AccessorySync {

    private const val NBT_SIZE_LIMIT = 50L * 1024 * 1024

    private val capabilityClass by lazy {
        runCatching { Class.forName("io.wispforest.accessories.api.AccessoriesCapability") }.getOrNull()
    }

    val isLoaded: Boolean by lazy {
        (capabilityClass != null).also { loaded ->
            if (loaded) CobblemonGuard.logger.info("[Accesorios] Soporte Accessories activado (mochilas equipadas se sincronizan).")
            else CobblemonGuard.logger.warn("[Accesorios] Accessories no detectado — accesorios/mochilas equipadas NO se sincronizan.")
        }
    }

    // ── Serializar ─────────────────────────────────────────────────────────

    fun serialize(player: ServerPlayerEntity): ByteArray? {
        if (!isLoaded) return null
        return runCatching {
            val capability = getCapability(player) ?: return null
            val containers = getContainers(capability) ?: return null
            val registries = player.server.registryManager

            val root = NbtCompound()
            val containerList = NbtList()

            for ((name, container) in containers) {
                if (container == null) continue
                val entry = NbtCompound()
                entry.putString("name", name.toString())
                getInventory(container, "getAccessories")?.let {
                    entry.put("items", it.toNbtList(registries))
                }
                getInventory(container, "getCosmeticAccessories")?.let {
                    entry.put("cosmetic", it.toNbtList(registries))
                }
                containerList.add(entry)
            }

            root.put("containers", containerList)
            val baos = ByteArrayOutputStream()
            NbtIo.writeCompressed(root, baos)
            CobblemonGuard.logger.debug("[Accesorios] Serializado ${player.name.string}: ${containerList.size} contenedores.")
            baos.toByteArray()
        }.onFailure { e ->
            CobblemonGuard.logger.error("[Accesorios] Error serializando ${player.name.string}: ${e.message}")
        }.getOrNull()
    }

    // ── Deserializar ───────────────────────────────────────────────────────

    fun deserialize(player: ServerPlayerEntity, data: ByteArray): Resultado {
        if (!isLoaded) return Resultado.OMITIDO
        return runCatching {
            // Accessories SI esta cargado (chequeado arriba): no obtener la capability o los
            // contenedores del jugador es la reflexion rota, y sus accesorios quedan los locales.
            val capability = getCapability(player) ?: run {
                CobblemonGuard.logger.error("[Accesorios] No se pudo obtener la capability de ${player.name.string}.")
                return Resultado.FALLO
            }
            val containers = getContainers(capability) ?: run {
                CobblemonGuard.logger.error("[Accesorios] No se pudieron obtener los contenedores de ${player.name.string}.")
                return Resultado.FALLO
            }
            val registries = player.server.registryManager

            val root = NbtIo.readCompressed(ByteArrayInputStream(data), NbtSizeTracker.of(NBT_SIZE_LIMIT))
            val containerList = root.getList("containers", NbtList.COMPOUND_TYPE.toInt())
            var restored = 0

            for (i in 0 until containerList.size) {
                val entry = containerList.getCompound(i)
                val name = entry.getString("name")
                val container = containers[name] ?: continue

                getInventory(container, "getAccessories")?.let { inv ->
                    if (entry.contains("items")) {
                        clearInventory(inv)
                        inv.readNbtList(entry.getList("items", NbtList.COMPOUND_TYPE.toInt()), registries)
                        restored++
                    }
                }
                getInventory(container, "getCosmeticAccessories")?.let { inv ->
                    if (entry.contains("cosmetic")) {
                        clearInventory(inv)
                        inv.readNbtList(entry.getList("cosmetic", NbtList.COMPOUND_TYPE.toInt()), registries)
                    }
                }
                markChanged(container)
            }

            updateContainers(capability)
            CobblemonGuard.logger.debug("[Accesorios] Restaurado ${player.name.string}: $restored contenedores.")
            Resultado.APLICADO
        }.getOrElse { e ->
            CobblemonGuard.logger.error("[Accesorios] Error deserializando ${player.name.string}: ${e.message}")
            Resultado.FALLO
        }
    }

    /**
     * Devuelve todos los ItemStacks equipados en slots de accesorios (normales y
     * cosméticos). Lo usa [BackpackSync] para detectar mochilas equipadas.
     */
    fun equippedStacks(player: ServerPlayerEntity): List<net.minecraft.item.ItemStack> {
        if (!isLoaded) return emptyList()
        return runCatching {
            val capability = getCapability(player) ?: return emptyList()
            val containers = getContainers(capability) ?: return emptyList()
            val result = ArrayList<net.minecraft.item.ItemStack>()
            for ((_, container) in containers) {
                if (container == null) continue
                for (method in listOf("getAccessories", "getCosmeticAccessories")) {
                    val inv = getInventory(container, method) ?: continue
                    for (i in 0 until inv.size()) {
                        val stack = inv.getStack(i)
                        if (!stack.isEmpty) result.add(stack)
                    }
                }
            }
            result
        }.getOrElse { emptyList() }
    }

    // ── Reflexión de Accessories ───────────────────────────────────────────

    /** AccessoriesCapability.get(LivingEntity) — estático, puede devolver null. */
    private val getCapabilityMethod by lazy {
        runCatching { capabilityClass?.getMethod("get", LivingEntity::class.java) }.getOrNull()
    }

    private fun getCapability(player: ServerPlayerEntity): Any? {
        val method = getCapabilityMethod ?: return null
        return runCatching { method.invoke(null, player) }.getOrNull()
    }

    /** capability.getContainers(): Map<String, AccessoriesContainer> */
    @Suppress("UNCHECKED_CAST")
    private fun getContainers(capability: Any): Map<*, *>? {
        return runCatching {
            ReflectCache.method(capability.javaClass, "getContainers", 0)
                ?.invoke(capability) as? Map<*, *>
        }.getOrNull()
    }

    /**
     * container.getAccessories()/getCosmeticAccessories() devuelve un
     * ExpandedSimpleContainer que ES un SimpleInventory de Minecraft.
     * Lo casteamos para poder usar toNbtList/readNbtList remapeados por Loom.
     */
    private fun getInventory(container: Any, methodName: String): SimpleInventory? {
        return runCatching {
            ReflectCache.method(container.javaClass, methodName, 0)
                ?.invoke(container) as? SimpleInventory
        }.getOrNull()
    }

    private fun clearInventory(inv: SimpleInventory) {
        for (i in 0 until inv.size()) inv.setStack(i, net.minecraft.item.ItemStack.EMPTY)
    }

    private fun markChanged(container: Any) {
        runCatching {
            ReflectCache.method(container.javaClass, "markChanged", 0)?.invoke(container)
        }
    }

    private fun updateContainers(capability: Any) {
        runCatching {
            ReflectCache.method(capability.javaClass, "updateContainers", 0)?.invoke(capability)
        }
    }
}
