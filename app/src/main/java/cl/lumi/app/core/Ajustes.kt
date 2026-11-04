package cl.lumi.app.core

import android.content.Context
import org.json.JSONObject

/** Qué puede hacer Lumi sin preguntar. El usuario decide cada categoría. */
enum class Categoria(val titulo: String, val porDefecto: Nivel) {
    ABRIR_APPS("Abrir apps", Nivel.PREGUNTAR),
    ALARMAS("Alarmas y temporizadores", Nivel.PREGUNTAR),
    RECORDATORIOS("Recordatorios", Nivel.PREGUNTAR),
    VOLUMEN("Volumen", Nivel.PERMITIDO),
    MUSICA("Pausar / siguiente / anterior", Nivel.PERMITIDO),
    SPOTIFY("Poner música en Spotify", Nivel.PERMITIDO),
    WEB("Buscar en la web y YouTube", Nivel.PERMITIDO),
    INICIO("Ir a la pantalla de inicio", Nivel.PREGUNTAR),
    RELOJ("Smartwatch: leer datos, medir y enviar avisos", Nivel.PERMITIDO),
}

enum class Nivel(val titulo: String) {
    PERMITIDO("Permitido"),
    PREGUNTAR("Preguntar antes"),
    BLOQUEADO("Bloqueado"),
}

enum class ModoIa(val titulo: String) {
    AUTO("Automático (PC si está, si no el teléfono)"),
    PC("Solo el Ollama del PC"),
    TELEFONO("Solo el modelo del teléfono"),
}

/** Temas del globo, con los mismos nombres que en el PC. */
enum class Tema(val titulo: String, val fondo: Int, val texto: Int, val acento: Int) {
    LUZ("Luz", 0xFFFFFFFF.toInt(), 0xFF1E1E24.toInt(), 0xFF7C5CFF.toInt()),
    TINTA("Tinta", 0xFF1B1B1F.toInt(), 0xFFF2F2F2.toInt(), 0xFFFF5C7A.toInt()),
    NOCHE("Noche", 0xFF101828.toInt(), 0xFFE6ECFF.toInt(), 0xFF6EA8FE.toInt()),
    PASTEL("Pastel", 0xFFFFF4F8.toInt(), 0xFF3B2F3F.toInt(), 0xFFF29BC2.toInt()),
    BOSQUE("Bosque", 0xFFEAF3EC.toInt(), 0xFF1E3A28.toInt(), 0xFF3E8E5E.toInt()),
}

class Ajustes(context: Context) {
    private val p = context.getSharedPreferences("lumi_ajustes", Context.MODE_PRIVATE)

    // --- IA ---
    var modoIa: ModoIa
        get() = runCatching { ModoIa.valueOf(p.getString("modoIa", null)!!) }.getOrDefault(ModoIa.AUTO)
        set(v) = p.edit().putString("modoIa", v.name).apply()
    var ollamaUrl: String
        get() = p.getString("ollamaUrl", "http://192.168.1.10:11434")!!
        set(v) = p.edit().putString("ollamaUrl", v.trim().trimEnd('/')).apply()
    var ollamaModelo: String
        get() = p.getString("ollamaModelo", "gemma3:4b")!!
        set(v) = p.edit().putString("ollamaModelo", v.trim()).apply()
    var modeloLocal: String?
        get() = p.getString("modeloLocal", null)
        set(v) = p.edit().putString("modeloLocal", v).apply()

    // --- Voz ---
    var hablar: Boolean
        get() = p.getBoolean("hablar", true)
        set(v) = p.edit().putBoolean("hablar", v).apply()
    var vozNombre: String?
        get() = p.getString("vozNombre", null)
        set(v) = p.edit().putString("vozNombre", v).apply()
    var vozVelocidad: Float
        get() = p.getFloat("vozVelocidad", 1.0f)
        set(v) = p.edit().putFloat("vozVelocidad", v).apply()
    var vozTono: Float
        get() = p.getFloat("vozTono", 1.1f)
        set(v) = p.edit().putFloat("vozTono", v).apply()

    // --- Apariencia ---
    var tema: Tema
        get() = runCatching { Tema.valueOf(p.getString("tema", null)!!) }.getOrDefault(Tema.LUZ)
        set(v) = p.edit().putString("tema", v.name).apply()
    var tamanoPersonajeDp: Int
        get() = p.getInt("tamano", 140)
        set(v) = p.edit().putInt("tamano", v).apply()
    var mostrarMic: Boolean
        get() = p.getBoolean("mostrarMic", true)
        set(v) = p.edit().putBoolean("mostrarMic", v).apply()
    var mostrarPie: Boolean
        get() = p.getBoolean("mostrarPie", true)
        set(v) = p.edit().putBoolean("mostrarPie", v).apply()
    var mostrarGlobo: Boolean
        get() = p.getBoolean("mostrarGlobo", true)
        set(v) = p.edit().putBoolean("mostrarGlobo", v).apply()

    /** Apps en las que Lumi se esconde sola (juegos, videos, etc.). */
    fun appsOcultar(): Set<String> = p.getStringSet("appsOcultar", emptySet())!!.toSet()
    fun fijarAppsOcultar(paquetes: Set<String>) = p.edit().putStringSet("appsOcultar", paquetes).apply()

    var sentarseEnBordes: Boolean
        get() = p.getBoolean("bordes", true)
        set(v) = p.edit().putBoolean("bordes", v).apply()

    var pasear: Boolean
        get() = p.getBoolean("pasear", true)
        set(v) = p.edit().putBoolean("pasear", v).apply()

    // --- Otros ---
    var ciudadClima: String
        get() = p.getString("ciudad", "Santiago")!!
        set(v) = p.edit().putString("ciudad", v.trim()).apply()
    var spotifyClientId: String
        get() = p.getString("spotifyClientId", "")!!
        set(v) = p.edit().putString("spotifyClientId", v.trim()).apply()

    // --- Permisos ---
    fun nivel(c: Categoria): Nivel =
        runCatching { Nivel.valueOf(p.getString("perm_${c.name}", null)!!) }.getOrDefault(c.porDefecto)

    fun fijarNivel(c: Categoria, n: Nivel) = p.edit().putString("perm_${c.name}", n.name).apply()

    // --- Atajos propios: "música" -> com.spotify.music ---
    fun atajos(): Map<String, String> {
        val json = JSONObject(p.getString("atajos", "{}")!!)
        return json.keys().asSequence().associateWith { json.getString(it) }
    }

    fun guardarAtajo(alias: String, paquete: String) {
        val json = JSONObject(p.getString("atajos", "{}")!!)
        json.put(Texto.normalizar(alias).trim(), paquete)
        p.edit().putString("atajos", json.toString()).apply()
    }

    fun borrarAtajo(alias: String) {
        val json = JSONObject(p.getString("atajos", "{}")!!)
        json.remove(alias)
        p.edit().putString("atajos", json.toString()).apply()
    }
}
