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
 * Claude settings: the user's own API key and the per-task limits.
 *
 * The key is encrypted with an AES key that lives in the Android Keystore (it can't be
 * exported), so the prefs file only holds ciphertext. The app has allowBackup=false, so
 * it isn't copied to cloud backups either. The key is never logged.
 */
object ClaudeSettings {
    private const val PREFS = "claude"
    private const val KEY_ALIAS = "another_macro_claude_key"

    /** Models the user can pick, with list prices (US$ per million tokens: input, output, cache read). */
    data class ModelInfo(val id: String, val label: String, val input: Double, val output: Double, val cacheRead: Double)

    val MODELS = listOf(
        ModelInfo("claude-opus-5-5", "Opus 5.5", 4.0, 20.0, 0.20),
        ModelInfo("claude-sonnet-5-5", "Sonnet 5.5", 2.0, 10.0, 0.20),
        // Economy: about 1/20 of Sonnet's price (prompts up to 100K tokens; context editing keeps
        // them well under that). Good for fine-tuning macros; weaker on hard, unfamiliar screens.
        ModelInfo("claude-haiku-5-5", "Haiku 5.5", 0.10, 0.50, 0.01),
    )
    val EFFORTS = listOf("low", "medium", "high")

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun model(c: Context): ModelInfo = prefs(c).getString("model", null)?.let { id -> MODELS.firstOrNull { it.id == id } } ?: MODELS.first()
    fun setModel(c: Context, id: String) = prefs(c).edit().putString("model", id).apply()

    fun effort(c: Context): String = prefs(c).getString("effort", "medium")!!
    fun setEffort(c: Context, e: String) = prefs(c).edit().putString("effort", e).apply()

    fun maxSteps(c: Context): Int = prefs(c).getInt("max_steps", 150)
    fun setMaxSteps(c: Context, n: Int) = prefs(c).edit().putInt("max_steps", n.coerceIn(1, 2000)).apply()

    fun maxCostUsd(c: Context): Double = prefs(c).getFloat("max_cost", 3f).toDouble()
    fun setMaxCostUsd(c: Context, v: Double) = prefs(c).edit().putFloat("max_cost", v.coerceIn(0.05, 100.0).toFloat()).apply()

    fun lastTask(c: Context): String = prefs(c).getString("last_task", "")!!
    fun setLastTask(c: Context, t: String) = prefs(c).edit().putString("last_task", t).apply()

    // --- the API key --------------------------------------------------------------------
    fun hasKey(c: Context) = prefs(c).contains("key_ct")

    /** The last 4 characters, for showing which key is saved. */
    fun keyHint(c: Context): String = apiKey(c)?.takeLast(4)?.let { "…$it" } ?: "none"

    fun setApiKey(c: Context, key: String) {
        val k = key.trim()
        require(k.isNotEmpty()) { "empty key" }
        setSecret(c, "key", k)
    }

    fun clearApiKey(c: Context) = clearSecret(c, "key")

    fun apiKey(c: Context): String? = secret(c, "key") ?: null.also {
        if (hasKey(c)) MacroLog.e("could not read the saved Claude key; enter it again")
    }

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
