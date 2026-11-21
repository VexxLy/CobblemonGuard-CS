package com.cobblemonguard.sync

import com.cobblemonguard.CobblemonGuard
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtSizeTracker
import net.minecraft.registry.RegistryWrapper
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.world.PersistentState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method

/**
 * Sincroniza el PROGRESO DE RCT (Radical Cobblemon Trainers) entre backends: entrenadores vencidos,
 * serie actual y anterior, series completadas. De eso sale el tope de nivel y lo que muestra la
 * Trainer Card, así que tiene que ser el mismo en toda la red.
 *
 * RCT lo guarda como PersistentState del overworld de cada servidor (un archivo por jugador,
 * clave TrainerPlayerData.filePath(player)). Se guarda con su propio writeNbt y se aplica con su
 * propio lector (TrainerPlayerData.Builder.of), y el objeto nuevo REEMPLAZA al del
 * PersistentStateManager: TrainerManager.getData(player) lo busca ahí cada vez, así que desde ese
 * momento RCT usa el de MySQL. Después se llama a sync() para que el cliente vea la Trainer Card.
 *
 * Importante: Builder.of descarta los ids de entrenador que ESTE servidor no conoce. Por eso el
 * datapack CobbleMMO-Cobbleverse va en todos los servidores (con los entrenadores apagados fuera de
 * las regiones): si no, un viaje al lobby borraría a Brock de la lista al volver a guardar.
 *
 * API verificada con javap contra rctmod-fabric-1.21.1-0.19.2-beta. Por reflexión: GUARD compila y
 * arranca sin RCT.
 *   RCTMod.getInstance().getTrainerManager().getData(PlayerEntity) -> TrainerPlayerData (PersistentState)
 *   TrainerPlayerData.filePath(PlayerEntity) -> String
 *   new TrainerPlayerData.Builder(PlayerEntity).of(NbtCompound, WrapperLookup) -> TrainerPlayerData
 *   TrainerPlayerData.sync()
 */
object RctSync {

    private const val NBT_SIZE_LIMIT = 4L * 1024 * 1024
    private const val CLAVE = "rct"

    private class Api(
        val getInstance: Method,
        val getTrainerManager: Method,
        val getData: Method,
        val filePath: Method,
        val builderCtor: java.lang.reflect.Constructor<*>,
        val builderOf: Method,
        val sync: Method,
    )

    private val api: Api? by lazy {
        runCatching {
            val rct = Class.forName("com.gitlab.srcmc.rctmod.api.RCTMod")
            val tm = Class.forName("com.gitlab.srcmc.rctmod.api.service.TrainerManager")
            val tpd = Class.forName("com.gitlab.srcmc.rctmod.api.data.save.TrainerPlayerData")
            val builder = Class.forName("com.gitlab.srcmc.rctmod.api.data.save.TrainerPlayerData\$Builder")
            Api(
                getInstance = rct.getMethod("getInstance"),
                getTrainerManager = rct.getMethod("getTrainerManager"),
                getData = tm.getMethod("getData", PlayerEntity::class.java),
                filePath = tpd.getMethod("filePath", PlayerEntity::class.java),
                builderCtor = builder.getConstructor(PlayerEntity::class.java),
                builderOf = builder.getMethod("of", NbtCompound::class.java, RegistryWrapper.WrapperLookup::class.java),
                sync = tpd.getMethod("sync"),
            )
        }.getOrNull()
    }

    val isLoaded: Boolean by lazy {
        (api != null).also { ok ->
            if (ok) CobblemonGuard.logger.info("[RCT] Radical Cobblemon Trainers detectado — se sincroniza el progreso de entrenadores.")
            else CobblemonGuard.logger.info("[RCT] Radical Cobblemon Trainers no está (o cambió su API) — no se sincroniza su progreso.")
        }
    }

    private fun datosVivos(player: ServerPlayerEntity): PersistentState? {
        val a = api ?: return null
        val manager = a.getTrainerManager.invoke(a.getInstance.invoke(null))
        return a.getData.invoke(manager, player) as? PersistentState
    }

    fun serialize(player: ServerPlayerEntity): ByteArray? {
        if (!isLoaded) return null
        return runCatching {
            val datos = datosVivos(player) ?: return null
            val interno = datos.writeNbt(NbtCompound(), player.server.registryManager)
            val nbt = NbtCompound()
            nbt.put(CLAVE, interno)
            val baos = ByteArrayOutputStream()
            NbtIo.writeCompressed(nbt, baos)
            baos.toByteArray()
        }.onFailure { e ->
            CobblemonGuard.logger.error("[RCT] Error serializando el progreso de ${player.name.string}: ${e.cause ?: e}")
        }.getOrNull()
    }

    fun deserialize(player: ServerPlayerEntity, data: ByteArray): Resultado {
        val a = api ?: return Resultado.OMITIDO
        return runCatching {
            val nbt = NbtIo.readCompressed(ByteArrayInputStream(data), NbtSizeTracker.of(NBT_SIZE_LIMIT))
            val interno = nbt.getCompound(CLAVE)
            if (interno.isEmpty) return Resultado.OMITIDO

            val leido = a.builderOf.invoke(a.builderCtor.newInstance(player), interno, player.server.registryManager)
            val nuevo = leido as? PersistentState ?: run {
                CobblemonGuard.logger.error("[RCT] RCT no pudo leer el progreso guardado de ${player.name.string}.")
                return Resultado.FALLO
            }
            val clave = a.filePath.invoke(null, player) as String
            val estados = player.server.overworld.persistentStateManager
            estados.set(clave, nuevo)
            nuevo.markDirty()

            // Comprobación: RCT tiene que devolver justo el objeto nuevo.
            if (datosVivos(player) !== nuevo) {
                CobblemonGuard.logger.error("[RCT] El progreso de ${player.name.string} no quedó aplicado (RCT sigue usando otro objeto).")
                return Resultado.FALLO
            }
            runCatching { a.sync.invoke(nuevo) }
                .onFailure { e -> CobblemonGuard.logger.warn("[RCT] No se pudo reenviar la Trainer Card a ${player.name.string}: ${e.cause ?: e}") }
            Resultado.APLICADO
        }.getOrElse { e ->
            CobblemonGuard.logger.error("[RCT] Error aplicando el progreso de ${player.name.string}: ${e.cause ?: e}")
            Resultado.FALLO
        }
    }
}
