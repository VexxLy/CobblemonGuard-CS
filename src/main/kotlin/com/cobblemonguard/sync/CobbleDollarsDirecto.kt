package com.cobblemonguard.sync

import com.cobblemonguard.CobblemonGuard
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.server.network.ServerPlayerEntity
import java.lang.reflect.Method
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Lee y fija el saldo de CobbleDollars DIRECTO en el jugador, sin pasar por Impactor ni por ningún
 * bridge. CobbleDollars guarda el saldo en el propio jugador (y en su NBT de cada servidor), así que
 * esto es lo único que de verdad cambia lo que el jugador tiene.
 *
 * Impactor + CobbleDollarsBridge no sirven para esto: el bridge lo aplica en otro hilo y sin
 * avisar si falla, así que GUARD diría "Datos restaurados", CobbleDollars seguiría con el saldo
 * local de ese servidor (0 en un servidor nuevo) y al salir se guardaría ese 0 encima del bueno.
 *
 * Por reflexión, para no atar la compilación a una versión de CobbleDollars.
 */
object CobbleDollarsDirecto {

    /** Clave con la que se guarda en MySQL (la misma de siempre: los datos viejos siguen valiendo). */
    const val CLAVE = "impactor:cobbledollars"

    private const val CLASE = "fr.harmex.cobbledollars.common.utils.extensions.PlayerExtensionKt"

    private val metodos: Pair<Method, Method>? by lazy {
        try {
            val c = Class.forName(CLASE)
            c.getMethod("getCobbleDollars", PlayerEntity::class.java) to
                c.getMethod("setCobbleDollars", PlayerEntity::class.java, BigInteger::class.java)
        } catch (e: ClassNotFoundException) {
            null
        } catch (e: Exception) {
            CobblemonGuard.logger.error("[Economy] La API de CobbleDollars cambió y no se puede usar directo: $e")
            null
        }
    }

    val disponible: Boolean get() = metodos != null

    /** Saldo actual del jugador, o null si CobbleDollars no está o no se pudo leer. */
    fun leer(player: ServerPlayerEntity): BigInteger? {
        val (get, _) = metodos ?: return null
        return try {
            get.invoke(null, player) as? BigInteger
        } catch (e: Exception) {
            CobblemonGuard.logger.error("[Economy] No se pudo leer CobbleDollars de ${player.name.string}: ${e.cause ?: e}")
            null
        }
    }

    /**
     * Fija el saldo y comprueba que quedó. Hay que llamarlo en el hilo del servidor.
     * @return true si al releer el saldo coincide con el pedido.
     */
    fun fijar(player: ServerPlayerEntity, monto: BigDecimal): Boolean {
        val (_, set) = metodos ?: return false
        val valor = monto.toBigInteger().max(BigInteger.ZERO)
        return try {
            set.invoke(null, player, valor)
            leer(player) == valor
        } catch (e: Exception) {
            CobblemonGuard.logger.error("[Economy] No se pudo fijar CobbleDollars de ${player.name.string}: ${e.cause ?: e}")
            false
        }
    }
}
