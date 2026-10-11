package com.cobblemonguard.discord

import com.cobblemonguard.CobblemonGuard
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Envía notificaciones al canal de Discord via Webhook.
 * Usa HttpURLConnection (sin dependencias extra — Java estándar).
 *
 * ── Por qué hay una cola y no un hilo por mensaje ──────────────────────────────
 * Antes cada notificación creaba su propio `Thread`. Eso está bien para un aviso
 * suelto, pero el peor caso es justamente el incidente: si MySQL se cae con 40
 * jugadores online, `sendSyncError` se dispara una vez por guardado fallido y salían
 * 40 hilos y 40 requests HTTP casi simultáneos contra un endpoint que limita a unas
 * pocas por segundo. Resultado: 429 y notificaciones PERDIDAS, cuando más falta hacían.
 *
 * Ahora hay un solo hilo consumidor, con espaciado entre envíos, cola acotada (si se
 * llena se descarta y se cuenta, nunca se bloquea a quien notifica) y agregación de
 * errores de sincronización: el primero sale al instante y los siguientes de la misma
 * ventana se resumen en un solo mensaje.
 */
object DiscordWebhook {

    private val url get() = CobblemonGuard.config.discordWebhookUrl
    private val enabled get() = CobblemonGuard.config.discordEnabled && url.isNotBlank()

    // ── Colores de embed ──────────────────────────────────────────────────
    private const val GREEN  = 0x57F287   // éxito
    private const val RED    = 0xED4245   // error
    private const val YELLOW = 0xFEE75C   // advertencia
    private const val BLUE   = 0x5865F2   // info
    private const val ORANGE = 0xE67E22   // rollback

    // ── Cola de envío ─────────────────────────────────────────────────────

    private const val QUEUE_CAPACITY = 128
    private const val MIN_SPACING_MS = 250L          // ~4 mensajes/s como techo
    private const val SYNC_ERROR_WINDOW_MS = 15_000L // ventana de agregación de errores

    private val queue = LinkedBlockingQueue<String>(QUEUE_CAPACITY)
    private val dropped = AtomicInteger(0)

    @Volatile private var workerStarted = false

    private fun ensureWorker() {
        if (workerStarted) return
        synchronized(this) {
            if (workerStarted) return
            workerStarted = true
            Thread {
                while (true) {
                    try {
                        val payload = queue.poll(1, TimeUnit.SECONDS)
                        if (payload != null) {
                            post(payload)
                            Thread.sleep(MIN_SPACING_MS)
                        }
                        flushPendingSyncErrors()
                    } catch (_: InterruptedException) {
                        return@Thread
                    } catch (e: Exception) {
                        CobblemonGuard.logger.warn("[Discord] Error en el hilo de envío: ${e.message}")
                    }
                }
            }.also { it.isDaemon = true; it.name = "CobblemonGUARD-Discord" }.start()
        }
    }

    private fun enqueue(payload: String) {
        ensureWorker()
        if (!queue.offer(payload)) {
            val n = dropped.incrementAndGet()
            if (n == 1 || n % 50 == 0) {
                CobblemonGuard.logger.warn("[Discord] Cola de notificaciones llena — $n mensaje(s) descartado(s).")
            }
        }
    }

    // ── Métodos públicos ──────────────────────────────────────────────────

    fun sendConnectionError(error: String) {
        if (!enabled) return
        enqueue(embed(
            title = "❌ CobblemonGUARD — Error MySQL",
            description = "No se pudo conectar a MySQL. Los datos de los jugadores NO se están guardando.",
            color = RED,
            fields = listOf(field("Error", truncate(error, 500)))
        ))
    }

    fun sendFirstJoin(playerName: String, uuid: String) {
        if (!enabled || !CobblemonGuard.config.discordNotifyFirstJoin) return
        enqueue(embed(
            title = "👋 Nuevo jugador registrado",
            description = "**$playerName** ha ingresado por primera vez al servidor.",
            color = BLUE,
            fields = listOf(field("UUID", uuid))
        ))
    }

