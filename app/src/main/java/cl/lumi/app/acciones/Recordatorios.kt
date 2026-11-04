package cl.lumi.app.acciones

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cl.lumi.app.LumiApp
import cl.lumi.app.MainActivity
import cl.lumi.app.R
import cl.lumi.app.overlay.OverlayService
import org.json.JSONArray
import org.json.JSONObject

/** Recordatorios propios de Lumi: se guardan en el teléfono y sobreviven a reinicios. */
object Recordatorios {

    data class Programado(val exacto: Boolean)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("lumi_recordatorios", Context.MODE_PRIVATE)

    private fun lista(ctx: Context): JSONArray = JSONArray(prefs(ctx).getString("lista", "[]"))

    private fun guardar(ctx: Context, arr: JSONArray) = prefs(ctx).edit().putString("lista", arr.toString()).apply()

    private fun pending(ctx: Context, id: Int, texto: String?, flags: Int): PendingIntent? {
        val i = Intent(ctx, RecordatorioReceiver::class.java).putExtra("id", id)
        if (texto != null) i.putExtra("texto", texto)
        return PendingIntent.getBroadcast(ctx, id, i, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Programa y comprueba que Android lo tiene registrado. Devuelve null si no quedó. */
    fun programar(ctx: Context, cuando: Long, texto: String): Programado? {
        val id = (cuando / 1000 % Int.MAX_VALUE).toInt() xor texto.hashCode()
        val arr = lista(ctx)
        arr.put(JSONObject().put("id", id).put("cuando", cuando).put("texto", texto))
        guardar(ctx, arr)
        val exacto = agendar(ctx, id, cuando, texto)
        val registrado = pending(ctx, id, null, PendingIntent.FLAG_NO_CREATE) != null
        return if (registrado) Programado(exacto) else null
    }

    private fun agendar(ctx: Context, id: Int, cuando: Long, texto: String): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, id, texto, PendingIntent.FLAG_UPDATE_CURRENT)!!
        return if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi)
            true
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, cuando, 5 * 60_000L, pi)
            false
        }
    }

    fun quitar(ctx: Context, id: Int) {
        val arr = lista(ctx)
        val nueva = JSONArray()
        for (k in 0 until arr.length()) arr.getJSONObject(k).let { if (it.getInt("id") != id) nueva.put(it) }
        guardar(ctx, nueva)
    }

    /** Después de reiniciar el teléfono, vuelve a programar los que siguen pendientes. */
    fun reprogramar(ctx: Context) {
        val arr = lista(ctx)
        val ahora = System.currentTimeMillis()
        for (k in 0 until arr.length()) {
            val o = arr.getJSONObject(k)
            val cuando = maxOf(o.getLong("cuando"), ahora + 5_000)
            agendar(ctx, o.getInt("id"), cuando, o.getString("texto"))
        }
    }
}

class RecordatorioReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val texto = intent.getStringExtra("texto") ?: "Tenías un recordatorio."
        Recordatorios.quitar(ctx, id)
        LumiApp.instancia.registro.anotar("Recordatorios", "Aviso: $texto", "mostrado")

        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            val abrir = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(ctx, LumiApp.CANAL_RECORDATORIOS)
                .setSmallIcon(R.drawable.ic_lumi)
                .setContentTitle("Recordatorio de Lumi")
                .setContentText(texto)
                .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(abrir)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(id, n)
        }
        // Si tienes el smartwatch conectado (app Mi Reloj), también te llega a la muñeca.
        Thread {
            if (MiReloj.avisarSiSePuede(ctx, "Recordatorio", texto))
                LumiApp.instancia.registro.anotar("Recordatorios", "Aviso al reloj: $texto", "enviado (sin comprobar)")
        }.start()
        // Si Lumi está en pantalla, también lo dice.
        OverlayService.activo?.decirDesdeFuera("Te recuerdo: $texto")
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Recordatorios.reprogramar(ctx)
    }
}
