package cl.lumi.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Pruebas del entendimiento de órdenes. Se corren sin teléfono: botón ▶ en Android Studio. */
class BrainTest {

    private val ahora = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 3, 14, 8, 0); set(Calendar.MILLISECOND, 0) }
    private fun e(t: String) = Brain.entender(t, ahora)

    @Test fun limpiaMuletillas() {
        assertEquals(Orden.Abrir("Spotify"), e("Oye Lumi, ¿me puedes abrir Spotify por favor?"))
        assertEquals(Orden.Abrir("whatsapp"), e("abre whatsapp po"))
    }

    @Test fun musica() {
        assertEquals(Orden.SpotifyCancion("Despacito"), e("pon Despacito en Spotify"))
        assertEquals(Orden.SpotifyArtista("Mon Laferte"), e("ponme música de Mon Laferte"))
        assertEquals(Orden.SpotifyPlaylist("Para Estudiar"), e("pon mi playlist Para Estudiar"))
        assertEquals(Orden.SpotifyAlbum("Un Verano Sin Ti"), e("pon el álbum Un Verano Sin Ti"))
        assertEquals(Orden.SpotifyMeGusta, e("pon mis me gusta"))
        assertEquals(Orden.Pausa, e("para la música"))
        assertEquals(Orden.QueSuena, e("¿qué está sonando?"))
    }

    @Test fun smartwatch() {
        assertEquals(Orden.RelojMedir, e("mide mi pulso"))
        assertEquals(Orden.RelojPulso, e("¿cómo está mi pulso?"))
        assertEquals(Orden.RelojPasos, e("¿cuántos pasos llevo?"))
        assertEquals(Orden.RelojResumen, e("¿cómo voy hoy?"))
        assertEquals(Orden.RelojEjercicio, e("¿cómo me fue en el último entrenamiento?"))
        assertEquals(Orden.RelojSincronizar, e("sincroniza el reloj"))
        assertEquals(Orden.RelojAvisar("compra pan"), e("manda al reloj que compra pan"))
        assertEquals(Orden.RelojAvisar("hola"), e("envía hola al reloj"))
    }

    @Test fun volumen() {
        assertEquals(Orden.VolumenSubir, e("sube el volumen"))
        assertEquals(Orden.VolumenFijar(40), e("volumen al 40"))
    }

    @Test fun reloj() {
        assertEquals(Orden.Alarma(7, 0), e("despiértame a las 7"))
        assertEquals(Orden.Alarma(18, 30), e("pon una alarma a las 6:30 de la tarde"))
        assertTrue(e("pon una alarma") is Orden.Charla) // sin hora, no inventa una
        assertEquals(Orden.Temporizador(1800), e("pon un temporizador de media hora"))
    }

    @Test fun recordatorios() {
        val r = e("recuérdame en 10 minutos que saque la ropa") as Orden.Recordatorio
        assertEquals("saque la ropa", r.texto)
        assertEquals(ahora.timeInMillis + 600_000, r.cuandoMillis)
        val r2 = e("recuérdame comprar pan a las 6") as Orden.Recordatorio
        assertEquals("comprar pan", r2.texto)
        assertEquals(18, Calendar.getInstance().apply { timeInMillis = r2.cuandoMillis }.get(Calendar.HOUR_OF_DAY))
        val r3 = e("recuérdame llamar a dos amigos a las 8 de la noche") as Orden.Recordatorio
        assertEquals("llamar a dos amigos", r3.texto)
    }

    @Test fun charla() {
        assertTrue(e("cuéntame un chiste") is Orden.Charla)
        assertTrue(e("hola, ¿cómo estás?") is Orden.Charla)
    }
}
