package cl.lumi.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Los mismos estados que en el PC. Cada uno es un archivo en la carpeta personaje/. */
enum class Estado(val archivo: String) {
    IDLE("idle"), WALK("walk"), FLY("fly"), GLIDE("glide"), DRAG("drag"), FALL("fall"), LAND("land"),
    PEEK("peek"), SLEEP("sleep"), TALK("talk"), THINK("think"), HAPPY("happy"), SAD("sad"),
}

/**
 * El personaje. Usa los WebP animados (con transparencia real) de la versión de PC, sin cambiarlos.
 * Busca primero en los archivos importados desde Ajustes y luego en assets/personaje/.
 * Si existe "<estado>_in" (por ejemplo sad_in), lo reproduce una vez antes del estado.
 * El cambio se hace con doble búfer: se prepara la imagen nueva detrás y recién ahí se intercambia, sin parpadeo.
 */
@SuppressLint("ViewConstructor")
class Personaje(context: Context, private val scope: CoroutineScope) : FrameLayout(context) {

    private val a = ImageView(context)
    private val b = ImageView(context)
    private var frente = a
    private var trabajo: Job? = null
    private val carpeta = File(context.filesDir, "personaje")
    private val tiempos: Map<String, Long> = leerTiempos()

    var estado: Estado? = null
        private set

    var hayImagenes: Boolean = true
        private set

    init {
        listOf(a, b).forEach {
            it.scaleType = ImageView.ScaleType.FIT_CENTER
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        b.visibility = INVISIBLE
    }

    /** Voltea el personaje para que mire hacia donde va. */
    fun mirar(haciaIzquierda: Boolean) { scaleX = if (haciaIzquierda) -1f else 1f }

    fun fijar(e: Estado, forzar: Boolean = false) {
        if (e == estado && !forzar) return
        estado = e
        trabajo?.cancel()
        trabajo = scope.launch {
            val entrada = "${e.archivo}_in"
            if (mostrar(entrada, repetir = false)) delay(tiempos[entrada] ?: 700L)
            if (!mostrar(e.archivo, repetir = true) && !mostrar(Estado.IDLE.archivo, repetir = true)) {
                hayImagenes = false
                ponerEnFrente(marcador())
            }
        }
    }

    private suspend fun mostrar(nombre: String, repetir: Boolean): Boolean {
        val d = withContext(Dispatchers.IO) { decodificar(nombre) } ?: return false
        if (d is AnimatedImageDrawable) {
            d.repeatCount = if (repetir) AnimatedImageDrawable.REPEAT_INFINITE else 0
        }
        ponerEnFrente(d)
        hayImagenes = true
        return true
    }

    private fun ponerEnFrente(d: Drawable) {
        val atras = if (frente === a) b else a
        atras.setImageDrawable(d)
        (d as? AnimatedImageDrawable)?.start()
        atras.visibility = VISIBLE
        frente.visibility = INVISIBLE
        (frente.drawable as? AnimatedImageDrawable)?.stop()
        frente.setImageDrawable(null)
        frente = atras
    }

    private fun decodificar(nombre: String): Drawable? {
        for (ext in listOf("webp", "gif", "png")) {
            val f = File(carpeta, "$nombre.$ext")
            if (f.exists()) return runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) }.getOrNull()
        }
        val assets = context.assets
        val enAssets = runCatching { assets.list("personaje")?.toList() }.getOrNull().orEmpty()
        for (ext in listOf("webp", "gif", "png")) {
            val n = "$nombre.$ext"
            if (n in enAssets) return runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(assets, "personaje/$n"))
            }.getOrNull()
        }
        return null
    }

    /** tiempos.json: {"sad_in": 1200} en milisegundos (si el número es chico, se toma como segundos). */
    private fun leerTiempos(): Map<String, Long> {
        val texto = File(carpeta, "tiempos.json").takeIf { it.exists() }?.readText()
            ?: runCatching { context.assets.open("personaje/tiempos.json").bufferedReader().readText() }.getOrNull()
            ?: return emptyMap()
        return runCatching {
            val j = JSONObject(texto)
            j.keys().asSequence().mapNotNull { k ->
                val v = j.get(k)
                val num = when (v) {
                    is Number -> v.toDouble()
                    is JSONObject -> v.optDouble("ms", v.optDouble("duracion", v.optDouble("duration", Double.NaN)))
                    else -> Double.NaN
                }
                if (num.isNaN()) null else k.removeSuffix(".webp") to (if (num < 20) num * 1000 else num).toLong()
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Mientras no estén los sprites: un círculo neutro (no es el diseño del personaje). */
    private fun marcador(): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xCC7C5CFF.toInt())
        setStroke(4, 0xFFFFFFFF.toInt())
    }
}
