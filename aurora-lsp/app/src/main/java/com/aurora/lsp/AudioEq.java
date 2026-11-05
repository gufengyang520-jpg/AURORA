package com.aurora.lsp;

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

    /** Band centres in Hz, chosen for a phone speaker (nothing useful below ~200 Hz). */
    public static final float[] BAND_HZ = {250f, 500f, 1000f, 2000f, 4000f, 6300f};
    public static final int BANDS = BAND_HZ.length;
    public static final float MAX_DB = 12f;

    private static Constructor<?> dpCtor;
    private static Constructor<?> bandCtor;
    private static Method setBandAll;
    private static Method setEnabled;
    private static Method hasControl;
    private static Method release;
    private static String resolveError;

    private Object effect;
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
            setEnabled = dp.getMethod("setEnabled", boolean.class);
            hasControl = dp.getMethod("hasControl");
            release = dp.getMethod("release");
            return true;
        } catch (Throwable error) {
            resolveError = describe(error);
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
            lastError = "";
            if (!Boolean.TRUE.equals(hasControl.invoke(effect))) {
                lastError = "no control over session " + session;
                return false;
            }
            return bandCount > 0;
        } catch (Throwable error) {
            lastError = describe(error);
            effect = null;
            return false;
        }
    }

    public int bandCount() {
        return bandCount;
    }

    /** gains in dB, mapped onto the first {@link #BANDS} bands. */
    public synchronized boolean apply(float[] gains, boolean enabledStage) {
        if (effect == null) return false;
        try {
            int used = Math.min(bandCount, Math.min(BANDS, gains == null ? 0 : gains.length));
            for (int i = 0; i < used; i++) {
                float gain = Math.max(-MAX_DB, Math.min(MAX_DB, gains[i]));
                setBandAll.invoke(effect, i, bandCtor.newInstance(true, BAND_HZ[i], gain));
            }
            // Surplus bands are left untouched: DynamicsProcessing reports them flat by default,
            // and parking them all on 6.3 kHz stacked filters we never meant to add.
            setEnabled.invoke(effect, enabledStage);
            return true;
        } catch (Throwable error) {
            lastError = describe(error);
            return false;
        }
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
        if (effect == null) return;
        try {
            release.invoke(effect);
        } catch (Throwable ignored) {
        }
        effect = null;
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

    static String describe(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String message = cause.getMessage();
        return message == null || message.isEmpty()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
    }
}