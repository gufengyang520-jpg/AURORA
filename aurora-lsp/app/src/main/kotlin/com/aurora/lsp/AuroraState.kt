package com.aurora.lsp

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for the UI.
 *
 * Reads go straight through Settings.Global (no root needed); writes go through `su` because the
 * module's settings live in the global namespace.
 */
class AuroraState(private val context: Context) {

    var enabled by mutableStateOf(false)
        private set
    var preset by mutableStateOf("flat")
        private set
    var strength by mutableStateOf(100)
        private set
    var curve by mutableStateOf(FloatArray(AudioEq.BANDS))
        private set

    var backendState by mutableStateOf("")
        private set
    var backendMessage by mutableStateOf("")
        private set
    var sessions by mutableStateOf("")
        private set
    var applied by mutableStateOf("")
        private set

    var loaded by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The user's own saved curve, independent of whichever preset is currently active. */
    var savedCurve by mutableStateOf<FloatArray?>(null)
        private set

    /** Recent engine state changes, oldest first. Shown on the diagnostics page. */
    var engineLog by mutableStateOf<List<String>>(emptyList())
        private set

    /** Presets the user saved, newest last. */
    var userPresets by mutableStateOf<List<UserPreset>>(emptyList())
        private set

    /** Package name to preset id. */
    var appMap by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** Stereo widening, 0-3 dB. */
    var spread by mutableStateOf(0f)
        private set

    /** Perceived loudness boost in dB (LoudnessEnhancer). */
    var loudnessDb by mutableStateOf(0f)
        private set

    /** NXP Virtualizer strength, 0-1000. */
    var virtualizer by mutableStateOf(0)
        private set

    /** Gains the engine actually wrote, as reported back through aurora_backend_bands. */
    var actualBands by mutableStateOf<FloatArray?>(null)
        private set

    /** What the module found when it probed this device at boot. */
    var device by mutableStateOf(DeviceProfile())
        private set

    val presetIndex: Int get() = Presets.indexOf(preset)

    /**
     * Settings.Global reads are binder round trips; doing eight of them on the main thread every
     * two seconds was enough to make page switches stutter. Read off the main thread, publish once.
     */
    suspend fun refresh() {
        apply(withContext(Dispatchers.IO) { readSnapshot() })
    }

    private class Snapshot(
        val enabled: String?,
        val preset: String?,
        val strength: String?,
        val state: String?,
        val message: String?,
        val sessions: String?,
        val applied: String?,
        val geq: String?,
        val geqSaved: String?,
        val bands: String?,
        val log: String?,
        val userPresetsRaw: String?,
        val appMapRaw: String?,
        val spreadRaw: String?,
        val loudnessRaw: String?,
        val virtualizerRaw: String?,
        val device: DeviceProfile
    )

    /** A preset the user saved and named. */
    data class UserPreset(val id: String, val name: String, val curve: FloatArray) {
        override fun equals(other: Any?): Boolean = other is UserPreset && other.id == id
        override fun hashCode(): Int = id.hashCode()
    }

    private fun readSnapshot(): Snapshot {
        val resolver = context.contentResolver
        fun read(key: String): String? = try {
            Settings.Global.getString(resolver, key)
        } catch (t: Throwable) {
            null
        }
        val resolver2 = context.contentResolver
        return Snapshot(
            enabled = read(AuroraEngine.K_ENABLED),
            preset = read(AuroraEngine.K_PRESET),
            strength = read(AuroraEngine.K_STRENGTH),
            state = read(AuroraEngine.R_STATE),
            message = read(AuroraEngine.R_MESSAGE),
            sessions = read(AuroraEngine.R_SESSIONS),
            applied = read(AuroraEngine.R_APPLIED),
            geq = read(AuroraEngine.K_GEQ),
            geqSaved = read(AuroraEngine.K_GEQ_SAVED),
            bands = read(AuroraEngine.R_BANDS),
            log = read(AuroraEngine.R_LOG),
            userPresetsRaw = read(AuroraEngine.K_USER_PRESETS),
            appMapRaw = read(AuroraEngine.K_APP_MAP),
            spreadRaw = read(AuroraEngine.K_SPREAD),
            loudnessRaw = read(AuroraEngine.K_LOUDNESS),
            virtualizerRaw = read(AuroraEngine.K_VIRTUALIZER),
            device = DeviceProfile.read(resolver2)
        )
    }

