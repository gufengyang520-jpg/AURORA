package com.aurora.lsp;

import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioManager;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies the AURORA curve to every live playback session.
 *
 * <p>Measured on this firmware: streaming players render through the spatializer output thread and
 * the Dolby DAP only acts as a control surface there, so its parameters never reach the audio.
 * A software equalizer attached to the same session does process the audio, which is why the
 * engine drives {@link AudioEq} instead.
 *
 * <p>Everything here runs on the engine thread. Audio calls block, and this process is
 * system_server: doing this on the main looper makes the watchdog kill the system.
 */
public final class AuroraEngine {

    private static final String TAG = "AURORA-LSP";

    public static final String K_ENABLED = "aurora_enabled";
    public static final String K_PANIC = "aurora_panic";
    public static final String K_PRESET = "aurora_preset";
    public static final String K_STRENGTH = "aurora_strength";
    public static final String K_GEQ = "aurora_geq";
    /**
     * The user's own saved curve. Kept apart from {@link #K_GEQ} so that switching to a preset
     * clears the live curve without throwing away the curve the user built by hand.
     */
    public static final String K_GEQ_SAVED = "aurora_geq_saved";
    public static final String K_SELFTEST = "aurora_selftest";

    public static final String R_STATE = "aurora_backend_state";
    public static final String R_MESSAGE = "aurora_backend_message";
    public static final String R_BANDS = "aurora_backend_bands";
    public static final String R_UPDATED = "aurora_backend_updated";
    public static final String R_SESSIONS = "aurora_backend_sessions";
    public static final String R_APPLIED = "aurora_backend_applied";
    /** Ring buffer of recent state changes, for the feedback form. App-only reads it. */
    public static final String R_LOG = "aurora_backend_log";
    /** App versionCode of the code actually running inside system_server. */
    public static final String R_ENGINE_VERSION = "aurora_engine_version";

    private static final int MAX_SESSIONS = 8;
    private static final int LOG_LINES = 30;
    private static final int PLAYER_STATE_STARTED = 2;
    private static final float MAX_BAND_DB = AudioEq.MAX_DB;
    /**
     * Ceiling for how much a curve may lift anything. A phone speaker is already close to
     * clipping at full volume, so a +12 dB shelf does not sound louder, it sounds broken.
     * Lifting is capped and the rest of the curve is shifted down to compensate.
     */
    private static final float MAX_BOOST_DB = 6f;

    private static volatile AuroraEngine instance;

    private final Context context;
    private final Map<Integer, AudioEq> effects = new HashMap<>();
    private final Set<Integer> settled = new HashSet<>();
    private final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
    private final java.text.SimpleDateFormat clock =
            new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
    private Method getActiveConfigs;
    private Method getSessionId;
    private Method getPlayerState;
    private boolean reflected;
    private String lastError = "";
    private String lastSignature = "";
    private int lastSelftestToken;
    private long selftestUntil;
    private Config lastGood;

    private AuroraEngine(Context context) {
        this.context = context;
    }

    public static AuroraEngine get(Context context) {
        if (instance == null) {
            synchronized (AuroraEngine.class) {
                if (instance == null) instance = new AuroraEngine(context);
            }
        }
        return instance;
    }

    /** Entry point, always called from the engine thread. */
    public void tick() {
        Config config = read();
        if (config.panic) {
            releaseAll();
            report("panic", "已通过 aurora_panic 停机，均衡器全部释放。", null, 0);
            return;
        }
        if (!config.enabled) {
            releaseAll();
            report("disabled", "叠加已关闭，音频走原厂链路。", null, 0);
            return;
        }
        Set<Integer> sessions = activeSessions();
        if (sessions.isEmpty()) {
            releaseAll();
            report("idle", "当前没有正在播放的会话，等待播放开始。", null, 0);
            return;
        }

        boolean testing = selftestActive();
        float[] gains = testing ? selftestGains() : buildGains(config);
        String desired = signature(gains);
        int applied = 0;
        String failure = "";

        int skipped = 0;
        for (Integer session : sessions) {
            AudioEq eq = effects.get(session);
            if (eq == null) {
                if (effects.size() >= MAX_SESSIONS) {
                    skipped++;
                    continue;
                }
                eq = new AudioEq();
                if (!eq.open(session)) {
                    failure = eq.lastError();
                    continue;
                }
                effects.put(session, eq);
            } else if (!settled.contains(session)) {
                // MiSound rebuilds the effect chain right after a track starts, which orphans an
                // effect created on the first tick. Rebuild once the session has settled.
                eq.close();
                eq = new AudioEq();
                if (!eq.open(session)) {
                    failure = eq.lastError();
                    continue;
                }
                effects.put(session, eq);
                settled.add(session);
            }
            if (eq.isApplied() && desired.equals(eq.signature()) && !testing) {
                applied++;
                continue;
            }
            if (!eq.apply(gains, true)) {
                failure = eq.lastError();
                continue;
            }
            eq.markApplied(desired);
            applied++;
        }

        releaseStale(sessions);
        if (applied == 0 && !failure.isEmpty()) {
            report("error", "写入失败：" + failure, gains, 0);
            return;
        }
        String message = testing
                ? "自检中：全频段 -12 dB，6 秒后自动恢复。"
                : "已写入 " + applied + " 个播放会话。";
        if (!failure.isEmpty()) message += " 部分会话失败：" + failure;
        if (skipped > 0) message += " 会话数已达上限，跳过 " + skipped + " 个。";
        report(testing ? "selftest" : "active", message, gains, applied);
    }

