package com.cobblemonguard.sync

import com.cobblemonguard.CobblemonGuard
import com.cobblemonguard.util.ReflectCache
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Acceso a la economía de Impactor vía REFLEXIÓN — mismo criterio que [CobblemonStorage]:
 * el mod sigue funcionando sin Impactor instalado, y no queda atado a una versión exacta
 * del jar (Impactor no publica en un maven con coordenadas estables).
 *
 * API real (VERIFICADA, no adivinada — confirmada con `javap` sobre el jar real de
 * Impactor en el proyecto hermano `cobblemmo-server`, ver
 * `net/cobblemmo/pokepad/gts/ImpactorGtsEconomy.java` y
 * `net/cobblemmo/economy/ArceusCoinEconomy.java`):
 *
 *   EconomyService.instance(): EconomyService                    — estático
 *   EconomyService.currencies(): <registry>                      — confirmado, se usa tal cual
 *   <registry>.currency(Key): Optional<Currency>                 — confirmado
 *   <registry>.registered(): Collection<?>                       — confirmado por nombre (comentario de ArceusCoin.java)
 *   EconomyService.account(Currency, UUID): CompletableFuture<Account>  — confirmado (`.join()` en el código real)
 *   Account.balance(): BigDecimal                                — confirmado
 *   Account.withdraw(BigDecimal): EconomyTransaction              — confirmado
 *   Account.deposit(BigDecimal): EconomyTransaction               — confirmado
 *   EconomyTransaction.successful(): Boolean                      — confirmado
 *
 * Lo único NO confirmado directamente es el nombre del getter de la key en `Currency`
 * (se asume `key()`, atenido a la convención sin prefijo "get" que usa el resto de la API
 * — `balance()`, `successful()`) y la forma exacta del retorno de `registered()`. Por eso
 * todo esto se trata como best-effort: si no se puede enumerar, [GuardConfig.economyCurrencyKeys]
 * es la vía explícita que SIEMPRE funciona (usa `currency(Key)`, 100% confirmado).
 *
 * NOTA DE RENDIMIENTO: los lookups de método pasan por [ReflectCache] — snapshotBalances
 * corre una vez por moneda por jugador en CADA guardado (quit/auto-save/backup), así que
 * sin cachear se re-escaneaba `Class.methods` repetidamente por segundo con varios
 * jugadores y varias monedas.
 */
object ImpactorEconomy {

    private val economyServiceClass by lazy {
        runCatching { Class.forName("net.impactdev.impactor.api.economy.EconomyService") }.getOrNull()
    }
    private val keyClass by lazy {
        runCatching { Class.forName("net.kyori.adventure.key.Key") }.getOrNull()
    }

    val isLoaded: Boolean by lazy { economyServiceClass != null && keyClass != null }

    // "instance()" es estático y siempre el mismo Method — se resuelve una sola vez.
    private val instanceMethod by lazy {
        runCatching { economyServiceClass?.getMethod("instance") }.getOrNull()
    }
    // "Key.key(String, String)" también es estático y estable.
    private val keyOfMethod by lazy {
        runCatching { keyClass?.getMethod("key", String::class.java, String::class.java) }.getOrNull()
    }

    private fun instance(): Any? = runCatching {
        instanceMethod?.invoke(null)
    }.onFailure { e ->
        CobblemonGuard.logger.warn("[Economy] Impactor no disponible: ${e.cause?.message ?: e.message}")
    }.getOrNull()

    private fun registry(service: Any): Any? = runCatching {
        ReflectCache.method(service.javaClass, "currencies", 0)?.invoke(service)
    }.getOrNull()

