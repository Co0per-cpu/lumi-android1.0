package cl.lumi.app.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Freno de emergencia. Cada vez que se activa, sube la "generación":
 * cualquier tarea que empezó antes la ve cambiada y se detiene antes del siguiente paso.
 */
object Freno {
    private val generacion = AtomicInteger(0)
    private val oyentes = CopyOnWriteArrayList<() -> Unit>()

    fun token(): Int = generacion.get()
    fun vigente(token: Int): Boolean = generacion.get() == token

    fun activar() {
        generacion.incrementAndGet()
        oyentes.forEach { runCatching { it() } }
    }

    fun alActivar(oyente: () -> Unit) { oyentes += oyente }
    fun quitar(oyente: () -> Unit) { oyentes -= oyente }
}
