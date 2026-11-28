package com.cobblemonguard.network

import com.cobblemonguard.CobblemonGuard
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisClientConfig
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.JedisPubSub
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Aviso de "lock liberado" por Redis/Valkey, con los locks en MySQL.
 *
 * Los locks siguen viviendo SOLO en la MySQL de la red ([MySqlLocks]). Esto no decide quién
 * tiene a un jugador: solo despierta al backend que está esperando para cargarlo.
 *
 * Sin esto, [CrossServerCoordinator.awaitRelease] pregunta a MySQL cada 100 ms → 500 ms hasta que
 * el otro backend suelta el lock. Con esto, el backend que suelta publica el UUID y el que espera
 * vuelve a mirar MySQL en ese momento. La consulta a MySQL sigue cada segundo de respaldo, por si
 * el aviso no llega (Valkey caído, corte, o el lock venció solo por TTL sin que nadie lo soltara).
 *
 * No hay "si Valkey se cae, los locks pasan a MySQL": tener los locks en dos lugares a la vez es
 * justo lo que abre la puerta a un dupeo (un backend mira uno, otro backend mira el otro). Por eso
 * Valkey acá solo acelera; si falla, todo sigue igual que hoy.
 *
 * Usa la conexión de `redisHost`, `redisPort`, `redisUsername`, `redisPassword` y `redisSsl`
 * del config de GUARD. Se activa con `avisosLockRedis: true` y `lockBackend: "mysql"`.
 */
object LockAvisos {

    private const val CANAL = "cobblemonguard:lock_liberado"

    /** Con el aviso andando, la consulta de respaldo a MySQL mientras se espera. */
    const val RESPALDO_MS = 1_000L

    @Volatile private var pool: JedisPool? = null
    @Volatile private var conectado = false
    @Volatile private var apagando = false
    @Volatile private var pubSub: JedisPubSub? = null
    private var hilo: Thread? = null
    private var envios: ExecutorService? = null

    private class Espera {
        @Volatile var version = 0L
        val monitor = Object()
    }

    /** Un backend esperando por jugador (no hay dos JOIN del mismo jugador a la vez en un backend). */
    private val esperas = ConcurrentHashMap<UUID, Espera>()

    private val cfg get() = CobblemonGuard.config

    /** true si la suscripción está viva: un lock liberado llega al instante. */
    val activo: Boolean get() = conectado

    @Synchronized
    fun iniciar() {
        if (hilo != null) return
        if (!cfg.crossServerSyncEnabled || !cfg.avisosLockRedisEffective || cfg.lockBackendEffective != "mysql") return
        if (cfg.redisPassword.isBlank()) {
            CobblemonGuard.logger.warn("[LockAvisos] avisosLockRedis está en true pero falta redisPassword: queda apagado.")
            return
        }
        apagando = false
        val direccion = HostAndPort(cfg.redisHost.trim(), cfg.redisPort)
        val cliente = clienteConfig()
        val pc = JedisPoolConfig().apply {
            maxTotal = 2
            maxIdle = 2
            minIdle = 0
            testOnBorrow = false
            testWhileIdle = false
        }
        pool = JedisPool(pc, direccion, cliente)
        envios = Executors.newSingleThreadExecutor { r ->
            Thread(r, "cobblemonguard-lock-avisos-envio").apply { isDaemon = true }
        }
        hilo = Thread({ bucle(direccion, cliente) }, "cobblemonguard-lock-avisos").apply {
            isDaemon = true
            start()
        }
        CobblemonGuard.logger.info("[LockAvisos] Conectando a ${cfg.redisHost}:${cfg.redisPort} para los avisos de lock liberado.")
    }

    private fun clienteConfig(): JedisClientConfig {
        val b = DefaultJedisClientConfig.builder()
            .ssl(cfg.redisSsl)
            .password(cfg.redisPassword)
            .timeoutMillis(3_000)
            .clientName("cobblemonguard-${cfg.serverId}")
        cfg.redisUsername?.takeIf { it.isNotBlank() }?.let { b.user(it.trim()) }
        return b.build()
    }

    private fun bucle(direccion: HostAndPort, cliente: JedisClientConfig) {
        var espera = 2_000L
        while (!apagando) {
            try {
                Jedis(direccion, cliente).use { j ->
                    val ps = object : JedisPubSub() {
                        override fun onSubscribe(channel: String?, subscribedChannels: Int) {
                            conectado = true
                            espera = 2_000L
                            CobblemonGuard.logger.info("[LockAvisos] Conectado: los cambios de servidor ya no esperan el sondeo de MySQL.")
                        }

                        override fun onMessage(channel: String?, message: String?) {
                            val uuid = runCatching { UUID.fromString(message) }.getOrNull() ?: return
                            despertar(uuid)
                        }
                    }
                    pubSub = ps
                    j.subscribe(ps, CANAL)
                }
            } catch (e: Exception) {
                if (apagando) break
                if (conectado) {
                    CobblemonGuard.logger.warn("[LockAvisos] Se cortó la conexión (${e.message}). Se sigue con el sondeo de MySQL.")
                }
            } finally {
                conectado = false
                pubSub = null
                // Los que esperaban vuelven a mirar MySQL ya, con el intervalo de siempre.
                esperas.keys.forEach { despertar(it) }
            }
            try {
                Thread.sleep(espera)
            } catch (e: InterruptedException) {
                break
            }
            espera = (espera * 2).coerceAtMost(60_000L)
        }
    }

    /** Publica que este backend soltó el lock de [uuid]. No bloquea; si falla, queda el sondeo. */
    fun publicar(uuid: UUID) {
        val p = pool ?: return
        val ex = envios ?: return
        if (!conectado) return
        runCatching {
            ex.execute {
                try {
                    p.resource.use { it.publish(CANAL, uuid.toString()) }
                } catch (e: Exception) {
                    CobblemonGuard.logger.debug("[LockAvisos] No se pudo avisar el lock liberado de $uuid: ${e.message}")
                }
            }
        }
    }

    /** Marca antes de mirar MySQL: si el aviso llega entre la consulta y la espera, no se pierde. */
    fun marca(uuid: UUID): Long = esperas.computeIfAbsent(uuid) { Espera() }.version

    /** Duerme hasta [ms] o hasta que llegue el aviso de [uuid], lo que pase primero. */
    fun esperar(uuid: UUID, marca: Long, ms: Long) {
        if (ms <= 0) return
        val e = esperas.computeIfAbsent(uuid) { Espera() }
        synchronized(e.monitor) {
            if (e.version != marca) return
            e.monitor.wait(ms)
        }
    }

    /** Ya cargó (o se rindió): se olvida la espera de [uuid]. */
    fun terminar(uuid: UUID) {
        esperas.remove(uuid)
    }

    private fun despertar(uuid: UUID) {
        val e = esperas[uuid] ?: return
        synchronized(e.monitor) {
            e.version++
            e.monitor.notifyAll()
        }
    }

    @Synchronized
    fun cerrar() {
        apagando = true
        conectado = false
        runCatching { pubSub?.unsubscribe() }
        runCatching { envios?.shutdown() }
        runCatching { pool?.close() }
        pool = null
        envios = null
        hilo = null
        esperas.keys.forEach { despertar(it) }
    }
}
