package cl.lumi.app.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro de acciones: qué se pidió, qué hizo Lumi y si se comprobó. Solo en el teléfono. */
class Registro(context: Context) {
    private val archivo = File(context.filesDir, "registro.txt")
    private val formato = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun anotar(categoria: String, accion: String, resultado: String) {
        val linea = "${formato.format(Date())} [$categoria] $accion → $resultado\n"
        archivo.appendText(linea)
        if (archivo.length() > 512 * 1024) {
            val lineas = archivo.readLines()
            archivo.writeText(lineas.takeLast(2000).joinToString("\n", postfix = "\n"))
        }
    }

    @Synchronized
    fun leer(): String = if (archivo.exists()) archivo.readText() else "(vacío)"

    @Synchronized
    fun borrar() {
        archivo.delete()
    }
}