    private fun apply(raw: Snapshot) {
        enabled = raw.enabled == "1"
        raw.preset?.let { if (it.isNotEmpty() && it != "null") preset = it }
        raw.strength?.toIntOrNull()?.let { strength = it.coerceIn(0, 100) }
        backendState = raw.state.orEmpty().let { if (it == "null") "" else it }
        backendMessage = raw.message.orEmpty().let { if (it == "null") "" else it }
        sessions = raw.sessions.orEmpty().let { if (it == "null") "" else it }
        applied = raw.applied.orEmpty().let { if (it == "null") "" else it }

        val stored = parseCurve(raw.geq)
        curve = stored ?: Presets.curve(Presets.indexOf(preset)).copyOf()
        if (stored != null) preset = "custom"
        savedCurve = parseCurve(raw.geqSaved)
        actualBands = parseCurve(raw.bands)
        engineLog = raw.log.orEmpty()
            .let { if (it == "null") "" else it }
            .split('\n')
            .filter { it.isNotBlank() }
            .takeLast(50)
        userPresets = parsePresets(raw.userPresetsRaw)
        appMap = parseAppMap(raw.appMapRaw)
        spread = (raw.spreadRaw?.toIntOrNull() ?: 0) / 10f
        loudnessDb = ((raw.loudnessRaw?.toIntOrNull() ?: 0) / 100f)
        virtualizer = raw.virtualizerRaw?.toIntOrNull() ?: 0
        device = raw.device
        loaded = true
    }

    suspend fun update(
        newEnabled: Boolean = enabled,
        newPreset: String = preset,
        newStrength: Int = strength,
        newCurve: FloatArray? = null
    ) {
        saving = true
        error = null
        // The preset key ends up inside a shell command; anything not in the table is rejected
        // here rather than escaped later.
        val safePreset = if (newPreset == "custom" || newPreset in Presets.KEYS) newPreset else "flat"
        val targetCurve = newCurve ?: Presets.curve(Presets.indexOf(safePreset)).copyOf()
        val isCustom = newCurve != null || safePreset == "custom"

        val command = buildString {
            append("settings put global ").append(AuroraEngine.K_ENABLED).append(' ')
            append(if (newEnabled) 1 else 0)
            append(" && settings put global ").append(AuroraEngine.K_PRESET).append(' ')
            append(if (isCustom) "flat" else safePreset)
            append(" && settings put global ").append(AuroraEngine.K_STRENGTH).append(' ')
            append(newStrength)
            if (isCustom) {
                val encoded = targetCurve.joinToString(",") { (it * 10).toInt().toString() }
                append(" && settings put global ").append(AuroraEngine.K_GEQ).append(' ').append(encoded)
                // Also keep a copy in the saved slot so switching presets later cannot lose it.
                append(" && settings put global ")
                    .append(AuroraEngine.K_GEQ_SAVED).append(' ').append(encoded)
            } else {
                append(" && settings delete global ").append(AuroraEngine.K_GEQ)
            }
            append(" && echo AURORA_OK")
        }

        val output = withContext(Dispatchers.IO) { runRoot(command) }
        saving = false
        if (!output.contains("AURORA_OK")) {
            AuroraLog.append(context, "save", "failed: " + output.take(200))
            error = "保存失败。请确认已给 AURORA 授予 Root 权限后重试。当前设置没有丢失。"
            return
        }
        enabled = newEnabled
        preset = if (isCustom) "custom" else safePreset
        strength = newStrength
        curve = targetCurve
        if (isCustom) savedCurve = targetCurve
        refresh()
    }

    /** Re-applies the curve the user saved earlier, after they switched to a preset. */
    suspend fun restoreSaved() {
        val saved = savedCurve ?: return
        update(newCurve = saved)
    }

    /** Writes the three advanced DSP parameters through su. */
    suspend fun updateAdvanced(
        spread: Float = this.spread,
        loudnessDb: Float = this.loudnessDb,
        virtualizer: Int = this.virtualizer
    ) {
        saving = true
        error = null
        val cmd = buildString {
            append("settings put global ").append(AuroraEngine.K_SPREAD).append(' ')
            append((spread * 10).toInt())
            append(" && settings put global ").append(AuroraEngine.K_LOUDNESS).append(' ')
            append((loudnessDb * 100).toInt())
            append(" && settings put global ").append(AuroraEngine.K_VIRTUALIZER).append(' ')
            append(virtualizer)
            append(" && echo AURORA_OK")
        }
        val output = withContext(Dispatchers.IO) { runRoot(cmd) }
        saving = false
        if (!output.contains("AURORA_OK")) {
            error = "保存失败。请确认已给 AURORA 授予 Root 权限。"
            return
        }
        this.spread = spread
        this.loudnessDb = loudnessDb
        this.virtualizer = virtualizer
        refresh()
    }

