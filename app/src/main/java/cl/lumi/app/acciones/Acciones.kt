package cl.lumi.app.acciones

import android.app.AlarmManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.view.KeyEvent
import cl.lumi.app.LumiApp
import cl.lumi.app.core.Categoria
import cl.lumi.app.core.Freno
import cl.lumi.app.core.Nivel
import cl.lumi.app.core.Orden
import cl.lumi.app.spotify.Spotify
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Ejecuta las órdenes. Reglas:
 *  - Revisa el permiso de cada categoría (Permitido / Preguntar / Bloqueado).
 *  - Comprueba que la acción pasó de verdad. Si no se puede comprobar, lo dice.
 *  - Anota todo en el registro.
 *  - Respeta el freno de emergencia entre pasos.
 */
class Acciones(private val ctx: Context, private val puente: Puente) {

    private val ajustes get() = LumiApp.instancia.ajustes
    private val registro get() = LumiApp.instancia.registro
    private val audio = ctx.getSystemService(AudioManager::class.java)
    val spotify = Spotify(ctx)

    /** Devuelve null si la orden es charla (va a la IA) o si Wikipedia no encontró nada. */
    suspend fun ejecutar(orden: Orden, token: Int): Resultado? {
        val r: Resultado? = when (orden) {
            is Orden.Charla -> return null
            Orden.Frenar -> Resultado.info("Frené todo.")
            Orden.Hora -> Resultado.info("Son las ${SimpleDateFormat("HH:mm", Locale.US).format(Date())}.")
            Orden.VolumenSubir, Orden.VolumenBajar, Orden.Silenciar, is Orden.VolumenFijar -> volumen(orden)
            Orden.Pausa, Orden.Reanudar, Orden.Siguiente, Orden.Anterior -> musica(orden)
            Orden.QueSuena -> spotify.queSuena()
            is Orden.Alarma -> alarma(orden)
            is Orden.Temporizador -> temporizador(orden)
            is Orden.Recordatorio -> recordatorio(orden)
            Orden.SpotifyMeGusta, is Orden.SpotifyCancion, is Orden.SpotifyArtista,
            is Orden.SpotifyPlaylist, is Orden.SpotifyAlbum -> spotifyPoner(orden, token)
            is Orden.Abrir -> abrir(orden.nombre, token)
            is Orden.Cerrar -> cerrar(orden.nombre)
            is Orden.YouTube -> youtube(orden.q)
            is Orden.Web -> web(orden.q)
            is Orden.Wikipedia -> Info.wikipedia(orden.q)?.let { Resultado.info(it) } ?: return null
            is Orden.Clima -> clima(orden.ciudad)
            Orden.RelojPulso -> relojPulso()
            Orden.RelojMedir -> relojMedir(token)
            Orden.RelojPasos -> relojPasos()
            Orden.RelojResumen -> relojResumen()
            Orden.RelojEjercicio -> relojEjercicio()
            Orden.RelojSincronizar -> relojSincronizar(token)
            is Orden.RelojAvisar -> relojAvisar(orden.texto)
        }
        return r
    }

    // ---------------- Permisos y registro ----------------

    /** null = puede seguir; si no, el resultado que hay que decir. */
    private suspend fun permiso(c: Categoria, pregunta: String): Resultado? = when (ajustes.nivel(c)) {
        Nivel.PERMITIDO -> null
        Nivel.BLOQUEADO -> Resultado.info("Tengo bloqueado \"${c.titulo.lowercase()}\". Lo puedes cambiar en Ajustes de Lumi.")
            .also { registro.anotar(c.titulo, pregunta, "bloqueado por permisos") }
        Nivel.PREGUNTAR -> {
            if (puente.confirmar(pregunta)) null
            else Resultado.info("Ok, no lo hago.").also { registro.anotar(c.titulo, pregunta, "rechazado por ti") }
        }
    }

