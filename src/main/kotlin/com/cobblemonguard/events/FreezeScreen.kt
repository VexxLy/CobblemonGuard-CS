package com.cobblemonguard.events

import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.item.ItemStack
import net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket
import net.minecraft.network.packet.s2c.play.TitleS2CPacket
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Congela al jugador (para que no se mueva mientras se cargan/guardan sus datos) y
 * muestra las pantallas/títulos de estado. Separado del [PlayerEventHandler] para no
 * mezclar la "presentación" con la orquestación de datos.
 */
object FreezeScreen {

    private val frozen = ConcurrentHashMap.newKeySet<UUID>()

    fun isFrozen(uuid: UUID): Boolean = uuid in frozen

    /** Quita al jugador del set de congelados sin enviar paquetes (limpieza en disconnect). */
    fun clear(uuid: UUID) { frozen.remove(uuid) }

    /**
     * Limpieza en el DISCONNECT: además de sacarlo del set, QUITA el efecto de Slowness.
     *
     * Sin esto, un jugador que se desconecta mientras está congelado (expulsado porque la
     * BD no respondía, caída de red a mitad de la carga, crash del servidor) queda con
     * Slowness 128 guardado en su playerdata de Minecraft. Normalmente se autocorrige —
     * al reconectar, freeze() + unfreeze() lo limpia — pero si mientras tanto alguien
     * DESACTIVA el mod para investigar (que es exactamente lo que uno hace cuando el mod
     * está fallando), esos jugadores quedan inmóviles y ya no hay nada que se los saque.
     *
     * Este evento corre antes de que Minecraft guarde el playerdata, así que el efecto
     * nunca llega a persistirse.
     */
    fun clear(player: ServerPlayerEntity) {
        val wasFrozen = frozen.remove(player.uuid)
        if (!wasFrozen) return
        runCatching { player.removeStatusEffect(StatusEffects.SLOWNESS) }
        runCatching { devolverItemsSueltos(player) }
    }

    /**
     * DESCARTA lo que haya en el cursor y en la grilla de crafteo 2x2. Se llama justo antes de
     * aplicar el inventario de MySQL en un REINGRESO.
     *
     * `PlayerInventory.readNbt` reemplaza el inventario principal, la armadura y la mano
     * secundaria, pero el cursor y la grilla no son parte del inventario: quedan como estaban.
     * Al entrar los dos están vacíos (vanilla no los persiste), así que lo que haya ahí lo puso
     * el jugador DURANTE la ventana de carga, sacándolo de su inventario LOCAL. Si se los dejaba,
     * después de aplicar MySQL —que todavía tiene esos ítems— volvían al inventario al cerrar la
     * pantalla y existían dos veces. Repetible en cada ingreso.
     *
     * Se descartan, no se devuelven: son copias locales de algo que MySQL ya trae. Y la grilla se
     * vacía slot por slot con setStack, no con clear(): setStack avisa al handler y recalcula el
     * resultado; con clear() quedaba en la casilla de resultado el ítem crafteado con lo que se
     * acaba de descartar, listo para sacarlo.
     */
    fun descartarItemsSueltos(player: ServerPlayerEntity) {
        for (handler in listOf(player.currentScreenHandler, player.playerScreenHandler).distinct()) {
            if (!handler.cursorStack.isEmpty) handler.cursorStack = ItemStack.EMPTY
        }
        val grilla = player.playerScreenHandler.craftingInput
        for (i in 0 until grilla.size()) {
            if (!grilla.getStack(i).isEmpty) grilla.setStack(i, ItemStack.EMPTY)
        }
        player.playerScreenHandler.syncState()
    }

    /**
     * Mete en el inventario lo que el jugador tenga en el cursor o en la grilla de crafteo 2x2.
     *
     * Al sacar a un jugador del mundo, vanilla llama a `PlayerScreenHandler.onClosed()`, y como
     * para entonces ya está desconectado, en vez de devolverle esas cosas al inventario las
     * SUELTA en el piso (`PlayerEntity.remove` → `onClosed` → `dropItem`). Eso pasa después de
     * este clear(), cuando ya no está congelado y [FreezeGuard] ya no lo intercepta.
     *
     * El caso concreto: abre su inventario durante la ventana de carga, agarra un stack con el
     * cursor o lo pone en la grilla, y lo expulsan porque la BD no respondió. El stack quedaba
     * tirado en el mundo de este backend y, en su próximo ingreso, MySQL le devolvía el mismo
     * stack en el inventario.
     *
     * Devolverlo al inventario local no duplica nada: ese inventario no se guarda en MySQL
     * (nunca se marcó como cargado) y en el próximo ingreso se reemplaza por el de la base.
     * Lo que no entre (inventario lleno) se deja donde estaba y vanilla hace lo suyo.
     */
    private fun devolverItemsSueltos(player: ServerPlayerEntity) {
        val inv = player.inventory
        for (handler in listOf(player.currentScreenHandler, player.playerScreenHandler).distinct()) {
            val cursor = handler.cursorStack
            if (cursor.isEmpty) continue
            val resto = cursor.copy()
            inv.insertStack(resto)
            handler.cursorStack = resto
        }
        val grilla = player.playerScreenHandler.craftingInput
        for (i in 0 until grilla.size()) {
            val stack = grilla.getStack(i)
            if (stack.isEmpty) continue
            val resto = stack.copy()
            inv.insertStack(resto)
            grilla.setStack(i, resto)
        }
    }

