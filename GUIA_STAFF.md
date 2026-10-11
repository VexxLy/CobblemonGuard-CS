# 🛡️ CobblemonGUARD — Guía para el Staff

Guía rápida para usar el mod dentro del juego. **Necesitas ser OP (nivel 2)**.
Todos los comandos empiezan con `/cg` (o `/cobblemonguard`, es lo mismo).

> El mod guarda solo en MySQL: **inventario, cofre del Ender, PC, equipo de 6, accesorios
> y contenido de mochilas**. No tienes que hacer nada en el día a día — guarda solo al
> salir, cada rato (auto-save) y al apagar el server. Los comandos son para casos especiales.

---

## ⚡ Comandos (resumen)

| Comando | Para qué sirve |
|---|---|
| `/cg status` | Ver si la BD está conectada y si el guardado está ACTIVO |
| `/cg info <jugador>` | Ver cuándo se guardó por última vez ese jugador |
| `/cg save <jugador>` | Guardar YA a un jugador |
| `/cg save all` | Guardar YA a **todos** los online |
| `/cg restore <jugador> [tipo]` | Devolverle su último guardado de la BD |
| `/cg backup <jugador>` | Crear un backup manual de un jugador |
| `/cg backup all` | Crear backup de **todos** los online |
| `/cg backups <jugador>` | Ver la lista de backups que tiene |
| `/cg rollback <jugador> <tiempo> [tipo]` | Devolverlo a como estaba hace X tiempo |
| `/cg saving start` / `stop` | Activar / pausar el guardado (ver abajo) |

**`[tipo]` es opcional** (por defecto = todo). Sirve para tocar solo una parte:

| tipo | qué afecta |
|---|---|
| `all` | Todo (por defecto) |
| `inventario` | Solo el inventario |
| `ender` | Solo el cofre del Ender |
| `pc` | Solo la PC de Cobblemon |
| `party` | Solo el equipo de 6 |
| `accesorios` | Solo accesorios / mochila equipada |
| `mochilas` | Solo el contenido de mochilas |

---

## 🎯 Tareas comunes

### Un jugador perdió sus cosas (bug, crasheo)
1. `/cg backups <jugador>` → mira qué backups tiene.
2. `/cg rollback <jugador> 1h` → lo devuelve a como estaba hace 1 hora.
   - Tiempos válidos: `30m`, `1h`, `2h`, `6h`, etc. (minutos u horas)
   - Solo una parte: `/cg rollback <jugador> 1h pc` (solo su PC)

### Solo quieres devolverle lo último guardado
```
/cg restore <jugador>
```
(trae su último guardado de la BD; NO es un backup histórico)

### Antes de reiniciar el server / un evento
```
/cg save all
```
Guarda a todos en el momento (además del guardado automático al apagar).

### Ver si el sistema está funcionando
```
/cg status
```
Debe decir `✔ Conectado a ...` y `Guardado: ACTIVO`.

---

## ⏸️ Pausar el guardado (`/cg saving stop`)

Esto **detiene TODAS las escrituras** a la base de datos. Úsalo solo en casos especiales
(mantenimiento, transferencia de datos). **La carga sigue funcionando**, solo se pausa el guardado.

- `⚠️ CUIDADO:` mientras esté pausado, **si un jugador sale, NO se guarda su progreso**.
- Para reactivar: `/cg saving start`
- Confírmalo con `/cg status` → `Guardado: ACTIVO`.
- Todo esto se avisa en el **Discord** del staff (quién lo activó/pausó).

> Si ves en Discord "⏸️ Guardado PAUSADO" y no fue a propósito → alguien lo pausó.
> Reactívalo con `/cg saving start`.

---

## ❌ Diferencia importante: `restore` vs `rollback`

- **`restore`** = trae el **último guardado** que hay en la BD (uno solo, se sobreescribe).
  Si el jugador no ha guardado lo nuevo, le trae lo viejo.
- **`rollback`** = trae un **backup histórico** de hace X tiempo (hay muchos, se guardan 1 semana).

Regla simple: para "devolver algo perdido de hace rato" → usa **`rollback`**.

---

## 🔔 Discord
Toda acción del staff se avisa automáticamente en el canal de Discord con:
- Qué se hizo, qué jugador(es) y **quién lo ejecutó**.

Así queda registro de todo.

---

## ✅ Qué SÍ y qué NO

**SÍ puedes:**
- Usar `restore`, `rollback`, `save`, `backup` para ayudar a jugadores.
- Hacer `/cg save all` antes de reinicios.

**NO hagas (salvo que sepas lo que haces):**
- `/cg saving stop` y olvidarte de reactivarlo → la gente pierde progreso.
- `rollback` a un tiempo muy viejo "por si acaso" → le quitas lo que ganó después.

**Ante la duda:** primero `/cg info <jugador>` y `/cg backups <jugador>` para ver qué hay,
y recién ahí decides.

---

*Cualquier problema raro (no guarda, no carga, errores en consola) → avisa al admin con
lo que dice `/cg status` y una captura de la consola.*
