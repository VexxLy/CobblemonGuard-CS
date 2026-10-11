package com.cobblemonguard.events

import com.cobblemonguard.CobblemonGuard
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.entity.ItemEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.ActionResult
import net.minecraft.util.TypedActionResult

/**
 * Lo que un jugador NO puede hacer mientras está congelado esperando sus datos.
 *
 * <h2>El agujero</h2>
 *
 * Desde el JOIN hasta que se aplican sus datos de MySQL, el jugador está en el mundo con el
 * `playerdata` LOCAL de este backend: el inventario, el cofre de ender y el equipo de la
 * última vez que pasó por acá. Esa ventana dura poco normalmente, pero puede estirarse hasta
 * ~38 s (30 s esperando a la BD + 8 s esperando a que otro backend suelte el lock). Y la
 * Lentitud 128 de [FreezeScreen] solo frena el movimiento: podía tirar los ítems al piso,
 * meterlos en un cofre, abrir su mochila, romper bloques. Después se le aplicaba el
 * inventario de MySQL encima y los ítems existían dos veces.
 *
 * <h2>Por qué no modo espectador</h2>
 *
 * Era el plan original, porque las reglas de vanilla del espectador ya bloquean todo eso. Pero
 * en 1.21.1 `ServerPlayerEntity.changeGameMode(SPECTATOR)` llama a `dropShoulderEntities()` y
 * a `stopRiding()`: a cada jugador, en cada ingreso, se le bajaba del hombro el Pokémon que
 * tuviera y se lo desmontaba si entraba montado. Además había que persistir el modo anterior
 * (si el playerdata se guardaba en medio de la ventana, el jugador quedaba en espectador para
 * siempre) y devolverlo a su posición (en espectador vuela atravesando paredes).
 *
 * Esto en cambio usa los eventos de Fabric API: todo del lado del servidor, sin mixins, y no
 * toca nada del jugador que no esté congelado.
 *
 * <h2>Lo que queda afuera, a sabiendas</h2>
 *
 * - **Comandos.** Fabric no tiene un evento para cancelar comandos arbitrarios. Los de
 *   cobblemmo-server (GTS, tienda, kits, wonder trade...) se cortan del lado de ese mod,
 *   preguntando `GuardApi.isPlayerDataLoaded` antes de operar.
 * - **Intercambios de Cobblemon.** Se negocian con paquetes propios de Cobblemon, no con
 *   interacciones vanilla, así que ningún callback de acá los ve.
 * - **Mover ítems dentro de su propio inventario.** El inventario del jugador se abre del lado
 *   del cliente y no hay evento para impedirlo. Lo que mueva entre ranuras se reemplaza entero
 *   cuando llega el inventario de MySQL; lo que deje en el CURSOR o en la grilla 2x2 no, y por
 *   eso se descarta justo antes de aplicar ([FreezeScreen.descartarItemsSueltos]).
 */
object FreezeGuard {

    fun register() {
        // ── Interacciones ───────────────────────────────────────────────────
        // Abrir cofres, barriles, el cofre de ender, hornos: es la forma de sacar ítems del
        // inventario local sin tirarlos al piso.
        UseBlockCallback.EVENT.register { player, _, _, _ ->
            if (congelado(player)) ActionResult.FAIL else ActionResult.PASS
        }
        // Usar un ítem: abrir la mochila, tirar una perla, comer.
        UseItemCallback.EVENT.register { player, _, hand ->
            if (congelado(player)) TypedActionResult.fail(player.getStackInHand(hand))
            else TypedActionResult.pass(ItemStack.EMPTY)
        }
        // Interactuar con entidades: marcos, aldeanos, monturas, cofres con ruedas.
        UseEntityCallback.EVENT.register { player, _, _, _, _ ->
            if (congelado(player)) ActionResult.FAIL else ActionResult.PASS
        }
        AttackEntityCallback.EVENT.register { player, _, _, _, _ ->
            if (congelado(player)) ActionResult.FAIL else ActionResult.PASS
        }
        AttackBlockCallback.EVENT.register { player, _, _, _, _ ->
            if (congelado(player)) ActionResult.FAIL else ActionResult.PASS
        }
        PlayerBlockBreakEvents.BEFORE.register { _, player, _, _, _ ->
            !congelado(player)
        }

        // ── Daño ────────────────────────────────────────────────────────────
        // Si muere durante la ventana, suelta todo el inventario LOCAL al piso; después se le
        // aplica el de MySQL y lo que quedó tirado existe dos veces. Y aparte, con Lentitud 128
        // no puede escapar: un jugador que entraba en medio de mobs se moría esperando a la BD.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, _, _ ->
            !(entity is ServerPlayerEntity && FreezeScreen.isFrozen(entity.uuid))
        }