    fun freeze(player: ServerPlayerEntity) {
        frozen.add(player.uuid)
        // Slowness amplifier 127 = Slowness 128 → movimiento horizontal completamente bloqueado.
        // Sin partículas (false, false, false) para que no se vea el efecto en el HUD.
        player.addStatusEffect(
            StatusEffectInstance(StatusEffects.SLOWNESS, 999_999, 127, false, false, false)
        )
    }

    fun unfreeze(player: ServerPlayerEntity) {
        if (!frozen.remove(player.uuid)) return
        player.removeStatusEffect(StatusEffects.SLOWNESS)
    }

    // ── Títulos / pantallas ────────────────────────────────────────────────

    /** Mientras se conecta a la BD (antes de saber si es primer ingreso). */
    fun connecting(player: ServerPlayerEntity) {
        player.networkHandler.sendPacket(TitleFadeS2CPacket(3, 9999, 5))
        player.networkHandler.sendPacket(TitleS2CPacket(Text.literal("⟳ CobblemonGUARD").formatted(Formatting.YELLOW)))
        player.networkHandler.sendPacket(SubtitleS2CPacket(Text.literal("Verificando base de datos...").formatted(Formatting.GRAY)))
        player.networkHandler.sendPacket(OverlayMessageS2CPacket(Text.literal("⟳ Por favor espera, no te muevas...").formatted(Formatting.YELLOW)))
    }

    /** Mientras se registra un jugador nuevo. */
    fun registering(player: ServerPlayerEntity) {
        player.networkHandler.sendPacket(TitleFadeS2CPacket(3, 9999, 5))
        player.networkHandler.sendPacket(TitleS2CPacket(Text.literal("★ Registrando perfil...").formatted(Formatting.GOLD)))
        player.networkHandler.sendPacket(SubtitleS2CPacket(Text.literal("Guardando tus datos en la base de datos").formatted(Formatting.GRAY)))
        player.networkHandler.sendPacket(OverlayMessageS2CPacket(Text.literal("⟳ Guardando... Por favor no te muevas.").formatted(Formatting.YELLOW)))
    }

    /** Mientras se restauran datos de un jugador existente. */
    fun loading(player: ServerPlayerEntity) {
        player.networkHandler.sendPacket(TitleFadeS2CPacket(3, 9999, 5))
        player.networkHandler.sendPacket(TitleS2CPacket(Text.literal("⟳ Cargando datos...").formatted(Formatting.AQUA)))
        player.networkHandler.sendPacket(SubtitleS2CPacket(Text.literal("Restaurando inventario y PC de Cobblemon").formatted(Formatting.GRAY)))
        player.networkHandler.sendPacket(OverlayMessageS2CPacket(Text.literal("⟳ Cargando... Por favor no te muevas.").formatted(Formatting.YELLOW)))
    }

    /** Éxito para primer ingreso. */
    fun welcome(player: ServerPlayerEntity) {
        player.networkHandler.sendPacket(ClearTitleS2CPacket(false))
        player.networkHandler.sendPacket(TitleFadeS2CPacket(5, 70, 25))
        player.networkHandler.sendPacket(TitleS2CPacket(Text.literal("¡Bienvenido!").formatted(Formatting.GREEN)))
        player.networkHandler.sendPacket(SubtitleS2CPacket(Text.literal("Tu perfil ha sido registrado correctamente").formatted(Formatting.GRAY)))
        player.networkHandler.sendPacket(OverlayMessageS2CPacket(Text.literal("✔ ¡Listo! Ya puedes moverte.").formatted(Formatting.GREEN)))
        player.sendMessage(
            Text.literal("").append(Text.literal("[CobblemonGUARD] ").formatted(Formatting.GOLD))
                .append(Text.literal("Tu perfil ha sido registrado. ¡Bienvenido al servidor!").formatted(Formatting.GREEN))
        )
    }

    /** Éxito para reingreso. */
    fun restored(player: ServerPlayerEntity) {
        player.networkHandler.sendPacket(ClearTitleS2CPacket(false))
        player.networkHandler.sendPacket(TitleFadeS2CPacket(5, 60, 20))
        player.networkHandler.sendPacket(TitleS2CPacket(Text.literal("✔ ¡Datos restaurados!").formatted(Formatting.GREEN)))
        player.networkHandler.sendPacket(SubtitleS2CPacket(Text.literal("Inventario y PC de Cobblemon sincronizados").formatted(Formatting.GRAY)))
        player.networkHandler.sendPacket(OverlayMessageS2CPacket(Text.literal("✔ ¡Listo! Ya puedes moverte.").formatted(Formatting.GREEN)))
    }

    /** Error (BD no disponible). */
    fun error(player: ServerPlayerEntity) {
        player.networkHandler.sendPacket(ClearTitleS2CPacket(false))
        player.networkHandler.sendPacket(TitleFadeS2CPacket(5, 100, 20))
        player.networkHandler.sendPacket(TitleS2CPacket(Text.literal("⚠ Error de sincronización").formatted(Formatting.RED)))
        player.networkHandler.sendPacket(SubtitleS2CPacket(Text.literal("No se pudo acceder a la base de datos").formatted(Formatting.GRAY)))
        player.sendMessage(
            Text.literal("").append(Text.literal("[CobblemonGUARD] ").formatted(Formatting.GOLD))
                .append(Text.literal("⚠ Error al conectar con MySQL. Avisa a un administrador.").formatted(Formatting.RED))
        )
    }
}
