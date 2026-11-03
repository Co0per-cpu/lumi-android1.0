package cl.lumi.app.acciones

import cl.lumi.app.net.Red
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Consultas de información (no son acciones): Wikipedia y clima. */
object Info {

    private fun get(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", "Lumi/0.1 (asistente personal)").build()
        Red.cliente.newCall(req).execute().use { r -> return if (r.isSuccessful) r.body?.string() else null }
    }

    /** Resumen corto de Wikipedia en español, o null si no hay artículo. */
    suspend fun wikipedia(q: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val buscar = "https://es.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "opensearch").addQueryParameter("search", q)
                .addQueryParameter("limit", "1").addQueryParameter("namespace", "0")
                .addQueryParameter("format", "json").build().toString()
            val titulo = JSONArray(get(buscar) ?: return@runCatching null).getJSONArray(1).optString(0)
            if (titulo.isNullOrBlank()) return@runCatching null
            val url = "https://es.wikipedia.org/api/rest_v1/page/summary/".toHttpUrl().newBuilder()
                .addPathSegment(titulo.replace(' ', '_')).build().toString()
            val json = JSONObject(get(url) ?: return@runCatching null)
            if (json.optString("type") == "disambiguation") return@runCatching null
            val extracto = json.optString("extract").trim()
            if (extracto.isEmpty()) null
            else extracto.split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" ").take(420)
        }.getOrNull()
    }

    data class Clima(val lugar: String, val texto: String)

    suspend fun clima(ciudad: String): Clima? = withContext(Dispatchers.IO) {
        runCatching {
            val geo = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("name", ciudad).addQueryParameter("count", "1")
                .addQueryParameter("language", "es").build().toString()
            val lugar = JSONObject(get(geo) ?: return@runCatching null).optJSONArray("results")?.optJSONObject(0)
                ?: return@runCatching null
            val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
                .addQueryParameter("latitude", lugar.getDouble("latitude").toString())
                .addQueryParameter("longitude", lugar.getDouble("longitude").toString())
                .addQueryParameter("current", "temperature_2m,weather_code")
                .addQueryParameter("daily", "temperature_2m_max,temperature_2m_min,precipitation_probability_max")
                .addQueryParameter("timezone", "auto").addQueryParameter("forecast_days", "1")
                .build().toString()
            val j = JSONObject(get(url) ?: return@runCatching null)
            val actual = j.getJSONObject("current")
            val diario = j.getJSONObject("daily")
            val t = actual.getDouble("temperature_2m").roundToInt()
            val max = diario.getJSONArray("temperature_2m_max").getDouble(0).roundToInt()
            val min = diario.getJSONArray("temperature_2m_min").getDouble(0).roundToInt()
            val lluvia = diario.getJSONArray("precipitation_probability_max").optInt(0, -1)
            val nombre = lugar.optString("name", ciudad)
            val desc = descripcion(actual.optInt("weather_code", -1))
            val extraLluvia = if (lluvia >= 0) " Probabilidad de lluvia: $lluvia%." else ""
            Clima(nombre, "En $nombre hay $t grados y está $desc. Hoy, entre $min y $max grados.$extraLluvia")
        }.getOrNull()
    }

    private fun descripcion(codigo: Int): String = when (codigo) {
        0 -> "despejado"
        1, 2 -> "parcialmente nublado"
        3 -> "nublado"
        45, 48 -> "con niebla"
        51, 53, 55, 56, 57 -> "lloviznando"
        61, 63, 65, 66, 67, 80, 81, 82 -> "lloviendo"
        71, 73, 75, 77, 85, 86 -> "nevando"
        95, 96, 99 -> "con tormenta"
        else -> "variable"
    }
}
