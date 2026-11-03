package cl.lumi.app.voz

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import cl.lumi.app.LumiApp
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Voz de Lumi con el motor de texto a voz del teléfono (Google o Samsung).
 * Habla frase por frase y avisa cuál está diciendo, para mostrar el texto sincronizado como en el PC.
 * La voz exacta (femenina, natural) se elige en Ajustes entre las voces en español instaladas.
 */
class Voz(context: Context, private val alListo: (Boolean) -> Unit = {}) {

    private val principal = Handler(Looper.getMainLooper())
    private var listo = false
    private val contador = AtomicInteger(0)
    private val pendientes = mutableSetOf<String>()
    var alEmpezarFrase: (String) -> Unit = {}
    var alTerminarTodo: () -> Unit = {}
    private val textos = mutableMapOf<String, String>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { estado ->
        listo = estado == TextToSpeech.SUCCESS
        if (listo) configurar()
        principal.post { alListo(listo) }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {
                val t = synchronized(textos) { textos[id] } ?: return
                principal.post { alEmpezarFrase(t) }
            }
            override fun onDone(id: String) = terminar(id)
            @Deprecated("Requerido por la API") override fun onError(id: String) = terminar(id)
            override fun onError(id: String, errorCode: Int) = terminar(id)
            override fun onStop(id: String, interrupted: Boolean) = terminar(id)
        })
    }

    private fun terminar(id: String) {
        val vacio = synchronized(textos) {
            textos.remove(id); pendientes.remove(id); pendientes.isEmpty()
        }
        if (vacio) principal.post { alTerminarTodo() }
    }

    fun configurar() {
        if (!listo) return
        val a = LumiApp.instancia.ajustes
        val elegida = a.vozNombre?.let { n -> tts.voices?.firstOrNull { it.name == n } }
        if (elegida != null) tts.setVoice(elegida)
        else {
            val idiomas = listOf(Locale.forLanguageTag("es-CL"), Locale.forLanguageTag("es-US"), Locale.forLanguageTag("es-MX"), Locale.forLanguageTag("es-ES"))
            idiomas.firstOrNull { tts.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }?.let { tts.setLanguage(it) }
        }
        tts.setSpeechRate(a.vozVelocidad)
        tts.setPitch(a.vozTono)
    }

    /** Voces en español instaladas, primero las que funcionan sin internet y las de mejor calidad. */
    fun vocesEspanol(): List<Voice> =
        (tts.voices ?: emptySet()).filter { it.locale.language == "es" }
            .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { -it.quality }, { it.locale.country != "CL" }, { it.name }))

    /** Encola una frase. Se puede llamar varias veces mientras llega la respuesta de la IA. */
    fun decir(frase: String) {
        val limpio = frase.replace(Regex("[*_#`>]"), "").trim()
        if (!listo || limpio.isEmpty()) return
        val id = "lumi_${contador.incrementAndGet()}"
        synchronized(textos) { textos[id] = limpio; pendientes += id }
        tts.speak(limpio, TextToSpeech.QUEUE_ADD, Bundle(), id)
    }

    fun lista(): Boolean = listo

    fun hablando(): Boolean = synchronized(textos) { pendientes.isNotEmpty() }

    fun detener() {
        tts.stop()
        synchronized(textos) { textos.clear(); pendientes.clear() }
    }

    fun cerrar() {
        detener()
        tts.shutdown()
    }
}
