package cl.lumi.app.acciones

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat

/**
 * Habla con la app Mi Reloj (la que maneja tu smartwatch Lhotse) en el mismo teléfono.
 * Mi Reloj sigue vinculada al reloj; Lumi solo lee los datos y le pide cosas.
 * Necesita el permiso "Datos de Mi Reloj", que das tú en Ajustes de Lumi.
 */
object MiReloj {
    const val PAQUETE = "com.coopercpu.reloj"
    const val PERMISO = "com.coopercpu.reloj.permission.DATOS"
    private const val AUTORIDAD = "com.coopercpu.reloj.datos"
    private val BASE: Uri = Uri.parse("content://$AUTORIDAD")

    data class Ahora(
        val conectado: Boolean,
        val vinculado: Boolean,
        val reloj: String,
        val pulso: Int,
        val pulsoMedido: Int,
        val pasos: Int,
        val calorias: Int,
        val distanciaM: Int,
        val minutosActivos: Int,
        val metaPasos: Int,
        val clima: String,
        val ultimaSync: Long,
    )

    data class DiaPulso(val fecha: String, val reposo: Int, val minimo: Int, val promedio: Int, val maximo: Int)

    data class Ejercicio(
        val inicio: String, val nombre: String, val duracionS: Int, val distanciaM: Int,
        val calorias: Int, val pasos: Int, val pulsoProm: Int, val pulsoMax: Int,
    )

    fun instalada(ctx: Context): Boolean =
        ctx.packageManager.resolveContentProvider(AUTORIDAD, 0) != null

    fun tienePermiso(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, PERMISO) == PackageManager.PERMISSION_GRANTED

    fun ahora(ctx: Context): Ahora? = runCatching {
        ctx.contentResolver.query(Uri.withAppendedPath(BASE, "ahora"), null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            fun i(n: String) = c.getInt(c.getColumnIndexOrThrow(n))
            fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n)) ?: ""
            Ahora(
                conectado = i("conectado") == 1,
                vinculado = i("vinculado") == 1,
                reloj = s("reloj"),
                pulso = i("pulso"),
                pulsoMedido = i("pulso_medido"),
                pasos = i("pasos"),
                calorias = i("calorias"),
                distanciaM = i("distancia_m"),
                minutosActivos = i("minutos_activos"),
                metaPasos = i("meta_pasos"),
                clima = s("clima"),
                ultimaSync = c.getLong(c.getColumnIndexOrThrow("ultima_sincronizacion")),
            )
        }
    }.getOrNull()

    fun pulsoReciente(ctx: Context): DiaPulso? = runCatching {
        ctx.contentResolver.query(Uri.withAppendedPath(BASE, "pulso"), null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            DiaPulso(
                c.getString(c.getColumnIndexOrThrow("fecha")),
                c.getInt(c.getColumnIndexOrThrow("reposo")),
                c.getInt(c.getColumnIndexOrThrow("minimo")),
                c.getInt(c.getColumnIndexOrThrow("promedio")),
                c.getInt(c.getColumnIndexOrThrow("maximo")),
            )
        }
    }.getOrNull()

    fun ultimoEjercicio(ctx: Context): Ejercicio? = runCatching {
        ctx.contentResolver.query(Uri.withAppendedPath(BASE, "ejercicios"), null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            fun i(n: String) = c.getInt(c.getColumnIndexOrThrow(n))
            Ejercicio(
                c.getString(c.getColumnIndexOrThrow("inicio")),
                c.getString(c.getColumnIndexOrThrow("nombre")),
                i("duracion_s"), i("distancia_m"), i("calorias"), i("pasos"), i("pulso_prom"), i("pulso_max"),
            )
        }
    }.getOrNull()

    /** Acciones: "notificar", "medir_pulso", "sincronizar", "refrescar". Devuelve si Mi Reloj la aceptó. */
    fun pedir(ctx: Context, metodo: String, titulo: String? = null, texto: String? = null): Boolean = runCatching {
        val extras = Bundle().apply {
            if (titulo != null) putString("titulo", titulo)
            if (texto != null) putString("texto", texto)
        }
        ctx.contentResolver.call(BASE, metodo, null, extras)?.getBoolean("ok") == true
    }.getOrDefault(false)

    /** Para avisos automáticos (recordatorios): solo si todo está listo, sin molestar si no. */
    fun avisarSiSePuede(ctx: Context, titulo: String, texto: String): Boolean =
        instalada(ctx) && tienePermiso(ctx) && pedir(ctx, "notificar", titulo, texto)
}
