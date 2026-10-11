package com.cobblemonguard.sync

import net.minecraft.server.network.ServerPlayerEntity

/**
 * Abstracción de "algo que se sincroniza" (inventario, PC, party, accesorios, mochilas...).
 *
 * Antes cada tipo se serializaba con bloques `if (config.syncX) ... else null` repetidos
 * en 5 sitios distintos (join, disconnect, server-stop, backup, force-save), lo que era
 * propenso a errores. Con esta interfaz + [SyncRegistry], todos los tipos se recorren en
 * un solo loop ([SyncService]); agregar un tipo nuevo = 1 entrada en el registro.
 */
interface DataSyncer {
    /** Tipo de dato que maneja este syncer. */
    val type: DataType

    /** ¿Está habilitado en la config? */
    fun isEnabled(): Boolean

    /** Serializa el dato del jugador a bytes (o null si no aplica). Hilo del servidor. */
    fun serialize(player: ServerPlayerEntity): ByteArray?

    /**
     * Aplica los bytes al jugador. Hilo del servidor.
     * @param onlyIfEmpty solo lo respetan PC/party (no pisar pokémon vivos en el ingreso).
     * @return qué pasó. Ver [Resultado].
     */
    fun deserialize(player: ServerPlayerEntity, data: ByteArray, onlyIfEmpty: Boolean): Resultado
}

/**
 * Lo que pasó al intentar aplicar un tipo de dato.
 *
 * Antes deserialize() devolvía Unit y cada implementación se tragaba su excepción en un
 * runCatching. O sea que "se aplicó" y "explotó a mitad de camino" eran indistinguibles
 * desde afuera: el ingreso seguía, se llamaba a markLoaded, el jugador quedaba jugando con
 * los datos LOCALES de este backend (por ejemplo la PC vieja del disco) y al desconectarse
 * eso se escribía encima de MySQL. Ahora el que llama puede ver el fallo y actuar.
 *
 * La distinción que importa es OMITIDO contra FALLO:
 * - OMITIDO es una decisión: el mod no está instalado en este backend, el snapshot de la
 *   BD está vacío y no se pisa lo vivo, o el ingreso conserva los Pokémon que Cobblemon ya
 *   cargó. El jugador queda en un estado consistente.
 * - FALLO es que había que aplicar algo y no se pudo. El jugador queda con datos que NO
 *   son los de MySQL, y dejarlo jugar así es una vía de duplicación.
 */
enum class Resultado { APLICADO, OMITIDO, FALLO }
