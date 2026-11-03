package cl.lumi.app.spotify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import cl.lumi.app.LumiApp
import cl.lumi.app.acciones.Resultado
import cl.lumi.app.core.Freno
import cl.lumi.app.core.Orden
import cl.lumi.app.core.Texto
import cl.lumi.app.net.Red
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Spotify con la Web API y PKCE, igual que en el PC: tú pones tu propio Client ID,
 * los tokens quedan cifrados en el teléfono. Controlar la reproducción requiere Premium.
 */
class Spotify(private val ctx: Context) {

    private val seguro = Seguro(ctx)
    private val ajustes get() = LumiApp.instancia.ajustes
    private val json = "application/json".toMediaType()

    fun conectado(): Boolean = seguro.leer(REFRESH) != null

    fun desconectar() {
        seguro.guardar(ACCESS, null); seguro.guardar(REFRESH, null); seguro.guardar(EXPIRA, null)
    }

    // ---------------- Login ----------------

    /** Abre el login en el navegador y espera la vuelta en 127.0.0.1:8765. Devuelve un mensaje para mostrar. */
    suspend fun conectar(abrir: (Uri) -> Unit): String {
        val clientId = ajustes.spotifyClientId
        if (clientId.isBlank()) return "Primero pega tu Client ID de Spotify."
        val verifier = aleatorio(64)
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = aleatorio(16)
        val url = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", ServidorLoopback.REDIRECT)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SCOPES)
            .build()

        val r = coroutineScope {
            val espera = async { ServidorLoopback.esperar() }
            delay(300) // que el servidor alcance a abrir el puerto
            abrir(url)
            espera.await()
        } ?: return "Se acabó el tiempo esperando a Spotify. Inténtalo de nuevo."

        if (r.error != null) return "Spotify respondió: ${r.error}."
        if (r.state != state || r.code == null) return "La respuesta de Spotify no coincide; por seguridad no la usé."

        val form = FormBody.Builder()
            .add("grant_type", "authorization_code").add("code", r.code)
            .add("redirect_uri", ServidorLoopback.REDIRECT).add("client_id", clientId)
            .add("code_verifier", verifier).build()
        return withContext(Dispatchers.IO) {
            val req = Request.Builder().url("https://accounts.spotify.com/api/token").post(form).build()
            Red.cliente.newCall(req).execute().use { resp ->
                val cuerpo = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext "No pude canjear el permiso (${resp.code}). Revisa que el Redirect URI en Spotify sea ${ServidorLoopback.REDIRECT}."
                guardarTokens(JSONObject(cuerpo))
                "Spotify conectado."
            }
        }
    }

    private fun guardarTokens(j: JSONObject) {
        seguro.guardar(ACCESS, j.getString("access_token"))
        j.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { seguro.guardar(REFRESH, it) }
        val expira = System.currentTimeMillis() + (j.optLong("expires_in", 3600) - 60) * 1000
        seguro.guardar(EXPIRA, expira.toString())
    }

    private suspend fun token(): String? = withContext(Dispatchers.IO) {
        val expira = seguro.leer(EXPIRA)?.toLongOrNull() ?: 0
        val access = seguro.leer(ACCESS)
        if (access != null && System.currentTimeMillis() < expira) return@withContext access
        refrescar()
    }

