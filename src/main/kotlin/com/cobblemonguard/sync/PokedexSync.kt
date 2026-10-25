package com.cobblemonguard.sync

import com.cobblemonguard.CobblemonGuard
import com.cobblemonguard.util.ReflectCache
import com.mojang.serialization.Codec
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtElement
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtSizeTracker
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Sincroniza la POKÉDEX de Cobblemon (vistos, capturados, formas, variocolor, géneros...) entre
 * backends, con el mismo flujo que el equipo y la PC: se guarda al salir / auto-save / apagado y se
 * aplica al entrar, protegida por el lock entre servidores.
 *
 * Sin esto cada backend tenía su propia Pokédex en world/pokedex/, y el jugador veía un porcentaje
 * distinto según el servidor (y las recompensas de la Pokédex de cobblemmo-server medían cosas
 * distintas en cada uno).
 *
 * ── API real verificada con javap (Cobblemon 1.8.0) ──────────────────────────────────────
 *   Cobblemon.INSTANCE.getPlayerDataManager()                 → PlayerInstancedDataStoreManager
 *   PlayerInstancedDataStoreManager.getPokedexData(ServerPlayerEntity) → PokedexManager (vivo)
 *   PokedexManager.Companion.getCODEC()                       → Codec<PokedexManager>
 *   AbstractPokedexManager.getSpeciesRecords()                → Map<Identifier, SpeciesDexRecord>
 *   SpeciesDexRecord.initialize(AbstractPokedexManager, Identifier)
 *   AbstractPokedexManager.clearCalculatedValues()
 *   PlayerInstancedDataStoreTypes.INSTANCE.getPOKEDEX()       → tipo para saveSingle
 *   PlayerInstancedDataStoreManager.saveSingle(data, tipo) / syncAllToPlayer(player)
 *
 * Se serializa con el CODEC propio de Cobblemon (el mismo que usa para su archivo .nbt), así que
 * el formato es exactamente el suyo.
 *
 * Al aplicar se REEMPLAZAN los registros del manager vivo por los de MySQL (no se crea otro
 * manager: el que tiene Cobblemon en su cache es el que usan las capturas, el escáner y la
 * sincronización al cliente). Igual que en el equipo, un snapshot SIN registros no pisa nada.
 */
object PokedexSync {

    private const val NBT_SIZE_LIMIT = 20L * 1024 * 1024
    private const val CLAVE = "pokedex"

    private val cobblemonClass by lazy {
        runCatching { Class.forName("com.cobblemon.mod.common.Cobblemon") }.getOrNull()
    }
    private val pokedexManagerClass by lazy {
        runCatching { Class.forName("com.cobblemon.mod.common.api.pokedex.PokedexManager") }.getOrNull()
    }
    private val speciesRecordClass by lazy {
        runCatching { Class.forName("com.cobblemon.mod.common.api.pokedex.SpeciesDexRecord") }.getOrNull()
    }
    private val abstractManagerClass by lazy {
        runCatching { Class.forName("com.cobblemon.mod.common.api.pokedex.AbstractPokedexManager") }.getOrNull()
    }