    private void releaseStale(Set<Integer> sessions) {
        List<Integer> gone = new ArrayList<>();
        for (Integer session : effects.keySet()) {
            if (!sessions.contains(session)) gone.add(session);
        }
        for (Integer session : gone) {
            AudioEq eq = effects.remove(session);
            if (eq != null) eq.close();
            settled.remove(session);
        }
    }

    private void releaseAll() {
        for (AudioEq eq : effects.values()) eq.close();
        effects.clear();
        settled.clear();
    }

    private Set<Integer> activeSessions() {
        Set<Integer> result = new HashSet<>();
        if (!reflect()) return result;
        try {
            Object manager = context.getSystemService(Context.AUDIO_SERVICE);
            Object value = getActiveConfigs.invoke(manager);
            if (!(value instanceof List)) return result;
            for (Object config : (List<?>) value) {
                int session = (Integer) getSessionId.invoke(config);
                int state = (Integer) getPlayerState.invoke(config);
                if (session <= 0 || session > 1_000_000) continue;
                if (state == PLAYER_STATE_STARTED) result.add(session);
            }
        } catch (Throwable error) {
            lastError = AudioEq.describe(error);
            Log.w(TAG, "session scan failed: " + lastError);
        }
        return result;
    }

    private boolean reflect() {
        if (reflected) return getActiveConfigs != null;
        reflected = true;
        try {
            Class<?> managerClass = Class.forName("android.media.AudioManager");
            getActiveConfigs = managerClass.getMethod("getActivePlaybackConfigurations");
            Class<?> configClass = Class.forName("android.media.AudioPlaybackConfiguration");
            getSessionId = configClass.getMethod("getSessionId");
            getPlayerState = configClass.getMethod("getPlayerState");
            return true;
        } catch (Throwable error) {
            lastError = AudioEq.describe(error);
            getActiveConfigs = null;
            Log.w(TAG, "playback configuration API unavailable: " + lastError);
            return false;
        }
    }

    private boolean selftestActive() {
        int token = 0;
        try {
            token = Settings.Global.getInt(context.getContentResolver(), K_SELFTEST, 0);
        } catch (Throwable ignored) {
        }
        if (token != lastSelftestToken) {
            lastSelftestToken = token;
            selftestUntil = android.os.SystemClock.elapsedRealtime() + 6000L;
        }
        return android.os.SystemClock.elapsedRealtime() < selftestUntil;
    }

    private static float[] selftestGains() {
        float[] flat = new float[AudioEq.BANDS];
        for (int i = 0; i < flat.length; i++) flat[i] = -MAX_BAND_DB;
        return flat;
    }

    /**
     * Preset or user curve, scaled by strength and then uniformly fitted into +/-{@link #MAX_BAND_DB}.
     *
     * <p>The shape is preserved: an earlier version subtracted the mean to keep loudness flat, but
     * that flattened exactly the tilt each preset is built around (a curve that lifts bass and cuts
     * treble averaged out to almost nothing). Scaling the whole curve instead keeps the contrast
     * between presets, which is the point of having presets at all.
     */
    static float[] buildGains(Config config) {
        float[] base = config.userCurve != null
                ? config.userCurve
                : Presets.curve(Presets.indexOf(config.preset));
        float scale = config.strength / 100f;
        float[] scaled = new float[AudioEq.BANDS];
        float peak = 0f;
        for (int i = 0; i < AudioEq.BANDS; i++) {
            scaled[i] = base[i] * scale;
            peak = Math.max(peak, Math.abs(scaled[i]));
        }
        float fit = peak > MAX_BAND_DB ? MAX_BAND_DB / peak : 1f;
        float[] out = new float[AudioEq.BANDS];
        for (int i = 0; i < AudioEq.BANDS; i++) {
            out[i] = scaled[i] * fit;
        }

        // Preamp: shift the whole curve down until nothing asks for more than MAX_BOOST_DB.
        // The shape, and with it the difference between presets, survives; only the absolute
        // level drops, which is exactly the trade a hardware EQ makes to stay clean.
        float loudest = -MAX_BAND_DB;
        for (int i = 0; i < AudioEq.BANDS; i++) loudest = Math.max(loudest, out[i]);
        if (loudest > MAX_BOOST_DB) {
            float shift = loudest - MAX_BOOST_DB;
            for (int i = 0; i < AudioEq.BANDS; i++) out[i] -= shift;
        }

        for (int i = 0; i < AudioEq.BANDS; i++) {
            out[i] = Math.max(-MAX_BAND_DB, Math.min(MAX_BOOST_DB, out[i]));
        }
        return out;
    }

