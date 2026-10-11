package com.cobblemonguard.sync

import com.cobblemonguard.CobblemonGuard
import net.minecraft.server.network.ServerPlayerEntity

/**
 * Registro central de todos los [DataSyncer]. Cada entrada es un adaptador fino sobre los
 * objetos de sincronización existentes (InventorySync, PokemonPCSync, ...).
 *
 * IMPORTANTE — el ORDEN importa al DESERIALIZAR:
 *   las mochilas (contenido en world-data) van ANTES que los accesorios (el ítem que las
 *   referencia). Por eso BACKPACKS aparece antes que ACCESSORIES en la lista.
 */
object SyncRegistry {

    private fun syncer(
        type: DataType,
        enabled: () -> Boolean,
        serialize: (ServerPlayerEntity) -> ByteArray?,
        deserialize: (ServerPlayerEntity, ByteArray, Boolean) -> Resultado
    ): DataSyncer = object : DataSyncer {
        override val type = type
        override fun isEnabled() = enabled()
        override fun serialize(player: ServerPlayerEntity) = serialize(player)
        override fun deserialize(player: ServerPlayerEntity, data: ByteArray, onlyIfEmpty: Boolean) =
            deserialize(player, data, onlyIfEmpty)
    }

    private val cfg get() = CobblemonGuard.config

    val syncers: List<DataSyncer> = listOf(
        syncer(DataType.INVENTORY, { cfg.syncInventory },
            InventorySync::serialize) { p, d, _ -> InventorySync.deserialize(p, d) },
        syncer(DataType.ENDERCHEST, { cfg.syncEnderChest },
            InventorySync::serializeEnderChest) { p, d, _ -> InventorySync.deserializeEnderChest(p, d) },
        syncer(DataType.PC, { cfg.syncCobblemonPC },
            PokemonPCSync::serialize) { p, d, o -> PokemonPCSync.deserialize(p, d, o) },
        syncer(DataType.PARTY, { cfg.syncCobblemonParty },
            PokemonPartySync::serialize) { p, d, o -> PokemonPartySync.deserialize(p, d, o) },
        syncer(DataType.BACKPACKS, { cfg.syncBackpackContents },
            BackpackSync::serialize) { p, d, _ -> BackpackSync.deserialize(p, d) },
        syncer(DataType.ACCESSORIES, { cfg.syncAccessories },
            AccessorySync::serialize) { p, d, _ -> AccessorySync.deserialize(p, d) },
        syncer(DataType.ECONOMY, { cfg.syncEconomy },
            EconomySync::serialize) { p, d, _ -> EconomySync.deserialize(p, d) },
    )
}