    val isLoaded: Boolean by lazy {
        (pokedexManagerClass != null && codec() != null).also { ok ->
            if (ok) CobblemonGuard.logger.info("[Pokédex] Sincronización de la Pokédex de Cobblemon activada.")
            else CobblemonGuard.logger.warn("[Pokédex] No se encontró la API de Pokédex de Cobblemon — no se sincroniza.")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun codec(): Codec<Any>? = runCatching {
        val companion = pokedexManagerClass!!.getField("Companion").get(null)
        ReflectCache.method(companion.javaClass, "getCODEC", 0)!!.invoke(companion) as Codec<Any>
    }.getOrNull()

    private fun dataManager(): Any? = runCatching {
        val cobblemon = cobblemonClass!!.getField("INSTANCE").get(null)
        ReflectCache.method(cobblemon.javaClass, "getPlayerDataManager", 0)!!.invoke(cobblemon)
    }.getOrNull()

    private fun livePokedex(player: ServerPlayerEntity): Any? = runCatching {
        val manager = dataManager() ?: return null
        val metodo = manager.javaClass.methods.firstOrNull {
            it.name == "getPokedexData" && it.parameterCount == 1 &&
                it.parameterTypes[0].isAssignableFrom(ServerPlayerEntity::class.java)
        } ?: return null
        metodo.invoke(manager, player)
    }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    private fun records(manager: Any): MutableMap<Any?, Any?>? = runCatching {
        ReflectCache.method(manager.javaClass, "getSpeciesRecords", 0)?.invoke(manager) as? MutableMap<Any?, Any?>
    }.getOrNull()

    fun serialize(player: ServerPlayerEntity): ByteArray? {
        if (!isLoaded) return null
        return runCatching {
            val live = livePokedex(player) ?: return null
            val encoded: NbtElement = codec()!!.encodeStart(NbtOps.INSTANCE, live).result().orElse(null) ?: run {
                CobblemonGuard.logger.error("[Pokédex] Cobblemon no pudo codificar la Pokédex de ${player.name.string}.")
                return null
            }
            val nbt = NbtCompound()
            nbt.put(CLAVE, encoded)
            val baos = ByteArrayOutputStream()
            NbtIo.writeCompressed(nbt, baos)
            baos.toByteArray()
        }.onFailure { e ->
            CobblemonGuard.logger.error("[Pokédex] Error serializando ${player.name.string}: ${e.message}")
        }.getOrNull()
    }

    fun deserialize(player: ServerPlayerEntity, data: ByteArray): Resultado {
        if (!isLoaded) return Resultado.OMITIDO
        return runCatching {
            val nbt = NbtIo.readCompressed(ByteArrayInputStream(data), NbtSizeTracker.of(NBT_SIZE_LIMIT))
            val elemento = nbt.get(CLAVE) ?: return Resultado.OMITIDO
            val decodificado = codec()!!.parse(NbtOps.INSTANCE, elemento).result().orElse(null) ?: run {
                CobblemonGuard.logger.error("[Pokédex] Cobblemon no pudo leer la Pokédex guardada de ${player.name.string}.")
                return Resultado.FALLO
            }
            val nuevos = records(decodificado) ?: return Resultado.FALLO
            // Anti-cero: un snapshot vacío no borra la Pokédex que el jugador tenga acá.
            if (nuevos.isEmpty()) return Resultado.OMITIDO

            val live = livePokedex(player) ?: run {
                CobblemonGuard.logger.error("[Pokédex] No se pudo obtener la Pokédex viva de ${player.name.string} — ¿cambió la API de Cobblemon?")
                return Resultado.FALLO
            }
            val vivos = records(live) ?: return Resultado.FALLO

            val initialize = speciesRecordClass?.methods?.firstOrNull { it.name == "initialize" && it.parameterCount == 2 }
                ?: error("SpeciesDexRecord.initialize no existe en esta versión de Cobblemon")
            vivos.clear()
            for ((id, registro) in nuevos) {
                if (id !is Identifier || registro == null) continue
                initialize.invoke(registro, live, id)
                vivos[id] = registro
            }
            runCatching { abstractManagerClass?.getMethod("clearCalculatedValues")?.invoke(live) }

            // Que Cobblemon lo escriba en su .nbt de este backend y se lo mande al cliente.
            val manager = dataManager()
            if (manager != null) {
                runCatching {
                    val tipos = Class.forName("com.cobblemon.mod.common.api.storage.player.PlayerInstancedDataStoreTypes")
                    val instancia = tipos.getField("INSTANCE").get(null)
                    val tipoPokedex = ReflectCache.method(tipos, "getPOKEDEX", 0)!!.invoke(instancia)
                    manager.javaClass.methods.firstOrNull { it.name == "saveSingle" && it.parameterCount == 2 }
                        ?.invoke(manager, live, tipoPokedex)
                }.onFailure { e -> CobblemonGuard.logger.warn("[Pokédex] No se pudo guardar la Pokédex local de ${player.name.string}: ${e.message}") }
                runCatching {
                    manager.javaClass.methods.firstOrNull { it.name == "syncAllToPlayer" && it.parameterCount == 1 }
                        ?.invoke(manager, player)
                }.onFailure { e -> CobblemonGuard.logger.warn("[Pokédex] No se pudo reenviar la Pokédex a ${player.name.string}: ${e.message}") }
            }
            CobblemonGuard.logger.debug("[Pokédex] Aplicada la Pokédex de ${player.name.string} (${vivos.size} especies).")
            Resultado.APLICADO
        }.getOrElse { e ->
            CobblemonGuard.logger.error("[Pokédex] Error aplicando la Pokédex de ${player.name.string}: ${e.message}")
            Resultado.FALLO
        }
    }
}
