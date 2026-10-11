package com.cobblemonguard.database

import com.cobblemonguard.sync.DataType

/**
 * Mapeo entre las columnas de la BD y los [DataType]. Centralizado en un solo lugar para
 * que agregar/quitar un tipo no implique tocar múltiples firmas de SQL.
 *
 * Nota: la columna `trinkets` corresponde a los ACCESORIOS equipados.
 */

fun PlayerDataRepository.PlayerData.toBlobMap(): Map<DataType, ByteArray?> = mapOf(
    DataType.INVENTORY   to inventory,
    DataType.ENDERCHEST  to enderChest,
    DataType.PC          to cobblemonPc,
    DataType.PARTY       to cobblemonParty,
    DataType.ACCESSORIES to trinkets,
    DataType.BACKPACKS   to backpacks,
    DataType.ECONOMY     to economy
)

fun BackupRepository.BackupEntry.toBlobMap(): Map<DataType, ByteArray?> = mapOf(
    DataType.INVENTORY   to inventory,
    DataType.ENDERCHEST  to enderChest,
    DataType.PC          to cobblemonPc,
    DataType.PARTY       to cobblemonParty,
    DataType.ACCESSORIES to trinkets,
    DataType.BACKPACKS   to backpacks,
    DataType.ECONOMY     to economy
)
