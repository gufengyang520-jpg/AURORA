package com.aurora.lsp;

import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.os.Build;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs once when the module boots inside system_server and records what this device can actually do.
 *
 * <p>Every field is written to {@link Settings.Global} so the app can show a verdict and, when the
 * chain is not usable, ship the whole profile home for a real adaptation instead of guessing.
 * Never throws: any failure becomes a recorded stage.
 */
public final class AuroraProbe {

    /** 0 means every check passed; anything else is the number of the check that failed. */
    public static final String K_STAGE = "aurora_probe_stage";
    public static final String K_REASON = "aurora_probe_reason";
    public static final String K_MODEL = "aurora_device_model";
    public static final String K_SOC = "aurora_device_soc";
    public static final String K_SDK = "aurora_device_sdk";
    public static final String K_MIUI = "aurora_device_miui";
    public static final String K_EFFECTS = "aurora_device_effects";
    public static final String K_OUTPUTS = "aurora_device_outputs";
    public static final String K_ROUTE = "aurora_device_route";
    public static final String K_PROBED_AT = "aurora_probe_at";

    private static final int MAX_EFFECTS = 24;
    private static final int MAX_OUTPUTS = 12;
    private static final int MAX_VALUE_CHARS = 3500;

    private AuroraProbe() {
    }

    public static void run(Context context) {
        ContentResolver resolver = context.getContentResolver();

        put(resolver, AuroraEngine.R_ENGINE_VERSION, String.valueOf(selfVersionCode(context)));
        put(resolver, K_MODEL, Build.MODEL + "/" + Build.DEVICE);
        put(resolver, K_SOC, Build.SOC_MANUFACTURER + "/" + Build.SOC_MODEL);
        put(resolver, K_SDK, String.valueOf(Build.VERSION.SDK_INT));
        put(resolver, K_MIUI, prop("ro.mi.os.version.name") + "/" + prop("ro.mi.os.version.incremental"));

        int stage = 0;
        String reason = "";

        AudioManager manager = null;
        try {
            Object service = context.getSystemService(Context.AUDIO_SERVICE);
            if (service instanceof AudioManager) manager = (AudioManager) service;
        } catch (Throwable error) {
            reason = "AudioManager: " + describe(error);
        }
        if (manager == null) {
            fail(resolver, 1, reason.isEmpty() ? "AudioManager unavailable" : reason);
            return;
        }

        // 2. Does this build expose the playback-configuration API we use to find sessions?
        Method sessionId = null;
        Method playerState = null;
        try {
            Class<?> config = Class.forName("android.media.AudioPlaybackConfiguration");
            sessionId = config.getMethod("getSessionId");
            playerState = config.getMethod("getPlayerState");
            Class.forName("android.media.AudioManager").getMethod("getActivePlaybackConfigurations");
        } catch (Throwable error) {
            sessionId = null;
            playerState = null;
            reason = describe(error);
        }
        if (sessionId == null || playerState == null) {
            fail(resolver, 2, "playback config API: " + reason);
            return;
        }

        // 3. Can the equaliser we drive actually be constructed on this build?
        Class<?> band = null;
        try {
            Class<?> dp = Class.forName("android.media.audiofx.DynamicsProcessing");
            dp.getConstructor(int.class, int.class);
            band = Class.forName("android.media.audiofx.DynamicsProcessing$EqBand");
            band.getConstructor(boolean.class, float.class, float.class);
            dp.getMethod("setPreEqBandAllChannelsTo", int.class, band);
            dp.getMethod("setEnabled", boolean.class);
            dp.getMethod("getPreEqByChannelIndex", int.class);
        } catch (Throwable error) {
            band = null;
            reason = describe(error);
        }
        if (band == null) {
            fail(resolver, 3, "DynamicsProcessing: " + reason);
            return;
        }

        // 4. What effects are registered globally on this device?
        String effects = collectEffects();
        put(resolver, K_EFFECTS, effects);

        // 5. What can it actually play through right now?
        String outputs = collectOutputs(manager);
        put(resolver, K_OUTPUTS, outputs);

        // 6. Which effect is bound to the active output route?
        put(resolver, K_ROUTE, audioProps(manager));

        put(resolver, K_STAGE, "0");
        put(resolver, K_REASON, "ok");
        put(resolver, K_PROBED_AT, String.valueOf(System.currentTimeMillis()));
        AuroraXposed.logExternal("probe ok effects=" + count(effects));
    }

