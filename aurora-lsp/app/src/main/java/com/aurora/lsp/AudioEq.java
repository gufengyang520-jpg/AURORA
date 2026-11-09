package com.aurora.lsp;

import android.os.Handler;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Multi-band equalizer bound to one playback session.
 *
 * <p>Why not the Dolby DAP: measured on this firmware, the {@code dap_hw} effect accepts vendor
 * parameters but does not apply them to audio that goes through the spatializer output thread
 * (which is what streaming players use). {@link android.media.audiofx.DynamicsProcessing} is a
 * software stage that really processes the PCM in the mix chain, and it works both on the normal
 * path and on the spatializer path.
 *
 * <p>The pre-EQ stage is used so the six bands can be placed wherever the speaker can actually
 * reproduce sound.
 */
public final class AudioEq {

    /** Band centres in Hz: low weight, warmth, boxiness, voice, clarity, air. */
    public static final float[] BAND_HZ = {80f, 200f, 500f, 1500f, 4000f, 10000f};
    public static final int BANDS = BAND_HZ.length;
    public static final float MAX_DB = 12f;

    private static Constructor<?> dpCtor;
    private static Constructor<?> bandCtor;
    private static Constructor<?> limiterCtor;
    private static Constructor<?> mbcBandCtor;
    private static Method setBandAll;
    private static Method setBandByChannel;
    private static Method setMbcBandAll;
    private static Method setLimiterAll;
    private static Method setEnabled;
    private static Method hasControl;
    private static Method release;
    private static String resolveError;

    // LoudnessEnhancer
    private static Constructor<?> leCtor;
    private static Method leSetTargetGain;
    private static Method leSetEnabled;
    private static Method leRelease;

    private Object effect;
    private Object loudnessEnhancer;
    private int bandCount;
    private String lastError = "";
    private String signature = "";
    private boolean applied;