    // Agregación de errores de sincronización (ver doc de la clase).
    private val suppressedErrors = AtomicInteger(0)
    @Volatile private var lastSyncErrorAt = 0L
    @Volatile private var lastSyncErrorSample = ""

    fun sendSyncError(playerName: String, action: String, error: String) {
        if (!enabled || !CobblemonGuard.config.discordNotifySyncErrors) return
        val now = System.currentTimeMillis()
        synchronized(suppressedErrors) {
            if (now - lastSyncErrorAt < SYNC_ERROR_WINDOW_MS) {
                // Dentro de la ventana: no mandamos otro mensaje, solo contamos.
                suppressedErrors.incrementAndGet()
                lastSyncErrorSample = "$action / $playerName"
                return
            }
            lastSyncErrorAt = now
        }
        enqueue(embed(
            title = "⚠️ Error de sincronización",
            description = "Falló la operación **$action** para el jugador **$playerName**.",
            color = YELLOW,
            fields = listOf(field("Error", truncate(error, 500)))
        ))
    }

    /** Si venció la ventana y quedaron errores sin reportar, manda un resumen. */
    private fun flushPendingSyncErrors() {
        if (suppressedErrors.get() == 0) return
        val now = System.currentTimeMillis()
        val count: Int
        val sample: String
        synchronized(suppressedErrors) {
            if (now - lastSyncErrorAt < SYNC_ERROR_WINDOW_MS) return
            count = suppressedErrors.getAndSet(0)
            if (count == 0) return
            sample = lastSyncErrorSample
            lastSyncErrorAt = now
        }
        enqueue(embed(
            title = "⚠️ Errores de sincronización (resumen)",
            description = "Se produjeron **$count** errores más en los últimos ${SYNC_ERROR_WINDOW_MS / 1000}s.",
            color = YELLOW,
            fields = listOf(field("Último", truncate(sample, 200)))
        ))
    }

    fun sendRollback(playerName: String, timeAgo: String, adminName: String) {
        if (!enabled || !CobblemonGuard.config.discordNotifyRollbacks) return
        enqueue(embed(
            title = "🔄 Rollback ejecutado",
            description = "Se restauraron los datos de **$playerName** al estado de **$timeAgo**.",
            color = ORANGE,
            fields = listOf(
                field("Jugador", playerName),
                field("Restaurado a", timeAgo),
                field("Ejecutado por", adminName)
            )
        ))
    }

    fun sendAdminSaveAll(adminName: String, players: List<String>) {
        if (!enabled) return
        enqueue(embed(
            title = "💾 Guardado masivo (save all)",
            description = "Se guardaron **${players.size}** jugadores en MySQL.",
            color = BLUE,
            fields = listOf(
                field("Ejecutado por", adminName),
                field("Jugadores (${players.size})", playerList(players), inline = false)
            )
        ))
    }

    fun sendAdminBackupAll(adminName: String, players: List<String>) {
        if (!enabled) return
        enqueue(embed(
            title = "🗂️ Backup masivo (backup all)",
            description = "Se creó un backup de **${players.size}** jugadores.",
            color = BLUE,
            fields = listOf(
                field("Ejecutado por", adminName),
                field("Jugadores (${players.size})", playerList(players), inline = false)
            )
        ))
    }

    fun sendAdminSave(adminName: String, playerName: String) {
        if (!enabled) return
        enqueue(embed(
            title = "💾 Guardado manual",
            description = "Se guardaron los datos de **$playerName**.",
            color = BLUE,
            fields = listOf(field("Ejecutado por", adminName))
        ))
    }

    fun sendAdminBackup(adminName: String, playerName: String) {
        if (!enabled) return
        enqueue(embed(
            title = "🗂️ Backup manual",
            description = "Se creó un backup de **$playerName**.",
            color = BLUE,
            fields = listOf(field("Ejecutado por", adminName))
        ))
    }

