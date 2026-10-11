package com.cobblemonguard.sync

import net.minecraft.server.network.ServerPlayerEntity

/**
 * Punto único para serializar/aplicar TODOS los datos de un jugador, recorriendo
 * [SyncRegistry]. Reemplaza los bloques duplicados que antes existían en 5 sitios.
 *
 * Todas las llamadas deben hacerse en el HILO DEL SERVIDOR.
 */
object SyncService {

    /** Serializa todos los tipos habilitados. Devuelve un mapa tipo → bytes (o null). */
    fun serialize(player: ServerPlayerEntity): Map<DataType, ByteArray?> =
        SyncRegistry.syncers.associate { s ->
            s.type to (if (s.isEnabled()) s.serialize(player) else null)
        }

    /**
     * Aplica al jugador los datos del mapa, filtrando por [types].
     *
     * Recorre TODOS los tipos aunque uno falle: si el inventario se aplicó y la PC no, igual
     * conviene que lo demás quede consistente, y además el que llama necesita la lista
     * completa de fallos para decirle al admin qué revisar.
     *
     * @param preserveLivePokemon en el ingreso normal (true): no pisa PC/party si el
     *   jugador ya tiene pokémon vivos (evita fantasmas). En restore/rollback: false.
     * @return el resultado de cada tipo que se intentó aplicar. Los tipos deshabilitados,
     *   fuera de [types] o sin blob no aparecen.
     */
    fun apply(
        player: ServerPlayerEntity,
        data: Map<DataType, ByteArray?>,
        types: Set<DataType> = SyncTypes.ALL,
        preserveLivePokemon: Boolean = false
    ): Map<DataType, Resultado> {
        val resultados = LinkedHashMap<DataType, Resultado>()
        for (s in SyncRegistry.syncers) {
            if (s.type !in types || !s.isEnabled()) continue
            val bytes = data[s.type] ?: continue
            resultados[s.type] = try {
                s.deserialize(player, bytes, preserveLivePokemon)
            } catch (e: Exception) {
                // Defensa extra: todas las implementaciones atrapan lo suyo, pero un syncer
                // nuevo que se olvide de hacerlo no puede cortar el recorrido de los demás.
                com.cobblemonguard.CobblemonGuard.logger.error(
                    "[Sync] ${s.type} lanzó al aplicar a ${player.name.string}: ${e.message}", e)
                Resultado.FALLO
            }
        }
        return resultados
    }

    /** Los tipos que fallaron, en el orden en que se aplicaron. */
    fun fallidos(resultados: Map<DataType, Resultado>): List<DataType> =
        resultados.filterValues { it == Resultado.FALLO }.keys.toList()
}
