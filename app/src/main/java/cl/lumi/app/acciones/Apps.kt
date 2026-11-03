package cl.lumi.app.acciones

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import cl.lumi.app.core.Texto
import kotlinx.coroutines.delay

data class AppInstalada(val etiqueta: String, val paquete: String)

object Apps {

    fun instaladas(ctx: Context): List<AppInstalada> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val lista = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(i, PackageManager.ResolveInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.queryIntentActivities(i, 0)
        return lista
            .map { AppInstalada(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.paquete }
            .filter { it.paquete != ctx.packageName }
            .sortedBy { Texto.normalizar(it.etiqueta) }
    }

    /** Busca por atajo propio, nombre exacto, comienzo, contenido o parecido (por si el oído entendió mal). */
    fun buscar(ctx: Context, nombre: String, atajos: Map<String, String>): Pair<AppInstalada?, List<AppInstalada>> {
        val n = Texto.normalizar(nombre).trim()
        val todas = instaladas(ctx)
        atajos[n]?.let { pkg -> todas.firstOrNull { it.paquete == pkg }?.let { return it to emptyList() } }
        val norm = todas.map { it to Texto.normalizar(it.etiqueta) }
        norm.firstOrNull { it.second == n }?.let { return it.first to emptyList() }
        norm.firstOrNull { it.second.startsWith(n) }?.let { return it.first to emptyList() }
        norm.firstOrNull { it.second.contains(n) }?.let { return it.first to emptyList() }
        val cercanas = norm.map { it.first to Texto.distancia(it.second, n) }.sortedBy { it.second }
        val mejor = cercanas.firstOrNull()
        if (mejor != null && n.length >= 4 && mejor.second <= 2) return mejor.first to emptyList()
        return null to cercanas.take(3).map { it.first }
    }

    fun tieneAccesoUso(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val modo = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return modo == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Comprueba que [paquete] pasó a primer plano después de [desde].
     * true = se vio; false = no apareció; null = no lo puedo saber (falta "Acceso al uso").
     */
    suspend fun aparecio(ctx: Context, paquete: String, desde: Long, esperaMs: Long = 4000): Boolean? {
        if (!tieneAccesoUso(ctx)) return null
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val limite = System.currentTimeMillis() + esperaMs
        while (System.currentTimeMillis() < limite) {
            val eventos = usm.queryEvents(desde - 1000, System.currentTimeMillis() + 1000)
            val e = UsageEvents.Event()
            while (eventos.hasNextEvent()) {
                eventos.getNextEvent(e)
                if (e.packageName == paquete && e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) return true
            }
            delay(300)
        }
        return false
    }

    /** Paquete que abriría este intent, o null si es el selector del sistema / no hay app. */
    fun paqueteDe(ctx: Context, intent: Intent): String? {
        val pm = ctx.packageManager
        val ri = (if (Build.VERSION.SDK_INT >= 33)
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        else @Suppress("DEPRECATION") pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY))
            ?: return null
        val pkg = ri.activityInfo?.packageName ?: return null
        return pkg.takeUnless { it == "android" }
    }
}
