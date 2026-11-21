package com.cobblemonguard.database

import com.cobblemonguard.CobblemonGuard
import com.zaxxer.hikari.HikariDataSource
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Registro de cuelgues de la base de datos (logs/cobblemonguard-db.log).
 *
 * Para qué: la MariaDB compartida del hosting se queda colgada cada tanto. Con esto queda escrito
 * CUÁNDO empezó, CUÁNTO duró y cómo estaba el pool en ese momento, con la hora exacta, para poder
 * mostrárselo al hosting y separar "la base no responde" de "el mod hace algo mal".
 *
 * Dos cosas:
 * - Cada préstamo de conexión del pool principal se mide ([medirPrestamo]); si tarda más que el
 *   umbral, se anota con el estado del pool (activas, libres, esperando, total).
 * - Una sonda cada N segundos pide una conexión y corre `SELECT 1` con su propio hilo. Si tarda
 *   más que el umbral o falla, se considera que la base está colgada y se anota el inicio; cuando
 *   vuelve a responder rápido, se anota el fin con la duración total.
 *
 * Config (config.json de GUARD): dbMonitorUmbralMs (5000; 0 apaga todo) y dbMonitorSondaSegundos (30).
 * También se escribe una línea WARN en el log del servidor al empezar y al terminar cada cuelgue.
 */
object DbMonitor {

    private val HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val archivo: Path = Paths.get("logs", "cobblemonguard-db.log")
    private val escritor = Executors.newSingleThreadExecutor { r -> Thread(r, "GUARD-DbMonitor-log").apply { isDaemon = true } }
    private var sonda: ScheduledExecutorService? = null

    /** Momento (ms) en que empezó el cuelgue actual; 0 = la base responde bien. */
    private val colgadaDesde = AtomicLong(0)
    private val peorDelCuelgue = AtomicLong(0)

    private val umbralMs: Long get() = CobblemonGuard.config.dbMonitorUmbralMsEffective.toLong()

    fun iniciar(ds: HikariDataSource) {
        if (umbralMs <= 0) {
            CobblemonGuard.logger.info("[DbMonitor] Apagado (dbMonitorUmbralMs = 0).")
            return
        }
        val cada = CobblemonGuard.config.dbMonitorSondaSegundosEffective.coerceAtLeast(5).toLong()
        val s = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "GUARD-DbMonitor-sonda").apply { isDaemon = true } }
        s.scheduleWithFixedDelay({ sondear(ds) }, cada, cada, TimeUnit.SECONDS)
        sonda = s
        escribir("INICIO monitor: umbral ${umbralMs} ms, sonda cada ${cada} s. ${estadoPool(ds)}")
        CobblemonGuard.logger.info("[DbMonitor] Activo: umbral $umbralMs ms, sonda cada $cada s -> $archivo")
    }

    fun detener() {
        sonda?.shutdownNow()
        sonda = null
        escritor.shutdown()
    }

    /** Mide cuánto tardó el pool en prestar una conexión (lo llama DatabaseManager.getConnection). */
    fun medirPrestamo(ms: Long, ds: HikariDataSource?, error: Throwable?) {
        val umbral = umbralMs
        if (umbral <= 0) return
        if (error != null) {
            escribir("PRÉSTAMO FALLIDO tras ${ms} ms en ${Thread.currentThread().name}: ${error.message}. ${estadoPool(ds)}")
        } else if (ms >= umbral) {
            escribir("PRÉSTAMO LENTO ${ms} ms en ${Thread.currentThread().name}. ${estadoPool(ds)}")
        }
    }

    private fun sondear(ds: HikariDataSource) {
        if (ds.isClosed) return
        val t0 = System.nanoTime()
        var error: Throwable? = null
        try {
            ds.connection.use { c -> c.createStatement().use { st -> st.queryTimeout = 120; st.executeQuery("SELECT 1").close() } }
        } catch (e: Throwable) {
            error = e
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val mala = error != null || ms >= umbralMs
        val ahora = System.currentTimeMillis()
        if (mala) {
            peorDelCuelgue.accumulateAndGet(ms) { a, b -> maxOf(a, b) }
            if (colgadaDesde.compareAndSet(0, ahora - ms)) {
                val motivo = error?.let { "error: ${it.message}" } ?: "SELECT 1 tardó $ms ms"
                escribir("CUELGUE INICIO ($motivo). ${estadoPool(ds)}")
                CobblemonGuard.logger.warn("[DbMonitor] La base no responde bien ($motivo). Detalle en $archivo")
            } else {
                escribir("CUELGUE SIGUE: SELECT 1 ${ms} ms${error?.let { " (${it.message})" } ?: ""}. ${estadoPool(ds)}")
            }
        } else {
            val desde = colgadaDesde.getAndSet(0)
            if (desde != 0L) {
                val dur = (ahora - desde) / 1000
                val peor = peorDelCuelgue.getAndSet(0)
                escribir("CUELGUE FIN: duró ~${dur} s (peor respuesta ${peor} ms). SELECT 1 ahora ${ms} ms. ${estadoPool(ds)}")
                CobblemonGuard.logger.warn("[DbMonitor] La base volvió a responder tras ~$dur s colgada.")
            }
        }
    }

    private fun estadoPool(ds: HikariDataSource?): String = runCatching {
        val mx = ds?.hikariPoolMXBean ?: return "Pool: sin datos"
        "Pool: activas=${mx.activeConnections} libres=${mx.idleConnections} esperando=${mx.threadsAwaitingConnection} total=${mx.totalConnections}"
    }.getOrDefault("Pool: sin datos")

    private fun escribir(linea: String) {
        val texto = "[${LocalDateTime.now().format(HORA)}] [${CobblemonGuard.config.serverId}] $linea\n"
        runCatching {
            escritor.execute {
                runCatching {
                    Files.createDirectories(archivo.parent)
                    Files.writeString(archivo, texto, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                }
            }
        }
    }
}
