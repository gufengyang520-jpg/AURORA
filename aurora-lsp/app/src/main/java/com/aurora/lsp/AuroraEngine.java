package com.aurora.lsp;

import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies the AURORA curve to every live playback session.
 */
public final class AuroraEngine {

    private static final String TAG = "AURORA-LSP";

    public static final String K_ENABLED = "aurora_enabled";
    public static final String K_PANIC = "aurora_panic";
    public static final String K_PRESET = "aurora_preset";
    public static final String K_STRENGTH = "aurora_strength";
    public static final String K_GEQ = "aurora_geq";
    public static final String K_GEQ_SAVED = "aurora_geq_saved";
    public static final String K_SELFTEST = "aurora_selftest";
    public static final String K_USER_PRESETS = "aurora_user_presets";
    public static final String K_APP_MAP = "aurora_app_map";
    /** Advanced settings: spread (stereo widening) and perceived loudness. */
    public static final String K_SPREAD = "aurora_spread";
    public static final String K_LOUDNESS = "aurora_loudness";

    public static final String R_STATE = "aurora_backend_state";
    public static final String R_MESSAGE = "aurora_backend_message";
    public static final String R_BANDS = "aurora_backend_bands";
    public static final String R_UPDATED = "aurora_backend_updated";
    public static final String R_SESSIONS = "aurora_backend_sessions";
    public static final String R_APPLIED = "aurora_backend_applied";
    public static final String R_LOG = "aurora_backend_log";
    public static final String R_ENGINE_VERSION = "aurora_engine_version";

    private static final int MAX_SESSIONS = 8;
    private static final int LOG_LINES = 50;
    private static final long PLAYBACK_DEBOUNCE_MS = 60L;

    /** Loudness compensation shape per band, scaled by how far the volume sits below 60%. */
    private static final float[] LOUDNESS_SHAPE = {1.0f, 0.5f, 0f, -0.1f, 0.3f, 0.6f};
    private static final float MAX_LOUDNESS_BOOST_DB = 4f;

    /**
     * Per-band ceiling. 80 Hz and 10 kHz are the widest; 200 Hz stays low because pushing the
     * muddy range makes everything sound thick. 500 Hz has headroom but is rarely needed.
     */
    private static final float[] MAX_BOOST_BY_BAND = {8f, 4f, 6f, 8f, 8f, 8f};

    private static volatile AuroraEngine instance;

    private final Context context;
    private final Map<Integer, AudioEq> effects = new HashMap<>();
    private final Map<Integer, Long> lastSeen = new HashMap<>();
    private final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
    private final java.text.SimpleDateFormat clock =
            new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
    private final Map<Integer, String> uidPackages = new HashMap<>();
    private Method getActiveConfigs;
    private Method getSessionId;
    private Method getPlayerState;
    private Method getClientUid;
    private Method getAudioDeviceInfo;
    private boolean reflected;
    private String lastError = "";
    private String lastSignature = "";
    private int lastSelftestToken;
    private long selftestUntil;
    private Config lastGood;

