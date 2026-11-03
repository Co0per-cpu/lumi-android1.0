package cl.lumi.app.overlay

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import cl.lumi.app.LumiApp
import cl.lumi.app.MainActivity
import cl.lumi.app.R
import cl.lumi.app.acciones.Acciones
import cl.lumi.app.acciones.Emocion
import cl.lumi.app.acciones.Puente
import cl.lumi.app.acciones.Resultado
import cl.lumi.app.core.Brain
import cl.lumi.app.core.Freno
import cl.lumi.app.core.Orden
import cl.lumi.app.core.Texto
import cl.lumi.app.ia.Historial
import cl.lumi.app.ia.Ia
import cl.lumi.app.ia.IaNoDisponible
import cl.lumi.app.ia.Local
import cl.lumi.app.ia.Mensaje
import cl.lumi.app.ia.Motor
import cl.lumi.app.ia.Trozo
import cl.lumi.app.voz.Oido
import cl.lumi.app.voz.Voz
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Lumi sobre las otras apps: el personaje (ventana pequeña que solo capta toques sobre él)
 * y un panel con el globo de mensajes, la pregunta Sí/No y la barra para escribir.
 *
 * Gestos sobre el personaje:
 *   toque = abrir/cerrar la barra · doble toque = escuchar · mantener = FRENO DE EMERGENCIA · arrastrar = mover.
 */
class OverlayService : LifecycleService(), Puente {

