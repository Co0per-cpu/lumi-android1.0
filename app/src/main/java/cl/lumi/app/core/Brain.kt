package cl.lumi.app.core

import java.util.Calendar

/** Lo que Lumi entendió. Si no es una orden, es Charla y va a la IA. */
sealed class Orden {
    object Frenar : Orden()
    object QueSuena : Orden()
    object Pausa : Orden()
    object Reanudar : Orden()
    object Siguiente : Orden()
    object Anterior : Orden()
    object Hora : Orden()
    object VolumenSubir : Orden()
    object VolumenBajar : Orden()
    object Silenciar : Orden()
    data class VolumenFijar(val porcentaje: Int) : Orden()
    data class Alarma(val hora: Int, val minuto: Int) : Orden()
    data class Temporizador(val segundos: Int) : Orden()
    data class Recordatorio(val cuandoMillis: Long, val texto: String) : Orden()
    object SpotifyMeGusta : Orden()
    data class SpotifyCancion(val q: String) : Orden()
    data class SpotifyArtista(val q: String) : Orden()
    data class SpotifyPlaylist(val q: String) : Orden()
    data class SpotifyAlbum(val q: String) : Orden()
    data class Abrir(val nombre: String) : Orden()
    data class Cerrar(val nombre: String) : Orden()
    data class YouTube(val q: String) : Orden()
    data class Wikipedia(val q: String, val original: String) : Orden()
    data class Web(val q: String) : Orden()
    data class Clima(val ciudad: String?) : Orden()
    data class Charla(val texto: String) : Orden()
}

/**
 * Entiende órdenes en español de Chile. Es el equivalente a core/brain.js del PC:
 * limpia muletillas ("oye", "¿me puedes…?", "quiero…") y reconoce la acción.
 * Busca sobre el texto normalizado y recorta el original en las mismas posiciones,
 * para que nombres y búsquedas conserven sus tildes.
 */
object Brain {

    private const val NUM =
        "(\\d{1,3}|un|una|uno|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce|quince|veinte|treinta|cuarenta|cincuenta|media)"

    private val numeros = mapOf(
        "un" to 1, "una" to 1, "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5,
        "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12,
        "quince" to 15, "veinte" to 20, "treinta" to 30, "cuarenta" to 40, "cincuenta" to 50,
    )

    private fun numero(s: String): Int? = s.toIntOrNull() ?: numeros[s]

    private val reInicio = Regex(
        "^(oye|oie|ey|hey|hola|lumi|ya|po|bueno|ok|okay|okey|porfa|por favor|amiga|amigo|mira|che)[\\s,.!¡]+"
    )
    private val rePedido = Regex(
        "^(me puedes|puedes|podrias|me podrias|me haces el favor de|haz el favor de|quiero que|quiero|necesito que|necesito|me gustaria|ayudame a|vamos a|dale|anda y)\\s+"
    )
    private val reFin = Regex("(?:[\\s,]+(?:por favor|porfa|porfis|plis|po|gracias|ya))*[\\s?!.¿¡,]*$")

    /** Quita muletillas al principio y al final. */
    fun limpiar(entrada: String): String {
        var o = entrada.trim().trimStart('¿', '¡', ' ')
        while (true) {
            val n = Texto.normalizar(o)
            val m = reInicio.find(n) ?: rePedido.find(n) ?: break
            o = o.substring(m.range.last + 1).trimStart('¿', '¡', ' ')
        }
        val m = reFin.find(Texto.normalizar(o))
        if (m != null && m.range.first < o.length) o = o.substring(0, m.range.first)
        return o.trim()
    }