    private final AudioManager.AudioPlaybackCallback playbackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    Handler handler = engineHandler;
                    if (handler == null) return;
                    handler.removeCallbacks(playbackTick);
                    handler.postDelayed(playbackTick, PLAYBACK_DEBOUNCE_MS);
                }
            };

    private final Runnable playbackTick = new Runnable() {
        @Override
        public void run() {
            tick();
        }
    };

    private volatile Handler engineHandler;

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

    public void attach(Handler handler) {
        this.engineHandler = handler;
        try {
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (manager != null) {
                manager.registerAudioPlaybackCallback(playbackCallback, handler);
                Log.i(TAG, "playback callback registered");
            }
        } catch (Throwable error) {
            Log.w(TAG, "playback callback unavailable: " + AudioEq.describe(error));
        }
    }

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
        Map<Integer, SessionInfo> sessions = activeSessions();
        if (sessions.isEmpty()) {
            releaseStale(android.os.SystemClock.elapsedRealtime());
            report("idle", "当前没有正在播放的会话，等待播放开始。", null, 0);
            return;
        }

        boolean testing = selftestActive();
        int applied = 0;
        String failure = "";
        float[] lastGains = null;

        long now = android.os.SystemClock.elapsedRealtime();
        int volume = musicVolumePercent();

        int skipped = 0;
        for (Map.Entry<Integer, SessionInfo> entry : sessions.entrySet()) {
            int session = entry.getKey();
            SessionInfo info = entry.getValue();
            lastSeen.put(session, now);

            float[] gains = testing ? selftestGains() : buildGains(config, info, volume);
            float[] spread = buildSpread(config);
            String desired = signature(gains) + "s" + config.spread + "l" + config.loudnessMb;
            lastGains = gains;

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
            }
            if (eq.isApplied() && desired.equals(eq.signature()) && !testing) {
                applied++;
                continue;
            }
            if (!eq.apply(gains, spread, config.loudnessMb, true)) {
                failure = eq.lastError();
                continue;
            }
            eq.markApplied(desired);
            applied++;
        }

        releaseStale(now);
        if (applied == 0 && !failure.isEmpty()) {
            report("error", "写入失败：" + failure, lastGains, 0);
            return;
        }
        String message = testing
                ? "自检中：全频段 -12 dB，6 秒后自动恢复。"
                : "已写入 " + applied + " 个播放会话。";
        if (!failure.isEmpty()) message += " 部分会话失败：" + failure;
        if (skipped > 0) message += " 会话数已达上限，跳过 " + skipped + " 个。";
        report(testing ? "selftest" : "active", message, lastGains, applied);
    }

    private static float[] buildSpread(Config config) {
        if (config.spread <= 0) return null;
        // Non-uniform: the ear localises high frequencies by inter-aural level difference, so
        // the top band gets the full amount, 4k gets most of it, 1.5k gets half. Bass stays
        // centred - separating it makes the low end thin, not wide.
        return new float[]{0f, 0f, 0f, config.spread * 0.5f, config.spread * 0.8f, config.spread};
    }

    private void releaseStale(long now) {
        List<Integer> gone = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : lastSeen.entrySet()) {
            if (now - entry.getValue() > 10_000L) gone.add(entry.getKey());
        }
        for (Integer session : gone) {
            lastSeen.remove(session);
            AudioEq eq = effects.remove(session);
            if (eq != null) eq.close();
        }
    }

    private void releaseAll() {
        for (AudioEq eq : effects.values()) eq.close();
        effects.clear();
        lastSeen.clear();
    }

    private Map<Integer, SessionInfo> activeSessions() {
        Map<Integer, SessionInfo> result = new HashMap<>();
        if (!reflect()) return result;
        try {
            Object manager = context.getSystemService(Context.AUDIO_SERVICE);
            Object value = getActiveConfigs.invoke(manager);
            if (!(value instanceof List)) return result;
            for (Object config : (List<?>) value) {
                int session = (Integer) getSessionId.invoke(config);
                int state = (Integer) getPlayerState.invoke(config);
                if (session <= 0 || session > 1_000_000) continue;
                if (state != 2) continue;
                int uid = uidOf(config);
                result.put(session, new SessionInfo(session, uid, deviceTypeOf(config), packageOf(uid)));
            }
        } catch (Throwable error) {
            lastError = AudioEq.describe(error);
            Log.w(TAG, "session scan failed: " + lastError);
        }
        return result;
    }

    private int uidOf(Object config) {
        if (getClientUid == null) return -1;
        try {
            Object value = getClientUid.invoke(config);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private int deviceTypeOf(Object config) {
        if (getAudioDeviceInfo == null) return -1;
        try {
            Object value = getAudioDeviceInfo.invoke(config);
            if (value instanceof android.media.AudioDeviceInfo) {
                return ((android.media.AudioDeviceInfo) value).getType();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private String packageOf(int uid) {
        if (uid <= 0) return null;
        if (uidPackages.containsKey(uid)) return uidPackages.get(uid);
        String resolved = null;
        try {
            String[] names = context.getPackageManager().getPackagesForUid(uid);
            if (names != null && names.length > 0) resolved = names[0];
        } catch (Throwable ignored) {
        }
        uidPackages.put(uid, resolved);
        return resolved;
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
            try { getClientUid = configClass.getMethod("getClientUid"); } catch (Throwable ignored) {}
            try { getAudioDeviceInfo = configClass.getMethod("getAudioDeviceInfo"); } catch (Throwable ignored) {}
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
        for (int i = 0; i < flat.length; i++) flat[i] = -AudioEq.MAX_DB;
        return flat;
    }

    private int musicVolumePercent() {
        try {
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (manager == null) return 100;
            int max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max <= 0) return 100;
            int current = manager.getStreamVolume(AudioManager.STREAM_MUSIC);
            return Math.max(0, Math.min(100, Math.round(current * 100f / max)));
        } catch (Throwable ignored) {
            return 100;
        }
    }

    static float[] buildGains(Config config, SessionInfo info, int volumePercent) {
        float[] base = resolveCurve(config, info);
        float scale = config.strength / 100f;
        float[] scaled = new float[AudioEq.BANDS];
        float peak = 0f;
        for (int i = 0; i < AudioEq.BANDS; i++) {
            scaled[i] = base[i] * scale;
            peak = Math.max(peak, Math.abs(scaled[i]));
        }
        float fit = peak > AudioEq.MAX_DB ? AudioEq.MAX_DB / peak : 1f;
        float[] out = new float[AudioEq.BANDS];
        for (int i = 0; i < AudioEq.BANDS; i++) {
            out[i] = scaled[i] * fit;
        }

        applyVolumeCompensation(out, volumePercent);
        applyRouteTrim(out, info);

        float loudest = -AudioEq.MAX_DB;
        for (int i = 0; i < AudioEq.BANDS; i++) loudest = Math.max(loudest, out[i]);
        if (loudest > 6f) {
            float shift = loudest - 6f;
            for (int i = 0; i < AudioEq.BANDS; i++) out[i] -= shift;
        }

        for (int i = 0; i < AudioEq.BANDS; i++) {
            float cap = MAX_BOOST_BY_BAND[Math.min(i, MAX_BOOST_BY_BAND.length - 1)];
            out[i] = Math.max(-AudioEq.MAX_DB, Math.min(cap, out[i]));
        }
        return out;
    }

    private static float[] resolveCurve(Config config, SessionInfo info) {
        String assigned = info == null || info.packageName == null
                ? null
                : config.appMap.get(info.packageName);
        if (assigned != null) {
            float[] saved = config.userPresets.get(assigned);
            if (saved != null) return saved;
            if (Presets.isBuiltIn(assigned)) return Presets.curve(Presets.indexOf(assigned));
        }
        if (config.userCurve != null) return config.userCurve;
        return Presets.curve(Presets.indexOf(config.preset));
    }

    private static void applyVolumeCompensation(float[] gains, int volumePercent) {
        if (volumePercent >= 60) return;
        float depth = (60 - volumePercent) / 60f;
        float amount = MAX_LOUDNESS_BOOST_DB * depth;
        for (int i = 0; i < gains.length && i < LOUDNESS_SHAPE.length; i++) {
            gains[i] += amount * LOUDNESS_SHAPE[i];
        }
    }

    private static void applyRouteTrim(float[] gains, SessionInfo info) {
        int type = info == null ? -1 : info.deviceType;
        switch (type) {
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case android.media.AudioDeviceInfo.TYPE_USB_HEADSET:
                gains[0] -= 3f;
                gains[1] -= 1f;
                break;
            case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                gains[0] -= 2f;
                break;
        }
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
            config.userPresets = parseUserPresets(Settings.Global.getString(resolver, K_USER_PRESETS));
            config.appMap = parseAppMap(Settings.Global.getString(resolver, K_APP_MAP));
            config.spread = clamp(Settings.Global.getInt(resolver, K_SPREAD, 0), 0, 80) / 10f;
            config.loudnessMb = clamp(Settings.Global.getInt(resolver, K_LOUDNESS, 0), 0, 500);
        } catch (Throwable error) {
            Log.w(TAG, "config read failed: " + AudioEq.describe(error));
            if (lastGood != null) return lastGood;
            config.enabled = false;
        }
        lastGood = config;
        return config;
    }

    static float[] parseCurve(String raw) {
        if (raw == null || raw.isEmpty() || raw.equals("null")) return null;
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

    private static Map<String, float[]> parseUserPresets(String raw) {
        Map<String, float[]> out = new HashMap<>();
        if (raw == null || raw.isEmpty() || "null".equals(raw)) return out;
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id");
                JSONArray curve = item.optJSONArray("curve");
                if (id == null || id.isEmpty() || curve == null) continue;
                if (curve.length() != AudioEq.BANDS) continue;
                float[] values = new float[AudioEq.BANDS];
                for (int b = 0; b < AudioEq.BANDS; b++) {
                    values[b] = (float) (curve.optDouble(b, 0) / 10.0);
                }
                out.put(id, values);
            }
        } catch (Throwable error) {
            Log.w(TAG, "user presets unreadable: " + AudioEq.describe(error));
        }
        return out;
    }

    private static Map<String, String> parseAppMap(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty() || "null".equals(raw)) return out;
        try {
            JSONObject object = new JSONObject(raw);
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = object.optString(key);
                if (key != null && !key.isEmpty() && value != null && !value.isEmpty()
                        && !"null".equals(value)) {
                    out.put(key, value);
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "app map unreadable: " + AudioEq.describe(error));
        }
        return out;
    }

    static final class Config {
        boolean enabled;
        boolean panic;
        String preset;
        int strength = 100;
        float[] userCurve;
        Map<String, float[]> userPresets = new HashMap<>();
        Map<String, String> appMap = new HashMap<>();
        /** Stereo widening, 0–8 dB, applied above 500 Hz. */
        float spread;
        /** LoudnessEnhancer target gain in millibels, 0–500 (0–5 dB). */
        int loudnessMb;
    }

    static final class SessionInfo {
        final int session;
        final int uid;
        final int deviceType;
        final String packageName;

        SessionInfo(int session, int uid, int deviceType, String packageName) {
            this.session = session;
            this.uid = uid;
            this.deviceType = deviceType;
            this.packageName = packageName;
        }
    }
}