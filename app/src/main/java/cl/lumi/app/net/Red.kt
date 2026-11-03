package cl.lumi.app.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Red {
    val cliente: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Para el streaming de la IA: sin límite de lectura total, solo de inactividad. */
    val streaming: OkHttpClient by lazy {
        cliente.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
    }

    /** Para ver rápido si el PC está en la red. */
    val rapido: OkHttpClient by lazy {
        cliente.newBuilder().connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(2, TimeUnit.SECONDS).build()
    }
}
