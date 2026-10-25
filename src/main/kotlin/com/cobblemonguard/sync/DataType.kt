package com.cobblemonguard.sync

/** Tipos de datos que se pueden guardar/restaurar por separado. */
enum class DataType { INVENTORY, ENDERCHEST, PC, PARTY, ACCESSORIES, BACKPACKS, ECONOMY, QUESTS, POKEDEX, MOCHILA }

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
        "bp", "backpack", "backpacks", "sophisticated"       -> setOf(DataType.BACKPACKS)
        // La mochila PROPIA de CobbleMMO (net.cobblemmo.mochila). "mochila" apuntaba antes a las
        // de Sophisticated Backpacks, que la red ya no usa.
        "mochila", "mochilas", "mcm", "mochilacm"           -> setOf(DataType.MOCHILA)
        "eco", "economia", "economy", "saldo", "saldos"      -> setOf(DataType.ECONOMY)
        "quests", "misiones", "ftbquests", "mis"             -> setOf(DataType.QUESTS)
        "pokedex", "dex"                                     -> setOf(DataType.POKEDEX)
        else -> null
    }
}
