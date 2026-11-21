package com.cobblemonguard.sync

import com.cobblemonguard.CobblemonGuard
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtSizeTracker
import net.minecraft.server.network.ServerPlayerEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Sincroniza el EQUIPO de 6 Pokémon del jugador (party).
 *
 * En Cobblemon la party es un almacenamiento independiente de la PC:
 *   PokemonStoreManager.getParty(player) -> PlayerPartyStore
 *
 * Usa el store completo (PartyStore.saveToNBT / loadFromNBT) vía [CobblemonStorage].
 */
object PokemonPartySync {

    private const val NBT_SIZE_LIMIT = 20L * 1024 * 1024

    fun serialize(player: ServerPlayerEntity): ByteArray? {
        if (!CobblemonStorage.isLoaded) return null
        return runCatching {
            val party = CobblemonStorage.getParty(player) ?: return null
            val registries = player.server.registryManager
            val nbt = CobblemonStorage.saveStore(party, registries) ?: return null
            CobblemonStorage.marcarGuardado(nbt)

            val baos = ByteArrayOutputStream()
            NbtIo.writeCompressed(nbt, baos)
            CobblemonGuard.logger.debug("[Party] Serializado equipo de ${player.name.string}.")
            baos.toByteArray()
        }.onFailure { e ->
            CobblemonGuard.logger.error("[Party] Error serializando ${player.name.string}: ${e.message}")
        }.getOrNull()
    }

    /**
     * @param onlyIfEmpty si true, NO toca el equipo si el jugador ya tiene pokémon vivos
     *   (caso ingreso normal EN SINGLE-SERVER: Cobblemon ya cargó su equipo del disco →
     *   no interferir, evita fantasmas/duplicados). En false (restore/rollback de admin,
     *   o ingreso normal cuando `crossServerSyncEnabled=true`. Ver PlayerEventHandler)
     *   SÍ se aplica MySQL, mezclando por UUID vía [CobblemonStorage.copyPartyToLive]
     *   (solo toca lo que difiere, no duplica). En redes multi-backend "ya tiene equipo"
     *   no implica estar al día (cada backend tiene su propio disco local) por eso ahí
     *   NO se debe pasar true.
     */
    fun deserialize(player: ServerPlayerEntity, data: ByteArray, onlyIfEmpty: Boolean = false): Resultado {
        // Sin Cobblemon en este backend no hay nada que aplicar: es una omisión, no un fallo.
        if (!CobblemonStorage.isLoaded) return Resultado.OMITIDO
        return runCatching {
            // PROTECCIÓN ANTI-CERO: si el snapshot de la BD está VACÍO, NO tocamos el
            // equipo del jugador (podría tener pokémon vivos en el guardado de Cobblemon).
            // Un vacío que GUARD guardó con su marca es real (el jugador dejó el equipo sin Pokémon)
            // y se aplica, salvo en single-server, donde manda el disco local. Sin la marca es un
            // snapshot viejo o migrado y no se toca nada.
            val conPokemon = CobblemonStorage.blobHasPokemon(data)
            if (!conPokemon && (onlyIfEmpty || !CobblemonStorage.blobVacioConfirmado(data))) {
                CobblemonGuard.logger.info("[Party] Snapshot de ${player.name.string} vacío en BD — se conserva su equipo actual.")
                return Resultado.OMITIDO
            }

            // Cobblemon SI está cargado (se chequeo arriba), así que no poder obtener el store
            // vivo es la reflexión rota, no una omisión.
            val live = CobblemonStorage.getParty(player) ?: run {
                CobblemonGuard.logger.error("[Party] No se pudo obtener el equipo viva de ${player.name.string} — ¿cambió la API de Cobblemon?")
                return Resultado.FALLO
            }

            // Ingreso normal: si ya tiene equipo, Cobblemon lo cargó del disco → no tocar.
            if (onlyIfEmpty && CobblemonStorage.storeHasAnyPokemon(live)) {
                CobblemonGuard.logger.debug("[Party] ${player.name.string} ya tiene equipo — se conserva (no se restaura desde BD).")
                return Resultado.OMITIDO
            }

            val registries = player.server.registryManager
            val nbt = NbtIo.readCompressed(ByteArrayInputStream(data), NbtSizeTracker.of(NBT_SIZE_LIMIT))

            // Cargamos en un store TEMPORAL y copiamos al vivo con set() (sincroniza al cliente).
            val detached = CobblemonStorage.newDetachedParty(player.uuid) ?: run {
                CobblemonGuard.logger.error("[Party] No se pudo crear el store temporal para ${player.name.string}.")
                return Resultado.FALLO
            }
            if (!CobblemonStorage.loadStore(detached, nbt, registries)) {
                CobblemonGuard.logger.error("[Party] Cobblemon no pudo leer el snapshot de ${player.name.string}.")
                return Resultado.FALLO
            }

            // Mezcla estricta: además de altas y bajas por UUID, actualiza el CONTENIDO de los
            // Pokémon que ya estaban (nivel, movimientos, objeto equipado). Lanza si no pudo,
            // y el getOrElse de abajo lo convierte en FALLO.
            val cambios = CobblemonStorage.mezclarParty(detached, live, registries, esperaPokemon = conPokemon)
            val changed = cambios > 0
            if (!conPokemon && changed) {
                CobblemonGuard.logger.info("[Party] ${player.name.string} dejó el equipo vacío en otro backend: se vacía también acá.")
            }
            if (changed) CobblemonStorage.sendPartyTo(live, player)
            CobblemonGuard.logger.debug("[Party] Restaurado equipo de ${player.name.string} (cambios=$changed).")
            Resultado.APLICADO
        }.getOrElse { e ->
            CobblemonGuard.logger.error("[Party] Error deserializando ${player.name.string}: ${e.message}")
            Resultado.FALLO
        }
    }
}
