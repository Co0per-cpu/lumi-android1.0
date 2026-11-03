package cl.lumi.app.core

object Texto {
    /**
     * Minúsculas y sin tildes, conservando el largo exacto (1 carácter por 1 carácter),
     * para poder buscar en el texto normalizado y recortar el original en las mismas posiciones.
     */
    fun normalizar(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    'á', 'à', 'ä', 'â', 'Á', 'À', 'Ä', 'Â' -> 'a'
                    'é', 'è', 'ë', 'ê', 'É', 'È', 'Ë', 'Ê' -> 'e'
                    'í', 'ì', 'ï', 'î', 'Í', 'Ì', 'Ï', 'Î' -> 'i'
                    'ó', 'ò', 'ö', 'ô', 'Ó', 'Ò', 'Ö', 'Ô' -> 'o'
                    'ú', 'ù', 'ü', 'û', 'Ú', 'Ù', 'Ü', 'Û' -> 'u'
                    'ñ', 'Ñ' -> 'n'
                    else -> c.lowercaseChar()
                }
            )
        }
        return sb.toString()
    }

    /** Distancia de edición, para encontrar apps aunque el nombre venga mal escrito o mal oído. */
    fun distancia(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val costo = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + costo)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Divide en frases para hablar y mostrar el texto sincronizado. */
    fun frases(s: String): List<String> =
        s.split(Regex("(?<=[.!?…])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
}
