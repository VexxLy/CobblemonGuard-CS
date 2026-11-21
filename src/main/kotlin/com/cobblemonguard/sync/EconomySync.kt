package com.cobblemonguard.sync

import com.cobblemonguard.CobblemonGuard
import net.minecraft.server.network.ServerPlayerEntity
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sincroniza los saldos de Impactor (CobbleDollars/primary, ArceusCoin, y cualquier otra
 * moneda registrada: ej. EventPoints, si se agrega su key en
 * [com.cobblemonguard.config.GuardConfig.economyCurrencyKeys]) vía [ImpactorEconomy].
 *
 * No-op silencioso si Impactor no está instalado (mismo criterio que el resto de [sync]:
 * ver [CobblemonStorage]).
 *
 * Formato en MySQL: texto plano UTF-8, una línea por moneda: "namespace:value=monto".
 * Elegido en vez de NBT/JSON porque son 1-3 pares clave/valor: no justifica esa maquinaria.
 */
object EconomySync {

    /**
     * Por jugador conectado: la ÚLTIMA línea buena de cada moneda (clave -> "clave=monto"), tal cual
     * llegó de MySQL al entrar y actualizada en cada guardado con lo que se pudo leer.
     *
     * Al guardar, el blob se arma con estas líneas y encima lo que se acaba de leer de Impactor. Así,
     * una moneda que en este momento NO se pudo leer (Impactor ya cerró su economía al apagar, el
     * bridge tardó más del límite, la moneda no existe en este backend) conserva su último valor bueno
     * en vez de desaparecer de la fila. Si desapareciera, al entrar a otro backend no habría línea que
     * aplicar y el jugador se quedaría con el saldo LOCAL de ese servidor (0 en el lobby), que después
     * se guardaría encima del bueno.
     *
     * Cubre también las monedas que este backend no tiene.
     */
    private val ultimas = ConcurrentHashMap<UUID, LinkedHashMap<String, String>>()

    /** Monedas que este backend SI pudo leer alguna vez en esta sesión del jugador (para avisar si dejan de leerse). */
    private val leidas = ConcurrentHashMap<UUID, MutableSet<String>>()

    /**
     * Jugadores a los que al entrar NO se les pudieron fijar los CobbleDollars de MySQL. Mientras estén
     * acá, su saldo local de CobbleDollars no se guarda encima del de MySQL (sería el saldo equivocado).
     */
    private val cdSinAplicar = ConcurrentHashMap.newKeySet<UUID>()

    /** Se llama cuando el jugador se fue y ya se serializó su último guardado. */
    fun olvidar(uuid: UUID) {
        ultimas.remove(uuid)
        leidas.remove(uuid)
        cdSinAplicar.remove(uuid)
    }

    /** "clave=monto, clave=monto" con los últimos saldos conocidos del jugador (para el log). */
    fun resumen(uuid: UUID): String =
        ultimas[uuid]?.values?.joinToString(", ") ?: "-"

