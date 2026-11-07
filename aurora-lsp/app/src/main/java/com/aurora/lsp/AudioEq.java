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

    /**
     * Band centres in Hz: low weight, warmth, boxiness, voice, clarity, air.
     *
     * <p>The previous layout started at 250 Hz. That made real bass unreachable - anything below
     * 250 Hz simply had no band - so every "more bass" curve had to pile its gain onto 250 and
     * 500 Hz, which sit exactly in the 150-600 Hz region that reads as muddy. Moving the two
     * lowest bands down to 80 and 200 Hz means the weight can be added where it is felt while the
     * muddy range stays flat or cut.
     */
    public static final float[] BAND_HZ = {80f, 200f, 500f, 1500f, 4000f, 10000f};
    public static final int BANDS = BAND_HZ.length;
    public static final float MAX_DB = 12f;

    private static Constructor<?> dpCtor;
    private static Constructor<?> bandCtor;
    private static Constructor<?> limiterCtor;
    private static Constructor<?> mbcBandCtor;
    private static Method setBandAll;
    private static Method setMbcBandAll;
    private static Method setLimiterAll;
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
            try {
                Class<?> limiter = Class.forName("android.media.audiofx.DynamicsProcessing$Limiter");
                limiterCtor = limiter.getConstructor(boolean.class, int.class, float.class,
                        float.class, float.class, float.class, float.class);
                setLimiterAll = dp.getMethod("setLimiterAllChannelsTo", limiter);
            } catch (Throwable ignored) {
                // The limiter is an extra safety net. If this build exposes a different shape,
                // the equalizer itself must still work.
                limiterCtor = null;
                setLimiterAll = null;
            }
            try {
                Class<?> mbcBand = Class.forName("android.media.audiofx.DynamicsProcessing$MbcBand");
                mbcBandCtor = mbcBand.getConstructor(boolean.class, float.class, float.class,
                        float.class, float.class, float.class, float.class, float.class,
                        float.class, float.class, float.class);
                setMbcBandAll = dp.getMethod("setMbcBandAllChannelsTo", int.class, mbcBand);
            } catch (Throwable ignored) {
                // Multiband compression is a refinement; losing it must not cost the EQ.
                mbcBandCtor = null;
                setMbcBandAll = null;
            }
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
            enableLimiter();
            enableBaseCompression();
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

    /**
     * Keeps the summed output below full scale.
     *
     * <p>A boosted shelf on an already loud track clips at the DAC, and clipping is heard as the
     * crackle and harshness people report with the spatializer on - that path adds its own gain
     * before the audio reaches us. The threshold sits just under 0 dBFS with a fast attack, so the
     * limiter only touches peaks that would have clipped and leaves the rest of the signal alone.
     *
     * <p>Best effort: a build without this API simply runs without it.
     */
    private void enableLimiter() {
        if (effect == null || limiterCtor == null || setLimiterAll == null) return;
        try {
            // -3 dBFS rather than -1: the extra 2 dB of headroom is what stops a bass-heavy
            // preset from clipping the speaker on loud, low-bitrate material.
            Object limiter = limiterCtor.newInstance(true, 0, 0.005f, 0.06f, 10f, -3f, 0f);
            setLimiterAll.invoke(effect, limiter);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Light compression on the lowest band.
     *
     * <p>This is the part an equalizer cannot do. Boosting 80 Hz makes bass audible but it also
     * makes loud hits swell past everything else, which on a phone speaker reads as boom rather
     * than punch. A gentle ratio with a slow release holds those peaks down so the bass can be
     * lifted without the whole mix losing clarity.
     *
     * <p>Only band 0 is touched; the rest of the spectrum is left uncompressed on purpose.
     */
    private void enableBaseCompression() {
        if (effect == null || mbcBandCtor == null || setMbcBandAll == null) return;
        try {
            Object band = mbcBandCtor.newInstance(
                    true,        // in use
                    120f,        // covers everything below this cutoff
                    0.020f,      // attack, slow enough to keep the initial transient
                    0.250f,      // release, fast enough to recover between hits
                    2.0f,        // ratio, gentle: only the loudest hits move
                    -24f,        // threshold
                    6f,          // knee
                    -60f,        // noise gate below this
                    1.0f,        // no downward expansion
                    0f,          // no pre gain
                    0f);         // no post gain
            setMbcBandAll.invoke(effect, 0, band);
        } catch (Throwable ignored) {
        }
    }

    /** gains in dB, mapped onto the first {@link #BANDS} bands. */
    public synchronized boolean apply(float[] gains, boolean enabledStage) {
        if (effect == null) return false;
        try {
            int used = Math.min(bandCount, Math.min(BANDS, gains == null ? 0 : gains.length));
            for (int i = 0; i < used; i++) {
                float gain = Math.max(-MAX_DB, Math.min(MAX_DB, gains[i]));
                // Catch per band, not per call. A device that rejects one particular frequency
                // reports "bad parameter value" with no hint which band caused it; recording the
                // index, centre and value turns that into something an adaptation can act on.
                try {
                    setBandAll.invoke(effect, i, bandCtor.newInstance(true, BAND_HZ[i], gain));
                } catch (Throwable error) {
                    lastError = "band " + i + " @ " + Math.round(BAND_HZ[i]) + " Hz, gain "
                            + String.format(java.util.Locale.US, "%.1f", gain) + " dB: "
                            + describe(error);
                    return false;
                }
            }
            // Surplus bands are left untouched: DynamicsProcessing reports them flat by default,
            // and parking them all on 6.3 kHz stacked filters we never meant to add.
            try {
                setEnabled.invoke(effect, enabledStage);
            } catch (Throwable error) {
                lastError = "setEnabled(" + enabledStage + "): " + describe(error);
                return false;
            }
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