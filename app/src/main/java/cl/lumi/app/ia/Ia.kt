package cl.lumi.app.ia

import android.content.Context
import android.os.Build
import cl.lumi.app.LumiApp
import cl.lumi.app.core.ModoIa
import cl.lumi.app.net.Red
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Mensaje(val rol: String, val texto: String) // rol: "user" | "assistant"

/** Historial de la conversación, guardado solo en el teléfono. */
class Historial(context: Context) {
    private val archivo = File(context.filesDir, "historial.json")
    private val mensajes = mutableListOf<Mensaje>()

    init {
        runCatching {
            val arr = JSONArray(archivo.readText())
            for (k in 0 until arr.length()) arr.getJSONObject(k).let { mensajes += Mensaje(it.getString("rol"), it.getString("texto")) }
        }
    }

    @Synchronized fun todos(): List<Mensaje> = mensajes.toList()

    @Synchronized fun agregar(m: Mensaje) {
        mensajes += m
        while (mensajes.size > 30) mensajes.removeAt(0)
        val arr = JSONArray()
        mensajes.forEach { arr.put(JSONObject().put("rol", it.rol).put("texto", it.texto)) }
        archivo.writeText(arr.toString())
    }

    @Synchronized fun borrar() { mensajes.clear(); archivo.delete() }
}

/** Dónde respondió la IA, para mostrarlo en la burbuja. */
enum class Motor(val etiqueta: String) { PC("PC"), TELEFONO("teléfono") }

sealed class Trozo {
    data class Usando(val motor: Motor) : Trozo()
    data class Texto(val t: String) : Trozo()
}

class IaNoDisponible(mensaje: String) : Exception(mensaje)

object Ia {

    fun sistema(): String {
        val fecha = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale.forLanguageTag("es-CL")).format(Date())
        return """
            Eres Lumi, una compañera que vive en el teléfono del usuario. Hablas español de Chile, cercano y cálido, sin exagerar los chilenismos.
            Responde corto (1 a 3 frases) salvo que te pidan más. Tu texto se lee en voz alta: no uses listas, emojis ni formato.
            Regla de honestidad, la más importante: tú solo conversas. Las acciones del teléfono (abrir apps, música, alarmas, recordatorios, volumen) las hace la app y la app te informa el resultado.
            Nunca digas que hiciste, abriste, pusiste o programaste algo. Si te piden una acción, di que no la entendiste como orden y sugiere cómo pedirla, por ejemplo "pon una alarma a las 7".
            Si no sabes algo, dilo. No inventes datos, fechas ni cifras. Nada de contenido sexual.
            Ahora es $fecha.
        """.trimIndent()
    }

    /** Elige motor según el modo y responde en trozos. */
    fun responder(ctx: Context, historial: List<Mensaje>): Flow<Trozo> = flow {
        val ajustes = LumiApp.instancia.ajustes
        val motor: Motor = when (ajustes.modoIa) {
            ModoIa.PC -> if (Ollama.disponible()) Motor.PC else throw IaNoDisponible("No encuentro el Ollama del PC en ${ajustes.ollamaUrl}. ¿Está encendido y en la misma Wi-Fi?")
            ModoIa.TELEFONO -> if (Local.disponible()) Motor.TELEFONO else throw IaNoDisponible("No hay un modelo cargado en el teléfono. Elige uno en Ajustes.")
            ModoIa.AUTO -> when {
                Ollama.disponible() -> Motor.PC
                Local.disponible() -> Motor.TELEFONO
                else -> throw IaNoDisponible("No encuentro el PC y no hay modelo en el teléfono, así que ahora no puedo conversar.")
            }
        }
        emit(Trozo.Usando(motor))
        val fuente = if (motor == Motor.PC) Ollama.chat(historial) else Local.chat(ctx, historial)
        fuente.collect { emit(Trozo.Texto(it)) }
    }
}

/** IA del PC por Wi-Fi (el mismo Ollama que usa Lumi en Windows). */
object Ollama {
    private val ajustes get() = LumiApp.instancia.ajustes