    private fun refrescar(): String? {
        val refresh = seguro.leer(REFRESH) ?: return null
        val form = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh)
            .add("client_id", ajustes.spotifyClientId).build()
        val req = Request.Builder().url("https://accounts.spotify.com/api/token").post(form).build()
        return runCatching {
            Red.cliente.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val j = JSONObject(resp.body!!.string())
                guardarTokens(j)
                j.getString("access_token")
            }
        }.getOrNull()
    }

    private data class Resp(val codigo: Int, val cuerpo: String)

    private suspend fun api(metodo: String, ruta: String, body: JSONObject? = null, query: Map<String, String> = emptyMap()): Resp =
        withContext(Dispatchers.IO) {
            var tk = token() ?: return@withContext Resp(401, "")
            repeat(2) { intento ->
                val url = "https://api.spotify.com/v1$ruta".toHttpUrl().newBuilder().apply {
                    query.forEach { (k, v) -> addQueryParameter(k, v) }
                }.build()
                val cuerpo = when {
                    body != null -> body.toString().toRequestBody(json)
                    metodo == "GET" -> null
                    else -> ByteArray(0).toRequestBody(null)
                }
                val req = Request.Builder().url(url).method(metodo, cuerpo).header("Authorization", "Bearer $tk").build()
                val r = runCatching {
                    Red.cliente.newCall(req).execute().use { Resp(it.code, it.body?.string().orEmpty()) }
                }.getOrElse { Resp(-1, it.message.orEmpty()) }
                if (r.codigo == 401 && intento == 0) {
                    tk = refrescar() ?: return@withContext r
                } else return@withContext r
            }
            Resp(-1, "")
        }

    // ---------------- Dispositivo ----------------

    private suspend fun dispositivo(): String? {
        val r = api("GET", "/me/player/devices")
        if (r.codigo != 200) return null
        val lista = JSONObject(r.cuerpo).optJSONArray("devices") ?: return null
        var activo: String? = null; var telefono: String? = null; var cualquiera: String? = null
        for (k in 0 until lista.length()) {
            val d = lista.getJSONObject(k)
            if (d.optBoolean("is_restricted")) continue
            val id = d.optString("id").takeIf { it.isNotEmpty() } ?: continue
            if (d.optBoolean("is_active")) activo = id
            if (d.optString("type") == "Smartphone") telefono = id
            if (cualquiera == null) cualquiera = id
        }
        return activo ?: telefono ?: cualquiera
    }

    /** Si Spotify no está abierto en ningún lado, lo abre en el teléfono y espera a que aparezca. */
    private suspend fun asegurarDispositivo(): String? {
        dispositivo()?.let { return it }
        val lanzar = ctx.packageManager.getLaunchIntentForPackage("com.spotify.music") ?: return null
        ctx.startActivity(lanzar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        repeat(12) {
            delay(700)
            dispositivo()?.let { return it }
        }
        return null
    }

    // ---------------- Reproducir ----------------

    suspend fun reproducir(orden: Orden, token: Int): Resultado {
        if (!conectado()) return Resultado.falla("Spotify no está conectado. Conéctalo en Ajustes de Lumi.")

        // 1) Encontrar qué poner
        val (cuerpo, nombre, esperado) = when (orden) {
            is Orden.SpotifyCancion -> buscar(orden.q, "track")?.let { t ->
                Triple(JSONObject().put("uris", JSONArray().put(t.uri)), t.nombre, setOf(t.uri))
            }
            is Orden.SpotifyArtista -> buscar(orden.q, "artist")?.let { a ->
                Triple(JSONObject().put("context_uri", a.uri), "música de ${a.nombre}", setOf(a.uri))
            }
            is Orden.SpotifyAlbum -> buscar(orden.q, "album")?.let { a ->
                Triple(JSONObject().put("context_uri", a.uri), "el álbum ${a.nombre}", setOf(a.uri))
            }
            is Orden.SpotifyPlaylist -> (miPlaylist(orden.q) ?: buscar(orden.q, "playlist"))?.let { p ->
                Triple(JSONObject().put("context_uri", p.uri), "la playlist ${p.nombre}", setOf(p.uri))
            }
            is Orden.SpotifyMeGusta -> meGusta()?.let { uris ->
                Triple(JSONObject().put("uris", JSONArray(uris)), "tus Me gusta", uris.toSet())
            }
            else -> null
        } ?: return Resultado.falla(
            when (orden) {
                is Orden.SpotifyMeGusta -> "No pude leer tus Me gusta de Spotify."
                else -> "No encontré eso en Spotify."
            }
        )
        if (!Freno.vigente(token)) return Resultado.info("Frenado.")

        // 2) Dónde
        val dev = asegurarDispositivo()
            ?: return Resultado.falla("No encontré Spotify abierto en ningún dispositivo. Abre Spotify y vuelve a pedírmelo.")
        if (!Freno.vigente(token)) return Resultado.info("Frenado.")

        // 3) Pedirlo
        val r = api("PUT", "/me/player/play", cuerpo, mapOf("device_id" to dev))
        if (r.codigo == 403) return Resultado.falla("Spotify no me dejó: controlar la música requiere Premium.")
        if (r.codigo !in 200..299) return Resultado.falla("Spotify rechazó la orden (${r.codigo}).")

        // 4) Comprobar que de verdad suena
        repeat(8) {
            delay(700)
            val estado = estado() ?: return@repeat
            val coincide = estado.item in esperado || estado.contexto in esperado
            if (estado.sonando && coincide) {
                return Resultado.ok("Listo, suena ${estado.descripcion ?: nombre}.")
            }
        }
        return Resultado.falla("Le pedí a Spotify $nombre, pero no me confirma que esté sonando.")
    }

    private data class Cosa(val uri: String, val nombre: String)

    private suspend fun buscar(q: String, tipo: String): Cosa? {
        val r = api("GET", "/search", query = mapOf("q" to q, "type" to tipo, "limit" to "10"))
        if (r.codigo != 200) return null
        val items = JSONObject(r.cuerpo).optJSONObject("${tipo}s")?.optJSONArray("items") ?: return null
        for (k in 0 until items.length()) {
            val it = items.optJSONObject(k) ?: continue // la API puede traer nulls en playlists
            val nombre = it.optString("name")
            val artista = it.optJSONArray("artists")?.optJSONObject(0)?.optString("name")
            return Cosa(it.getString("uri"), if (artista != null && tipo != "artist") "$nombre de $artista" else nombre)
        }
        return null
    }

    private suspend fun miPlaylist(q: String): Cosa? {
        val buscada = Texto.normalizar(q)
        var offset = 0
        val todas = mutableListOf<Cosa>()
        while (offset < 200) {
            val r = api("GET", "/me/playlists", query = mapOf("limit" to "50", "offset" to "$offset"))
            if (r.codigo != 200) break
            val items = JSONObject(r.cuerpo).optJSONArray("items") ?: break
            for (k in 0 until items.length()) {
                val p = items.optJSONObject(k) ?: continue
                todas += Cosa(p.getString("uri"), p.optString("name"))
            }
            if (items.length() < 50) break
            offset += 50
        }
        val norm = todas.map { it to Texto.normalizar(it.nombre) }
        return norm.firstOrNull { it.second == buscada }?.first
            ?: norm.firstOrNull { it.second.contains(buscada) }?.first
            ?: norm.minByOrNull { Texto.distancia(it.second, buscada) }?.takeIf { Texto.distancia(it.second, buscada) <= 2 }?.first
    }

    private suspend fun meGusta(): List<String>? {
        val r = api("GET", "/me/tracks", query = mapOf("limit" to "50"))
        if (r.codigo != 200) return null
        val items = JSONObject(r.cuerpo).optJSONArray("items") ?: return null
        val uris = (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optJSONObject("track")?.optString("uri") }
        return uris.takeIf { it.isNotEmpty() }
    }

    // ---------------- Estado y controles ----------------

    private data class Estado(val sonando: Boolean, val item: String?, val contexto: String?, val descripcion: String?)

    private suspend fun estado(): Estado? {
        val r = api("GET", "/me/player")
        if (r.codigo != 200 || r.cuerpo.isBlank()) return null
        val j = JSONObject(r.cuerpo)
        val item = j.optJSONObject("item")
        val desc = item?.let {
            val artista = it.optJSONArray("artists")?.optJSONObject(0)?.optString("name")
            if (artista != null) "${it.optString("name")} de $artista" else it.optString("name")
        }
        return Estado(j.optBoolean("is_playing"), item?.optString("uri"), j.optJSONObject("context")?.optString("uri"), desc)
    }

    /** Pausa / play / siguiente / anterior con comprobación. null = no hay nada activo en Spotify. */
    suspend fun control(orden: Orden): Resultado? {
        if (!conectado()) return null
        val antes = estado() ?: return null
        val (metodo, ruta) = when (orden) {
            Orden.Pausa -> "PUT" to "/me/player/pause"
            Orden.Reanudar -> "PUT" to "/me/player/play"
            Orden.Siguiente -> "POST" to "/me/player/next"
            Orden.Anterior -> "POST" to "/me/player/previous"
            else -> return null
        }
        if (orden == Orden.Pausa && !antes.sonando) return Resultado.info("No estaba sonando nada en Spotify.")
        val r = api(metodo, ruta)
        if (r.codigo == 403) return Resultado.falla("Spotify no me dejó: requiere Premium.")
        if (r.codigo !in 200..299) return Resultado.falla("Spotify rechazó la orden (${r.codigo}).")
        repeat(6) {
            delay(600)
            val e = estado() ?: return@repeat
            when (orden) {
                Orden.Pausa -> if (!e.sonando) return Resultado.ok("Pausé la música.")
                Orden.Reanudar -> if (e.sonando) return Resultado.ok("Listo, sigue ${e.descripcion ?: "la música"}.")
                else -> if (e.item != antes.item) return Resultado.ok("Ahora suena ${e.descripcion ?: "otra canción"}.")
            }
        }
        return Resultado.falla("Le mandé la orden a Spotify, pero no veo el cambio.")
    }

    suspend fun queSuena(): Resultado {
        if (!conectado()) return Resultado.info("Sin Spotify conectado no puedo ver qué suena. Lo puedes conectar en Ajustes.")
        val r = api("GET", "/me/player/currently-playing")
        if (r.codigo == 204 || r.cuerpo.isBlank()) return Resultado.info("No está sonando nada en Spotify.")
        if (r.codigo != 200) return Resultado.falla("No pude preguntarle a Spotify (${r.codigo}).")
        val j = JSONObject(r.cuerpo)
        val item = j.optJSONObject("item") ?: return Resultado.info("Spotify está reproduciendo algo, pero no me dice qué.")
        val artista = item.optJSONArray("artists")?.optJSONObject(0)?.optString("name")
        val pausa = if (j.optBoolean("is_playing")) "" else " (en pausa)"
        return Resultado.info("Es ${item.optString("name")}${if (artista != null) " de $artista" else ""}$pausa.")
    }

    companion object {
        private const val ACCESS = "sp_access"
        private const val REFRESH = "sp_refresh"
        private const val EXPIRA = "sp_expira"
        private const val SCOPES =
            "user-read-playback-state user-modify-playback-state user-read-currently-playing " +
                "playlist-read-private playlist-read-collaborative user-library-read"

        private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        private fun aleatorio(bytes: Int) = b64url(ByteArray(bytes).also { SecureRandom().nextBytes(it) })
    }
}