    private static String signature(float[] gains) {
        StringBuilder builder = new StringBuilder();
        for (float gain : gains) {
            builder.append(Math.round(gain * 16)).append(',');
        }
        return builder.toString();
    }

    private void report(String state, String message, float[] gains, int applied) {
        String bands = gains == null ? "" : join(gains);
        String sessions = join(effects.keySet());
        String signature = state + '|' + message + '|' + bands + '|' + applied + '|' + sessions;
        if (signature.equals(lastSignature)) return;
        lastSignature = signature;
        try {
            ContentResolver resolver = context.getContentResolver();
            Settings.Global.putString(resolver, R_STATE, state);
            Settings.Global.putString(resolver, R_MESSAGE, message);
            Settings.Global.putString(resolver, R_BANDS, bands);
            Settings.Global.putString(resolver, R_SESSIONS, sessions);
            Settings.Global.putString(resolver, R_APPLIED, String.valueOf(applied));
            Settings.Global.putLong(resolver, R_UPDATED, System.currentTimeMillis());
            record(resolver, state, message);
        } catch (Throwable error) {
            Log.w(TAG, "report failed: " + AudioEq.describe(error));
        }
        Log.i(TAG, "backend=" + state + ": " + message + " sessions=" + sessions);
    }

    /** Keeps the last N state changes only: the app ships this with a bug report. */
    private void record(ContentResolver resolver, String state, String message) {
        recent.addLast(clock.format(new java.util.Date()) + " " + state + " " + message);
        while (recent.size() > LOG_LINES) recent.removeFirst();
        StringBuilder builder = new StringBuilder();
        for (String line : recent) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(line);
        }
        Settings.Global.putString(resolver, R_LOG, builder.toString());
    }

    /** Tenths of a dB, so the app can show a compact integer list. */
    private static String join(float[] gains) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < gains.length; i++) {
            if (i > 0) builder.append(',');
            builder.append(Math.round(gains[i] * 10));
        }
        return builder.toString();
    }

    private static String join(Set<Integer> values) {
        StringBuilder builder = new StringBuilder();
        for (Integer value : values) {
            if (builder.length() > 0) builder.append(',');
            builder.append(value);
        }
        return builder.toString();
    }

    private Config read() {
        Config config = new Config();
        try {
            ContentResolver resolver = context.getContentResolver();
            config.enabled = Settings.Global.getInt(resolver, K_ENABLED, 0) == 1;
            config.panic = Settings.Global.getInt(resolver, K_PANIC, 0) == 1;
            config.preset = Settings.Global.getString(resolver, K_PRESET);
            config.strength = clamp(Settings.Global.getInt(resolver, K_STRENGTH, 100), 0, 100);
            config.userCurve = parseCurve(Settings.Global.getString(resolver, K_GEQ));
        } catch (Throwable error) {
            // One failed read used to mean "disabled", which released every effect and flipped the
            // sound back to stock mid-song. Keep the last good config instead.
            Log.w(TAG, "config read failed: " + AudioEq.describe(error));
            if (lastGood != null) return lastGood;
            config.enabled = false;
        }
        lastGood = config;
        return config;
    }

    /** Stored as tenths of a dB so the value survives a round trip through settings. */
    static float[] parseCurve(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String[] parts = raw.split(",");
        if (parts.length != AudioEq.BANDS) return null;
        float[] curve = new float[AudioEq.BANDS];
        try {
            for (int i = 0; i < AudioEq.BANDS; i++) {
                curve[i] = Integer.parseInt(parts[i].trim()) / 10f;
            }
        } catch (NumberFormatException error) {
            return null;
        }
        return curve;
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    static final class Config {
        boolean enabled;
        boolean panic;
        String preset;
        int strength = 100;
        float[] userCurve;
    }
}