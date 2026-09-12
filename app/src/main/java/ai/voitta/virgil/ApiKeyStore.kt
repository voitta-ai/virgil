package ai.voitta.virgil

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The user's own Anthropic API key, held in Keystore-backed encrypted
 * preferences.
 *
 * A key on a device is only as protected as the device. That is acceptable for
 * single-user testing; anything wider needs a backend proxy holding the key
 * server-side. The key is never logged and never written to the evaluation log.
 */
object ApiKeyStore {

    private const val FILE = "virgil_secrets"
    private const val KEY = "anthropic_api_key"

    private fun preferences(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val retval = EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return retval
    }

    fun get(context: Context): String? {
        val stored = preferences(context).getString(KEY, null)
        val retval = if (stored.isNullOrBlank()) null else stored
        return retval
    }

    fun set(context: Context, value: String) {
        preferences(context).edit().putString(KEY, value.trim()).apply()
    }

    fun clear(context: Context) {
        preferences(context).edit().remove(KEY).apply()
    }
}
