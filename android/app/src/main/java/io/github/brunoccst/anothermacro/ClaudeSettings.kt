package io.github.brunoccst.anothermacro

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
 * Phone connector settings: the relay address and the two tokens.
 *
 * The tokens are encrypted with an AES key that lives in the Android Keystore (it can't
 * be exported), so the prefs file only holds ciphertext. The app has allowBackup=false,
 * so they aren't copied to cloud backups either. They are never logged.
 */
object ClaudeSettings {
    const val RELAY_GUIDE = "https://github.com/brunoccst/another-macro/blob/main/relay/README.md"
    private const val PREFS = "claude"
    private const val KEY_ALIAS = "another_macro_claude_key"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- secrets, encrypted with a key that never leaves the Android Keystore ----------------
    fun setSecret(c: Context, name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs(c).edit()
            .putString("${name}_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("${name}_ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
    }

    fun clearSecret(c: Context, name: String) = prefs(c).edit().remove("${name}_iv").remove("${name}_ct").apply()

    fun secret(c: Context, name: String): String? {
        val p = prefs(c)
        val iv = p.getString("${name}_iv", null) ?: return null
        val ct = p.getString("${name}_ct", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    // --- the phone connector ------------------------------------------------------------------
    /** The relay's base URL, e.g. https://another-macro-relay.<name>.workers.dev (no trailing slash). */
    fun relayUrl(c: Context): String = prefs(c).getString("relay_url", "")!!
    fun setRelayUrl(c: Context, url: String) = prefs(c).edit().putString("relay_url", url.trim().trimEnd('/')).apply()

    fun phoneToken(c: Context): String? = secret(c, "phone_token")
    fun connectorToken(c: Context): String? = secret(c, "connector_token")

    /** New random tokens for both ends; the old ones stop working once the relay has the new ones. */
    fun newTokens(c: Context) {
        setSecret(c, "phone_token", randomToken())
        setSecret(c, "connector_token", randomToken())
    }

    fun randomToken(): String {
        val b = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        return Base64.encodeToString(b, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
