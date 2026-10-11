package com.cobblemonguard.sync

/** Tipos de datos que se pueden guardar/restaurar por separado. */
enum class DataType { INVENTORY, ENDERCHEST, PC, PARTY, ACCESSORIES, BACKPACKS, ECONOMY }

/** Utilidades para trabajar con conjuntos de [DataType] (usadas por los comandos). */
object SyncTypes {

    /** Todos los tipos (restauración completa). */
    val ALL: Set<DataType> = DataType.entries.toSet()

    /**
     * Convierte un texto de comando en un conjunto de tipos.
     * "all"/"todo" → todos; alias en español e inglés para cada tipo.
     * Devuelve null si el texto no es válido.
     */
    fun parse(input: String): Set<DataType>? = when (input.lowercase()) {
        "all", "todo", "todos"                               -> ALL
        "inv", "inventory", "inventario"                     -> setOf(DataType.INVENTORY)
        "ender", "enderchest", "cofreender"                  -> setOf(DataType.ENDERCHEST)
        "pc"                                                 -> setOf(DataType.PC)
        "party", "equipo", "team"                            -> setOf(DataType.PARTY)
        "acc", "accessories", "accesorios", "trinkets"       -> setOf(DataType.ACCESSORIES)
        "bp", "backpack", "backpacks", "mochila", "mochilas" -> setOf(DataType.BACKPACKS)
        "eco", "economia", "economy", "saldo", "saldos"      -> setOf(DataType.ECONOMY)
        else -> null
    }
}
