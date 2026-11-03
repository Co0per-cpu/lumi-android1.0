package cl.lumi.app.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Servicio de accesibilidad OPCIONAL. Solo se usa para saber dónde están los rectángulos de la pantalla
 * (widgets, íconos, ventanas) y así Lumi puede pararse o sentarse en sus bordes.
 * No lee textos, no hace gestos, no guarda nada.
 */
class BordesService : AccessibilityService() {

    companion object {
        @Volatile var activo: BordesService? = null
            private set
        /** La burbuja se suscribe para saber cuándo cambió lo que hay en pantalla. */
        @Volatile var alCambiarPantalla: (() -> Unit)? = null
    }

    override fun onServiceConnected() {
        activo = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.packageName?.toString() == packageName) return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            alCambiarPantalla?.invoke()
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        activo = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        activo = null
        super.onDestroy()
    }

    /** Rectángulos visibles, en coordenadas de pantalla, de al menos [minAncho] x [minAlto] píxeles. */
    fun rectangulos(minAncho: Int, minAlto: Int): List<Rect> {
        val out = mutableListOf<Rect>()
        val ventanas = runCatching { windows }.getOrNull().orEmpty()
        for (w in ventanas) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val raiz = runCatching { w.root }.getOrNull() ?: continue
            if (raiz.packageName?.toString() == packageName) continue
            recorrer(raiz, 0, out, minAncho, minAlto)
        }
        return out
    }

    private fun recorrer(n: AccessibilityNodeInfo, profundidad: Int, out: MutableList<Rect>, minAncho: Int, minAlto: Int) {
        if (out.size > 400 || profundidad > 30 || !n.isVisibleToUser) return
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.width() >= minAncho && r.height() >= minAlto) out += r
        for (i in 0 until n.childCount) {
            val hijo = runCatching { n.getChild(i) }.getOrNull() ?: continue
            recorrer(hijo, profundidad + 1, out, minAncho, minAlto)
        }
    }
}
