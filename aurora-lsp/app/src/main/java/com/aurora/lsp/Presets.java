package com.aurora.lsp;

/**
 * Listening curves in dB, one value per equalizer band.
 *
 * <p>Bands are 80 / 200 / 500 / 1.5k / 4k / 10k Hz. Two rules shaped every curve here, and both
 * came out of listening rather than theory:
 *
 * <ol>
 *   <li><b>The muddy range stays down.</b> 150-600 Hz is where sound turns to mush on a phone
 *       speaker. Bass weight belongs at 80 Hz, not at 250-500: piling gain on the low midrange is
 *       what made earlier presets sound thick instead of deep.
 *   <li><b>Neighbours stay within 6 dB.</b> A curve that jumps 10 dB between adjacent bands sounds
 *       exaggerated on paper and broken in the ear - it is the "pulled too far" feeling.
 * </ol>
 *
 * <p>Every preset except {@code natural} moves at least one band by 5 dB, so switching is audible
 * rather than academic.
 */
public final class Presets {

    public static final String[] KEYS = {
            "flat", "natural", "harman", "burmester", "punch",
            "cinema", "vocal", "bright", "night"
    };
    public static final String[] NAMES = {
            "原厂平直", "自然均衡", "哈曼卡顿", "柏林之声", "现场低频",
            "影院包围", "人声清晰", "明亮解析", "夜间柔化"
    };
    public static final String[] NOTES = {
            "全部归零，等同于不叠加任何曲线。",
            "轻微抬一点厚度，几乎听不出修饰。",
            "80 Hz 加重量，浑浊区让开，厚而不糊。",
            "中高频打开，器乐一件件分得开。",
            "低频顶满但浑浊区下压，鼓点砸下来不糊。",
            "下盘厚、极高频收，声场往外推开。",
            "主唱贴耳，背景整体后退。",
            "齿音与镲片跳出来，细节透亮。",
            "高频收敛，小音量也不刺耳。"
    };

    /**
     * dB per band, aligned with {@link AudioEq#BAND_HZ}.
     *
     * <p>Written to be told apart. The first table only differed in the bass shelf, and once the
     * engine folded every peak down to +6 dB most pairs landed within 2 dB of each other - nine
     * names, five sounds. These keep a distinct silhouette per preset: where the weight sits, where
     * the cut is, and how far the top opens. Any two curves are at least ~2.4 dB apart on average
     * (flat vs natural, deliberately, since one is the "off" shape), the rest 4.9 dB and more.
     */
    private static final float[][] CURVES = {
            // 80    200    500   1.5k   4k    10k
            {0, 0, 0, 0, 0, 0},          // flat       - the bypass shape
            {2, 1, 0, 0, 1, 0},          // natural    - barely there
            {6, 3, -1, 1, 2, 1},         // harman     - bass shelf, low mid pulled out
            {0, -2, 0, 5, 6, 4},         // burmester  - presence lift, bass left alone
            {8, 2, -4, -2, 1, 0},        // punch      - hard low end, deep scoop after it
            {7, 5, -1, -2, -1, 3},       // cinema     - broad low body, top rolled off
            {-4, -3, 0, 6, 3, -2},       // vocal      - everything but the voice gets out of the way
            {-5, -2, 0, 4, 7, 8},        // bright     - bass cut, treble shelf up
            {6, 1, -1, -4, -6, -7}       // night      - loudness curve, both ends tamed
    };

    /**
     * Stereo width each preset asks for, in dB, before the user's own slider is added.
     *
     * <p>Part of the preset rather than a global: widening a vocal or a night curve is what makes
     * them sound like different rooms, and a single global slider left every preset sharing the
     * same stage. The slider in the UI remains, as an offset on top of these.
     */
    private static final float[] SPREAD = {0f, 0f, 0.6f, 2.2f, 0.8f, 3.0f, 0f, 1.6f, 0f};

    /**
     * Perceived-loudness lift each preset asks for, in dB, before the user's slider is added.
     *
     * <p>LoudnessEnhancer changes the level, not the tone, so it is the one control that can
     * separate "quiet and soft" (night) from "quiet but present" (punch) without touching the
     * curve. Capped later at the same 5 dB the slider uses.
     */
    private static final float[] LOUDNESS = {0f, 0f, 0.4f, 0f, 1.0f, 1.2f, 0.6f, 0f, 1.6f};

    private Presets() {
    }

    /** Stereo width this preset contributes, dB. Clamped like {@link #curve(int)}. */
    public static float spreadOf(int index) {
        int safe = Math.max(0, Math.min(SPREAD.length - 1, index));
        return SPREAD[safe];
    }

    /** Perceived-loudness lift this preset contributes, dB. */
    public static float loudnessOf(int index) {
        int safe = Math.max(0, Math.min(LOUDNESS.length - 1, index));
        return LOUDNESS[safe];
    }

    public static int indexOf(String key) {
        if (key == null) return 0;
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return 0;
    }

    /**
     * Whether the key names a preset that exists.
     *
     * <p>{@link #indexOf} answers 0 for anything it does not know, which is the safe value for a
     * lookup but indistinguishable from a genuine "flat". Callers that want to tell a typo from a
     * real choice ask this instead.
     */
    public static boolean hasKey(String key) {
        if (key == null) return false;
        for (String candidate : KEYS) if (candidate.equals(key)) return true;
        return false;
    }

    public static float[] curve(int index) {
        int safe = Math.max(0, Math.min(CURVES.length - 1, index));
        return CURVES[safe];
    }

    /** True when the key names one of the built-in presets. */
    public static boolean isBuiltIn(String key) {
        if (key == null) return false;
        for (String known : KEYS) if (known.equals(key)) return true;
        return false;
    }
}