    fun entender(entrada: String, ahora: Calendar = Calendar.getInstance()): Orden {
        val o = limpiar(entrada)
        val n = Texto.normalizar(o)
        if (n.isBlank()) return Orden.Charla(entrada)

        fun grupo(m: MatchResult, i: Int): String {
            val g = m.groups[i] ?: return ""
            return o.substring(g.range.first, g.range.last + 1).trim()
        }

        // --- Freno y controles de música ---
        if (Regex("^(pausa|pausar|pausala|pon pausa|para la musica|deten la musica|para la cancion|deten la cancion)$").matches(n)) return Orden.Pausa
        if (Regex("^(para|frena|frenate|detente|basta|stop|alto|callate|cancela|cancelalo)( todo| ya| eso)?$").matches(n)) return Orden.Frenar
        if (Regex("^(sigue|continua|reanuda|play|dale play|pon play|reanuda la musica|sigue la musica|continua la musica|pon musica|pon la musica|pon algo de musica)$").matches(n)) return Orden.Reanudar
        if (Regex("^(siguiente|la siguiente|siguiente cancion|salta|saltala|salta la cancion|salta esta|cambia la cancion|cambia de cancion|otra cancion|pasa la cancion|pasala)$").matches(n)) return Orden.Siguiente
        if (Regex("^(anterior|la anterior|cancion anterior|vuelve a la anterior|la de antes|pon la anterior)$").matches(n)) return Orden.Anterior
        if (Regex("(que (cancion )?(esta sonando|suena|es esta|es esto que suena)|como se llama (esta|la) cancion|que estoy escuchando)").containsMatchIn(n)) return Orden.QueSuena
        if (Regex("^(que hora es|me dices la hora|dime la hora|la hora)$").matches(n)) return Orden.Hora

        // --- Volumen ---
        if (Regex("^(silencio|silencia|silencialo|mutea|silencia el (telefono|celular|volumen)|sin sonido|quita el sonido)$").matches(n)) return Orden.Silenciar
        Regex("(volumen|sonido)\\s+(al|a|en)\\s+(\\d{1,3})").find(n)?.let { m ->
            return Orden.VolumenFijar(m.groupValues[3].toInt().coerceIn(0, 100))
        }
        if (Regex("(volumen|sonido)\\s+(al\\s+)?maximo").containsMatchIn(n)) return Orden.VolumenFijar(100)
        if (Regex("^(sube|subele|subir|aumenta|mas)\\s+(el\\s+|un poco el\\s+)?(volumen|sonido)").containsMatchIn(n)) return Orden.VolumenSubir
        if (Regex("^(baja|bajale|bajar|disminuye|menos)\\s+(el\\s+|un poco el\\s+)?(volumen|sonido)").containsMatchIn(n)) return Orden.VolumenBajar

        // --- Alarma ---
        if (Regex("^(despiertame|levantame|alarma|pon una alarma|ponme una alarma|pon alarma|crea una alarma|programa una alarma|ponme alarma)\\b").containsMatchIn(n)) {
            val h = buscarHora(n, 0)
            if (h != null) return Orden.Alarma(h.hora, h.minuto)
            return Orden.Charla(entrada.trim())
        }

        // --- Temporizador ---
        if (Regex("^(temporizador|timer|cronometro|cuenta regresiva|pon un temporizador|ponme un temporizador)\\b").containsMatchIn(n)) {
            val d = buscarDuracion(n)
            if (d != null) return Orden.Temporizador(d.segundos)
            return Orden.Charla(entrada.trim())
        }

        // --- Recordatorio ---
        Regex("^(recuerdame|recordarme|avisame|ponme un recordatorio|pon un recordatorio|crea un recordatorio)\\s+(.+)$").find(n)?.let { m ->
            val desde = m.groups[2]!!.range.first
            val resto = n.substring(desde)
            val restoO = o.substring(desde)
            var cuando: Long? = null
            var rango: IntRange? = null
            val enDur = Regex("\\ben\\s+").let { re ->
                re.findAll(resto).firstNotNullOfOrNull { e ->
                    buscarDuracion(resto.substring(e.range.last + 1))?.takeIf { it.inicio == 0 }
                        ?.let { d -> d to (e.range.first..(e.range.last + 1 + d.fin)) }
                }
            }
            if (enDur != null) {
                cuando = ahora.timeInMillis + enDur.first.segundos * 1000L
                rango = enDur.second
            } else {
                val h = buscarHora(resto, 0)
                if (h != null) {
                    val manana = Regex("\\bmanana\\b").containsMatchIn(resto)
                    cuando = proximaHora(ahora, h, manana, ambigua = !h.calificada)
                    rango = h.rango
                }
            }
            if (cuando != null && rango != null) {
                var texto = (restoO.removeRange(rango.first, minOf(rango.last + 1, restoO.length)))
                texto = Regex("\\b(manana|mañana)\\b", RegexOption.IGNORE_CASE).replace(texto, " ")
                texto = texto.trim().replace(Regex("\\s{2,}"), " ")
                texto = texto.replace(Regex("^(que|de|para|a)\\s+", RegexOption.IGNORE_CASE), "").trim()
                if (texto.isEmpty()) texto = "lo que me pediste"
                return Orden.Recordatorio(cuando, texto)
            }
        }

        // --- YouTube ---
        Regex("^(pon|ponme|busca|buscame|abre|reproduce|ve|mira|quiero ver)\\s+(.+?)\\s+en\\s+youtube$").find(n)?.let { return Orden.YouTube(grupo(it, 2)) }
        Regex("^(busca en youtube|youtube)\\s+(.+)$").find(n)?.let { return Orden.YouTube(grupo(it, 2)) }

        // --- Spotify ---
        val pon = "(pon|ponme|reproduce|reproduceme|toca|tocame|quiero escuchar|escuchar)"
        val enSpotify = "(\\s+en\\s+spotify)?"
        if (Regex("^$pon\\s+(mis\\s+|las\\s+)?(me gusta|me gustas|canciones que me gustan|canciones favoritas|favoritas|canciones guardadas)$enSpotify$").matches(n)) return Orden.SpotifyMeGusta
        Regex("^$pon\\s+(la\\s+|mi\\s+|una\\s+)?(playlist|lista)\\s+(de\\s+|que se llama\\s+)?(.+?)$enSpotify$").find(n)?.let { return Orden.SpotifyPlaylist(grupo(it, 5)) }
        Regex("^$pon\\s+(el\\s+)?(album|disco)\\s+(de\\s+)?(.+?)$enSpotify$").find(n)?.let { return Orden.SpotifyAlbum(grupo(it, 5)) }
        Regex("^($pon\\s+)?(musica|canciones|temas|algo)\\s+de\\s+(.+?)$enSpotify$").find(n)?.let { return Orden.SpotifyArtista(grupo(it, 4)) }
        Regex("^$pon\\s+(la\\s+cancion\\s+|el\\s+tema\\s+)?(.+?)$enSpotify$").find(n)?.let {
            val q = grupo(it, 3)
            if (q.isNotBlank()) return Orden.SpotifyCancion(q)
        }

        // --- Apps ---
        Regex("^(abre|abreme|abrir|inicia|lanza|ejecuta|entra a|entra en|anda a|ve a)\\s+(la\\s+app\\s+|la\\s+aplicacion\\s+|el\\s+|la\\s+)?(.+)$").find(n)?.let { return Orden.Abrir(grupo(it, 3)) }
        Regex("^(cierra|cerrar|cierrame|mata)\\s+(la\\s+app\\s+|la\\s+aplicacion\\s+|el\\s+|la\\s+)?(.+)$").find(n)?.let { return Orden.Cerrar(grupo(it, 3)) }

        // --- Clima ---
        if (Regex("\\b(clima|el tiempo|temperatura|va a llover|llueve|lloviendo|pronostico|hace frio|hace calor)\\b").containsMatchIn(n)) {
            val c = Regex("\\ben\\s+(.+)$").find(n)?.let { grupo(it, 1) }
            val ciudad = c?.takeUnless { Regex("^(la|el|esta|este)\\b").containsMatchIn(Texto.normalizar(it)) }
            return Orden.Clima(ciudad?.takeIf { it.isNotBlank() })
        }

        // --- Wikipedia y web ---
        Regex("^(que es|que son|quien es|quien fue|quien era|que significa|que fue|wikipedia|busca en wikipedia|dime que es|cuentame sobre|cuentame de|hablame de)\\s+(un\\s+|una\\s+|el\\s+|la\\s+|los\\s+|las\\s+)?(.+)$").find(n)?.let {
            return Orden.Wikipedia(grupo(it, 3), entrada)
        }
        Regex("^(busca en google|busca en internet|busca|buscame|googlea|investiga)\\s+(.+)$").find(n)?.let { return Orden.Web(grupo(it, 2)) }

        return Orden.Charla(entrada.trim())
    }

