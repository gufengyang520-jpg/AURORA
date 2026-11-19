package com.aurora.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the latency-sensitive usage rule and the limiter headroom report.
 *
 * <p>Games are the one stream where the ten-band chain's extra 10 ms of latency is felt rather than
 * heard, so precise mode quietly drops to six bands for them per session. The limiter headroom
 * string is the honest substitute for a gain-reduction meter AOSP never exposed: it reports how far
 * below the limiter threshold the curve's peak sits, i.e. the source level that would trip it.
 */
public class LatencyAndLimiterTest {

    private static AuroraEngine.Config config() {
        AuroraEngine.Config c = new AuroraEngine.Config();
        c.enabled = true;
        c.preset = "flat";
        c.strength = 100;
        return c;
    }

    @Test
    public void onlyGamesAreLatencySensitive() {
        assertTrue("a game must drop out of precise mode",
                AuroraEngine.isLatencySensitive(android.media.AudioAttributes.USAGE_GAME));
        assertTrue("media keeps precise mode",
                !AuroraEngine.isLatencySensitive(android.media.AudioAttributes.USAGE_MEDIA));
        assertTrue("a call is handled by isCallUsage, not here",
                !AuroraEngine.isLatencySensitive(
                        android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION));
        assertTrue("an unknown usage keeps precise mode",
                !AuroraEngine.isLatencySensitive(-1));
    }

    @Test
    public void headroomIsThresholdMinusPeak() {
        AuroraEngine.Config c = config();
        c.limiter = new AudioEq.LimiterConfig(-6f, 4f, 5f, 80f);
        // A +4 dB boost against a -6 dBFS threshold trips the limiter at a -10 dBFS source level:
        // trip = threshold - peak. This is what the merge ceiling lands on for a loud curve.
        assertEquals("trip is the source level that reaches the limiter",
                "thr=-6.0 peak=+4.0 trip=-10.0dBFS",
                AuroraEngine.limiterHeadroom(c, new float[]{4, 0, 0, 0, 0, 0}));
    }

    @Test
    public void headroomTracksTheLoudestGain() {
        AuroraEngine.Config c = config();
        c.limiter = new AudioEq.LimiterConfig(-6f, 4f, 5f, 80f);
        // A curve whose loudest band is +2 still reports that +2, not the merge ceiling.
        assertEquals("the reported peak is the curve's actual peak, not the ceiling",
                "thr=-6.0 peak=+2.0 trip=-8.0dBFS",
                AuroraEngine.limiterHeadroom(c, new float[]{-3, -3, 2, 1, 0, -1}));
    }

    @Test
    public void aFlatCurveReportsZeroPeakAndTripsAtTheThreshold() {
        AuroraEngine.Config c = config();
        c.limiter = new AudioEq.LimiterConfig(-6f, 4f, 5f, 80f);
        // No boost means the signal reaches the limiter untouched, so it trips exactly at -6 dBFS.
        assertEquals("a flat curve has no headroom to give up",
                "thr=-6.0 peak=+0.0 trip=-6.0dBFS",
                AuroraEngine.limiterHeadroom(c, new float[]{0, 0, 0, 0, 0, 0}));
    }

    @Test
    public void headroomUsesTheDefaultLimiterWhenUnset() {
        AuroraEngine.Config c = config();
        c.limiter = null;
        // DEFAULT_LIMITER is -6 dBFS; the curve's own +2 peak is what counts against it.
        assertEquals("a null limiter falls back to the compiled-in default",
                "thr=-6.0 peak=+2.0 trip=-8.0dBFS",
                AuroraEngine.limiterHeadroom(c, new float[]{2, 0, 0, 0, 0, 0}));
    }

    @Test
    public void headroomReflectsAPackThatMovesTheThreshold() {
        AuroraEngine.Config c = config();
        c.limiter = new AudioEq.LimiterConfig(-3f, 2f, 5f, 80f);
        // A higher threshold leaves less headroom: peak +4 against -3 = 7 dB trip.
        assertEquals("a raised threshold shrinks the trip level",
                "thr=-3.0 peak=+4.0 trip=-7.0dBFS",
                AuroraEngine.limiterHeadroom(c, new float[]{4, 0, 0, 0, 0, 0}));
    }
}
