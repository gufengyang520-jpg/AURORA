package com.aurora.lsp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
            fetched = try {
                val code = connection.responseCode
                if (code !in 200..299) return@withContext Result.Failed("HTTP $code")
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
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
}
