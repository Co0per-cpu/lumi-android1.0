package cl.lumi.app.spotify

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda secretos cifrados con una llave del Android Keystore (no sale del teléfono).
 * Es el equivalente a safeStorage de la versión de PC.
 */
class Seguro(context: Context) {
    private val prefs = context.getSharedPreferences("lumi_seguro", Context.MODE_PRIVATE)
    private val alias = "lumi_llave_secretos"

    private fun llave(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun guardar(clave: String, valor: String?) {
        if (valor == null) { prefs.edit().remove(clave).apply(); return }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, llave())
        val datos = c.iv + c.doFinal(valor.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(clave, Base64.encodeToString(datos, Base64.NO_WRAP)).apply()
    }

    fun leer(clave: String): String? {
        val b64 = prefs.getString(clave, null) ?: return null
        return runCatching {
            val datos = Base64.decode(b64, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, llave(), GCMParameterSpec(128, datos, 0, 12))
            String(c.doFinal(datos, 12, datos.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }
}
