package com.cobblemonguard.sync

import com.cobblemonguard.CobblemonGuard
import net.minecraft.server.network.ServerPlayerEntity
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sincroniza los saldos de Impactor (CobbleDollars/primary, ArceusCoin, y cualquier otra
 * moneda registrada — ej. EventPoints, si se agrega su key en
 * [com.cobblemonguard.config.GuardConfig.economyCurrencyKeys]) vía [ImpactorEconomy].
 *
 * No-op silencioso si Impactor no está instalado (mismo criterio que el resto de [sync] —
 * ver [CobblemonStorage]).
 *
 * Formato en MySQL: texto plano UTF-8, una línea por moneda: "namespace:value=monto".
 * Elegido en vez de NBT/JSON porque son 1-3 pares clave/valor — no justifica esa maquinaria.
 */
object EconomySync {

    /**
     * Por jugador conectado: las líneas de su blob de MySQL cuyas monedas NO existen en este
     * backend, tal cual llegaron. Se vuelven a escribir en cada guardado.
     *
     * Sin esto, pasar por un backend que no tiene una moneda le BORRABA ese saldo en toda la red:
     * al guardar, el blob se arma solo con las monedas registradas acá, y como la columna se
     * reemplaza entera, la línea que faltaba desaparecía de MySQL. Le pasaba a cada jugador que
     * entrara a un lobby sin ArceusCoin, o a un backend donde su registro asíncrono falló.
     *
     * Mientras el jugador está acá, esos saldos no los puede cambiar nadie por esta vía (la moneda
     * no existe acá, y él no está en otro backend), así que conservarlos tal cual es lo correcto.
     */
    private val lineasAjenas = ConcurrentHashMap<UUID, List<String>>()

    /** Se llama cuando el jugador se fue y ya se serializó su último guardado. */
    fun olvidar(uuid: UUID) {
        lineasAjenas.remove(uuid)
    }

    fun serialize(player: ServerPlayerEntity): ByteArray? {
        if (!ImpactorEconomy.isLoaded) return null
        return runCatching {
            val extra = CobblemonGuard.config.economyCurrencyKeys
            val balances = ImpactorEconomy.snapshotBalances(player.uuid, extra)
            // Las monedas ajenas que mientras tanto se registraron acá ya vienen en balances,
            // con su valor de este backend: esas no se repiten.
            val ajenas = lineasAjenas[player.uuid].orEmpty()
                .filter { linea -> linea.substring(0, linea.lastIndexOf('=')) !in balances.keys }
            if (balances.isEmpty() && ajenas.isEmpty()) return null
            (balances.entries.map { (k, v) -> "$k=${v.toPlainString()}" } + ajenas)
                .joinToString("\n")
                .toByteArray(Charsets.UTF_8)
        }.onFailure { e ->
            CobblemonGuard.logger.error("[Economy] Error serializando saldos de ${player.name.string}: ${e.message}")
        }.getOrNull()
    }

    /**
     * Aplica los saldos guardados. Igual que Inventario/EnderChest/Mochilas/Accesorios
     * (y a diferencia de PC/Party), SIEMPRE se aplica — no hay noción útil de "vacío" para
     * un saldo (0 es un valor legítimo, no "todavía no cargado").
     */
    fun deserialize(player: ServerPlayerEntity, data: ByteArray): Resultado {
        if (!ImpactorEconomy.isLoaded) return Resultado.OMITIDO
        return runCatching {
            val text = String(data, Charsets.UTF_8)
            var applied = 0
            var total = 0
            var rechazados = 0
            val ajenas = ArrayList<String>()
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                total++
                val idx = line.lastIndexOf('=')
                if (idx <= 0) continue
                val key = line.substring(0, idx)
                val amount = runCatching { BigDecimal(line.substring(idx + 1)) }.getOrNull() ?: continue
                // Una linea que se pudo leer pero que Impactor no acepto es un saldo que quedo
                // con el valor local de este backend: eso es plata que se puede gastar dos veces.
                // Una moneda que este backend NO TIENE, en cambio, no es un fallo: aca no hay
                // ningun saldo local de esa moneda que se pueda gastar.
                when (ImpactorEconomy.setBalance(player.uuid, key, amount)) {
                    ImpactorEconomy.ResultadoSaldo.OK -> applied++
                    ImpactorEconomy.ResultadoSaldo.MONEDA_AUSENTE -> ajenas.add(line)
                    ImpactorEconomy.ResultadoSaldo.ERROR -> rechazados++
                }
            }
            if (ajenas.isEmpty()) lineasAjenas.remove(player.uuid) else lineasAjenas[player.uuid] = ajenas
            if (rechazados > 0) {
                CobblemonGuard.logger.error("[Economy] Impactor rechazo $rechazados saldo(s) de ${player.name.string}.")
                return Resultado.FALLO
            }
            if (total > 0) {
                CobblemonGuard.logger.debug("[Economy] Restaurados $applied/$total saldo(s) para ${player.name.string}.")
            }
            Resultado.APLICADO
        }.getOrElse { e ->
            CobblemonGuard.logger.error("[Economy] Error restaurando saldos de ${player.name.string}: ${e.message}")
            Resultado.FALLO
        }
    }
}
