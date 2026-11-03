package cl.lumi.app

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import cl.lumi.app.acciones.Apps
import cl.lumi.app.core.Categoria
import cl.lumi.app.core.ModoIa
import cl.lumi.app.core.Nivel
import cl.lumi.app.core.Tema
import cl.lumi.app.ia.Historial
import cl.lumi.app.ia.Local
import cl.lumi.app.ia.Ollama
import cl.lumi.app.overlay.OverlayService
import cl.lumi.app.spotify.ServidorLoopback
import cl.lumi.app.spotify.Spotify
import cl.lumi.app.voz.Voz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/** Ajustes de Lumi: permisos, IA, voz, Spotify, permisos por acción, atajos, apariencia y registro. */
class MainActivity : AppCompatActivity() {

    private val ajustes get() = LumiApp.instancia.ajustes
    private lateinit var raiz: LinearLayout
    private val refrescos = mutableListOf<() -> Unit>()
    private var voz: Voz? = null

    private val pedirPermisos = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refrescar() }

    private val elegirModelo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { copiarModelo(it) } }

    private val elegirPersonaje = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importarPersonaje(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Lumi"
        raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(raiz) })
        voz = Voz(this) { refrescar() }
        construir()
    }

    override fun onResume() {
        super.onResume()
        refrescar()
    }

    override fun onDestroy() {
        voz?.cerrar()
        super.onDestroy()
    }

    private fun refrescar() = refrescos.forEach { it() }

    // ---------------- Pequeños ayudantes de interfaz ----------------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    private fun titulo(t: String) = raiz.addView(TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(24), 0, dp(6))
    })

    private fun nota(t: String, dentro: ViewGroup = raiz) = TextView(this).apply {
        text = t
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        alpha = 0.75f
        setPadding(0, dp(2), 0, dp(6))
    }.also { dentro.addView(it) }

    private fun boton(t: String, dentro: ViewGroup = raiz, accion: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { accion() }
    }.also { dentro.addView(it) }

    private fun campo(hint: String, valor: String, tipo: Int = InputType.TYPE_CLASS_TEXT, guardar: (String) -> Unit) =
        EditText(this).apply {
            this.hint = hint
            setText(valor)
            inputType = tipo
            setSingleLine()
            setOnFocusChangeListener { _, foco -> if (!foco) guardar(text.toString()) }
        }.also { raiz.addView(it) }

    private fun <T> selector(opciones: List<T>, actual: T, etiqueta: (T) -> String, dentro: ViewGroup = raiz, alElegir: (T) -> Unit) =
        Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, opciones.map(etiqueta))
            setSelection(opciones.indexOf(actual).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = alElegir(opciones[pos])
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }.also { dentro.addView(it) }

    private fun permisoFila(nombre: String, explicacion: String, tiene: () -> Boolean, pedir: () -> Unit) {
        val b = boton("") { pedir() }
        nota(explicacion)
        refrescos += { b.text = (if (tiene()) "✓ " else "✗ ") + nombre }
    }

    private fun tienePermiso(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun aviso(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    // ---------------- Pantalla ----------------

    private fun construir() {
        // ===== Lumi =====
        titulo("Lumi")
        val estado = nota("")
        val bInicio = boton("") {
            if (OverlayService.activo != null) OverlayService.detener(this)
            else {
                if (!Settings.canDrawOverlays(this)) { aviso("Primero dale el permiso de mostrarse sobre otras apps."); return@boton }
                OverlayService.iniciar(this)
            }
            raiz.postDelayed({ refrescar() }, 400)
        }
        refrescos += {
            val on = OverlayService.activo != null
            bInicio.text = if (on) "Detener Lumi" else "Iniciar Lumi"
            estado.text = if (on) "Lumi está en pantalla. Tócala para escribir, doble toque para hablar, mantenla presionada para frenar todo."
            else "Lumi está detenida."
        }

        // ===== Permisos del sistema =====
        titulo("Permisos del teléfono")
        nota("Lumi solo usa lo que le des. Puedes quitar cualquiera cuando quieras desde Ajustes del teléfono.")
        permisoFila("Mostrarse sobre otras apps", "Necesario para la burbuja flotante.", { Settings.canDrawOverlays(this) }) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        permisoFila("Micrófono", "Solo se enciende cuando tú lo pides. Si lo das con Lumi activa, reiníciala.", { tienePermiso(Manifest.permission.RECORD_AUDIO) }) {
            pedirPermisos.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
        permisoFila("Notificaciones", "Para el aviso fijo con el botón Frenar y para los recordatorios.", { tienePermiso(Manifest.permission.POST_NOTIFICATIONS) }) {
            pedirPermisos.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        permisoFila("Acceso al uso", "Para COMPROBAR que una app de verdad se abrió. Sin esto, Lumi te avisa que no lo puede confirmar.", { Apps.tieneAccesoUso(this) }) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        permisoFila("Alarmas exactas", "Para que los recordatorios lleguen a la hora justa.", { getSystemService(AlarmManager::class.java).canScheduleExactAlarms() }) {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }

        // ===== IA =====
        titulo("Inteligencia artificial")
        selector(ModoIa.entries, ajustes.modoIa, { it.titulo }) { ajustes.modoIa = it }
        nota("PC: el mismo Ollama de tu computador, por Wi-Fi. En el PC, Ollama debe escuchar en la red (variable OLLAMA_HOST=0.0.0.0) y el firewall debe permitir el puerto 11434.")
        campo("http://IP-del-PC:11434", ajustes.ollamaUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { ajustes.ollamaUrl = it }
        val campoModelo = campo("Modelo del PC (ej. gemma3:4b)", ajustes.ollamaModelo) { ajustes.ollamaModelo = it }
        val resPc = nota("")
        boton("Probar conexión con el PC") {
            currentFocus?.clearFocus()
            resPc.text = "Probando…"
            lifecycleScope.launch {
                val modelos = Ollama.modelos()
                resPc.text = when {
                    modelos.isEmpty() && !Ollama.disponible() -> "No encontré el PC en ${ajustes.ollamaUrl}."
                    modelos.isEmpty() -> "El PC responde, pero no tiene modelos."
                    ajustes.ollamaModelo !in modelos -> "Conectado. Ojo: ${ajustes.ollamaModelo} no está. Hay: ${modelos.joinToString()}"
                    else -> "Conectado. Modelos: ${modelos.joinToString()}"
                }
                if (ajustes.ollamaModelo !in modelos && modelos.isNotEmpty()) {
                    AlertDialog.Builder(this@MainActivity).setTitle("Elegir modelo del PC")
                        .setItems(modelos.toTypedArray()) { _, k -> ajustes.ollamaModelo = modelos[k]; campoModelo.setText(modelos[k]) }
                        .show()
                }
            }
        }
        nota("Teléfono: un modelo .litertlm guardado en el teléfono (por ejemplo Gemma 3 1B int4, ~600 MB, de la comunidad LiteRT en Hugging Face). Funciona sin PC ni internet.")
        val resLocal = nota("")
        refrescos += {
            val ruta = ajustes.modeloLocal
            resLocal.text = when {
                !Local.soportado() -> "Este teléfono no es compatible con la IA local."
                ruta == null || !File(ruta).exists() -> "Sin modelo en el teléfono."
                else -> "Modelo: ${File(ruta).name} (${File(ruta).length() / 1_000_000} MB)"
            }
        }
        boton("Elegir modelo del teléfono (.litertlm)") { elegirModelo.launch(arrayOf("*/*")) }

        // ===== Voz =====
        titulo("Voz")
        Switch(this).apply {
            text = "Lumi habla en voz alta"
            isChecked = ajustes.hablar
            setOnCheckedChangeListener { _, v -> ajustes.hablar = v }
        }.also { raiz.addView(it) }
        nota("Elige una voz femenina en español. Las de mejor calidad aparecen primero. Si no te gusta ninguna, instala más voces en Ajustes > Idioma > Salida de texto a voz.")
        val contVoces = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        raiz.addView(contVoces)
        refrescos += {
            contVoces.removeAllViews()
            val voces = voz?.takeIf { it.lista() }?.vocesEspanol().orEmpty()
            if (voces.isEmpty()) nota("Cargando voces…", contVoces)
            else {
                val actual = voces.firstOrNull { it.name == ajustes.vozNombre } ?: voces.first()
                selector(voces, actual, { v ->
                    val red = if (v.isNetworkConnectionRequired) " · necesita internet" else ""
                    "${v.locale.displayCountry} — ${v.name}$red"
                }, contVoces) { v -> ajustes.vozNombre = v.name; voz?.configurar() }
            }
        }
        fun deslizador(etq: String, valor: Float, guardar: (Float) -> Unit) {
            val t = nota("$etq: ${"%.2f".format(valor)}")
            SeekBar(this).apply {
                max = 150
                progress = ((valor - 0.5f) * 100).roundToInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                        val v = 0.5f + p / 100f
                        t.text = "$etq: ${"%.2f".format(v)}"
                        guardar(v)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) { voz?.configurar() }
                })
            }.also { raiz.addView(it) }
        }
        deslizador("Velocidad", ajustes.vozVelocidad) { ajustes.vozVelocidad = it }
        deslizador("Tono", ajustes.vozTono) { ajustes.vozTono = it }
        boton("Probar voz") {
            voz?.configurar()
            voz?.decir("Hola, soy Lumi. ¿Te gusta cómo sueno?")
        }

        // ===== Spotify =====
        titulo("Spotify")
        nota("Usa tu propio Client ID (developer.spotify.com). En tu app de Spotify agrega este Redirect URI: ${ServidorLoopback.REDIRECT} — es el mismo que en el PC. Controlar la música requiere Premium.")
        campo("Client ID", ajustes.spotifyClientId) { ajustes.spotifyClientId = it }
        val spotify = Spotify(this)
        val resSp = nota("")
        val bSp = boton("") {
            currentFocus?.clearFocus()
            if (spotify.conectado()) { spotify.desconectar(); refrescar(); return@boton }
            resSp.text = "Abriendo Spotify… cuando aceptes, vuelve aquí."
            lifecycleScope.launch {
                val msg = spotify.conectar { url -> CustomTabsIntent.Builder().build().launchUrl(this@MainActivity, url) }
                resSp.text = msg
                refrescar()
            }
        }
        refrescos += { bSp.text = if (spotify.conectado()) "Desconectar Spotify" else "Conectar Spotify" }

        // ===== Control: permisos por acción =====
        titulo("Qué puede hacer Lumi")
        nota("Para cada tipo de acción: Permitido, Preguntar antes o Bloqueado. Lumi siempre comprueba lo que hace y lo anota en el registro.")
        Categoria.entries.forEach { c ->
            nota(c.titulo).apply { alpha = 1f; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }
            selector(Nivel.entries, ajustes.nivel(c), { it.titulo }) { ajustes.fijarNivel(c, it) }
        }

        // ===== Atajos =====
        titulo("Atajos propios")
        nota("Por ejemplo: \"música\" → Spotify. Después puedes decir \"abre música\".")
        val contAtajos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        raiz.addView(contAtajos)
        refrescos += {
            contAtajos.removeAllViews()
            val apps = Apps.instaladas(this).associateBy { it.paquete }
            ajustes.atajos().forEach { (alias, pkg) ->
                boton("\"$alias\" → ${apps[pkg]?.etiqueta ?: pkg}   (tocar para borrar)", contAtajos) {
                    ajustes.borrarAtajo(alias); refrescar()
                }
            }
        }
        boton("Agregar atajo") { nuevoAtajo() }

        // ===== Apariencia =====
        titulo("Personaje y apariencia")
        nota("El personaje ya viene incluido. Si quieres reemplazar sus animaciones, importa un zip con los GIF o WebP (idle, talk, sad_in…); se usan tal cual, sin cambiar el diseño.")
        val resPj = nota("")
        refrescos += {
            val n = File(filesDir, "personaje").listFiles()?.count { it.extension in listOf("webp", "gif", "png") } ?: 0
            resPj.text = if (n > 0) "Usando $n animaciones importadas." else "Usando el personaje incluido."
        }
        boton("Importar personaje (.zip)") { elegirPersonaje.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) }
        nota("Tema del globo")
        selector(Tema.entries, ajustes.tema, { it.titulo }) { ajustes.tema = it }
        nota("Tamaño del personaje")
        val tamanos = listOf(60 to "Mini", 80 to "Muy pequeño", 100 to "Pequeño", 120 to "Mediano", 140 to "Normal", 170 to "Grande", 200 to "Muy grande")
        selector(tamanos.map { it.first }, ajustes.tamanoPersonajeDp, { t -> tamanos.first { it.first == t }.second }) {
            ajustes.tamanoPersonajeDp = it
            OverlayService.activo?.aplicarAjustes()
        }
        Switch(this).apply {
            text = "Pasear y esconderse en los bordes sola"
            isChecked = ajustes.pasear
            setOnCheckedChangeListener { _, v -> ajustes.pasear = v }
        }.also { raiz.addView(it) }

        // ===== Ocultar =====
        titulo("Ocultar a Lumi")
        nota("En la barra de Lumi, el botón del ojo la oculta 15 minutos, 1 hora o hasta que la llames. También puedes ocultarla y mostrarla desde la notificación o aquí.")
        val bOcultar = boton("") {
            val s = OverlayService.activo ?: return@boton
            if (s.estaOculta()) s.mostrar() else s.ocultar(null)
            refrescar()
        }
        refrescos += {
            val s = OverlayService.activo
            bOcultar.visibility = if (s == null) View.GONE else View.VISIBLE
            bOcultar.text = if (s?.estaOculta() == true) "Mostrar a Lumi" else "Ocultar a Lumi ahora"
        }
        val resApps = nota("")
        refrescos += {
            val n = ajustes.appsOcultar().size
            resApps.text = when {
                n == 0 -> "No se oculta sola en ninguna app."
                !Apps.tieneAccesoUso(this) -> "Se oculta en $n apps, pero falta dar \"Acceso al uso\" (arriba) para que funcione."
                else -> "Se oculta sola mientras usas $n apps."
            }
        }
        boton("Elegir apps donde se esconde sola") { elegirAppsOcultar() }
        fun interruptor(t: String, v: Boolean, guardar: (Boolean) -> Unit) = Switch(this).apply {
            text = t
            isChecked = v
            setOnCheckedChangeListener { _, x -> guardar(x); OverlayService.activo?.aplicarAjustes() }
        }.also { raiz.addView(it) }
        interruptor("Mostrar el globo de texto (si lo apagas, solo habla)", ajustes.mostrarGlobo) { ajustes.mostrarGlobo = it }
        interruptor("Mostrar el botón de micrófono en la barra", ajustes.mostrarMic) { ajustes.mostrarMic = it }
        interruptor("Mostrar qué IA respondió (PC o teléfono)", ajustes.mostrarPie) { ajustes.mostrarPie = it }

        // ===== Otros =====
        titulo("Clima")
        campo("Ciudad por defecto", ajustes.ciudadClima) { ajustes.ciudadClima = it }

        titulo("Registro y privacidad")
        nota("Todo queda en este teléfono: el registro de acciones, el historial de conversación y las credenciales cifradas.")
        boton("Ver registro de acciones") {
            AlertDialog.Builder(this).setTitle("Registro")
                .setMessage(LumiApp.instancia.registro.leer().lines().takeLast(200).reversed().joinToString("\n"))
                .setPositiveButton("Cerrar", null)
                .setNeutralButton("Borrar") { _, _ -> LumiApp.instancia.registro.borrar() }
                .show()
        }
        boton("Borrar historial de conversación") {
            Historial(this).borrar(); aviso("Historial borrado. Reinicia Lumi si estaba activa.")
        }
    }

    private fun elegirAppsOcultar() {
        val apps = Apps.instaladas(this)
        val elegidas = ajustes.appsOcultar().toMutableSet()
        val marcadas = apps.map { it.paquete in elegidas }.toBooleanArray()
        AlertDialog.Builder(this).setTitle("Lumi se esconde en…")
            .setMultiChoiceItems(apps.map { it.etiqueta }.toTypedArray(), marcadas) { _, k, si ->
                if (si) elegidas += apps[k].paquete else elegidas -= apps[k].paquete
            }
            .setPositiveButton("Guardar") { _, _ ->
                ajustes.fijarAppsOcultar(elegidas)
                if (elegidas.isNotEmpty() && !Apps.tieneAccesoUso(this)) aviso("Para esto, dale a Lumi \"Acceso al uso\" en Permisos.")
                refrescar()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun nuevoAtajo() {
        val apps = Apps.instaladas(this)
        val alias = EditText(this).apply { hint = "Palabra (ej. música)" }
        AlertDialog.Builder(this).setTitle("Nuevo atajo").setView(alias)
            .setPositiveButton("Elegir app") { _, _ ->
                val a = alias.text.toString().trim()
                if (a.isEmpty()) return@setPositiveButton
                AlertDialog.Builder(this).setTitle("¿Qué app abre \"$a\"?")
                    .setItems(apps.map { it.etiqueta }.toTypedArray()) { _, k ->
                        ajustes.guardarAtajo(a, apps[k].paquete); refrescar()
                    }.show()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun copiarModelo(uri: Uri) {
        aviso("Copiando el modelo… puede tardar un poco.")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(filesDir, "modelos").apply { mkdirs() }
                    val nombre = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.takeIf { it.endsWith(".litertlm") }
                        ?: "modelo.litertlm"
                    val destino = File(dir, nombre)
                    contentResolver.openInputStream(uri)!!.use { i -> destino.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                    dir.listFiles()?.filter { it != destino }?.forEach { it.delete() } // solo un modelo a la vez
                    destino
                }
            }
            r.onSuccess {
                Local.liberar()
                ajustes.modeloLocal = it.absolutePath
                aviso("Modelo listo: ${it.name}")
            }.onFailure { aviso("No pude copiar el modelo: ${it.message}") }
            refrescar()
        }
    }

    /** Extrae solo imágenes y tiempos.json, sin carpetas (evita rutas raras dentro del zip). */
    private fun importarPersonaje(uri: Uri) {
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(filesDir, "personaje").apply { mkdirs() }
                    var cuenta = 0
                    ZipInputStream(contentResolver.openInputStream(uri)!!).use { zip ->
                        while (true) {
                            val e = zip.nextEntry ?: break
                            val nombre = e.name.substringAfterLast('/').substringAfterLast('\\')
                            val ext = nombre.substringAfterLast('.', "").lowercase()
                            if (!e.isDirectory && !nombre.startsWith(".") && ext in listOf("webp", "gif", "png", "json")) {
                                File(dir, nombre.lowercase()).outputStream().use { zip.copyTo(it) }
                                if (ext != "json") cuenta++
                            }
                            zip.closeEntry()
                        }
                    }
                    cuenta
                }.getOrElse { -1 }
            }
            aviso(if (n >= 0) "Importé $n animaciones. Reinicia Lumi para verlas." else "No pude leer el zip.")
            refrescar()
        }
    }
}