    // ---------- Horas y duraciones ----------

    data class HoraEncontrada(val hora: Int, val minuto: Int, val calificada: Boolean, val rango: IntRange)
    data class Duracion(val segundos: Int, val inicio: Int, val fin: Int)

    private const val HORA_NUM = "(\\d{1,2}|una|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez|once|doce)"
    private const val HORA_RESTO =
        "(?:\\s*[:.]\\s*(\\d{2})|\\s+y\\s+(media|cuarto|\\d{1,2})|\\s+(\\d{2})\\b)?" +
            "(?:\\s*(am|pm|a\\.\\s?m\\.|p\\.\\s?m\\.|de la manana|de la tarde|de la noche|de la madrugada|hrs|horas)\\b)?"
    private val reHoraFirme = Regex("\\b(?:a las|para las|las|a la)\\s+$HORA_NUM$HORA_RESTO")
    private val reHoraSuelta = Regex("\\ba\\s+$HORA_NUM$HORA_RESTO")

    /** Busca "a las 7", "a las 7:30", "a las 7 y media de la tarde", "a las 19 hrs". */
    fun buscarHora(n: String, desde: Int): HoraEncontrada? {
        val m = reHoraFirme.find(n, desde) ?: reHoraSuelta.find(n, desde) ?: return null
        var h = numero(m.groupValues[1]) ?: return null
        val min = when {
            m.groupValues[2].isNotEmpty() -> m.groupValues[2].toInt()
            m.groupValues[3] == "media" -> 30
            m.groupValues[3] == "cuarto" -> 15
            m.groupValues[3].isNotEmpty() -> m.groupValues[3].toInt()
            m.groupValues[4].isNotEmpty() -> m.groupValues[4].toInt()
            else -> 0
        }
        val cal = m.groupValues[5]
        var calificada = h > 12 || cal.isNotEmpty()
        when {
            cal.startsWith("p") || cal == "de la tarde" || cal == "de la noche" -> if (h < 12) h += 12
            cal.startsWith("a") || cal == "de la manana" || cal == "de la madrugada" -> if (h == 12) h = 0
            cal == "hrs" || cal == "horas" -> calificada = true
        }
        if (h !in 0..23 || min !in 0..59) return null
        return HoraEncontrada(h, min, calificada, m.range)
    }