    private fun keyOf(fullKey: String): Any? {
        val method = keyOfMethod ?: return null
        val parts = fullKey.split(":", limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return runCatching { method.invoke(null, parts[0], parts[1]) }.getOrNull()
    }

    private fun currencyByKeyString(registry: Any, fullKey: String): Any? = runCatching {
        val keyObj = keyOf(fullKey) ?: return null
        val method = ReflectCache.method(registry.javaClass, "currency", 1) ?: return null
        val optional = method.invoke(registry, keyObj)
        (optional as? java.util.Optional<*>)?.orElse(null)
    }.getOrNull()

    private fun currencyKeyString(currency: Any): String? = runCatching {
        val m = ReflectCache.method(currency.javaClass, "key", 0) ?: return null
        m.invoke(currency)?.toString()
    }.getOrNull()

    /** Descubre monedas registradas enumerando el registry (best-effort, ver doc de la clase). */
    private fun discoverCurrencies(registry: Any): Map<String, Any> {
        val found = LinkedHashMap<String, Any>()
        val method = ReflectCache.method(registry.javaClass, "registered", 0)
            ?: ReflectCache.method(registry.javaClass, "all", 0)
            ?: return found
        val raw = runCatching { method.invoke(registry) }.getOrNull()
        val elements: List<Any> = (raw as? Collection<*>)?.filterNotNull() ?: return found
        for (el in elements) {
            val keyFromCurrency = currencyKeyString(el)
            if (keyFromCurrency != null) {
                found[keyFromCurrency] = el
                continue
            }
            // El elemento podría ser directamente un Key (Adventure) en vez de una Currency.
            val asKeyString = runCatching { el.toString() }.getOrNull()
            if (asKeyString != null && asKeyString.contains(":")) {
                currencyByKeyString(registry, asKeyString)?.let { found[asKeyString] = it }
            }
        }
        return found
    }

    private fun account(service: Any, currency: Any, uuid: UUID): Any? = runCatching {
        val method = ReflectCache.method(service.javaClass, "account", 2) ?: return null
        @Suppress("UNCHECKED_CAST")
        val future = method.invoke(service, currency, uuid) as? Future<Any> ?: return null
        future.get(5, TimeUnit.SECONDS)
    }.onFailure { e ->
        CobblemonGuard.logger.warn("[Economy] Error obteniendo cuenta para $uuid: ${e.cause?.message ?: e.message}")
    }.getOrNull()

    /**
     * Saldo actual de [uuid] en TODAS las monedas que se puedan enumerar en Impactor, más
     * las [extraKeys] configuradas explícitamente (para monedas que no aparezcan en la
     * enumeración, ej. si `registered()` no se comporta como se espera).
     */
    fun snapshotBalances(uuid: UUID, extraKeys: List<String>): Map<String, BigDecimal> {
        if (!isLoaded) return emptyMap()
        val service = instance() ?: return emptyMap()
        val reg = registry(service) ?: return emptyMap()

        val currencies = LinkedHashMap<String, Any>()
        currencies.putAll(discoverCurrencies(reg))
        for (k in extraKeys) {
            if (k in currencies) continue
            currencyByKeyString(reg, k)?.let { currencies[k] = it }
        }

        val balances = LinkedHashMap<String, BigDecimal>()
        for ((key, currency) in currencies) {
            runCatching {
                val acc = account(service, currency, uuid) ?: return@runCatching
                val bal = ReflectCache.method(acc.javaClass, "balance", 0)?.invoke(acc) as? BigDecimal ?: return@runCatching
                balances[key] = bal
            }.onFailure { e ->
                CobblemonGuard.logger.warn("[Economy] Error leyendo saldo de '$key' para $uuid: ${e.message}")
            }
        }
        return balances
    }

    /**
     * Fija el saldo de [uuid] en la moneda [fullKey] (formato "namespace:value") al valor
     * [target], vía deposit/withdraw de la diferencia (Impactor no expone un "set" directo).
     * @return [ResultadoSaldo.OK] si quedó aplicado (o ya estaba en ese valor);
     *   [ResultadoSaldo.MONEDA_AUSENTE] si esa moneda no existe en ESTE backend;
     *   [ResultadoSaldo.ERROR] si existía y no se pudo fijar.
     */
    fun setBalance(uuid: UUID, fullKey: String, target: BigDecimal): ResultadoSaldo {
        if (!isLoaded) return ResultadoSaldo.ERROR
        val service = instance() ?: return ResultadoSaldo.ERROR
        val reg = registry(service) ?: return ResultadoSaldo.ERROR
        // Búsqueda ESTRICTA. currencyByKeyString se traga cualquier excepción y devuelve null, y
        // eso no se puede distinguir de "la moneda no existe": si la API de Impactor cambiaba,
        // todas las monedas pasaban a "ausentes", el saldo no se aplicaba y nadie se enteraba.
        // Acá solo un Optional vacío, devuelto por Impactor, cuenta como moneda ausente.
        val keyObj = keyOf(fullKey) ?: return ResultadoSaldo.ERROR
        val buscar = ReflectCache.method(reg.javaClass, "currency", 1) ?: return ResultadoSaldo.ERROR
        val encontrada = try {
            buscar.invoke(reg, keyObj) as? java.util.Optional<*>
        } catch (e: Exception) {
            CobblemonGuard.logger.error("[Economy] Error buscando la moneda '$fullKey' en Impactor: ${e.cause?.message ?: e.message}")
            return ResultadoSaldo.ERROR
        } ?: return ResultadoSaldo.ERROR
        val currency = encontrada.orElse(null) ?: run {
            CobblemonGuard.logger.warn("[Economy] Moneda '$fullKey' no está registrada en Impactor en este servidor — se conserva sin aplicar.")
            return ResultadoSaldo.MONEDA_AUSENTE
        }
        return runCatching {
            val acc = account(service, currency, uuid) ?: return ResultadoSaldo.ERROR
            val balMethod = ReflectCache.method(acc.javaClass, "balance", 0) ?: return ResultadoSaldo.ERROR
            val current = balMethod.invoke(acc) as? BigDecimal ?: return ResultadoSaldo.ERROR
            val diff = target.subtract(current)
            if (diff.signum() == 0) return ResultadoSaldo.OK

            val methodName = if (diff.signum() > 0) "deposit" else "withdraw"
            val amount = diff.abs()
            val method = ReflectCache.method(acc.javaClass, methodName, 1) ?: return ResultadoSaldo.ERROR
            val tx = method.invoke(acc, amount) ?: return ResultadoSaldo.ERROR
            val ok = ReflectCache.method(tx.javaClass, "successful", 0)?.invoke(tx) as? Boolean ?: false
            if (ok) ResultadoSaldo.OK else ResultadoSaldo.ERROR
        }.onFailure { e ->
            CobblemonGuard.logger.error("[Economy] Error fijando saldo de '$fullKey' para $uuid: ${e.cause?.message ?: e.message}")
        }.getOrDefault(ResultadoSaldo.ERROR)
    }

    /**
     * "La moneda no existe acá" no es lo mismo que "no se pudo fijar el saldo", y tratarlas
     * igual dejaba a toda la red sin poder entrar: el blob trae una línea por CADA moneda
     * registrada en el backend que lo guardó, así que alcanzaba con que un lobby no tuviera
     * ArceusCoin (o que su registro asíncrono fallara al arrancar) para que ese backend
     * rechazara a todos.
     */
    enum class ResultadoSaldo { OK, MONEDA_AUSENTE, ERROR }
}
