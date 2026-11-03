package cl.lumi.app.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * Recibe la vuelta del login de Spotify en http://127.0.0.1:8765/callback, igual que en el PC.
 * Spotify solo acepta http para direcciones loopback, y así no hace falta un dominio propio.
 * Escucha solo en 127.0.0.1: nada fuera del teléfono puede conectarse.
 */
object ServidorLoopback {
    const val PUERTO = 8765
    const val REDIRECT = "http://127.0.0.1:$PUERTO/callback"

    data class Respuesta(val code: String?, val state: String?, val error: String?)

    suspend fun esperar(timeoutMs: Int = 180_000): Respuesta? = withContext(Dispatchers.IO) {
        ServerSocket(PUERTO, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = timeoutMs
            while (true) {
                val sock = try { server.accept() } catch (_: SocketTimeoutException) { return@withContext null }
                sock.use { s ->
                    s.soTimeout = 5000
                    val linea = s.getInputStream().bufferedReader().readLine() ?: return@use
                    // "GET /callback?code=...&state=... HTTP/1.1"
                    val ruta = linea.split(" ").getOrNull(1) ?: return@use
                    if (!ruta.startsWith("/callback")) {
                        responder(s.getOutputStream(), 404, "No encontrado")
                        return@use
                    }
                    val params = ruta.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
                        val (k, v) = it.split('=', limit = 2)
                        k to URLDecoder.decode(v, "UTF-8")
                    }
                    val ok = params["code"] != null
                    responder(
                        s.getOutputStream(), 200,
                        if (ok) "Listo, Spotify quedó conectado. Ya puedes volver a Lumi."
                        else "Spotify no entregó el permiso (${params["error"] ?: "sin detalle"}). Vuelve a Lumi e inténtalo otra vez."
                    )
                    return@withContext Respuesta(params["code"], params["state"], params["error"])
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }

    private fun responder(out: java.io.OutputStream, codigo: Int, mensaje: String) {
        val html = """<!doctype html><html lang="es"><meta charset="utf-8">
            |<meta name="viewport" content="width=device-width,initial-scale=1">
            |<body style="font-family:sans-serif;padding:32px;font-size:18px">$mensaje</body></html>""".trimMargin()
        val cuerpo = html.toByteArray(Charsets.UTF_8)
        val estado = if (codigo == 200) "OK" else "Not Found"
        out.write("HTTP/1.1 $codigo $estado\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${cuerpo.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(cuerpo)
        out.flush()
    }
}
