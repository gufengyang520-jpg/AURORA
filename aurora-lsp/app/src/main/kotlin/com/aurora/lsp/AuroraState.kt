package com.aurora.lsp

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val device: DeviceProfile
    )

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