    fun serialize(player: ServerPlayerEntity): ByteArray? {
        val uuid = player.uuid
        val previas = ultimas[uuid]
        val balances: Map<String, BigDecimal> = if (!ImpactorEconomy.isLoaded) emptyMap() else runCatching {
            ImpactorEconomy.snapshotBalances(uuid, CobblemonGuard.config.economyCurrencyKeys)
        }.onFailure { e ->
            CobblemonGuard.logger.error("[Economy] Error leyendo saldos de ${player.name.string}: ${e.message}")
        }.getOrDefault(emptyMap())

        val resultado = LinkedHashMap<String, String>()
        if (previas != null) resultado.putAll(previas)
        for ((k, v) in balances) resultado[k] = "$k=${v.toPlainString()}"
        // CobbleDollars se lee DIRECTO del jugador (es donde vive el saldo de verdad), no de Impactor.
        val cdLeido = if (CobbleDollarsDirecto.disponible) CobbleDollarsDirecto.leer(player) else null
        if (cdLeido != null && uuid in cdSinAplicar) {
            // Al entrar no se pudo fijar el saldo de MySQL: el local es el equivocado y no se guarda.
            CobblemonGuard.logger.warn(
                "[Economy] ${player.name.string}: no se guardan sus CobbleDollars locales ($cdLeido) porque al entrar " +
                "no se pudo aplicar el saldo de MySQL. Se conserva: ${previas?.get(CobbleDollarsDirecto.CLAVE) ?: "-"}."
            )
            // El snapshot de Impactor también trae el saldo local: se vuelve al de MySQL.
            previas?.get(CobbleDollarsDirecto.CLAVE)?.let { resultado[CobbleDollarsDirecto.CLAVE] = it }
        } else if (cdLeido != null) {
            resultado[CobbleDollarsDirecto.CLAVE] = "${CobbleDollarsDirecto.CLAVE}=$cdLeido"
        }
        val leidasAhora = if (cdLeido != null) balances.keys + CobbleDollarsDirecto.CLAVE else balances.keys

        // Avisar las monedas que esta sesión ya había leído y ahora no: se guarda su último valor bueno.
        val yaLeidas = leidas.computeIfAbsent(uuid) { ConcurrentHashMap.newKeySet() }
        val perdidas = yaLeidas.filter { it !in leidasAhora }
        if (perdidas.isNotEmpty()) {
            val conservadas = perdidas.joinToString(", ") { resultado[it] ?: "$it=?" }
            CobblemonGuard.logger.warn(
                "[Economy] No se pudo leer ${perdidas.joinToString(", ")} de ${player.name.string} al guardar " +
                "(¿economía de Impactor ya cerrada o bridge lento?). Se conserva el último valor bueno: $conservadas."
            )
        }
        yaLeidas.addAll(leidasAhora)

        if (resultado.isEmpty()) return null
        ultimas[uuid] = resultado
        return resultado.values.joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    /**
     * Aplica los saldos guardados. Igual que Inventario/EnderChest/Mochilas/Accesorios
     * (y a diferencia de PC/Party), SIEMPRE se aplica: no hay noción útil de "vacío" para
     * un saldo (0 es un valor legítimo, no "todavía no cargado").
     */
    fun deserialize(player: ServerPlayerEntity, data: ByteArray): Resultado {
        if (!ImpactorEconomy.isLoaded) return Resultado.OMITIDO
        return runCatching {
            val text = String(data, Charsets.UTF_8)
            var applied = 0
            var total = 0
            var rechazados = 0
            val todas = LinkedHashMap<String, String>()
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                total++
                val idx = line.lastIndexOf('=')
                if (idx <= 0) continue
                val key = line.substring(0, idx)
                val amount = runCatching { BigDecimal(line.substring(idx + 1)) }.getOrNull() ?: continue
                todas[key] = line.trim()
                // CobbleDollars se fija DIRECTO en el jugador y se comprueba (ver CobbleDollarsDirecto).
                if (key == CobbleDollarsDirecto.CLAVE && CobbleDollarsDirecto.disponible) {
                    if (CobbleDollarsDirecto.fijar(player, amount)) {
                        applied++
                        cdSinAplicar.remove(player.uuid)
                    } else {
                        rechazados++
                        cdSinAplicar.add(player.uuid)
                        CobblemonGuard.logger.error(
                            "[Economy] CobbleDollars de ${player.name.string} NO quedaron en ${amount.toPlainString()} " +
                            "(tiene ${CobbleDollarsDirecto.leer(player) ?: "?"})."
                        )
                    }
                    continue
                }
                // Una línea que se pudo leer pero que Impactor no aceptó es un saldo que quedó
                // con el valor local de este backend: eso es plata que se puede gastar dos veces.
                // Una moneda que este backend NO TIENE, en cambio, no es un fallo: acá no hay
                // ningún saldo local de esa moneda que se pueda gastar.
                when (ImpactorEconomy.setBalance(player.uuid, key, amount)) {
                    ImpactorEconomy.ResultadoSaldo.OK -> applied++
                    ImpactorEconomy.ResultadoSaldo.MONEDA_AUSENTE -> Unit
                    ImpactorEconomy.ResultadoSaldo.ERROR -> rechazados++
                }
            }
            // Punto de partida del próximo guardado: lo que trajo MySQL (todas las monedas, existan o no acá).
            ultimas[player.uuid] = todas
            if (rechazados > 0) {
                CobblemonGuard.logger.error("[Economy] Impactor rechazó $rechazados saldo(s) de ${player.name.string}.")
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
