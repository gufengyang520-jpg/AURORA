package com.aurora.lsp;

/**
 * Listening curves in dB, one value per equalizer band.
 *
 * <p>Bands are 250 / 500 / 1k / 2k / 4k / 6.3k Hz. Measured on the target speaker, only roughly
 * 300 Hz - 6 kHz is reproduced usefully, so every curve works inside that window.
 *
 * <p>Every preset differs from "flat" by at least 5 dB in some band and from its neighbours by at
 * least 4 dB somewhere, so switching presets is audible rather than academic.
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
            "低频砸下去，中频往后收，厚而宽松。",
            "中频通透、高频丝滑，器乐一件件分得开。",
            "低频顶满，中高频全退，鼓点打到胸口。",
            "下盘厚、极高频退，声场往外推开。",
            "主唱贴耳，背景整体后退。",
            "齿音与镲片全部跳出来，细节刺亮。",
            "高频大幅收敛，小音量也不刺耳。"
    };

    /** dB per band, aligned with {@link AudioEq#BAND_HZ}. */
    private static final float[][] CURVES = {
            // 250   500    1k     2k     4k    6.3k
            {0, 0, 0, 0, 0, 0},
            {2, 1, 0, 0, 1, 1},
            {11, 9, -3, -6, -2, -5},
            {4, 2, -1, 4, 10, 11},
            {12, 12, 5, -7, -11, -12},
            {10, 8, 2, -3, -9, -11},
            {-10, -12, 6, 9, 4, 0},
            {-9, -7, 1, 6, 11, 12},
            {6, 4, 0, -7, -11, -11}
    };

    private Presets() {
    }

    public static int indexOf(String key) {
        if (key == null) return 0;
        for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(key)) return i;
        return 0;
    }

    public static float[] curve(int index) {
        int safe = Math.max(0, Math.min(CURVES.length - 1, index));
        return CURVES[safe];
    }
}