    companion object {
        const val ACCION_FRENO = "cl.lumi.FRENO"
        const val ACCION_OCULTAR = "cl.lumi.OCULTAR"
        const val ACCION_SALIR = "cl.lumi.SALIR"
        private const val ID_NOTIF = 1

        @Volatile var activo: OverlayService? = null
            private set

        fun iniciar(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, OverlayService::class.java))
        fun detener(ctx: Context) = ctx.stopService(Intent(ctx, OverlayService::class.java))
    }

    private val app get() = LumiApp.instancia
    private val ajustes get() = app.ajustes
    private lateinit var wm: WindowManager
    private lateinit var personaje: Personaje
    private lateinit var pPersonaje: WindowManager.LayoutParams
    private lateinit var panel: LinearLayout
    private lateinit var pPanel: WindowManager.LayoutParams
    private lateinit var globo: TextView
    private lateinit var pie: TextView
    private lateinit var filaConfirmar: LinearLayout
    private lateinit var barra: LinearLayout
    private lateinit var campo: EditText
    private lateinit var botonMic: ImageButton

    private lateinit var voz: Voz
    private lateinit var oido: Oido
    private lateinit var acciones: Acciones
    private lateinit var historial: Historial

    private var panelPuesto = false
    private var barraAbierta = false
    private var oculto = false
    private var trabajo: Job? = null
    private var ocultarGlobo: Job? = null
    private var animacion: ValueAnimator? = null
    private var confirmacion: CompletableDeferred<Boolean>? = null
    private var ultimoToque = System.currentTimeMillis()
    private val tamano get() = dp(ajustes.tamanoPersonajeDp)

    private val alFrenar: () -> Unit = {
        lifecycleScope.launch {
            trabajo?.cancel()
            animacion?.cancel()
            voz.detener()
            oido.detener()
            confirmacion?.complete(false)
            app.registro.anotar("Freno", "Freno de emergencia", "activado: se detuvo todo")
            mostrarGlobo("Frené todo.")
            personaje.fijar(Estado.IDLE)
        }
    }

    // ======================= Ciclo de vida =======================

    override fun onCreate() {
        super.onCreate()
        activo = this
        arrancarPrimerPlano()
        wm = getSystemService(WindowManager::class.java)
        historial = Historial(this)
        voz = Voz(this)
        oido = Oido(this)
        acciones = Acciones(this, this)
        Freno.alActivar(alFrenar)
        crearPersonaje()
        crearPanel()
        personaje.fijar(Estado.IDLE)
        lifecycleScope.launch { vidaPropia() }

        val prefs = getSharedPreferences("lumi_estado", MODE_PRIVATE)
        lifecycleScope.launch {
            delay(600)
            if (!personaje.hayImagenes) {
                mostrarGlobo("Todavía no tengo mis imágenes. Impórtalas en Ajustes de Lumi > Personaje.")
            } else if (!prefs.getBoolean("saludo", false)) {
                prefs.edit().putBoolean("saludo", true).apply()
                decir("¡Hola! Tócame para escribirme, tócame dos veces para hablarme o mantenme presionada para frenar todo.", Emocion.FELIZ)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACCION_FRENO -> Freno.activar()
            ACCION_OCULTAR -> alternarOculto()
            ACCION_SALIR -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activo = null
        Freno.quitar(alFrenar)
        animacion?.cancel()
        runCatching { voz.cerrar() }
        runCatching { oido.detener() }
        runCatching { wm.removeView(personaje) }
        if (panelPuesto) runCatching { wm.removeView(panel) }
        Local.liberar()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && trabajo?.isActive != true) Local.liberar()
    }

    private fun arrancarPrimerPlano() {
        fun pi(accion: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, OverlayService::class.java).setAction(accion), PendingIntent.FLAG_IMMUTABLE
        )
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, LumiApp.CANAL_SERVICIO)
            .setSmallIcon(R.drawable.ic_lumi)
            .setContentTitle("Lumi está activa")
            .setContentText("Mantén presionada a Lumi o toca Frenar para detener todo.")
            .setOngoing(true)
            .setContentIntent(abrir)
            .addAction(0, "Frenar", pi(ACCION_FRENO, 1))
            .addAction(0, "Ocultar / mostrar", pi(ACCION_OCULTAR, 2))
            .addAction(0, "Cerrar Lumi", pi(ACCION_SALIR, 3))
            .build()
        val conMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val tipo = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
            (if (conMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        ServiceCompat.startForeground(this, ID_NOTIF, n, tipo)
    }

    // ======================= Ventanas =======================

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()
    private fun anchoPantalla() = wm.currentWindowMetrics.bounds.width()
    private fun altoPantalla() = wm.currentWindowMetrics.bounds.height()

    @SuppressLint("ClickableViewAccessibility")
    private fun crearPersonaje() {
        personaje = Personaje(this, lifecycleScope)
        pPersonaje = WindowManager.LayoutParams(
            tamano, tamano,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = anchoPantalla() - tamano - dp(12)
            y = altoPantalla() / 3
            title = "Lumi"
        }
        wm.addView(personaje, pPersonaje)

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var x0 = 0f; var y0 = 0f; var px0 = 0; var py0 = 0; var arrastrando = false
        val gestos = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { alternarBarra(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { escuchar(); return true }
            override fun onLongPress(e: MotionEvent) {
                if (arrastrando) return
                personaje.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                vibrar()
                Freno.activar()
            }
        })
        personaje.setOnTouchListener { _, e ->
            gestos.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = e.rawX; y0 = e.rawY; px0 = pPersonaje.x; py0 = pPersonaje.y; arrastrando = false
                    despertar()
                    animacion?.cancel()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (!arrastrando && hypot(dx, dy) > slop) {
                        arrastrando = true
                        personaje.fijar(Estado.DRAG)
                    }
                    if (arrastrando) {
                        moverA(px0 + dx.roundToInt(), py0 + dy.roundToInt())
                        if (dx != 0f) personaje.mirar(dx < 0)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (arrastrando) soltar()
            }
            true
        }
    }

    private fun moverA(x: Int, y: Int) {
        pPersonaje.x = x
        pPersonaje.y = y.coerceIn(0, altoPantalla() - tamano)
        runCatching { wm.updateViewLayout(personaje, pPersonaje) }
        reubicarPanel()
    }

    /** Al soltar: si quedó cerca de un borde, se esconde asomándose; si no, flota donde quedó. */
    private fun soltar() {
        val w = anchoPantalla()
        val borde = dp(28)
        when {
            pPersonaje.x < borde -> esconderEnBorde(izquierda = true)
            pPersonaje.x > w - tamano - borde -> esconderEnBorde(izquierda = false)
            else -> {
                pPersonaje.x = pPersonaje.x.coerceIn(0, w - tamano)
                moverA(pPersonaje.x, pPersonaje.y)
                personaje.fijar(Estado.LAND)
                lifecycleScope.launch { delay(500); if (personaje.estado == Estado.LAND) personaje.fijar(Estado.IDLE) }
            }
        }
    }

    private fun esconderEnBorde(izquierda: Boolean) {
        val w = anchoPantalla()
        val destino = if (izquierda) -(tamano * 0.45f).roundToInt() else w - (tamano * 0.55f).roundToInt()
        volarA(destino, pPersonaje.y, 350) {
            personaje.mirar(haciaIzquierda = !izquierda)
            personaje.fijar(Estado.PEEK)
        }
    }

    private fun volarA(x: Int, y: Int, ms: Long, alTerminar: () -> Unit = {}) {
        animacion?.cancel()
        val x0 = pPersonaje.x; val y0 = pPersonaje.y
        if (x != x0) personaje.mirar(x < x0)
        animacion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                moverA((x0 + (x - x0) * f).roundToInt(), (y0 + (y - y0) * f).roundToInt())
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelada = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelada = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelada) alTerminar() }
            })
            start()
        }
    }

    private fun crearPanel() {
        val tema = ajustes.tema
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val fondoGlobo = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(tema.fondo)
            setStroke(dp(1), tema.acento)
        }
        globo = TextView(this).apply {
            background = fondoGlobo
            setTextColor(tema.texto)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { if (!barraAbierta) quitarPanelSiVacio(forzar = true) }
        }
        pie = TextView(this).apply {
            setTextColor(tema.acento)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(14), dp(2), dp(14), 0)
            visibility = View.GONE
        }
        fun boton(t: String, si: Boolean) = Button(this).apply {
            text = t
            isAllCaps = false
            setTextColor(if (si) 0xFFFFFFFF.toInt() else tema.texto)
            backgroundTintList = ColorStateList.valueOf(if (si) tema.acento else tema.fondo)
            setOnClickListener { confirmacion?.complete(si) }
        }
        filaConfirmar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            visibility = View.GONE
            addView(boton("No", false))
            addView(boton("Sí", true))
        }
        campo = EditText(this).apply {
            hint = "Escríbele a Lumi…"
            setTextColor(tema.texto)
            setHintTextColor((tema.texto and 0x00FFFFFF) or 0x88000000.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            maxLines = 3
            background = null
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { enviarCampo(); true } else false
            }
        }
        fun icono(res: Int, desc: String, accion: () -> Unit) = ImageButton(this).apply {
            setImageResource(res)
            contentDescription = desc
            background = null
            imageTintList = ColorStateList.valueOf(tema.acento)
            setOnClickListener { accion() }
        }
        botonMic = icono(android.R.drawable.ic_btn_speak_now, "Hablar") { escuchar() }
        barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat(); setColor(tema.fondo); setStroke(dp(1), tema.acento)
            }
            setPadding(dp(10), 0, dp(4), 0)
            addView(campo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(botonMic)
            addView(icono(android.R.drawable.ic_menu_send, "Enviar") { enviarCampo() })
            addView(icono(android.R.drawable.ic_menu_close_clear_cancel, "Cerrar") { alternarBarra() })
        }
        val margen = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) }
        panel.addView(globo, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(pie)
        panel.addView(filaConfirmar, margen)
        panel.addView(barra, LinearLayout.LayoutParams(margen))
        globo.visibility = View.GONE

        pPanel = WindowManager.LayoutParams(
            minOf(anchoPantalla() - dp(24), dp(340)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "Lumi panel"
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
    }

    private fun ponerPanel() {
        if (oculto) return
        if (!panelPuesto) {
            reubicarPanel(agregar = true)
            runCatching { wm.addView(panel, pPanel) }.onSuccess { panelPuesto = true }
        } else reubicarPanel()
    }

    private fun quitarPanelSiVacio(forzar: Boolean = false) {
        val vacio = globo.visibility != View.VISIBLE && filaConfirmar.visibility != View.VISIBLE && !barraAbierta
        if (forzar && !barraAbierta && confirmacion == null) {
            globo.visibility = View.GONE; pie.visibility = View.GONE
        }
        if ((vacio || forzar) && !barraAbierta && confirmacion == null && panelPuesto) {
            runCatching { wm.removeView(panel) }
            panelPuesto = false
        }
    }

    /** Pone el panel arriba del personaje (o abajo si no cabe), sin salirse de la pantalla. */
    private fun reubicarPanel(agregar: Boolean = false) {
        if (!panelPuesto && !agregar) return
        val w = anchoPantalla(); val h = altoPantalla()
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(pPanel.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST)
        )
        val alto = panel.measuredHeight
        val centro = pPersonaje.x + tamano / 2
        pPanel.x = (centro - pPanel.width / 2).coerceIn(dp(8), maxOf(dp(8), w - pPanel.width - dp(8)))
        val arriba = pPersonaje.y - alto - dp(4)
        pPanel.y = if (arriba > dp(32)) arriba else minOf(pPersonaje.y + tamano + dp(4), h - alto - dp(8))
        if (panelPuesto) runCatching { wm.updateViewLayout(panel, pPanel) }
    }

    private fun alternarBarra() {
        despertar()
        barraAbierta = !barraAbierta
        val imm = getSystemService(InputMethodManager::class.java)
        if (barraAbierta) {
            barra.visibility = View.VISIBLE
            pPanel.flags = pPanel.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            ponerPanel()
            runCatching { wm.updateViewLayout(panel, pPanel) }
            campo.requestFocus()
            campo.postDelayed({ imm.showSoftInput(campo, InputMethodManager.SHOW_IMPLICIT) }, 150)
        } else {
            imm.hideSoftInputFromWindow(campo.windowToken, 0)
            barra.visibility = View.GONE
            pPanel.flags = pPanel.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (panelPuesto) runCatching { wm.updateViewLayout(panel, pPanel) }
            programarOcultarGlobo(2500)
            quitarPanelSiVacio()
        }
    }

    private fun alternarOculto() {
        oculto = !oculto
        personaje.visibility = if (oculto) View.GONE else View.VISIBLE
        if (oculto) {
            if (barraAbierta) alternarBarra()
            if (panelPuesto) { runCatching { wm.removeView(panel) }; panelPuesto = false }
        }
    }

    // ======================= Globo y voz =======================

    private fun mostrarGlobo(texto: String, etiqueta: String? = null) {
        ocultarGlobo?.cancel()
        globo.text = texto
        globo.visibility = View.VISIBLE
        pie.text = etiqueta.orEmpty()
        pie.visibility = if (etiqueta != null) View.VISIBLE else View.GONE
        ponerPanel()
        panel.post { reubicarPanel() }
    }

    private fun programarOcultarGlobo(ms: Long) {
        ocultarGlobo?.cancel()
        ocultarGlobo = lifecycleScope.launch {
            delay(ms)
            if (voz.hablando() || confirmacion != null) return@launch
            globo.visibility = View.GONE; pie.visibility = View.GONE
            quitarPanelSiVacio()
        }
    }

    private fun msLectura(t: String) = 4000L + t.length * 60L

    /** Muestra y dice un texto, frase por frase, y después reacciona con la emoción. */
    private fun decir(texto: String, emocion: Emocion = Emocion.NEUTRA, etiqueta: String? = null) {
        val frases = Texto.frases(texto).ifEmpty { listOf(texto) }
        if (!ajustes.hablar || !voz.lista()) {
            mostrarGlobo(texto, etiqueta)
            reaccionar(emocion)
            programarOcultarGlobo(msLectura(texto))
            return
        }
        var dichas = 0
        mostrarGlobo(frases.first(), etiqueta)
        voz.alEmpezarFrase = { _ ->
            dichas++
            mostrarGlobo(frases.take(dichas).joinToString(" "), etiqueta)
        }
        voz.alTerminarTodo = {
            mostrarGlobo(texto, etiqueta)
            reaccionar(emocion)
            programarOcultarGlobo(msLectura(texto) / 2)
        }
        personaje.fijar(Estado.TALK)
        frases.forEach { voz.decir(it) }
    }

    private fun reaccionar(e: Emocion) {
        val (estado, ms) = when (e) {
            Emocion.FELIZ -> Estado.HAPPY to 2500L
            Emocion.TRISTE -> Estado.SAD to 3500L
            Emocion.NEUTRA -> Estado.IDLE to 0L
        }
        personaje.fijar(estado)
        if (ms > 0) lifecycleScope.launch {
            delay(ms)
            if (personaje.estado == estado) personaje.fijar(Estado.IDLE)
        }
    }

    /** Para recordatorios que llegan desde fuera del servicio. */
    fun decirDesdeFuera(texto: String) {
        lifecycleScope.launch { decir(texto, Emocion.NEUTRA) }
    }

    private fun vibrar() {
        runCatching {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    // ======================= Puente (preguntas Sí/No) =======================

    override suspend fun confirmar(pregunta: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            confirmacion?.complete(false)
            confirmacion = d
            filaConfirmar.visibility = View.VISIBLE
            decir(pregunta)
            personaje.fijar(Estado.THINK)
        }
        val r = withTimeoutOrNull(30_000) { d.await() } ?: false
        withContext(Dispatchers.Main) {
            if (confirmacion === d) confirmacion = null
            filaConfirmar.visibility = View.GONE
            reubicarPanel()
        }
        return r
    }

    override fun avisar(texto: String) {
        lifecycleScope.launch { mostrarGlobo(texto); personaje.fijar(Estado.THINK) }
    }

    // ======================= Escuchar y procesar =======================

    private fun escuchar() {
        despertar()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            decir("Necesito permiso de micrófono. Dámelo en Ajustes de Lumi y vuelve a iniciarme.", Emocion.TRISTE)
            return
        }
        if (oido.escuchando()) { oido.detener(); return }
        voz.detener()
        oido.alEstado = { on ->
            botonMic.imageTintList = ColorStateList.valueOf(if (on) 0xFFE53935.toInt() else ajustes.tema.acento)
            if (on) { mostrarGlobo("Te escucho…"); personaje.fijar(Estado.THINK) }
        }
        oido.alParcial = { mostrarGlobo("“$it”") }
        oido.alFinal = { enviar(it) }
        oido.alError = { decir(it, Emocion.TRISTE) }
        oido.escuchar()
    }

    private fun enviarCampo() {
        val t = campo.text.toString()
        campo.setText("")
        enviar(t)
    }

    private val reSi = Regex("^(si|sip|dale|ya|ok|okay|bueno|hazlo|confirmo|claro|por favor)\\b")
    private val reNo = Regex("^(no|nop|cancela|mejor no|para|deja)\\b")

    private fun enviar(textoCrudo: String) {
        val texto = textoCrudo.trim()
        if (texto.isEmpty()) return
        despertar()

        // Si Lumi está esperando un sí o un no, la respuesta va para esa pregunta.
        confirmacion?.let { c ->
            val n = Texto.normalizar(Brain.limpiar(texto))
            when {
                reSi.containsMatchIn(n) -> c.complete(true)
                reNo.containsMatchIn(n) -> c.complete(false)
                else -> { mostrarGlobo("Lo tomo como un no."); c.complete(false) }
            }
            return
        }

        trabajo?.cancel()
        voz.detener()
        val token = Freno.token()
        trabajo = lifecycleScope.launch {
            mostrarGlobo("…")
            personaje.fijar(Estado.THINK)
            val orden = Brain.entender(texto)
            if (orden == Orden.Frenar) { Freno.activar(); return@launch }
            val r: Resultado? = try {
                acciones.ejecutar(orden, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Resultado.falla("Algo falló y no lo hice: ${e.message ?: e.javaClass.simpleName}")
            }
            if (!Freno.vigente(token)) return@launch
            if (r != null) {
                historial.agregar(Mensaje("user", texto))
                historial.agregar(Mensaje("assistant", r.texto))
                decir(r.texto, r.emocion)
            } else {
                conversar(texto, token)
            }
        }
    }

    private suspend fun conversar(texto: String, token: Int) {
        historial.agregar(Mensaje("user", texto))
        val sb = StringBuilder()
        var hablado = 0
        var motor: Motor? = null
        val hablar = ajustes.hablar && voz.lista()
        voz.alEmpezarFrase = {}
        voz.alTerminarTodo = {
            if (trabajo?.isActive != true) { reaccionar(Emocion.NEUTRA); programarOcultarGlobo(msLectura(sb.toString()) / 2) }
        }
        try {
            Ia.responder(this, historial.todos()).collect { trozo ->
                if (!Freno.vigente(token)) throw CancellationException("frenado")
                when (trozo) {
                    is Trozo.Usando -> { motor = trozo.motor; mostrarGlobo("…", "IA: ${trozo.motor.etiqueta}") }
                    is Trozo.Texto -> {
                        if (sb.isEmpty()) personaje.fijar(Estado.TALK)
                        sb.append(trozo.t)
                        mostrarGlobo(sb.toString().trim(), motor?.let { "IA: ${it.etiqueta}" })
                        if (hablar) {
                            // Dice cada frase apenas está completa.
                            val m = Regex("[.!?…](\\s)").findAll(sb, hablado).lastOrNull()
                            if (m != null) {
                                voz.decir(sb.substring(hablado, m.range.first + 1))
                                hablado = m.range.last + 1
                            }
                        }
                    }
                }
            }
            val respuesta = sb.toString().trim()
            if (respuesta.isEmpty()) { decir("No me salió ninguna respuesta. ¿Me lo repites?", Emocion.TRISTE); return }
            historial.agregar(Mensaje("assistant", respuesta))
            if (hablar && hablado < sb.length) voz.decir(sb.substring(hablado))
            if (!hablar || !voz.hablando()) { reaccionar(Emocion.NEUTRA); programarOcultarGlobo(msLectura(respuesta)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IaNoDisponible) {
            decir(e.message ?: "La IA no está disponible.", Emocion.TRISTE)
        } catch (e: Exception) {
            decir("No pude conversar: ${e.message ?: e.javaClass.simpleName}", Emocion.TRISTE)
        }
    }

    // ======================= Vida propia: pasear, esconderse, dormir =======================

    private fun despertar() {
        ultimoToque = System.currentTimeMillis()
        if (personaje.estado == Estado.SLEEP) personaje.fijar(Estado.IDLE)
    }

    private suspend fun vidaPropia() {
        while (lifecycleScope.isActive) {
            delay(Random.nextLong(25_000, 45_000))
            if (oculto || barraAbierta || confirmacion != null || voz.hablando() || trabajo?.isActive == true) continue
            val quieto = personaje.estado == Estado.IDLE || personaje.estado == Estado.PEEK
            if (!quieto) continue
            if (System.currentTimeMillis() - ultimoToque > 3 * 60_000) {
                personaje.fijar(Estado.SLEEP)
                continue
            }
            if (!ajustes.pasear) continue
            val w = anchoPantalla(); val h = altoPantalla()
            if (Random.nextFloat() < 0.3f) {
                esconderEnBorde(izquierda = Random.nextBoolean())
            } else {
                val nx = (pPersonaje.x + Random.nextInt(-w / 3, w / 3)).coerceIn(dp(8), w - tamano - dp(8))
                val ny = (pPersonaje.y + Random.nextInt(-h / 6, h / 6)).coerceIn(dp(48), h - tamano - dp(48))
                personaje.fijar(if (ny > pPersonaje.y + dp(40)) Estado.GLIDE else Estado.FLY)
                volarA(nx, ny, Random.nextLong(1500, 3000)) { personaje.fijar(Estado.IDLE) }
            }
        }
    }
}