    private static synchronized boolean resolve() {
        if (dpCtor != null) return true;
        if (resolveError != null) return false;
        try {
            Class<?> dp = Class.forName("android.media.audiofx.DynamicsProcessing");
            Class<?> band = Class.forName("android.media.audiofx.DynamicsProcessing$EqBand");
            dpCtor = dp.getConstructor(int.class, int.class);
            bandCtor = band.getConstructor(boolean.class, float.class, float.class);
            setBandAll = dp.getMethod("setPreEqBandAllChannelsTo", int.class, band);
            setBandByChannel = dp.getMethod("setPreEqBandByChannelIndex", int.class, int.class, band);
            setEnabled = dp.getMethod("setEnabled", boolean.class);
            hasControl = dp.getMethod("hasControl");
            release = dp.getMethod("release");
            try {
                Class<?> limiter = Class.forName("android.media.audiofx.DynamicsProcessing$Limiter");
            limiterCtor = limiter.getConstructor(boolean.class, int.class, float.class,
                    float.class, float.class, float.class, float.class);
                setLimiterAll = dp.getMethod("setLimiterAllChannelsTo", limiter);
            } catch (Throwable ignored) {
            }
            try {
                Class<?> mbcBand = Class.forName("android.media.audiofx.DynamicsProcessing$MbcBand");
            mbcBandCtor = mbcBand.getConstructor(boolean.class, float.class, float.class,
                    float.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, float.class);
                setMbcBandAll = dp.getMethod("setMbcBandAllChannelsTo", int.class, mbcBand);
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable error) {
            resolveError = describe(error);
            return false;
        }
    }

    private static synchronized boolean resolveLoudness() {
        if (leCtor != null) return true;
        try {
            Class<?> le = Class.forName("android.media.audiofx.LoudnessEnhancer");
            leCtor = le.getConstructor(int.class, int.class);
            leSetTargetGain = le.getMethod("setTargetGain", int.class);
            leSetEnabled = le.getMethod("setEnabled", boolean.class);
            leRelease = le.getMethod("release");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public synchronized boolean open(int session) {
        close();
        if (!resolve()) {
            lastError = resolveError;
            return false;
        }
        if (session <= 0) {
            lastError = "invalid session " + session;
            return false;
        }
        try {
            effect = dpCtor.newInstance(0, session);
            Object preEq = effect.getClass()
                    .getMethod("getPreEqByChannelIndex", int.class).invoke(effect, 0);
            bandCount = (Integer) preEq.getClass().getMethod("getBandCount").invoke(preEq);
            if (!Boolean.TRUE.equals(hasControl.invoke(effect))) {
                lastError = "no control over session " + session;
                return false;
            }
            enableLimiter();
            enableBaseCompression();
            openLoudness(session);
            return bandCount > 0;
        } catch (Throwable error) {
            lastError = describe(error);
            effect = null;
            return false;
        }
    }

    private void openLoudness(int session) {
        if (!resolveLoudness()) return;
        try {
            loudnessEnhancer = leCtor.newInstance(0, session);
        } catch (Throwable ignored) {
            loudnessEnhancer = null;
        }
    }

    public int bandCount() {
        return bandCount;
    }

    /**
     * Writes the curve, optionally with per-channel spread for stereo widening.
     *
     * <p>When {@code spread} is non-zero and per-channel access works, the left channel gets
     * {@code gains + spread} and the right gets {@code gains - spread} above 500 Hz. The brain
     * reads that inter-channel difference as width. Low frequencies stay centred: separating
     * bass makes it thin rather than wide.
     */
    public synchronized boolean apply(float[] gains, float[] spread, int loudnessMillibels,
                                      boolean enabledStage) {
        if (effect == null) return false;
        try {
            int used = Math.min(bandCount, Math.min(BANDS, gains == null ? 0 : gains.length));
            boolean perChannel = setBandByChannel != null;
            for (int i = 0; i < used; i++) {
                float gain = Math.max(-MAX_DB, Math.min(MAX_DB, gains[i]));
                // Surplus bands are left untouched: DynamicsProcessing reports them flat by
                // default, and parking them all on the top band stacked filters we never meant to
                // add.
                if (perChannel && widened(spread, i)) {
                    float delta = Math.max(-8f, Math.min(8f, spread[i]));
                    float[] pair = channelGains(gain, delta);
                    // Channel 0 = left, channel 1 = right
                    setBandByChannel.invoke(effect, 0, i,
                            bandCtor.newInstance(true, BAND_HZ[i], pair[0]));
                    try {
                        setBandByChannel.invoke(effect, 1, i,
                                bandCtor.newInstance(true, BAND_HZ[i], pair[1]));
                    } catch (Throwable mono) {
                        // The session has no second channel. Keep the band by putting it on all
                        // channels: losing the widening is better than losing the curve, and
                        // losing the whole write is worse than both.
                        setBandAll.invoke(effect, i, bandCtor.newInstance(true, BAND_HZ[i], gain));
                    }
                } else {
                    setBandAll.invoke(effect, i, bandCtor.newInstance(true, BAND_HZ[i], gain));
                }
            }
            setEnabled.invoke(effect, enabledStage);

            // LoudnessEnhancer: boosts perceived loudness without raising peaks.
            if (loudnessEnhancer != null && leSetTargetGain != null) {
                try {
                    leSetTargetGain.invoke(loudnessEnhancer, loudnessMillibels);
                    leSetEnabled.invoke(loudnessEnhancer, true);
                } catch (Throwable ignored) {
                }
            }

            return true;
        } catch (Throwable error) {
            lastError = describe(error);
            return false;
        }
    }

    private static float clamp(float v) {
        return Math.max(-MAX_DB, Math.min(MAX_DB, v));
    }

    /** Below this the two channels get the same gain: separating bass makes it thin, not wide. */
    static final float WIDEN_FROM_HZ = 500f;

    /**
     * Whether this band is written per channel.
     *
     * <p>Split out of the reflection call so the rule itself can be tested on a plain JVM - the
     * effect object cannot be built there, but the decision it depends on can.
     */
    static boolean widened(float[] spread, int bandIndex) {
        return spread != null
                && bandIndex >= 0
                && bandIndex < spread.length
                && bandIndex < BAND_HZ.length
                && BAND_HZ[bandIndex] >= WIDEN_FROM_HZ;
    }

    /** Left and right gains for one band, clamped the same way the single-channel path is. */
    static float[] channelGains(float gain, float delta) {
        return new float[]{clamp(gain + delta), clamp(gain - delta)};
    }

    public synchronized boolean isApplied() {
        return applied;
    }

    public synchronized void markApplied(String value) {
        applied = true;
        signature = value;
    }

    public synchronized String signature() {
        return signature;
    }

    public synchronized void close() {
        if (effect != null) {
            try { release.invoke(effect); } catch (Throwable ignored) {}
        }
        if (loudnessEnhancer != null) {
            try { leRelease.invoke(loudnessEnhancer); } catch (Throwable ignored) {}
        }
        effect = null;
        loudnessEnhancer = null;
        bandCount = 0;
        applied = false;
        signature = "";
    }

    public synchronized String lastError() {
        return lastError;
    }

    public synchronized boolean isOpen() {
        if (effect == null) return false;
        try {
            return Boolean.TRUE.equals(hasControl.invoke(effect));
        } catch (Throwable error) {
            return false;
        }
    }

    private void enableLimiter() {
        if (effect == null || limiterCtor == null || setLimiterAll == null) return;
        try {
            Object limiter = limiterCtor.newInstance(true, 0, 0.005f, 0.06f, 10f, -3f, 0f);
            setLimiterAll.invoke(effect, limiter);
        } catch (Throwable ignored) {
        }
    }

    private void enableBaseCompression() {
        if (effect == null || mbcBandCtor == null || setMbcBandAll == null) return;
        try {
            Object band = mbcBandCtor.newInstance(
                    true, 120f, 0.020f, 0.250f, 2.0f, -24f, 6f, -60f, 1.0f, 0f, 0f);
            setMbcBandAll.invoke(effect, 0, band);
        } catch (Throwable ignored) {
        }
    }

    static String describe(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String message = cause.getMessage();
        return message == null || message.isEmpty()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
    }
}