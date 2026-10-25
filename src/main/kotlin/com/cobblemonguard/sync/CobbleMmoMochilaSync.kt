package com.cobblemonguard.sync

import com.cobblemonguard.CobblemonGuard
import net.minecraft.server.network.ServerPlayerEntity
import java.lang.reflect.Method

/**
 * Sincroniza la MOCHILA PROPIA de CobbleMMO (contenido + nivel/exp de cada jugador) entre
 * backends, en la misma fila y con el mismo lock que el inventario.
 *
 * Por qué acá y no en una tabla de cobblemmo-server: la mochila y el inventario tienen que
 * viajar JUNTOS. Si un jugador saca 64 diamantes de la mochila y cambia de servidor, el
 * inventario (con los diamantes) y la mochila (sin ellos) se escriben en la misma sentencia; si
 * se escribieran por separado y fallara la segunda, el otro backend cargaría el inventario nuevo
 * con la mochila vieja y los diamantes existirían dos veces.
 *
 * El formato lo decide cobblemmo-server (net.cobblemmo.mochila.storage.MochilaRed): acá solo se
 * pasan los bytes. Todo por reflexión: GUARD no depende de CobbleMMO para compilar ni arrancar.
 *
 *   MochilaRed.serializar(ServerPlayerEntity) → byte[] (null = no hay nada que guardar)
 *   MochilaRed.aplicar(ServerPlayerEntity, byte[]) → int (0 APLICADO, 1 OMITIDO, 2 FALLO)
 */
object CobbleMmoMochilaSync {

    private const val CLASE = "net.cobblemmo.mochila.storage.MochilaRed"

    private val clase: Class<*>? by lazy { runCatching { Class.forName(CLASE) }.getOrNull() }

    private val mSerializar: Method? by lazy {
        runCatching { clase?.getMethod("serializar", ServerPlayerEntity::class.java) }.getOrNull()
    }
    private val mAplicar: Method? by lazy {
        runCatching { clase?.getMethod("aplicar", ServerPlayerEntity::class.java, ByteArray::class.java) }.getOrNull()
    }

    val isLoaded: Boolean by lazy {
        (mSerializar != null && mAplicar != null).also { ok ->
            if (ok) CobblemonGuard.logger.info("[MochilaCM] Mochila de CobbleMMO detectada — se sincroniza.")
            else CobblemonGuard.logger.info("[MochilaCM] cobblemmo-server no está (o es una versión sin MochilaRed) — no se sincroniza la mochila.")
        }
    }

    fun serialize(player: ServerPlayerEntity): ByteArray? {
        if (!isLoaded) return null
        return runCatching { mSerializar!!.invoke(null, player) as? ByteArray }
            .onFailure { e -> CobblemonGuard.logger.error("[MochilaCM] Error serializando la mochila de ${player.name.string}: ${e.cause ?: e}") }
            .getOrNull()
    }

    fun deserialize(player: ServerPlayerEntity, data: ByteArray): Resultado {
        if (!isLoaded) return Resultado.OMITIDO
        return runCatching {
            when (mAplicar!!.invoke(null, player, data) as? Int) {
                0 -> Resultado.APLICADO
                1 -> Resultado.OMITIDO
                else -> Resultado.FALLO
            }
        }.getOrElse { e ->
            CobblemonGuard.logger.error("[MochilaCM] Error aplicando la mochila de ${player.name.string}: ${e.cause ?: e}")
            Resultado.FALLO
        }
    }
}
