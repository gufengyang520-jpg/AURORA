package com.aurora.lsp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Fetches the per-device adaptation pack from the release host and stores it in Settings for the
 * engine.
 *
 * <p>The pack lets a device be tuned - band table, per-band boost ceilings, limiter shape -
 * without shipping a new APK: the engine reads {@code aurora_device_pack} on every tick. The
 * device profile the app reports identifies models as "product/device", so packs match on the
 * product code (e.g. 2512BPNDAC), never on the friendly name.
 */
object DevicePack {

    const val ENDPOINT = "https://lingluandf.online/aurora/devices.json"

    /**
     * Signature header the server must attach: ECDSA P-256 over SHA-256 of the raw response body,
     * DER-encoded, base64. The public key below is the release key; a response without this
     * header, or with a signature that does not verify, is rejected outright - a hostile
     * devices.json can move the band table, raise the boost ceilings and reshape the limiter, so
     * an unsigned pack is a config takeover, not a missing feature.
     */
    const val SIGNATURE_HEADER = "X-Aurora-Signature"

    /** Release public key (SPKI DER, base64). The matching private key stays out of the APK. */
    private const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEBuu71aX+duJEp/asXmH7ZQmv4itZznv0IMqIQJmEQY0FFiGej5TKy/t6pOT0MT0IN4lIbX8FAprk9Ar9/MxBbQ=="

    sealed interface Result {
        /** A pack matched and was written to Settings. */
        data class Applied(val name: String) : Result

        /** The stored pack is already this one. */
        data class Unchanged(val name: String) : Result

        /** Server reachable, no pack for this model: the defaults stay in force. */
        object NoMatch : Result

        data class Failed(val reason: String) : Result
    }

    suspend fun checkAndApply(state: AuroraState): Result = withContext(Dispatchers.IO) {
        val model = state.device.model
        val stored = state.currentPackRaw()
        val fetched: String
        try {
            val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("User-Agent", "AURORA-Android")
            connection.setRequestProperty("Cache-Control", "no-cache")
            val signatureHeader: String? = connection.getHeaderField(SIGNATURE_HEADER)
            val body = try {
                val code = connection.responseCode
                if (code !in 200..299) return@withContext Result.Failed("HTTP $code")
                connection.inputStream.readBytes()
            } finally {
                connection.disconnect()
            }
            if (!verify(body, signatureHeader)) {
                return@withContext Result.Failed("signature invalid")
            }
            fetched = body.toString(Charsets.UTF_8)
        } catch (t: Throwable) {
            return@withContext Result.Failed(t.javaClass.simpleName)
        }

        val pack = match(fetched, model)
            ?: return@withContext run {
                // A model that gained a pack and later lost it must not keep running the old one.
                if (!stored.isNullOrBlank() && stored != "null") {
                    state.applyDevicePack(null)
                }
                Result.NoMatch
            }

        val json = pack.toString()
        if (json == stored) return@withContext Result.Unchanged(pack.optString("name"))
        if (!state.applyDevicePack(json)) return@withContext Result.Failed("write rejected")
        Result.Applied(pack.optString("name"))
    }

    /** First pack whose match list contains this product code, or null. */
    private fun match(raw: String, model: String): JSONObject? {
        return try {
            val packs = JSONObject(raw).optJSONArray("packs") ?: return null
            val product = model.substringBefore('/')
            for (i in 0 until packs.length()) {
                val pack = packs.optJSONObject(i) ?: continue
                val matchList = pack.optJSONArray("match") ?: continue
                for (m in 0 until matchList.length()) {
                    val candidate = matchList.optString(m)
                    if (candidate == product || model.contains(candidate)) return pack
                }
            }
            null
        } catch (t: Throwable) {
            null
        }
    }

    /** The server file packs may carry fields the engine does not use yet; they are dropped here. */
    internal fun packJsonForTest(raw: String, model: String): String? =
        match(raw, model)?.toString()

    /**
     * Verifies the ECDSA P-256 signature over the exact response bytes. Missing header, a
     * signature that is not base64/DER, or a mismatch all return false, which fails the fetch
     * closed - no pack is applied from an unverifiable response.
     */
    fun verify(body: ByteArray, signatureB64: String?): Boolean {
        if (signatureB64.isNullOrBlank()) return false
        return try {
            val key: PublicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY_B64)))
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initVerify(key)
            signature.update(body)
            signature.verify(Base64.getDecoder().decode(signatureB64))
        } catch (t: Throwable) {
            false
        }
    }
}
