package cl.lumi.app.acciones

enum class Emocion { FELIZ, TRISTE, NEUTRA }

/**
 * Lo que Lumi dice después de una acción.
 * [comprobado] = se verificó que pasó de verdad (o que falló). Si es false, el texto lo dice.
 */
data class Resultado(val texto: String, val emocion: Emocion, val comprobado: Boolean = true) {
    companion object {
        fun ok(texto: String) = Resultado(texto, Emocion.FELIZ)
        fun falla(texto: String) = Resultado(texto, Emocion.TRISTE)
        fun info(texto: String) = Resultado(texto, Emocion.NEUTRA)
        fun sinComprobar(texto: String) = Resultado(texto, Emocion.NEUTRA, comprobado = false)
    }
}

/** Lo que la burbuja le ofrece a las acciones: preguntar "¿sí o no?". */
interface Puente {
    suspend fun confirmar(pregunta: String): Boolean
    fun avisar(texto: String)
}
