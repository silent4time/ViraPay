package ir.virapay.app

import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PIN is never stored in plain text: PBKDF2 hash + random salt. */
object PinStore {
    private const val ITERATIONS = 50000

    fun isSet(prefs: SharedPreferences): Boolean = prefs.contains("pin_hash") && prefs.contains("pin_salt")

    fun set(prefs: SharedPreferences, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_hash", Base64.encodeToString(derive(pin, salt), Base64.NO_WRAP))
            .apply()
    }

    fun verify(prefs: SharedPreferences, pin: String): Boolean {
        val salt = Base64.decode(prefs.getString("pin_salt", "") ?: return false, Base64.NO_WRAP)
        val stored = Base64.decode(prefs.getString("pin_hash", "") ?: return false, Base64.NO_WRAP)
        return MessageDigest.isEqual(stored, derive(pin, salt))
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    }
}
