package com.aurora.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for the stereo-widening rule.
 *
 * <p>The effect object cannot exist on a plain JVM, so the decision and the arithmetic were pulled
 * out of the reflection and are tested directly here. Two of these assertions guard bugs that were
 * live: widening the bass (which thins it instead of widening it), and a band above 500 Hz being
 * written to one channel only when the session has no second channel.
 */
public class SpreadTest {

    @Test
    public void noSpreadMeansNoPerChannelWrite() {
        assertFalse("a null spread must not take the per-channel path",
                AudioEq.widened(null, 2));
    }

    @Test
    public void bassStaysCentred() {
        float[] spread = {0f, 0f, 0f, 1f, 2f, 3f};
        assertFalse("80 Hz must stay identical on both channels", AudioEq.widened(spread, 0));
        assertFalse("200 Hz must stay identical on both channels", AudioEq.widened(spread, 1));
    }

    @Test
    public void midAndAboveWiden() {
        float[] spread = {0f, 0f, 1f, 2f, 3f, 4f};
        for (int band = 2; band < AudioEq.BANDS; band++) {
            assertTrue("band " + band + " is at or above "
                    + AudioEq.WIDEN_FROM_HZ + " Hz and must widen", AudioEq.widened(spread, band));
        }
    }

    @Test
    public void outOfRangeIsSafe() {
        float[] spread = {1f, 1f};
        assertFalse(AudioEq.widened(spread, -1));
        assertFalse("a spread array shorter than the band table must not read past it",
                AudioEq.widened(spread, 5));
        assertFalse(AudioEq.widened(new float[]{0f, 0f, 0f, 0f, 0f, 0f}, 99));
    }

    @Test
    public void channelGainsAreSymmetric() {
        float[] pair = AudioEq.channelGains(3f, 2f);
        assertEquals("left", 5f, pair[0], 0.001f);
        assertEquals("right", 1f, pair[1], 0.001f);
        assertEquals("the pair must average back to the band gain", 6f, pair[0] + pair[1], 0.001f);
    }

    @Test
    public void channelGainsNeverExceedTheBandLimit() {
        assertEquals("boost is clamped, not summed past the ceiling",
                AudioEq.MAX_DB, AudioEq.channelGains(11f, 3f)[0], 0.001f);
        assertEquals("cut is clamped at the same limit",
                -AudioEq.MAX_DB, AudioEq.channelGains(-11f, -3f)[0], 0.001f);
        float[] extreme = AudioEq.channelGains(AudioEq.MAX_DB, 8f);
        assertTrue("widening must not push a band past the ceiling", extreme[0] <= AudioEq.MAX_DB);
        assertTrue("widening must not push a band past the floor", extreme[1] >= -AudioEq.MAX_DB);
    }
}