package com.cobblemonguard.sync

import com.cobblemonguard.CobblemonGuard
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtSizeTracker
import net.minecraft.server.network.ServerPlayerEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Sincroniza la PC completa de Cobblemon (todas las cajas de almacenamiento).
 *
 * Usa el store completo (PCStore.saveToNBT / loadFromNBT) vía [CobblemonStorage],
 * el mismo mecanismo que Cobblemon usa para persistir a disco.
 *
 * NOTA: la PC es un almacenamiento SEPARADO del equipo de 6 (party). El equipo se
 * sincroniza aparte en [PokemonPartySync].
 *
 * Formato en MySQL: NBT comprimido producido por PCStore.saveToNBT.
 */
object PokemonPCSync {

    private const val NBT_SIZE_LIMIT = 100L * 1024 * 1024

    fun serialize(player: ServerPlayerEntity): ByteArray? {
        if (!CobblemonStorage.isLoaded) return null
        return runCatching {
            val pc = CobblemonStorage.getPC(player) ?: return null
            val registries = player.server.registryManager
            val nbt = CobblemonStorage.saveStore(pc, registries) ?: return null

            val baos = ByteArrayOutputStream()
            NbtIo.writeCompressed(nbt, baos)
            CobblemonGuard.logger.debug("[PC] Serializado ${player.name.string}.")
            baos.toByteArray()
        }.onFailure { e ->
            CobblemonGuard.logger.error("[PC] Error serializando ${player.name.string}: ${e.message}")
        }.getOrNull()
    }

    /**
     * @param onlyIfEmpty si true, NO toca la PC si el jugador ya tiene pokémon en ella
     *   (caso ingreso normal EN SINGLE-SERVER: Cobblemon ya la cargó del disco → no
     *   interferir, evita fantasmas/duplicados). En false (restore/rollback de admin, o
     *   ingreso normal cuando `crossServerSyncEnabled=true` — ver PlayerEventHandler)
     *   SÍ se aplica MySQL, mezclando por UUID vía [CobblemonStorage.copyPCToLive] (solo
     *   toca lo que difiere, no duplica). En redes multi-backend "ya tiene pokémon" no
     *   implica estar al día — cada backend tiene su propio disco local — por eso ahí NO
     *   se debe pasar true.
     */
    fun deserialize(player: ServerPlayerEntity, data: ByteArray, onlyIfEmpty: Boolean = false): Resultado {
        // Sin Cobblemon en este backend no hay nada que aplicar: es una omision, no un fallo.
        if (!CobblemonStorage.isLoaded) return Resultado.OMITIDO
        return runCatching {
            // PROTECCIÓN ANTI-CERO: si el snapshot de la BD está VACÍO, NO tocamos la PC
            // del jugador (podría tener pokémon vivos en el guardado de Cobblemon).
            if (!CobblemonStorage.blobHasPokemon(data)) {
                CobblemonGuard.logger.info("[PC] Snapshot de ${player.name.string} vacío en BD — se conserva su PC actual.")
                return Resultado.OMITIDO
            }

            // Cobblemon SI esta cargado (se chequeo arriba), asi que no poder obtener el store
            // vivo es la reflexion rota, no una omision.
            val live = CobblemonStorage.getPC(player) ?: run {
                CobblemonGuard.logger.error("[PC] No se pudo obtener la PC viva de ${player.name.string} — ¿cambio la API de Cobblemon?")
                return Resultado.FALLO
            }

            // Ingreso normal: si ya tiene PC con pokémon, Cobblemon la cargó del disco → no tocar.
            if (onlyIfEmpty && CobblemonStorage.storeHasAnyPokemon(live)) {
                CobblemonGuard.logger.debug("[PC] ${player.name.string} ya tiene PC — se conserva (no se restaura desde BD).")
                return Resultado.OMITIDO
            }

            val registries = player.server.registryManager
            val nbt = NbtIo.readCompressed(ByteArrayInputStream(data), NbtSizeTracker.of(NBT_SIZE_LIMIT))

            // Cargamos en un store TEMPORAL y copiamos al vivo con set() (sincroniza al cliente).
            val detached = CobblemonStorage.newDetachedPC(player.uuid) ?: run {
                CobblemonGuard.logger.error("[PC] No se pudo crear el store temporal para ${player.name.string}.")
                return Resultado.FALLO
            }
            if (!CobblemonStorage.loadStore(detached, nbt, registries)) {
                CobblemonGuard.logger.error("[PC] Cobblemon no pudo leer el snapshot de ${player.name.string}.")
                return Resultado.FALLO
            }

            // Mezcla estricta: además de altas y bajas por UUID, actualiza el CONTENIDO de los
            // Pokémon que ya estaban (nivel, movimientos, objeto equipado). Lanza si no pudo,
            // y el getOrElse de abajo lo convierte en FALLO.
            val cambios = CobblemonStorage.mezclarPC(detached, live, registries, esperaPokemon = true)
            val changed = cambios > 0
            if (changed) CobblemonStorage.sendTo(live, player)
            CobblemonGuard.logger.debug("[PC] Restaurado ${player.name.string} (cambios=$changed).")
            Resultado.APLICADO
        }.getOrElse { e ->
            CobblemonGuard.logger.error("[PC] Error deserializando ${player.name.string}: ${e.message}")
            Resultado.FALLO
        }
    }
}
