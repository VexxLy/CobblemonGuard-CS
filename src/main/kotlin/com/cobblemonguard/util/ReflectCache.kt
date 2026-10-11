package com.cobblemonguard.util

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache de miembros resueltos por reflexión (Method/Constructor), para no volver a
 * escanear `Class.methods` (un array COMPLETO de métodos públicos, incluidos los
 * heredados, con búsqueda lineal por nombre) en cada llamada.
 *
 * POR QUÉ IMPORTA (optimización de TPS): varios de estos lookups corren en paths
 * calientes — por jugador, por SLOT de PC/party (hasta cientos por restore), por
 * moneda de Impactor, por mochila anidada — y varios de esos paths corren en el
 * HILO DEL SERVIDOR durante joins/restores (no en el executor de BD). Sin cachear,
 * con muchos jugadores conectándose o guardando cerca en el tiempo (ej. tras un
 * reinicio del server, o el guardado masivo de backups) ese re-escaneo repetido de
 * `Class.methods` se nota como micro-lag / caídas de TPS. Cacheando el
 * Method/Constructor la PRIMERA vez, las siguientes llamadas son una invocación
 * directa — el costo normal de una llamada por reflexión, sin el escaneo previo.
 *
 * La key incluye la clase EN TIEMPO DE EJECUCIÓN del objetivo, así que sigue
 * siendo correcto aunque la implementación concreta de un mod de terceros cambie
 * entre versiones (simplemente se cachea una entrada nueva para la clase nueva) —
 * no hay riesgo de "quedarse pegado" a un Method de una clase vieja.
 */
object ReflectCache {
    private val methods = ConcurrentHashMap<String, Method?>()
    private val ctors = ConcurrentHashMap<String, Constructor<*>?>()

    /** Busca (y cachea) un método público por nombre + cantidad de parámetros. */
    fun method(cls: Class<*>, name: String, paramCount: Int): Method? {
        val key = "${cls.name}#$name/$paramCount"
        return methods.getOrPut(key) {
            cls.methods.firstOrNull { it.name == name && it.parameterCount == paramCount }
        }
    }

    /**
     * Como [method], pero con un filtro EXTRA sobre los tipos de parámetro — para
     * sobrecargas que solo difieren en tipo (ej. `set(int, Pokemon)` vs
     * `set(PCPosition, Pokemon)`, ambos con parameterCount=2).
     *
     * El [extraMatch] se evalúa una sola vez (al resolver y cachear), no en cada
     * llamada — por eso debe ser un predicado puro sobre el propio [Method]
     * (tipos de parámetro, anotaciones, etc.), nunca sobre el valor de los
     * argumentos de una invocación particular.
     */
    fun method(cls: Class<*>, name: String, paramCount: Int, extraMatch: (Method) -> Boolean): Method? {
        val key = "${cls.name}#$name/$paramCount/typed"
        return methods.getOrPut(key) {
            cls.methods.firstOrNull { it.name == name && it.parameterCount == paramCount && extraMatch(it) }
        }
    }

    /** Busca (y cachea) un constructor público por tipos de parámetro exactos. */
    fun constructor(cls: Class<*>, vararg paramTypes: Class<*>): Constructor<*>? {
        val key = cls.name + "#<init>(" + paramTypes.joinToString(",") { it.name } + ")"
        return ctors.getOrPut(key) {
            runCatching { cls.getConstructor(*paramTypes) }.getOrNull()
        }
    }
}
