package com.aurora.lsp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

import org.junit.Test;

/** The opening fade: a scaled copy of the target curve, never a curve of another shape. */
public class RampTest {

    private static final float[] CURVE = {-4f, -2f, 0f, 3f, 6f, 2f};

    @Test
    public void aFractionOfZeroIsSilence() {
        assertArrayEquals(new float[]{0f, 0f, 0f, 0f, 0f, 0f}, AuroraEngine.fadeStep(CURVE, 0f), 1e-6f);
    }

    @Test
    public void aFractionOfOneIsTheCurveItself() {
        assertArrayEquals(CURVE, AuroraEngine.fadeStep(CURVE, 1f), 1e-6f);
    }

    @Test
    public void everyFrameKeepsTheShapeOfTheTarget() {
        float[] half = AuroraEngine.fadeStep(CURVE, 0.5f);
        for (int i = 0; i < CURVE.length; i++) {
            assertEquals(CURVE[i] * 0.5f, half[i], 1e-6f);
        }
    }

    @Test
    public void cutsAreRampedFromTheFlatSideToo() {
        // A fade that only handles boosts would pop on every downward step as well: -4 dB
        // scaled by a half is -2 dB, not 0 dB.
        assertEquals(-2f, AuroraEngine.fadeStep(CURVE, 0.5f)[0], 1e-6f);
    }

    @Test
    public void theResultIsANewArrayTheCallerCanKeep() {
        float[] frame = AuroraEngine.fadeStep(CURVE, 0.25f);
        assertNotSame(CURVE, frame);
        assertEquals(CURVE.length, frame.length);
        assertEquals(6f * 0.25f, frame[4], 1e-6f);
    }
}
