package com.cobblemonguard.util

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache de miembros resueltos por reflexión (Method/Constructor), para no volver a
 * escanear `Class.methods` (un array COMPLETO de métodos públicos, incluidos los
 * heredados, con búsqueda lineal por nombre) en cada llamada.
 *
 * POR QUÉ IMPORTA (optimización de TPS): varios de estos lookups corren en paths
 * calientes, por jugador, por SLOT de PC/party (hasta cientos por restore), por
 * moneda de Impactor, por mochila anidada, y varios de esos paths corren en el
 * HILO DEL SERVIDOR durante joins/restores (no en el executor de BD). Sin cachear,
 * con muchos jugadores conectándose o guardando cerca en el tiempo (ej. tras un
 * reinicio del server, o el guardado masivo de backups) ese re-escaneo repetido de
 * `Class.methods` se nota como micro-lag / caídas de TPS. Cacheando el
 * Method/Constructor la PRIMERA vez, las siguientes llamadas son una invocación
 * directa: el costo normal de una llamada por reflexión, sin el escaneo previo.
 *
 * La key incluye la clase EN TIEMPO DE EJECUCIÓN del objetivo, así que sigue
 * siendo correcto aunque la implementación concreta de un mod de terceros cambie
 * entre versiones (simplemente se cachea una entrada nueva para la clase nueva):
 * no hay riesgo de "quedarse pegado" a un Method de una clase vieja.
 */
object ReflectCache {
    private val methods = ConcurrentHashMap<String, Method?>()
    private val ctors = ConcurrentHashMap<String, Constructor<*>?>()

    /** Busca (y cachea) un método público por nombre + cantidad de parámetros. */
    fun method(cls: Class<*>, name: String, paramCount: Int): Method? {
        val key = "${cls.name}#$name/$paramCount"
        return cachear(key) { cls.methods.firstOrNull { it.name == name && it.parameterCount == paramCount } }
    }

    /**
     * getOrPut de Kotlin sobre un ConcurrentHashMap hace putIfAbsent(key, null) cuando no
     * encuentra nada, y ConcurrentHashMap no acepta null: un método inexistente tiraba
     * NullPointerException en vez de devolver null. Los "no encontrado" no se cachean.
     */
    private fun cachear(key: String, buscar: () -> Method?): Method? {
        methods[key]?.let { return it }
        val m = buscar()?.let { invocable(it) } ?: return null
        return methods.putIfAbsent(key, m) ?: m
    }

    /**
     * `Class.methods` devuelve los métodos públicos de la clase REAL del objeto. Si esa clase no es
     * pública (una clase interna privada o de paquete de otro mod, p.ej. la que implementa una
     * interfaz de Impactor), `invoke` falla con "cannot access a member ... with modifiers public"
     * aunque el método sea público. En ese caso se busca el mismo método en una interfaz o superclase
     * pública (la API), que es lo que hay que llamar; si no hay, se intenta abrir el acceso.
     */
    private fun invocable(m: Method): Method? {
        if (Modifier.isPublic(m.declaringClass.modifiers)) return m
        publico(m.declaringClass, m.name, m.parameterTypes, HashSet())?.let { return it }
        runCatching { m.trySetAccessible() }
        return m
    }

    private fun publico(cls: Class<*>, name: String, tipos: Array<Class<*>>, vistos: MutableSet<Class<*>>): Method? {
        if (!vistos.add(cls)) return null
        if (Modifier.isPublic(cls.modifiers)) {
            runCatching { cls.getMethod(name, *tipos) }.getOrNull()
                ?.takeIf { Modifier.isPublic(it.declaringClass.modifiers) }
                ?.let { return it }
        }
        for (i in cls.interfaces) publico(i, name, tipos, vistos)?.let { return it }
        return cls.superclass?.let { publico(it, name, tipos, vistos) }
    }

    /**
     * Como [method], pero con un filtro EXTRA sobre los tipos de parámetro: para
     * sobrecargas que solo difieren en tipo (ej. `set(int, Pokemon)` vs
     * `set(PCPosition, Pokemon)`, ambos con parameterCount=2).
     *
     * El [extraMatch] se evalúa una sola vez (al resolver y cachear), no en cada
     * llamada, por eso debe ser un predicado puro sobre el propio [Method]
     * (tipos de parámetro, anotaciones, etc.), nunca sobre el valor de los
     * argumentos de una invocación particular.
     */
    fun method(cls: Class<*>, name: String, paramCount: Int, extraMatch: (Method) -> Boolean): Method? {
        val key = "${cls.name}#$name/$paramCount/typed"
        return cachear(key) {
            cls.methods.firstOrNull { it.name == name && it.parameterCount == paramCount && extraMatch(it) }
        }
    }

    /** Busca (y cachea) un constructor público por tipos de parámetro exactos. */
    fun constructor(cls: Class<*>, vararg paramTypes: Class<*>): Constructor<*>? {
        val key = cls.name + "#<init>(" + paramTypes.joinToString(",") { it.name } + ")"
        ctors[key]?.let { return it }
        val c = runCatching { cls.getConstructor(*paramTypes) }.getOrNull() ?: return null
        return ctors.putIfAbsent(key, c) ?: c
    }
}