    fun sendAdminRestore(adminName: String, playerName: String, tipo: String) {
        if (!enabled) return
        enqueue(embed(
            title = "♻️ Restauración manual",
            description = "Se restauraron datos de **$playerName** desde MySQL.",
            color = ORANGE,
            fields = listOf(
                field("Tipo", tipo),
                field("Ejecutado por", adminName)
            )
        ))
    }

    fun sendSavingToggle(adminName: String, active: Boolean) {
        if (!enabled) return
        enqueue(embed(
            title = if (active) "▶️ Guardado ACTIVADO" else "⏸️ Guardado PAUSADO",
            description = if (active) "El mod vuelve a guardar datos en MySQL."
                          else "El mod dejó de escribir en MySQL (la carga sigue activa).",
            color = if (active) GREEN else YELLOW,
            fields = listOf(field("Ejecutado por", adminName))
        ))
    }

    /** Une la lista de jugadores en un solo texto, truncado al límite de Discord. */
    private fun playerList(players: List<String>): String {
        if (players.isEmpty()) return "—"
        return truncate(players.joinToString(", "), 1000)
    }

    fun sendServerReady(host: String, database: String) {
        if (!enabled) return
        enqueue(embed(
            title = "🚀 CobblemonGUARD — Servidor listo",
            description = "El mod está activo y sincronizando datos con MySQL.",
            color = GREEN,
            fields = listOf(
                field("MySQL", "$host/$database"),
                // Antes acá había un "1.0.0" hardcodeado que quedó desactualizado dos
                // versiones. Ahora sale de los metadatos reales del mod.
                field("Versión", CobblemonGuard.modVersion)
            )
        ))
    }

    // ── Construcción del JSON ─────────────────────────────────────────────

    private data class Field(val name: String, val value: String, val inline: Boolean = true)

    private fun field(name: String, value: String, inline: Boolean = true) = Field(name, value, inline)

    private fun embed(
        title: String,
        description: String,
        color: Int,
        fields: List<Field> = emptyList()
    ): String {
        val fieldsJson = fields.joinToString(",") { f ->
            """{"name":"${escape(f.name)}","value":"${escape(f.value)}","inline":${f.inline}}"""
        }
        return """
            {
              "embeds": [{
                "title": "${escape(title)}",
                "description": "${escape(description)}",
                "color": $color,
                "timestamp": "${Instant.now()}",
                "footer": {"text": "CobblemonGUARD"},
                "fields": [$fieldsJson]
              }]
            }
        """.trimIndent()
    }

    // ── Envío HTTP ────────────────────────────────────────────────────────

    private fun post(payload: String) {
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("User-Agent", "CobblemonGUARD/${CobblemonGuard.modVersion}")
            conn.doOutput = true
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000

            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code == 429) {
                // Rate limit: respetamos el retry-after antes de seguir vaciando la cola.
                val retryAfter = conn.getHeaderField("Retry-After")?.toDoubleOrNull() ?: 1.0
                CobblemonGuard.logger.warn("[Discord] Rate limit (429) — esperando ${retryAfter}s.")
                Thread.sleep((retryAfter * 1000).toLong().coerceIn(500L, 10_000L))
            } else if (code != 204 && code != 200) {
                CobblemonGuard.logger.warn("[Discord] Respuesta inesperada del webhook: $code")
            }
            conn.disconnect()
        }.onFailure { e ->
            CobblemonGuard.logger.warn("[Discord] Error enviando notificación: ${e.message}")
        }
    }

    /**
     * Escapa una cadena para meterla en un string JSON.
     *
     * Antes solo cubría `\`, `"`, `\n` y `\r`. Cualquier OTRO carácter de control —un
     * tabulador dentro de un mensaje de error de MySQL, por ejemplo— generaba un JSON
     * inválido y Discord rechazaba el webhook entero. Ahora se escapan todos los
     * caracteres por debajo de 0x20 en su forma \\uXXXX.
     */
    private fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '"'  -> sb.append("\\\"")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' '   -> sb.append("\\u").append(String.format("%04x", c.code))
                else      -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun truncate(s: String, max: Int) = if (s.length > max) s.take(max) + "..." else s
}
