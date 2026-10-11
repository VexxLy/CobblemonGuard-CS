package com.cobblemonguard.util

import com.cobblemonguard.CobblemonGuard
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity

/**
 * Permisos por NODO vía LuckPerms, con fallback a nivel de OP.
 *
 * ── El problema ────────────────────────────────────────────────────────────────
 * Todos los subcomandos estaban detrás de `hasPermissionLevel(2)`, es decir OP. Para
 * dejar que un moderador pueda hacer `/cg backups` o `/cg rollback` había que darle OP
 * completo, con todo lo que eso habilita en el resto del servidor (y en el resto de los
 * mods). En una red que ya usa LuckPerms para todo lo demás, eso es un agujero
 * innecesario.
 *
 * ── La solución ────────────────────────────────────────────────────────────────
 * Acceso a LuckPerms por REFLEXIÓN, igual que con Cobblemon/Impactor/Accessories: no se
 * compila contra él, así que el mod sigue funcionando exactamente igual en un servidor
 * sin LuckPerms.
 *
 * La resolución es a prueba de accidentes:
 *   - Nodo explícitamente en TRUE en LuckPerms  → permitido.
 *   - Nodo explícitamente en FALSE              → denegado (aunque sea OP: si te tomaste
 *                                                 el trabajo de negarlo, se respeta).
 *   - Sin definir, sin LuckPerms, o error       → se cae al chequeo de OP de siempre.
 *
 * O sea que en un servidor sin LuckPerms el comportamiento es idéntico al de antes, y en
 * uno con LuckPerms nunca se concede MÁS de lo que ya concedía el nivel de OP salvo que
 * un admin lo haya otorgado a propósito.
 *
 * Nodos usados (ver [com.cobblemonguard.commands.GuardCommand]):
 *   cobblemonguard.status · .save · .restore · .info · .backup · .backups
 *   cobblemonguard.rollback · .saving · .reload · .unlock · .limpiar · .restaurarfila
 */
object Permissions {

    private const val OP_LEVEL = 2

    private val luckPermsClass by lazy {
        runCatching { Class.forName("net.luckperms.api.LuckPermsProvider") }.getOrNull()
    }

    val isLuckPermsPresent: Boolean by lazy {
        (luckPermsClass != null).also { present ->
            if (present) CobblemonGuard.logger.info("[Permisos] LuckPerms detectado — se usan nodos 'cobblemonguard.*' (fallback: OP nivel $OP_LEVEL).")
            else CobblemonGuard.logger.info("[Permisos] LuckPerms no detectado — los comandos usan OP nivel $OP_LEVEL.")
        }
    }

    /** `LuckPermsProvider.get()` — la instancia es un singleton, se resuelve una vez. */
    private val luckPerms: Any? by lazy {
        val cls = luckPermsClass ?: return@lazy null
        runCatching { cls.getMethod("get").invoke(null) }
            .onFailure { CobblemonGuard.logger.warn("[Permisos] LuckPerms presente pero no inicializado: ${it.cause?.message ?: it.message}") }
            .getOrNull()
    }

    /** `luckPerms.getPlayerAdapter(ServerPlayerEntity::class.java)` — también estable. */
    private val playerAdapter: Any? by lazy {
        val lp = luckPerms ?: return@lazy null
        runCatching {
            ReflectCache.method(lp.javaClass, "getPlayerAdapter", 1)
                ?.invoke(lp, ServerPlayerEntity::class.java)
        }.getOrNull()
    }

    /**
     * ¿[source] tiene el nodo `cobblemonguard.[node]`?
     *
     * @param node sufijo del nodo, sin el prefijo `cobblemonguard.`
     */
    fun has(source: ServerCommandSource, node: String): Boolean {
        val fallback = source.hasPermissionLevel(OP_LEVEL)
        // La consola, los bloques de comandos y cualquier fuente que no sea un jugador no
        // tienen usuario de LuckPerms: se resuelven por nivel, como siempre.
        val player = source.player ?: return fallback
        return when (tristate(player, "cobblemonguard.$node")) {
            true  -> true
            false -> false
            null  -> fallback
        }
    }

    /**
     * ¿Tiene AL MENOS UNO de estos nodos? (para decidir si mostrar el comando raíz).
     *
     * Brigadier evalúa los `requires` de todo el árbol de comandos por CADA jugador que
     * entra, así que el atajo por nivel de OP evita consultarle a LuckPerms diez nodos
     * seguidos para alguien que ya pasa igual.
     */
    fun hasAny(source: ServerCommandSource, vararg nodes: String): Boolean {
        if (source.hasPermissionLevel(OP_LEVEL)) return true
        return nodes.any { has(source, it) }
    }

    /**
     * @return true/false si LuckPerms tiene el nodo definido, o null si no está definido
     *   (o si LuckPerms no está disponible / falló la consulta).
     */
    private fun tristate(player: ServerPlayerEntity, node: String): Boolean? {
        val adapter = playerAdapter ?: return null
        return runCatching {
            val data = ReflectCache.method(adapter.javaClass, "getPermissionData", 1)
                ?.invoke(adapter, player) ?: return null
            val tri = ReflectCache.method(data.javaClass, "checkPermission", 1)
                ?.invoke(data, node) ?: return null
            // Tristate es un enum: TRUE / FALSE / UNDEFINED. Solo los dos primeros son
            // una decisión; UNDEFINED significa "no opino", y ahí manda el nivel de OP.
            when ((tri as? Enum<*>)?.name) {
                "TRUE"  -> true
                "FALSE" -> false
                else    -> null
            }
        }.getOrNull()
    }
}
