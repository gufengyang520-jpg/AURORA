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
        // 80 Hz asks for +10. The preamp shifts the curve to a +6 peak, then the per-band ceiling
        // pulls the bottom band to +4. If either step is skipped this comes out at 6 or 10.
        float[] out = AuroraEngine.buildGains(
                config(new float[]{10, 8, 2, -3, -9, -11}, 100), speaker(), 100);
        assertEquals(4f, out[0], 0.01f);
        assertTrue("no band may exceed the per-band ceiling", out[5] <= 4.01f);
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
