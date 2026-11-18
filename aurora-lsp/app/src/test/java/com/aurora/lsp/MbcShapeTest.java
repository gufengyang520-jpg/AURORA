package com.aurora.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The base compression table.
 *
 * <p>AOSP leaves every MbcBand at ratio 1, which is no compression. Writing one band and leaving
 * five alone is therefore not "light compression everywhere", it is "a compressor on the bass and
 * nothing above it" - and the bands that were left alone still carry AOSP defaults that nothing in
 * this app can see. These assertions keep the whole table explicit.
 */
public class MbcShapeTest {

    @Test
    public void everyBandIsDescribed() {
        assertEquals(AudioEq.MBC_BANDS, AudioEq.MBC_CUTOFF_HZ.length);
        assertEquals(AudioEq.MBC_BANDS, AudioEq.MBC_RATIO.length);
        assertEquals(AudioEq.MBC_BANDS, AudioEq.MBC_THRESHOLD_DB.length);
        assertEquals(AudioEq.MBC_BANDS, AudioEq.MBC_ATTACK_MS.length);
        assertEquals(AudioEq.MBC_BANDS, AudioEq.MBC_RELEASE_MS.length);
    }

    @Test
    public void cutoffsRiseStrictly() {
        for (int i = 1; i < AudioEq.MBC_CUTOFF_HZ.length; i++) {
            assertTrue("band " + i, AudioEq.MBC_CUTOFF_HZ[i] > AudioEq.MBC_CUTOFF_HZ[i - 1]);
        }
    }

    @Test
    public void onlyTheBassIsCompressed() {
        assertTrue(AudioEq.MBC_RATIO[0] > 1f);
        assertTrue(AudioEq.MBC_RATIO[1] > 1f);
        for (int i = 2; i < AudioEq.MBC_RATIO.length; i++) {
            assertEquals("band " + i, 1f, AudioEq.MBC_RATIO[i], 1e-6f);
        }
    }

    @Test
    public void theBassBandsUseRealTimeConstants() {
        // Values near zero are seconds in a millisecond field: that was the bug this table exists
        // to keep out. A compressor needs an attack slow enough to keep the transient.
        for (int i = 0; i < 2; i++) {
            assertTrue("band " + i + " attack", AudioEq.MBC_ATTACK_MS[i] >= 10f);
            assertTrue("band " + i + " release", AudioEq.MBC_RELEASE_MS[i] >= 100f);
        }
    }

    @Test
    public void releaseIsSlowerThanAttackEverywhere() {
        for (int i = 0; i < AudioEq.MBC_BANDS; i++) {
            assertTrue("band " + i, AudioEq.MBC_RELEASE_MS[i] > AudioEq.MBC_ATTACK_MS[i]);
        }
    }

    @Test
    public void theTopBandStopsBelowTheUltrasonic() {
        // 16 kHz rather than 20 kHz: above it there is nothing this phone can reproduce, and a
        // band that never triggers only adds latency to the lookup.
        assertEquals(16000f, AudioEq.MBC_CUTOFF_HZ[AudioEq.MBC_BANDS - 1], 1e-3f);
    }
}
