package ai.voitta.virgil

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * One API key per waterfall vendor, in Keystore-backed encrypted preferences.
 *
 * A key on a device is only as protected as the device. That is acceptable for
 * single-user testing; anything wider needs a backend proxy holding keys
 * server-side. Keys are never logged and never written to the evaluation log.
 */
object ApiKeyStore {

    private const val FILE = "virgil_secrets"

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

    fun get(context: Context, vendor: String): String? {
        val stored = preferences(context).getString("key_$vendor", null)
        val retval = if (stored.isNullOrBlank()) null else stored
        return retval
    }

    fun set(context: Context, vendor: String, value: String) {
        preferences(context).edit().putString("key_$vendor", value.trim()).apply()
    }

    fun clear(context: Context, vendor: String) {
        preferences(context).edit().remove("key_$vendor").apply()
    }
}
