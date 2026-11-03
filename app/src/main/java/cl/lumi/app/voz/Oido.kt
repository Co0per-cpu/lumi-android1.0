package cl.lumi.app.voz

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Oído de Lumi. El micrófono se abre SOLO cuando lo pides (botón o doble toque) y se cierra solo.
 * Prefiere el reconocimiento dentro del teléfono (privado); si el paquete de español no está, usa el normal.
 * Todas las llamadas deben hacerse en el hilo principal.
 */
class Oido(private val ctx: Context) {

    var alParcial: (String) -> Unit = {}
    var alFinal: (String) -> Unit = {}
    var alError: (String) -> Unit = {}
    var alEstado: (Boolean) -> Unit = {}

    private var sr: SpeechRecognizer? = null
    private var probandoLocal = true

    fun escuchando(): Boolean = sr != null

    fun escuchar() {
        detener()
        probandoLocal = SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
        iniciar()
    }

    private fun iniciar() {
        val r = if (probandoLocal) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else {
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { alError("Este teléfono no tiene reconocimiento de voz disponible."); return }
            SpeechRecognizer.createSpeechRecognizer(ctx)
        }
        sr = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = alEstado(true)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(alParcial)
            }

            override fun onResults(results: Bundle?) {
                val texto = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                detener()
                if (texto.isNotBlank()) alFinal(texto) else alError("No te escuché bien.")
            }

            override fun onError(error: Int) {
                val sinIdioma = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                    error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
                if (probandoLocal && sinIdioma) {
                    // El paquete de español no está en el teléfono: probar con el reconocimiento normal.
                    sr?.destroy(); sr = null
                    probandoLocal = false
                    iniciar()
                    return
                }
                detener()
                alError(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No te escuché bien."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "No tengo permiso de micrófono. Dámelo en Ajustes de Lumi."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "El reconocimiento de voz necesita internet y no hay conexión."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El micrófono está ocupado."
                        else -> "Falló el reconocimiento de voz (error $error)."
                    }
                )
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CL")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        r.startListening(intent)
    }

    fun detener() {
        sr?.let { runCatching { it.stopListening(); it.destroy() } }
        if (sr != null) alEstado(false)
        sr = null
    }
}
