package ir.virapay.app

import android.content.Context
import android.provider.Settings
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Device-bound licensing (built, but OFF while AppConfig.LICENSE_ENABLED = false).
 *
 * Flow:
 *  1. The app computes a device hash and a random nonce and sends {key, device, nonce} to the server.
 *  2. First activation: the server binds the key to that device hash, stores it, and returns
 *     a token = base64url(payload) + "." + base64url(ECDSA-P256-signature(payload)).
 *     payload = {"key","dev","nonce","iat","v"}.
 *  3. Any other device that sends the same key is refused ("already_used").
 *  4. The app verifies the signature with the embedded public key, checks that
 *     payload.dev equals THIS device and payload.nonce equals the nonce it sent (anti-replay /
 *     fake-server). The token is then stored; later launches only re-verify it offline.
 */
object LicenseManager {
    private const val PREFS = "ViraPayLicense"

    sealed class Result {
        object Ok : Result()
        data class Error(val code: String) : Result()
    }

    fun enabled(): Boolean = AppConfig.LICENSE_ENABLED

    fun maxBots(ctx: Context): Int =
        if (!enabled() || isLicensed(ctx)) Int.MAX_VALUE else AppConfig.FREE_MAX_BOTS

    fun maxCards(ctx: Context): Int =
        if (!enabled() || isLicensed(ctx)) Int.MAX_VALUE else AppConfig.FREE_MAX_CARDS

    /** Stable per-install-device identifier (hashed, never sent in clear). */
    fun deviceHash(ctx: Context): String {
        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        return sha256Hex("$androidId|${ctx.packageName}")
    }

    fun isLicensed(ctx: Context): Boolean {
        val token = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("token", null)
            ?: return false
        return verify(ctx, token, null) != null
    }

    /** Blocking network call: run it on a background thread. */
    fun activate(ctx: Context, licenseKey: String): Result {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val body = JSONObject()
            .put("key", licenseKey.trim())
            .put("device", deviceHash(ctx))
            .put("nonce", nonce)
            .put("app", ctx.packageName)
            .toString()
        return try {
            val conn = URL(AppConfig.LICENSE_SERVER_URL.trimEnd('/') + "/activate")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            if (!json.optBoolean("ok")) {
                Result.Error(json.optString("error", "server_error"))
            } else {
                val token = json.getString("token")
                if (verify(ctx, token, nonce) == null) {
                    Result.Error("bad_signature")
                } else {
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit().putString("token", token).apply()
                    Result.Ok
                }
            }
        } catch (e: Exception) {
            Result.Error("network")
        }
    }

    /** Returns the payload if the token is valid for THIS device (and nonce, when given). */
    private fun verify(ctx: Context, token: String, expectedNonce: String?): JSONObject? {
        return try {
            val parts = token.split(".")
            if (parts.size != 2) return null
            val payloadBytes = Base64.decode(parts[0], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val sigBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val pub = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(Base64.decode(AppConfig.LICENSE_PUBLIC_KEY, Base64.DEFAULT))
            )
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(pub)
            sig.update(payloadBytes)
            if (!sig.verify(sigBytes)) return null
            val payload = JSONObject(String(payloadBytes, Charsets.UTF_8))
            if (payload.optString("dev") != deviceHash(ctx)) return null
            if (expectedNonce != null && payload.optString("nonce") != expectedNonce) return null
            payload
        } catch (e: Exception) {
            null
        }
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