        // ── Ítems soltados ──────────────────────────────────────────────────
        // Tirar un ítem (Q) no tiene evento en Fabric. Lo que sí se ve es el ItemEntity que
        // aparece en el mundo, y se lo vacía en el mismo instante en que se carga.
        ServerEntityEvents.ENTITY_LOAD.register { entity, _ ->
            if (entity is ItemEntity) interceptarSoltado(entity)
        }
    }

    private fun congelado(player: PlayerEntity): Boolean =
        player is ServerPlayerEntity && FreezeScreen.isFrozen(player.uuid)

    /**
     * Si [item] lo soltó un jugador congelado, se le devuelve el stack al inventario y el
     * ItemEntity queda vacío (vanilla lo descarta solo en su próximo tick).
     *
     * Se vacía en vez de llamar a discard() porque esto corre DENTRO del alta de la entidad en
     * el mundo, y sacarla del administrador de entidades mientras la está agregando es buscar
     * problemas. Un stack vacío además no lo puede levantar nadie, ni siquiera una tolva que
     * esté justo abajo, que no respeta el tiempo de espera de recogida.
     *
     * Devolverlo al inventario es correcto en los dos casos:
     * - En un REINGRESO, ese inventario es el local y se va a reemplazar entero por el de MySQL.
     * - En un PRIMER ingreso, ese inventario es la única copia y es lo que se guarda.
     * Y nunca devuelve a un jugador ya descongelado: congelado ⇒ sus datos todavía no se
     * aplicaron, porque aplicar y descongelar pasan juntos en el mismo tick.
     */
    private fun interceptarSoltado(item: ItemEntity) {
        if (item.stack.isEmpty) return
        // Solo ítems RECIÉN creados. ENTITY_LOAD también se dispara para lo que se carga del
        // disco con los chunks, y al entrar un jugador se cargan justo los chunks donde estaba:
        // lo que él mismo tiró en una sesión anterior aparece con él como lanzador. Sin este
        // filtro se le metía en el inventario local, que después se reemplaza por el de MySQL,
        // es decir: se BORRABA del piso. Un ítem guardado en disco ya vivió al menos un tick;
        // uno recién soltado tiene edad 0.
        if (item.itemAge != 0) return
        val jugador = quienLoSolto(item) ?: return
        // En creativo, insertStack con el inventario lleno pone la cantidad en 0 y devuelve true,
        // o sea que BORRA el ítem. Y a un jugador en creativo no hay nada que cuidarle: puede
        // sacar lo que quiera del menú.
        if (jugador.isCreative) return
        val stack = item.stack.copy()
        val inv = jugador.inventory
        inv.insertStack(stack)                 // consume lo que entra de [stack]
        if (stack.isEmpty) {
            item.setStack(ItemStack.EMPTY)
        } else {
            // No debería pasar —el hueco del que salió sigue libre—, pero si no entró todo, lo
            // que sobra se queda en el ItemEntity en vez de perderse.
            item.setStack(stack)
            CobblemonGuard.logger.warn(
                "[Freeze] ${jugador.name.string} soltó ${stack.count}x ${stack.item} congelado y no entró de vuelta en su inventario."
            )
        }
        jugador.playerScreenHandler.syncState()
    }

    /**
     * El jugador congelado que soltó [item], si fue uno.
     *
     * Solo se mira quién lo tiró (`getOwner`), que vanilla marca al tirar con Q o soltar desde
     * una ranura. Hubo una segunda regla que reconocía por la posición exacta de aparición lo
     * que se suelta SIN dueño, pero no tenía casos reales y sí falsos positivos: lo que suelta un
     * jugador al desconectarse queda en disco con edad 0 justo en esa posición, y al volver a
     * entrar en el mismo lugar se le metía en el inventario local, que después se reemplaza por
     * el de MySQL. Se borraba. Lo que se suelta sin dueño mientras está congelado (el cursor y la
     * grilla al desconectarlo) ya lo cubre [FreezeScreen.clear].
     */
    private fun quienLoSolto(item: ItemEntity): ServerPlayerEntity? {
        val dueno = item.owner as? ServerPlayerEntity ?: return null
        return if (FreezeScreen.isFrozen(dueno.uuid)) dueno else null
    }
}