    private fun anotar(c: Categoria, accion: String, r: Resultado): Resultado {
        val estado = when {
            !r.comprobado -> "SIN COMPROBAR"
            r.emocion == Emocion.TRISTE -> "FALLÓ"
            else -> "OK"
        }
        registro.anotar(c.titulo, accion, "$estado — ${r.texto}")
        return r
    }

    private fun desc(o: Orden): String = o.toString().substringAfterLast('$').substringBefore('@')

    private fun lanzar(i: Intent): Boolean = runCatching {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)

    // ---------------- Volumen ----------------

    private fun porcentaje(): Int {
        val s = AudioManager.STREAM_MUSIC
        return (audio.getStreamVolume(s) * 100f / audio.getStreamMaxVolume(s)).roundToInt()
    }

    private suspend fun volumen(o: Orden): Resultado {
        permiso(Categoria.VOLUMEN, "¿Cambio el volumen?")?.let { return it }
        val s = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(s)
        val antes = audio.getStreamVolume(s)
        val f = AudioManager.FLAG_SHOW_UI
        when (o) {
            Orden.Silenciar -> audio.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, f)
            Orden.VolumenSubir -> {
                if (audio.isStreamMute(s)) audio.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
                repeat(2) { audio.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, f) }
            }
            Orden.VolumenBajar -> repeat(2) { audio.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, f) }
            is Orden.VolumenFijar -> {
                if (audio.isStreamMute(s)) audio.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0)
                audio.setStreamVolume(s, (o.porcentaje * max / 100f).roundToInt(), f)
            }
            else -> {}
        }
        delay(200)
        val despues = audio.getStreamVolume(s)
        val r = when (o) {
            Orden.Silenciar -> {
                if (audio.isStreamMute(s) || despues == 0) Resultado.ok("Silencié el volumen.")
                else Resultado.falla("Intenté silenciar, pero Android no lo cambió.")
            }
            Orden.VolumenSubir -> when {
                despues > antes -> Resultado.ok("Subí el volumen a ${porcentaje()}%.")
                antes == max -> Resultado.info("Ya estaba al máximo.")
                else -> Resultado.falla("Intenté subir el volumen, pero Android no me dejó (quizás por el aviso de volumen seguro con audífonos).")
            }
            Orden.VolumenBajar -> when {
                despues < antes -> Resultado.ok("Bajé el volumen a ${porcentaje()}%.")
                antes == 0 -> Resultado.info("Ya estaba en cero.")
                else -> Resultado.falla("Intenté bajar el volumen, pero no cambió.")
            }
            is Orden.VolumenFijar -> {
                val p = porcentaje()
                if (abs(p - o.porcentaje) <= 100 / max + 1) Resultado.ok("Volumen al $p%.")
                else Resultado.falla("Pedí el volumen al ${o.porcentaje}%, pero quedó en $p%.")
            }
            else -> Resultado.falla("No entendí qué hacer con el volumen.")
        }
        return anotar(Categoria.VOLUMEN, desc(o), r)
    }

    // ---------------- Música ----------------

    private suspend fun musica(o: Orden): Resultado {
        permiso(Categoria.MUSICA, "¿Controlo la música?")?.let { return it }
        // Con Spotify conectado se puede comprobar todo; si no, teclas multimedia.
        spotify.control(o)?.let { return anotar(Categoria.MUSICA, desc(o), it) }

        val sonabaAntes = audio.isMusicActive
        if (o == Orden.Pausa && !sonabaAntes) return Resultado.info("No está sonando nada.")
        val tecla = when (o) {
            Orden.Pausa -> KeyEvent.KEYCODE_MEDIA_PAUSE
            Orden.Reanudar -> KeyEvent.KEYCODE_MEDIA_PLAY
            Orden.Siguiente -> KeyEvent.KEYCODE_MEDIA_NEXT
            else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, tecla))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, tecla))
        delay(1000)
        val suena = audio.isMusicActive
        val r = when (o) {
            Orden.Pausa -> if (!suena) Resultado.ok("Pausé la música.") else Resultado.falla("Mandé pausa, pero sigue sonando.")
            Orden.Reanudar -> if (suena) Resultado.ok("Listo, sigue la música.") else Resultado.falla("Mandé play, pero no suena nada. Puede que no haya ninguna app de música abierta.")
            else -> {
                if (suena) Resultado.sinComprobar("Mandé la orden. Suena música, pero sin Spotify conectado no puedo confirmar que cambió la canción.")
                else Resultado.falla("Mandé la orden, pero no suena nada.")
            }
        }
        return anotar(Categoria.MUSICA, desc(o), r)
    }

    private suspend fun spotifyPoner(o: Orden, token: Int): Resultado {
        val que = when (o) {
            is Orden.SpotifyCancion -> o.q
            is Orden.SpotifyArtista -> "música de ${o.q}"
            is Orden.SpotifyPlaylist -> "la playlist ${o.q}"
            is Orden.SpotifyAlbum -> "el álbum ${o.q}"
            else -> "tus Me gusta"
        }
        permiso(Categoria.SPOTIFY, "¿Pongo $que en Spotify?")?.let { return it }
        puente.avisar("Buscando $que en Spotify…")
        return anotar(Categoria.SPOTIFY, "Poner $que", spotify.reproducir(o, token))
    }

    // ---------------- Reloj ----------------

    private fun hhmm(h: Int, m: Int) = "%d:%02d".format(h, m)

    private suspend fun alarma(o: Orden.Alarma): Resultado {
        val hh = hhmm(o.hora, o.minuto)
        permiso(Categoria.ALARMAS, "¿Pongo una alarma a las $hh?")?.let { return it }
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, o.hora)
            .putExtra(AlarmClock.EXTRA_MINUTES, o.minuto)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Lumi")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (Apps.paqueteDe(ctx, i) == null && ctx.packageManager.queryIntentActivities(i, 0).isEmpty())
            return anotar(Categoria.ALARMAS, "Alarma $hh", Resultado.falla("No encontré una app de reloj que acepte alarmas."))
        if (!lanzar(i)) return anotar(Categoria.ALARMAS, "Alarma $hh", Resultado.falla("El reloj no aceptó la alarma."))

        val esperado = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, o.hora); set(Calendar.MINUTE, o.minuto); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val am = ctx.getSystemService(AlarmManager::class.java)
        repeat(10) {
            delay(400)
            val prox = am.nextAlarmClock
            if (prox != null && abs(prox.triggerTime - esperado) < 61_000)
                return anotar(Categoria.ALARMAS, "Alarma $hh", Resultado.ok("Listo, alarma a las $hh."))
        }
        val prox = am.nextAlarmClock
        val r = if (prox != null && prox.triggerTime < esperado)
            Resultado.sinComprobar("Se la pedí al reloj, pero no la puedo confirmar porque tienes otra alarma antes. Revísala en la app Reloj.")
        else Resultado.falla("Le pedí la alarma de las $hh al reloj, pero no la veo programada. Revisa la app Reloj.")
        return anotar(Categoria.ALARMAS, "Alarma $hh", r)
    }

    private suspend fun temporizador(o: Orden.Temporizador): Resultado {
        val texto = when {
            o.segundos % 3600 == 0 -> "${o.segundos / 3600} h"
            o.segundos % 60 == 0 -> "${o.segundos / 60} min"
            else -> "${o.segundos} s"
        }
        permiso(Categoria.ALARMAS, "¿Pongo un temporizador de $texto?")?.let { return it }
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, o.segundos)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Lumi")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val r = if (lanzar(i))
            Resultado.sinComprobar("Le pedí al reloj un temporizador de $texto. Android no me deja comprobarlo, así que mira que aparezca.")
        else Resultado.falla("No encontré una app de reloj para el temporizador.")
        return anotar(Categoria.ALARMAS, "Temporizador $texto", r)
    }

    private suspend fun recordatorio(o: Orden.Recordatorio): Resultado {
        val cal = Calendar.getInstance().apply { timeInMillis = o.cuandoMillis }
        val hoy = Calendar.getInstance()
        val manana = (hoy.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val dia = when {
            cal.get(Calendar.DAY_OF_YEAR) == hoy.get(Calendar.DAY_OF_YEAR) && cal.get(Calendar.YEAR) == hoy.get(Calendar.YEAR) -> "hoy"
            cal.get(Calendar.DAY_OF_YEAR) == manana.get(Calendar.DAY_OF_YEAR) && cal.get(Calendar.YEAR) == manana.get(Calendar.YEAR) -> "mañana"
            else -> "el " + SimpleDateFormat("d 'de' MMMM", Locale.forLanguageTag("es-CL")).format(cal.time)
        }
        val cuando = "$dia a las ${hhmm(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))}"
        permiso(Categoria.RECORDATORIOS, "¿Te recuerdo \"${o.texto}\" $cuando?")?.let { return it }
        val p = Recordatorios.programar(ctx, o.cuandoMillis, o.texto)
        val r = when {
            p == null -> Resultado.falla("No pude programar el recordatorio.")
            p.exacto -> Resultado.ok("Listo, te recuerdo ${o.texto} $cuando.")
            else -> Resultado.ok("Listo, te recuerdo ${o.texto} $cuando. Ojo: sin el permiso de alarmas exactas puede llegar unos minutos tarde.")
        }
        return anotar(Categoria.RECORDATORIOS, "Recordatorio $cuando: ${o.texto}", r)
    }

    // ---------------- Apps ----------------

    private suspend fun abrir(nombre: String, token: Int): Resultado {
        val (app, parecidas) = Apps.buscar(ctx, nombre, ajustes.atajos())
        if (app == null) {
            val sug = if (parecidas.isNotEmpty()) " ¿Quizás ${parecidas.joinToString(", ") { it.etiqueta }}?" else ""
            return anotar(Categoria.ABRIR_APPS, "Abrir $nombre", Resultado.falla("No encontré ninguna app llamada $nombre.$sug"))
        }
        permiso(Categoria.ABRIR_APPS, "¿Abro ${app.etiqueta}?")?.let { return it }
        if (!Freno.vigente(token)) return Resultado.info("Frenado.")
        val i = ctx.packageManager.getLaunchIntentForPackage(app.paquete)
            ?: return anotar(Categoria.ABRIR_APPS, "Abrir ${app.etiqueta}", Resultado.falla("${app.etiqueta} no se puede abrir directamente."))
        val desde = System.currentTimeMillis()
        if (!lanzar(i)) return anotar(Categoria.ABRIR_APPS, "Abrir ${app.etiqueta}", Resultado.falla("Android no me dejó abrir ${app.etiqueta}."))
        val r = when (Apps.aparecio(ctx, app.paquete, desde)) {
            true -> Resultado.ok("Listo, abrí ${app.etiqueta}.")
            false -> Resultado.falla("Intenté abrir ${app.etiqueta}, pero no apareció en pantalla.")
            null -> Resultado.sinComprobar("Le pedí a Android que abra ${app.etiqueta}, pero no puedo confirmarlo. Si me das \"Acceso al uso\" en Ajustes, lo compruebo.")
        }
        return anotar(Categoria.ABRIR_APPS, "Abrir ${app.etiqueta}", r)
    }

    private suspend fun cerrar(nombre: String): Resultado {
        if (ajustes.nivel(Categoria.INICIO) == Nivel.BLOQUEADO)
            return Resultado.info("Android no deja que una app cierre otras, y tengo bloqueado ir al inicio.")
        val si = puente.confirmar("Android no deja que una app cierre a otras. ¿Te llevo a la pantalla de inicio?")
        if (!si) return Resultado.info("Ok, no hago nada.")
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pkg = Apps.paqueteDe(ctx, home)
        val desde = System.currentTimeMillis()
        if (!lanzar(home)) return anotar(Categoria.INICIO, "Ir al inicio (pedido: cerrar $nombre)", Resultado.falla("No pude ir al inicio."))
        val r = when (pkg?.let { Apps.aparecio(ctx, it, desde) }) {
            true -> Resultado.ok("Listo, estás en el inicio. $nombre sigue abierta en segundo plano.")
            false -> Resultado.falla("Intenté ir al inicio, pero no lo vi aparecer.")
            null -> Resultado.sinComprobar("Te mandé al inicio, pero no lo puedo confirmar.")
        }
        return anotar(Categoria.INICIO, "Ir al inicio (pedido: cerrar $nombre)", r)
    }

    // ---------------- Web ----------------

    private suspend fun abrirYComprobar(c: Categoria, accion: String, intents: List<Intent>, ok: String): Resultado {
        for (i in intents) {
            val pkg = Apps.paqueteDe(ctx, i)
            val desde = System.currentTimeMillis()
            if (!lanzar(i)) continue
            val r = when (pkg?.let { Apps.aparecio(ctx, it, desde) }) {
                true -> Resultado.ok(ok)
                false -> Resultado.falla("Intenté abrirlo, pero no apareció.")
                null -> Resultado.sinComprobar("$ok (no lo puedo confirmar sin \"Acceso al uso\").")
            }
            return anotar(c, accion, r)
        }
        return anotar(c, accion, Resultado.falla("No encontré una app que pueda abrir eso."))
    }

    private suspend fun youtube(q: String): Resultado {
        permiso(Categoria.WEB, "¿Busco \"$q\" en YouTube?")?.let { return it }
        val app = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))
        return abrirYComprobar(Categoria.WEB, "YouTube: $q", listOf(app, web), "Busqué $q en YouTube.")
    }

    private suspend fun web(q: String): Resultado {
        permiso(Categoria.WEB, "¿Busco \"$q\" en internet?")?.let { return it }
        val buscar = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
        val ver = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))
        return abrirYComprobar(Categoria.WEB, "Buscar: $q", listOf(buscar, ver), "Busqué $q.")
    }

    // ---------------- Smartwatch (app Mi Reloj) ----------------

    /** null = todo listo para hablar con Mi Reloj; si no, lo que hay que decir. */
    private fun relojNoListo(): Resultado? = when {
        !MiReloj.instalada(ctx) -> Resultado.falla("No encuentro la app Mi Reloj en el teléfono. Es la que se conecta a tu smartwatch.")
        !MiReloj.tienePermiso(ctx) -> Resultado.info("Para leer tu reloj necesito el permiso \"Datos de Mi Reloj\". Dámelo en Ajustes de Lumi, en Permisos del teléfono.")
        else -> null
    }

    /** Lee el estado actual; si el reloj no está conectado, lo dice en vez de inventar. */
    private suspend fun relojAhora(): Pair<MiReloj.Ahora?, Resultado?> {
        relojNoListo()?.let { return null to it }
        MiReloj.pedir(ctx, "refrescar")
        delay(1500)
        val a = MiReloj.ahora(ctx) ?: return null to Resultado.falla("Mi Reloj no me respondió.")
        if (!a.vinculado) return null to Resultado.info("Mi Reloj todavía no tiene un reloj vinculado.")
        return a to null
    }

    private fun km(m: Int) = if (m >= 1000) "%.1f km".format(Locale.forLanguageTag("es-CL"), m / 1000.0) else "$m metros"

    private suspend fun relojPulso(): Resultado {
        permiso(Categoria.RELOJ, "¿Leo tu pulso del reloj?")?.let { return it }
        val (a, err) = relojAhora()
        if (a == null) return anotar(Categoria.RELOJ, "Leer pulso", err!!)
        val r = when {
            !a.conectado -> {
                val dia = MiReloj.pulsoReciente(ctx)
                if (dia != null && dia.promedio > 0)
                    Resultado.info("El reloj no está conectado ahora. Lo último sincronizado (${dia.fecha}): promedio ${dia.promedio}, en reposo ${dia.reposo}, máximo ${dia.maximo} ppm.")
                else Resultado.info("El reloj no está conectado ahora, así que no puedo leer tu pulso.")
            }
            a.pulsoMedido > 0 -> Resultado.ok("Tu última medición dio ${a.pulsoMedido} pulsaciones por minuto.")
            a.pulso > 0 -> Resultado.ok("Tu reloj marca ${a.pulso} pulsaciones por minuto.")
            else -> Resultado.info("El reloj está conectado, pero no tiene un pulso reciente. Si quieres, dime \"mide mi pulso\".")
        }
        return anotar(Categoria.RELOJ, "Leer pulso", r)
    }

    private suspend fun relojMedir(token: Int): Resultado {
        permiso(Categoria.RELOJ, "¿Le pido al reloj que mida tu pulso?")?.let { return it }
        val (a, err) = relojAhora()
        if (a == null) return anotar(Categoria.RELOJ, "Medir pulso", err!!)
        if (!a.conectado) return anotar(Categoria.RELOJ, "Medir pulso", Resultado.falla("El reloj no está conectado, no puedo medir."))
        if (!MiReloj.pedir(ctx, "medir_pulso")) return anotar(Categoria.RELOJ, "Medir pulso", Resultado.falla("Mi Reloj no aceptó la medición."))
        puente.avisar("Midiendo tu pulso… quédate quieto un momento.")
        delay(2000)
        repeat(30) {
            if (!Freno.vigente(token)) return Resultado.info("Frenado.")
            val v = MiReloj.ahora(ctx)?.pulsoMedido ?: 0
            if (v > 0) return anotar(Categoria.RELOJ, "Medir pulso", Resultado.ok("Listo: $v pulsaciones por minuto."))
            delay(1500)
        }
        return anotar(Categoria.RELOJ, "Medir pulso", Resultado.falla("El reloj no me devolvió la medición. Revisa que esté bien puesto en la muñeca."))
    }

    private suspend fun relojPasos(): Resultado {
        permiso(Categoria.RELOJ, "¿Leo tus pasos del reloj?")?.let { return it }
        val (a, err) = relojAhora()
        if (a == null) return anotar(Categoria.RELOJ, "Leer pasos", err!!)
        if (!a.conectado) return anotar(Categoria.RELOJ, "Leer pasos", Resultado.info("El reloj no está conectado ahora, así que no tengo tus pasos de hoy."))
        val falta = a.metaPasos - a.pasos
        val meta = when {
            a.metaPasos <= 0 -> ""
            falta <= 0 -> " ¡Ya cumpliste tu meta de ${a.metaPasos}!"
            else -> " Te faltan $falta para tu meta de ${a.metaPasos}."
        }
        return anotar(Categoria.RELOJ, "Leer pasos", Resultado.ok("Llevas ${a.pasos} pasos hoy.$meta"))
    }

    private suspend fun relojResumen(): Resultado {
        permiso(Categoria.RELOJ, "¿Leo el resumen de tu reloj?")?.let { return it }
        val (a, err) = relojAhora()
        if (a == null) return anotar(Categoria.RELOJ, "Resumen", err!!)
        if (!a.conectado) return anotar(Categoria.RELOJ, "Resumen", Resultado.info("El reloj no está conectado ahora. Acércalo al teléfono y vuelve a preguntarme."))
        val partes = mutableListOf("Hoy llevas ${a.pasos} pasos, ${a.calorias} calorías y ${km(a.distanciaM)}")
        if (a.minutosActivos > 0) partes += "${a.minutosActivos} minutos activo"
        val pulso = if (a.pulsoMedido > 0) a.pulsoMedido else a.pulso
        var texto = partes.joinToString(", ") + "."
        if (pulso > 0) texto += " Tu pulso está en $pulso."
        return anotar(Categoria.RELOJ, "Resumen", Resultado.ok(texto))
    }

    private suspend fun relojEjercicio(): Resultado {
        permiso(Categoria.RELOJ, "¿Leo tu último ejercicio?")?.let { return it }
        relojNoListo()?.let { return anotar(Categoria.RELOJ, "Último ejercicio", it) }
        val e = MiReloj.ultimoEjercicio(ctx)
            ?: return anotar(Categoria.RELOJ, "Último ejercicio", Resultado.info("No tengo ejercicios guardados. Registra uno en el reloj y sincronízalo en Mi Reloj."))
        val min = e.duracionS / 60
        val extra = buildList {
            if (e.distanciaM > 0) add(km(e.distanciaM))
            add("${e.calorias} calorías")
            if (e.pulsoProm > 0) add("pulso promedio ${e.pulsoProm}")
        }.joinToString(", ")
        return anotar(Categoria.RELOJ, "Último ejercicio",
            Resultado.ok("Tu último ejercicio fue ${e.nombre.lowercase()} el ${e.inicio.take(10)}: $min minutos, $extra."))
    }

    private suspend fun relojSincronizar(token: Int): Resultado {
        permiso(Categoria.RELOJ, "¿Sincronizo el reloj?")?.let { return it }
        val (a, err) = relojAhora()
        if (a == null) return anotar(Categoria.RELOJ, "Sincronizar", err!!)
        if (!a.conectado) return anotar(Categoria.RELOJ, "Sincronizar", Resultado.falla("El reloj no está conectado."))
        val antes = a.ultimaSync
        if (!MiReloj.pedir(ctx, "sincronizar")) return anotar(Categoria.RELOJ, "Sincronizar", Resultado.falla("Mi Reloj no aceptó sincronizar."))
        puente.avisar("Sincronizando el reloj…")
        repeat(60) {
            if (!Freno.vigente(token)) return Resultado.info("Frenado.")
            delay(2000)
            if ((MiReloj.ahora(ctx)?.ultimaSync ?: 0L) > antes)
                return anotar(Categoria.RELOJ, "Sincronizar", Resultado.ok("Listo, sincronicé el reloj."))
        }
        return anotar(Categoria.RELOJ, "Sincronizar", Resultado.sinComprobar("Le pedí sincronizar, pero no vi que terminara. Revisa Mi Reloj."))
    }

    private suspend fun relojAvisar(texto: String): Resultado {
        permiso(Categoria.RELOJ, "¿Mando \"$texto\" al reloj?")?.let { return it }
        val (a, err) = relojAhora()
        if (a == null) return anotar(Categoria.RELOJ, "Aviso al reloj", err!!)
        if (!a.conectado) return anotar(Categoria.RELOJ, "Aviso al reloj", Resultado.falla("El reloj no está conectado, no pude mandarlo."))
        val ok = MiReloj.pedir(ctx, "notificar", "Lumi", texto)
        val r = if (ok) Resultado.sinComprobar("Lo mandé al reloj. No puedo ver su pantalla, así que confírmame si te llegó.")
        else Resultado.falla("Mi Reloj no aceptó el aviso.")
        return anotar(Categoria.RELOJ, "Aviso al reloj: $texto", r)
    }

    private suspend fun clima(ciudad: String?): Resultado {
        val c = ciudad ?: ajustes.ciudadClima
        return Info.clima(c)?.let { Resultado.info(it.texto) }
            ?: Resultado.falla("No pude consultar el clima de $c. ¿Hay internet?")
    }
}
