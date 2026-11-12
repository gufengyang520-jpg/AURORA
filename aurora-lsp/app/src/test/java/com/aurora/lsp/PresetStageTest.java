package com.aurora.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for what a preset brings beyond its curve: its own stereo width and its own loudness lift.
 *
 * <p>These used to be global: every preset shared one spread slider and one LoudnessEnhancer
 * target, so switching from 影院 to 夜间 changed the curve and nothing else. The presets now carry
 * a stage setting of their own and the sliders sit on top of it, which is what these assert - both
 * that the preset contributes, and that the contribution disappears the moment the curve stops
 * being that preset.
 */
public class PresetStageTest {

    private static AuroraEngine.Config config(String preset) {
        AuroraEngine.Config c = new AuroraEngine.Config();
        c.enabled = true;
        c.preset = preset;
        c.strength = 100;
        return c;
    }

    private static AuroraEngine.SessionInfo speaker() {
        return new AuroraEngine.SessionInfo(1, -1, -1, null);
    }

    @Test
    public void aPresetBringsItsOwnWidthWithTheSliderAtZero() {
        AuroraEngine.Config flat = config("flat");
        AuroraEngine.Config cinema = config("cinema");

        assertNull("flat asks for no widening, so nothing is written",
                AuroraEngine.buildSpread(flat, speaker()));
        float[] out = AuroraEngine.buildSpread(cinema, speaker());
        assertTrue("cinema must widen without the slider being touched", out != null);
        assertEquals("the top band carries the preset's own amount",
                Presets.spreadOf(Presets.indexOf("cinema")), out[5], 0.01f);
    }

    @Test
    public void theSliderAddsToThePresetRatherThanReplacingIt() {
        AuroraEngine.Config c = config("cinema");
        float fromPreset = AuroraEngine.totalSpread(c, speaker());

        c.spread = 2f;
        assertEquals("the slider is an offset, not an override",
                fromPreset + 2f, AuroraEngine.totalSpread(c, speaker()), 0.01f);
    }

    @Test
    public void theTotalIsCappedAtTheSliderRange() {
        AuroraEngine.Config c = config("cinema");
        c.spread = 8f;
        assertEquals("preset plus slider may not exceed the slider's own maximum",
                AuroraEngine.MAX_SPREAD_DB, AuroraEngine.totalSpread(c, speaker()), 0.01f);
    }

    @Test
    public void aPresetBringsItsOwnLoudnessLift() {
        AuroraEngine.Config night = config("night");
        assertEquals("night is deliberately lifted",
                Math.round(Presets.loudnessOf(Presets.indexOf("night")) * 100f),
                AuroraEngine.totalLoudnessMb(night, speaker()));

        AuroraEngine.Config flat = config("flat");
        assertEquals("flat asks for no lift", 0, AuroraEngine.totalLoudnessMb(flat, speaker()));
    }

    @Test
    public void theLoudnessCapHoldsWhenTheSliderIsAlreadyUp() {
        AuroraEngine.Config c = config("night");
        c.loudnessMb = AuroraEngine.MAX_LOUDNESS_MB;
        assertEquals("the preset may not push past the cap the pack validated",
                AuroraEngine.MAX_LOUDNESS_MB, AuroraEngine.totalLoudnessMb(c, speaker()));
    }

    @Test
    public void aHandDrawnCurveDropsThePresetExtras() {
        AuroraEngine.Config c = config("cinema");
        c.userCurve = new float[]{0, 0, 0, 0, 0, 0};

        assertEquals("a custom curve belongs to no preset, so it inherits nothing",
                0f, AuroraEngine.totalSpread(c, speaker()), 0.001f);
        assertEquals("and no lift either", 0, AuroraEngine.totalLoudnessMb(c, speaker()));
        assertEquals("which also means no spread write at all", -1,
                AuroraEngine.curvePresetIndex(c, speaker()));
    }

    @Test
    public void anAppAssignmentAlsoSwitchesTheStageSettings() {
        AuroraEngine.Config c = config("flat");
        c.appMap.put("com.example.player", "cinema");

        AuroraEngine.SessionInfo assigned =
                new AuroraEngine.SessionInfo(1, 10001, -1, "com.example.player");
        assertEquals("the assigned preset's width follows the assignment",
                Presets.spreadOf(Presets.indexOf("cinema")),
                AuroraEngine.totalSpread(c, assigned), 0.01f);
        assertEquals("while a session without the assignment keeps the global preset",
                0f, AuroraEngine.totalSpread(c, speaker()), 0.001f);
    }

    @Test
    public void everyPresetIsAtLeastTwoAndAHalfDecibelsFromItsNearestNeighbour() {
        // The first curve table differed only in the low shelf, and the +6 dB peak fit then pulled
        // all nine shapes onto each other: two names could not be told apart by ear. This is the
        // regression guard for that - not "the curves are pretty", but "no two are the same".
        int count = Presets.KEYS.length;
        float closest = Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                float[] a = AuroraEngine.buildGains(
                        config(Presets.KEYS[i]), speaker(), 100);
                float[] b = AuroraEngine.buildGains(
                        config(Presets.KEYS[j]), speaker(), 100);
                float sum = 0f;
                for (int k = 0; k < a.length; k++) {
                    float d = a[k] - b[k];
                    sum += d * d;
                }
                float distance = (float) Math.sqrt(sum);
                assertTrue("the processed curves for " + Presets.KEYS[i] + " and "
                        + Presets.KEYS[j] + " are only " + distance + " dB apart",
                        distance >= 2.4f);
                closest = Math.min(closest, distance);
            }
        }
        assertEquals("the closest pair is flat vs natural, and it is deliberate",
                2.45f, closest, 0.2f);
    }
}
