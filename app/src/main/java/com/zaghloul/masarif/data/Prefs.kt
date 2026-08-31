package com.zaghloul.masarif.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES/GCM through the Android Keystore. The key never leaves secure hardware, so
 * an API key stored here is not readable from a backup or by another app.
 *
 * Hand-rolled rather than pulled from androidx.security to keep the dependency
 * list minimal; the primitives below are the same ones that library uses.
 */
object Crypto {
    private const val KEY_ALIAS = "masarif_secret_key_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String = try {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = c.iv
        val body = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + body, Base64.NO_WRAP)
    } catch (_: Exception) {
        ""
    }

    fun decrypt(encoded: String): String = try {
        if (encoded.isBlank()) "" else {
            val all = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = all.copyOfRange(0, IV_LEN)
            val body = all.copyOfRange(IV_LEN, all.size)
            val c = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            }
            String(c.doFinal(body), Charsets.UTF_8)
        }
    } catch (_: Exception) {
        ""
    }
}

class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("masarif", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- onboarding

    var onboardingDone: Boolean
        get() = sp.getBoolean(K_ONBOARDED, false)
        set(v) = sp.edit().putBoolean(K_ONBOARDED, v).apply()

    var backfillDone: Boolean
        get() = sp.getBoolean(K_BACKFILL, false)
        set(v) = sp.edit().putBoolean(K_BACKFILL, v).apply()

    // ------------------------------------------------------------------- money

    /** Monthly take-home. 0 means "work it out from detected salary credits". */
    var salary: Double
        get() = sp.getFloat(K_SALARY, 0f).toDouble()
        set(v) = sp.edit().putFloat(K_SALARY, v.toFloat()).apply()

    /** Day of month the budget cycle restarts on, usually payday. */
    var cycleStartDay: Int
        get() = sp.getInt(K_CYCLE_DAY, 1).coerceIn(1, 28)
        set(v) = sp.edit().putInt(K_CYCLE_DAY, v.coerceIn(1, 28)).apply()

    /** Amount you want left over each month. */
    var savingsTarget: Double
        get() = sp.getFloat(K_SAVINGS, 0f).toDouble()
        set(v) = sp.edit().putFloat(K_SAVINGS, v.toFloat()).apply()

    var currency: String
        get() = sp.getString(K_CURRENCY, "AED") ?: "AED"
        set(v) = sp.edit().putString(K_CURRENCY, v).apply()

    // -------------------------------------------------------------- capture

    /** Packages whose notifications are ignored entirely. */
    var mutedPackages: Set<String>
        get() = sp.getStringSet(K_MUTED, emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(K_MUTED, v).apply()

    fun mute(pkg: String) {
        mutedPackages = mutedPackages + pkg
    }

    fun unmute(pkg: String) {
        mutedPackages = mutedPackages - pkg
    }

    var captureNotifications: Boolean
        get() = sp.getBoolean(K_CAP_NOTIF, true)
        set(v) = sp.edit().putBoolean(K_CAP_NOTIF, v).apply()

    var captureSms: Boolean
        get() = sp.getBoolean(K_CAP_SMS, true)
        set(v) = sp.edit().putBoolean(K_CAP_SMS, v).apply()

    // ----------------------------------------------------------------- coach

    /** "none", "anthropic" or "openai". */
    var llmProvider: String
        get() = sp.getString(K_LLM_PROVIDER, "none") ?: "none"
        set(v) = sp.edit().putString(K_LLM_PROVIDER, v).apply()

    var llmModel: String
        get() = sp.getString(K_LLM_MODEL, "") ?: ""
        set(v) = sp.edit().putString(K_LLM_MODEL, v).apply()

    /** Stored encrypted; the plaintext only exists in memory during a request. */
    var llmApiKey: String
        get() = Crypto.decrypt(sp.getString(K_LLM_KEY, "") ?: "")
        set(v) = sp.edit().putString(K_LLM_KEY, if (v.isBlank()) "" else Crypto.encrypt(v)).apply()

    val hasLlmKey: Boolean get() = !sp.getString(K_LLM_KEY, "").isNullOrBlank()

    /**
     * When false (the default) the coach receives category totals only.
     * When true it also receives merchant names. Card numbers and raw message
     * text are never sent either way.
     */
    var llmShareMerchants: Boolean
        get() = sp.getBoolean(K_LLM_MERCHANTS, false)
        set(v) = sp.edit().putBoolean(K_LLM_MERCHANTS, v).apply()

    var lastCoachText: String
        get() = sp.getString(K_COACH_TEXT, "") ?: ""
        set(v) = sp.edit().putString(K_COACH_TEXT, v).apply()

    var lastCoachAt: Long
        get() = sp.getLong(K_COACH_AT, 0L)
        set(v) = sp.edit().putLong(K_COACH_AT, v).apply()

    companion object {
        private const val K_ONBOARDED = "onboarded"
        private const val K_BACKFILL = "backfill_done"
        private const val K_SALARY = "salary"
        private const val K_CYCLE_DAY = "cycle_day"
        private const val K_SAVINGS = "savings_target"
        private const val K_CURRENCY = "currency"
        private const val K_MUTED = "muted_pkgs"
        private const val K_CAP_NOTIF = "cap_notif"
        private const val K_CAP_SMS = "cap_sms"
        private const val K_LLM_PROVIDER = "llm_provider"
        private const val K_LLM_MODEL = "llm_model"
        private const val K_LLM_KEY = "llm_key"
        private const val K_LLM_MERCHANTS = "llm_merchants"
        private const val K_COACH_TEXT = "coach_text"
        private const val K_COACH_AT = "coach_at"

        const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-5"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
    }
}
