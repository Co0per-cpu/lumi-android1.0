package cl.lumi.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import cl.lumi.app.core.Ajustes
import cl.lumi.app.core.Registro

class LumiApp : Application() {

    lateinit var ajustes: Ajustes
        private set
    lateinit var registro: Registro
        private set

    override fun onCreate() {
        super.onCreate()
        instancia = this
        ajustes = Ajustes(this)
        registro = Registro(this)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL_SERVICIO, "Lumi activa", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Aviso fijo mientras Lumi está en pantalla. Incluye el freno de emergencia."
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CANAL_RECORDATORIOS, "Recordatorios", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Recordatorios que le pediste a Lumi."
            }
        )
    }

    companion object {
        const val CANAL_SERVICIO = "lumi_servicio"
        const val CANAL_RECORDATORIOS = "lumi_recordatorios"
        lateinit var instancia: LumiApp
            private set
    }
}
