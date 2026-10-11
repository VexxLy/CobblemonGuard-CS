package com.cobblemonguard.sync

import com.cobblemonguard.CobblemonGuard
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtSizeTracker
import net.minecraft.server.network.ServerPlayerEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Serializa/deserializa el inventario completo del jugador.
 * Sophisticated Backpacks almacena sus contenidos en el NBT del propio ItemStack,
 * por lo que quedan incluidos automáticamente aquí.
 *
 * API real de Yarn 1.21.1 (verificado con javap):
 *   PlayerInventory.writeNbt(NbtList)         — sin registries
 *   PlayerInventory.readNbt(NbtList)          — sin registries
 *   SimpleInventory.toNbtList(WrapperLookup)  — CON registries
 *   NbtSizeTracker.of(long)                   — factory estático
 */
object InventorySync {

    private const val NBT_SIZE_LIMIT = 50L * 1024 * 1024 // 50 MB

    // ──────────────────────────────────────────────
    // Inventario principal (36 slots + armadura + offhand)
    // ──────────────────────────────────────────────

    fun serialize(player: ServerPlayerEntity): ByteArray? {
        return runCatching {
            val root = NbtCompound()
            val inventoryList = NbtList()
            player.inventory.writeNbt(inventoryList)   // writeNbt sin registries en Yarn 1.21.1
            root.put("inventory", inventoryList)
            compress(root)
        }.onFailure { e ->
            CobblemonGuard.logger.error("[Inventario] Error serializando ${player.name.string}: ${e.message}")
        }.getOrNull()
    }

    fun deserialize(player: ServerPlayerEntity, data: ByteArray): Resultado {
        return runCatching {
            val root = decompress(data)
            // serialize() escribe esta clave SIEMPRE. Si no esta, el blob no es nuestro o
            // esta roto, y aplicarlo "en silencio" dejaria al jugador con su inventario local.
            if (!root.contains("inventory")) {
                CobblemonGuard.logger.error("[Inventario] El blob de ${player.name.string} no tiene la clave 'inventory'.")
                return Resultado.FALLO
            }
            player.inventory.readNbt(root.getList("inventory", NbtList.COMPOUND_TYPE.toInt()))
            player.inventory.markDirty()
            player.playerScreenHandler.syncState()
            Resultado.APLICADO
        }.getOrElse { e ->
            CobblemonGuard.logger.error("[Inventario] Error deserializando ${player.name.string}: ${e.message}")
            Resultado.FALLO
        }
    }

    // ──────────────────────────────────────────────
    // Ender Chest (SimpleInventory — SÍ necesita registries)
    // ──────────────────────────────────────────────

    fun serializeEnderChest(player: ServerPlayerEntity): ByteArray? {
        return runCatching {
            val registries = player.server.registryManager
            val root = NbtCompound()
            root.put("ender_chest", player.enderChestInventory.toNbtList(registries))
            compress(root)
        }.onFailure { e ->
            CobblemonGuard.logger.error("[EnderChest] Error serializando ${player.name.string}: ${e.message}")
        }.getOrNull()
    }

    fun deserializeEnderChest(player: ServerPlayerEntity, data: ByteArray): Resultado {
        return runCatching {
            val registries = player.server.registryManager
            val root = decompress(data)
            if (!root.contains("ender_chest")) {
                CobblemonGuard.logger.error("[EnderChest] El blob de ${player.name.string} no tiene la clave 'ender_chest'.")
                return Resultado.FALLO
            }
            player.enderChestInventory.readNbtList(
                root.getList("ender_chest", NbtList.COMPOUND_TYPE.toInt()),
                registries
            )
            Resultado.APLICADO
        }.getOrElse { e ->
            CobblemonGuard.logger.error("[EnderChest] Error deserializando ${player.name.string}: ${e.message}")
            Resultado.FALLO
        }
    }

    // ──────────────────────────────────────────────
    // Helpers NBT comprimido
    // ──────────────────────────────────────────────

    private fun compress(nbt: NbtCompound): ByteArray {
        val baos = ByteArrayOutputStream()
        NbtIo.writeCompressed(nbt, baos)
        return baos.toByteArray()
    }

    private fun decompress(data: ByteArray): NbtCompound {
        // NbtSizeTracker.of(long) — nombre correcto en Yarn 1.21.1 (no ofBytes)
        return NbtIo.readCompressed(ByteArrayInputStream(data), NbtSizeTracker.of(NBT_SIZE_LIMIT))
    }
}