    private val reDuracion = Regex(
        "(?:(un|una)\\s+hora\\s+y\\s+media|$NUM\\s+(segundos?|minutos?|mins?|horas?)(\\s+y\\s+media)?)"
    )

    /** "5 minutos", "media hora", "una hora y media", "dos horas y media". */
    fun buscarDuracion(n: String): Duracion? {
        val m = reDuracion.find(n) ?: return null
        val seg: Int = if (m.groupValues[1].isNotEmpty()) {
            90 * 60
        } else {
            val unidad = m.groupValues[3]
            val porUnidad = when {
                unidad.startsWith("seg") -> 1
                unidad.startsWith("min") -> 60
                else -> 3600
            }
            val cant = m.groupValues[2]
            val base = if (cant == "media") porUnidad / 2 else (numero(cant) ?: return null) * porUnidad
            base + if (m.groupValues[4].isNotEmpty()) porUnidad / 2 else 0
        }
        if (seg <= 0) return null
        return Duracion(seg, m.range.first, m.range.last)
    }

    /** Próxima vez que llegue esa hora. Si es ambigua ("a las 6"), elige la más cercana entre 6:00 y 18:00. */
    fun proximaHora(ahora: Calendar, h: HoraEncontrada, manana: Boolean, ambigua: Boolean): Long {
        fun en(hora: Int): Calendar = (ahora.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hora); set(Calendar.MINUTE, h.minuto)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (manana) add(Calendar.DAY_OF_YEAR, 1)
            else if (!after(ahora)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val a = en(h.hora)
        if (!ambigua || h.hora >= 12 || manana) return a.timeInMillis
        val b = en(h.hora + 12)
        return minOf(a.timeInMillis, b.timeInMillis)
    }
}