    private static String collectEffects() {
        List<String> items = new ArrayList<>();
        try {
            AudioEffect.Descriptor[] all = AudioEffect.queryEffects();
            if (all != null) {
                for (AudioEffect.Descriptor d : all) {
                    if (d == null || items.size() >= MAX_EFFECTS) continue;
                    String type = d.type == null ? "" : d.type.toString().substring(0, 8);
                    String uuid = d.uuid == null ? "" : d.uuid.toString().substring(0, 8);
                    items.add(safe(d.name) + "|" + type + "|" + uuid + "|" + safe(d.implementor));
                }
            }
        } catch (Throwable error) {
            items.add("error|" + describe(error));
        }
        String joined = join(items);
        return truncate(joined);
    }

    private static String collectOutputs(AudioManager manager) {
        List<String> items = new ArrayList<>();
        try {
            AudioDeviceInfo[] all = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            if (all != null) {
                for (AudioDeviceInfo d : all) {
                    if (d == null || items.size() >= MAX_OUTPUTS) continue;
                    items.add(d.getType() + "|" + safe(d.getProductName() == null
                            ? "" : d.getProductName().toString())
                            + "|" + d.getSampleRates().length + "|" + d.getChannelCounts().length);
                }
            }
        } catch (Throwable error) {
            items.add("error|" + describe(error));
        }
        return truncate(join(items));
    }

    /** Output parameters we can actually read back; the effect-to-device table is not exposed. */
    private static String audioProps(AudioManager manager) {
        try {
            StringBuilder out = new StringBuilder();
            out.append("rate=").append(audioProp(manager, AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE));
            out.append(";frames=").append(audioProp(manager, AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER));
            return truncate(out.toString());
        } catch (Throwable error) {
            return "error|" + describe(error);
        }
    }

    private static String audioProp(AudioManager manager, String key) {
        try {
            String value = manager.getProperty(key);
            return value == null ? "?" : value;
        } catch (Throwable ignored) {
            return "?";
        }
    }

    private static void fail(ContentResolver resolver, int stage, String reason) {
        put(resolver, K_STAGE, String.valueOf(stage));
        put(resolver, K_REASON, truncate(reason));
        put(resolver, K_PROBED_AT, String.valueOf(System.currentTimeMillis()));
        AuroraXposed.logExternal("probe failed at stage " + stage + ": " + reason);
    }

    private static void put(ContentResolver resolver, String key, String value) {
        try {
            Settings.Global.putString(resolver, key, value == null ? "" : value);
        } catch (Throwable ignored) {
        }
    }

    private static String prop(String name) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            Object value = get.invoke(null, name);
            return value == null ? "" : value.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** The versionCode of the code running here, so the app can spot a stale engine. */
    private static int selfVersionCode(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo("com.aurora.audio.lsp", 0);
            return (int) (Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode()
                    : info.versionCode);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int count(String joined) {
        if (joined.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < joined.length(); i++) if (joined.charAt(i) == ';') n++;
        return n;
    }

    private static String join(List<String> items) {
        StringBuilder builder = new StringBuilder();
        for (String item : items) {
            if (builder.length() > 0) builder.append(';');
            builder.append(item);
        }
        return builder.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('|', '/').replace(';', ',');
    }

    private static String truncate(String value) {
        return value.length() <= MAX_VALUE_CHARS ? value : value.substring(0, MAX_VALUE_CHARS);
    }

    private static String describe(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String message = cause.getMessage();
        return message == null || message.isEmpty()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
    }
}