    suspend fun disponible(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("${ajustes.ollamaUrl}/api/tags").build()
            Red.rapido.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** Lista de modelos instalados en el PC, para elegir en Ajustes. */
    suspend fun modelos(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("${ajustes.ollamaUrl}/api/tags").build()
            Red.rapido.newCall(req).execute().use { r ->
                val arr = JSONObject(r.body!!.string()).getJSONArray("models")
                (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
            }
        }.getOrDefault(emptyList())
    }

    fun chat(historial: List<Mensaje>): Flow<String> = flow {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", Ia.sistema()))
        historial.forEach { msgs.put(JSONObject().put("role", it.rol).put("content", it.texto)) }
        val body = JSONObject()
            .put("model", ajustes.ollamaModelo)
            .put("messages", msgs)
            .put("stream", true)
            .put("options", JSONObject().put("temperature", 0.7))
        val req = Request.Builder().url("${ajustes.ollamaUrl}/api/chat")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val call = Red.streaming.newCall(req)
        try {
            call.execute().use { r ->
                if (!r.isSuccessful) throw IaNoDisponible("El PC respondió con error ${r.code}. ¿Está instalado el modelo ${ajustes.ollamaModelo}?")
                val src = r.body!!.source()
                while (!src.exhausted()) {
                    val linea = src.readUtf8Line() ?: break
                    if (linea.isBlank()) continue
                    val j = JSONObject(linea)
                    j.optJSONObject("message")?.optString("content")?.takeIf { it.isNotEmpty() }?.let { emit(it) }
                    if (j.optBoolean("done")) break
                }
            }
        } finally {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * IA dentro del teléfono con LiteRT-LM (modelos .litertlm, por ejemplo Gemma 3 1B int4).
 * El motor se carga una vez y se mantiene; se libera con [liberar].
 */
object Local {
    private val ajustes get() = LumiApp.instancia.ajustes
    private val candado = Mutex()
    private var motor: Engine? = null
    private var rutaCargada: String? = null

    fun soportado(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

    fun disponible(): Boolean {
        val ruta = ajustes.modeloLocal ?: return false
        return soportado() && File(ruta).exists()
    }

    private suspend fun cargar(ctx: Context): Engine = candado.withLock {
        val ruta = ajustes.modeloLocal ?: throw IaNoDisponible("No hay modelo en el teléfono.")
        motor?.let { if (rutaCargada == ruta) return@withLock it else runCatching { it.close() } }
        withContext(Dispatchers.Default) {
            // Primero la GPU; si falla, la CPU.
            val e = try {
                Engine(EngineConfig(modelPath = ruta, backend = Backend.GPU(), cacheDir = ctx.cacheDir.path)).also { it.initialize() }
            } catch (t: Throwable) {
                try {
                    Engine(EngineConfig(modelPath = ruta, backend = Backend.CPU(), cacheDir = ctx.cacheDir.path)).also { it.initialize() }
                } catch (t2: Throwable) {
                    throw IaNoDisponible("No pude cargar el modelo del teléfono: ${t2.message ?: t2.javaClass.simpleName}")
                }
            }
            motor = e; rutaCargada = ruta
            e
        }
    }

    fun chat(ctx: Context, historial: List<Mensaje>): Flow<String> = flow {
        val engine = cargar(ctx)
        // El modelo del teléfono recibe el historial reciente dentro de las instrucciones, para que
        // la conversación siga aunque antes haya respondido el PC.
        val previos = historial.dropLast(1).takeLast(8).joinToString("\n") {
            (if (it.rol == "user") "Usuario: " else "Lumi: ") + it.texto
        }
        val instrucciones = Ia.sistema() + if (previos.isNotEmpty()) "\n\nConversación reciente:\n$previos" else ""
        val config = ConversationConfig(
            systemInstruction = Contents.of(instrucciones),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
        )
        val ultimo = historial.lastOrNull()?.texto ?: return@flow
        engine.createConversation(config).use { conv ->
            conv.sendMessageAsync(ultimo).collect { m -> emit(m.toString()) }
        }
    }.flowOn(Dispatchers.Default)

    fun liberar() {
        runCatching { motor?.close() }
        motor = null; rutaCargada = null
    }
}
