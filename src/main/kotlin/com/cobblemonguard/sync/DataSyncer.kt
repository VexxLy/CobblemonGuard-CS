package com.cobblemonguard.sync

import net.minecraft.server.network.ServerPlayerEntity

/**
 * Abstracción de "algo que se sincroniza" (inventario, PC, party, accesorios, mochilas...).
 *
 * Con esta interfaz + [SyncRegistry], todos los tipos se recorren en un solo loop
 * ([SyncService]) en vez de repetir `if (config.syncX) ... else null` en join, disconnect,
 * server-stop, backup y force-save. Agregar un tipo nuevo es 1 entrada en el registro.
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
 * Si deserialize() devolviera Unit y cada implementación se tragara su excepción, "se aplicó"
 * y "explotó a mitad de camino" serían indistinguibles desde afuera: el ingreso seguiría, se
 * llamaría a markLoaded, el jugador quedaría jugando con los datos LOCALES de este backend
 * (por ejemplo la PC vieja del disco) y al desconectarse eso se escribiría encima de MySQL.
 * Con esto el que llama puede ver el fallo y actuar.
 *
 * La distinción que importa es OMITIDO contra FALLO:
 * - OMITIDO es una decisión: el mod no está instalado en este backend, el snapshot de la
 *   BD está vacío y no se pisa lo vivo, o el ingreso conserva los Pokémon que Cobblemon ya
 *   cargó. El jugador queda en un estado consistente.
 * - FALLO es que había que aplicar algo y no se pudo. El jugador queda con datos que NO
 *   son los de MySQL, y dejarlo jugar así es una vía de duplicación.
 */
enum class Resultado { APLICADO, OMITIDO, FALLO }
