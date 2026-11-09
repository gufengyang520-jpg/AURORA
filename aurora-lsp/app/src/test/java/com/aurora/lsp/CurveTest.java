package com.aurora.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the curve maths.
 *
 * <p>These three assertions cover the three steps that have already caused user-visible bugs: a
 * flat curve must stay flat, a peak must be fitted to the ceiling instead of overshooting, and a
 * quiet volume must lift the bottom band. Each one fails if its step is inverted or skipped.
 */
public class CurveTest {

    private static AuroraEngine.Config config(float[] curve, int strength) {
        AuroraEngine.Config c = new AuroraEngine.Config();
        c.enabled = true;
        c.userCurve = curve;
        c.strength = strength;
        return c;
    }

    /** uid -1 and device -1 mean "no app, unknown route", i.e. no trimming. */
    private static AuroraEngine.SessionInfo speaker() {
        return new AuroraEngine.SessionInfo(1, -1, -1, null);
    }

    @Test
    public void flatCurveStaysFlat() {
        float[] out = AuroraEngine.buildGains(
                config(new float[]{0, 0, 0, 0, 0, 0}, 100), speaker(), 100);
        for (float gain : out) {
            assertEquals(0f, gain, 0.01f);
        }
    }

    @Test
    public void peakIsCappedNotOvershot() {
        // 80 Hz asks for +10. The preamp shifts the whole curve down until its loudest band sits at
        // +6, so the result is +6, not +10. This used to assert +4 because the 80 Hz band had its
        // own 4 dB ceiling; that ceiling is 8 dB now, so the preamp is what does the work here.
        float[] out = AuroraEngine.buildGains(
                config(new float[]{10, 8, 2, -3, -9, -11}, 100), speaker(), 100);

        assertEquals("the preamp must pull the loudest band down to +6", 6f, out[0], 0.01f);
        for (float gain : out) {
            assertTrue("no band may sit above the preamp ceiling", gain <= 6.01f);
        }

        // Shifting the whole curve is what keeps the preset's shape: the gaps between bands are the
        // same as in the source curve. Anything that normalised per band would break this.
        assertEquals("80 Hz vs 500 Hz gap must survive the shift", -8f, out[2] - out[0], 0.01f);
        assertEquals("80 Hz vs 200 Hz gap must survive the shift", -2f, out[1] - out[0], 0.01f);

        assertEquals("the quietest band still lands on the floor limit", -12f, out[5], 0.01f);
    }

    @Test
    public void quietVolumeLiftsTheBottomBand() {
        float[] loud = AuroraEngine.buildGains(
                config(new float[]{0, 0, 0, 0, 0, 0}, 100), speaker(), 100);
        float[] quiet = AuroraEngine.buildGains(
                config(new float[]{0, 0, 0, 0, 0, 0}, 100), speaker(), 30);
        assertTrue("80 Hz must be lifted at low volume", quiet[0] > loud[0] + 1f);
        // The middle of the spectrum is deliberately left alone.
        assertEquals(loud[2], quiet[2], 0.01f);
    }
}