    suspend fun selfTest() {
        val token = System.currentTimeMillis() / 1000
        withContext(Dispatchers.IO) {
            runRoot("settings put global " + AuroraEngine.K_SELFTEST + " " + token)
        }
    }

    private fun parseCurve(raw: String?): FloatArray? {
        if (raw == null || raw.isEmpty() || raw == "null") return null
        val parts = raw.split(",")
        if (parts.size != AudioEq.BANDS) return null
        return FloatArray(AudioEq.BANDS) { parts[it].trim().toIntOrNull()?.div(10f) ?: return null }
    }

    private fun parsePresets(raw: String?): List<UserPreset> {
        if (raw.isNullOrEmpty() || raw == "null") return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                val curve = item.optJSONArray("curve") ?: return@mapNotNull null
                if (id.isEmpty() || curve.length() != AudioEq.BANDS) return@mapNotNull null
                UserPreset(
                    id = id,
                    name = item.optString("name").ifBlank { "未命名" },
                    curve = FloatArray(AudioEq.BANDS) {
                        (curve.optDouble(it, 0.0) / 10.0).toFloat()
                    }
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun parseAppMap(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty() || raw == "null") return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val out = mutableMapOf<String, String>()
            obj.keys().forEach { key ->
                val value = obj.optString(key)
                if (value.isNotEmpty() && value != "null") out[key] = value
            }
            out
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    private fun presetsToJson(presets: List<UserPreset>): String {
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(JSONObject().apply {
                put("id", preset.id)
                put("name", preset.name)
                put("curve", JSONArray(preset.curve.map { (it * 10).toInt() }))
            })
        }
        return array.toString()
    }

    private fun appMapToJson(map: Map<String, String>): String {
        val json = JSONObject()
        map.forEach { (key, value) -> json.put(key, value) }
        return json.toString()
    }

    /** Saves the given curve under a new name; the newest preset lands at the end of the list. */
    suspend fun savePreset(name: String, curve: FloatArray) {
        val id = "u" + System.currentTimeMillis()
        val updated = userPresets + UserPreset(id, name.trim().ifBlank { "我的预设" }, curve)
        commit(AuroraEngine.K_USER_PRESETS, presetsToJson(updated))
    }

    suspend fun renamePreset(id: String, name: String) {
        val updated = userPresets.map {
            if (it.id == id) UserPreset(it.id, name.trim().ifBlank { it.name }, it.curve) else it
        }
        commit(AuroraEngine.K_USER_PRESETS, presetsToJson(updated))
    }

    suspend fun deletePreset(id: String) {
        commit(AuroraEngine.K_USER_PRESETS, presetsToJson(userPresets.filterNot { it.id == id }))
        // Assignments pointing at the removed preset would silently fall back; clear them now.
        val cleaned = appMap.filterNot { it.value == id }
        if (cleaned.size != appMap.size) {
            commit(AuroraEngine.K_APP_MAP, appMapToJson(cleaned))
        }
    }

    suspend fun assignApp(packageName: String, presetId: String?) {
        val updated = appMap.toMutableMap()
        if (presetId == null) updated.remove(packageName) else updated[packageName] = presetId
        commit(AuroraEngine.K_APP_MAP, appMapToJson(updated))
    }

    /** Writes one JSON key through su, then reloads so the UI reflects what actually landed. */
    private suspend fun commit(key: String, json: String) {
        val escaped = json.replace("'", "'\\''")
        val output = withContext(Dispatchers.IO) {
            runRoot("settings put global $key '$escaped' && echo AURORA_OK")
        }
        if (!output.contains("AURORA_OK")) {
            AuroraLog.append(context, "preset", "write failed: " + output.take(160))
            error = "保存失败。请确认已给 AURORA 授予 Root 权限。"
            return
        }
        error = null
        refresh()
    }

    private fun runRoot(command: String): String {
        val output = StringBuilder()
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val running = process
            val reader = Thread {
                BufferedReader(InputStreamReader(running.inputStream)).use { input ->
                    var line: String? = input.readLine()
                    while (line != null && output.length < 4000) {
                        synchronized(output) { output.append(line).append('\n') }
                        line = input.readLine()
                    }
                }
            }
            reader.start()
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return "timeout"
            }
            reader.join(600)
            output.toString()
        } catch (t: Throwable) {
            process?.destroyForcibly()
            "error:${t.javaClass.simpleName}"
        }
    }
}