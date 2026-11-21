package com.ccs.zenith_rider.graphics;

import android.graphics.LinearGradient;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * Day cycle and colour management: dawn -> day -> golden hour -> sunset -> dusk -> night.
 *
 * Every phase is a complete, deliberately muted palette in which the snow, the ridges and the
 * haze all take the colour of the sky, so the whole frame reads as one mood. Solid colours are
 * interpolated in RGB every step into a preallocated int array. Gradients cannot be mutated, so
 * one shader per phase is cached and the renderer cross-fades the two neighbouring phases - no
 * shader is ever created per frame.
 */
public final class EnvironmentPalette {

    public static final int DAWN = 0;
    public static final int DAY = 1;
    public static final int GOLDEN = 2;
    public static final int SUNSET = 3;
    public static final int DUSK = 4;
    public static final int NIGHT = 5;
    public static final int PHASES = 6;

    /** Length of the full day cycle in seconds. */
    public static final float CYCLE_SECONDS = 252f;

    public static final int SKY_TOP = 0;
    public static final int SKY_MID = 1;
    public static final int SKY_BOTTOM = 2;
    public static final int SUN = 3;
    /** Farthest ridge: almost the colour of the sky behind it. */
    public static final int FAR = 4;
    /** Nearest background hills. */
    public static final int MID = 5;
    public static final int HAZE = 6;
    /** Lit snow of the slope the rider is on. */
    public static final int TERRAIN = 7;
    public static final int RIM = 8;
    public static final int SILHOUETTE = 9;
    public static final int ACCENT = 10;
    public static final int SCARF = 11;
    public static final int CLOUD = 12;
    public static final int FORE = 13;
    public static final int CRYSTAL = 14;
    /** Tint of the light that blooms around the sun or the moon. */
    public static final int GLOW = 15;
    /** Shadowed snow: the deeper tonal bands of the slope and the rock faces. */
    public static final int SHADE = 16;
    public static final int TREE = 17;
    /** Lantern and window light. */
    public static final int LIGHT = 18;
    public static final int COLORS = 19;

    private static final int[][] KEY = {
            // DAWN: cool slate sky warming to peach at the horizon, pale misty snow
            {0xFF5C6F8C, 0xFFA9A7B8, 0xFFF2CDB0, 0xFFFFF3DC, 0xFFB9B4C4, 0xFF6E6F8E, 0xFFE9D3C8,
                    0xFFEDE6E6, 0xFFFFFFFF, 0xFF2B2A3D, 0xFFFFC98A, 0xFFE2575C, 0xFFF6E4DA,
                    0xFF2A2B3F, 0xFF9FA8F0, 0xFFFFD9B0, 0xFFB9B5C8, 0xFF4B4F68, 0xFFFFD9A0},
            // DAY: airy glacier blue, bright snow
            {0xFF5B8FBF, 0xFF9CC3DC, 0xFFE3EEF0, 0xFFFFFFF2, 0xFFB4CFE0, 0xFF6B93AD, 0xFFE6F0F2,
                    0xFFF7F8F6, 0xFFFFFFFF, 0xFF1F2E3A, 0xFF3FE0D0, 0xFFE0444F, 0xFFFFFFFF,
                    0xFF1C3442, 0xFF58D0EE, 0xFFFFFFE6, 0xFFC2D5DE, 0xFF2C4A5A, 0xFFFFF0C0},
            // GOLDEN: dusty blue above warm sand and gold, cream snow
            {0xFF6F86A8, 0xFFD9B9A0, 0xFFFFDDA6, 0xFFFFF2C8, 0xFFCBB3A8, 0xFF8A6F78, 0xFFF6D7B4,
                    0xFFF3E2D0, 0xFFFFF6E0, 0xFF2E2030, 0xFFFFB85C, 0xFFD94A4A, 0xFFFFE6C8,
                    0xFF2B1D2C, 0xFFF08FA8, 0xFFFFD08A, 0xFFC9A99E, 0xFF4A3A48, 0xFFFFD890},
            // SUNSET: plum, rose and orange; the back-lit slope falls into warm shadow
            {0xFF3A2F5C, 0xFFB5566A, 0xFFF7A064, 0xFFFFE0A8, 0xFF9A5A72, 0xFF5A3352, 0xFFE98A6E,
                    0xFF6B4654, 0xFFFFC79A, 0xFF1C1020, 0xFFFFB347, 0xFFFFC857, 0xFFF29A7E,
                    0xFF1A0E1C, 0xFFFF86AC, 0xFFFF9A5C, 0xFF4A2F42, 0xFF2E1A2E, 0xFFFFC870},
            // DUSK: blue hour, a last band of dusty pink, the first lights come on
            {0xFF232A4E, 0xFF5A5480, 0xFFC98C96, 0xFFFFD0B0, 0xFF6A5F88, 0xFF3A3560, 0xFF9A7E9C,
                    0xFF4A4668, 0xFFC9B6D6, 0xFF12101F, 0xFF8CE8E0, 0xFFFF9A6A, 0xFF8A6F92,
                    0xFF100E1C, 0xFFA08CFF, 0xFFE58A8A, 0xFF332F50, 0xFF1F1C35, 0xFFFFC27A},
            // NIGHT: deep navy, moonlit blue snow
            {0xFF0B1226, 0xFF18264A, 0xFF34507A, 0xFFEAF2FF, 0xFF24365C, 0xFF16223F, 0xFF3C5682,
                    0xFF3A4A6E, 0xFFBFD4F5, 0xFF070A16, 0xFF6CF5E0, 0xFF6CF5E0, 0xFF2E4068,
                    0xFF060913, 0xFF8094FF, 0xFF9CC0FF, 0xFF263352, 0xFF0F172B, 0xFFFFC66E}
    };

    /** How dark the sky is per phase: drives stars, aurora and glow trails. */
    private static final float[] NIGHT_AMOUNT = {0.10f, 0f, 0f, 0.04f, 0.50f, 1f};
    /** How strongly lanterns and windows are lit per phase. */
    private static final float[] LIGHT_AMOUNT = {0.30f, 0f, 0f, 0.45f, 1f, 1f};
    /** Colours that turn grey and dark under heavy cloud; signal colours stay untouched. */
    private static final int[] WEATHERED = {SUN, FAR, MID, HAZE, TERRAIN, RIM, CLOUD, FORE, SHADE, TREE};
    /** Musical mode used by the audio engine for each phase. */
    private static final int[] MUSIC_PHASE = {0, 1, 1, 2, 2, 3};

    /** Current interpolated colours, indexed by the constants above. */
    public final int[] color = new int[COLORS];

    /** Position in the cycle, 0..PHASES (0 dawn, 1 day, 2 golden, 3 sunset, 4 dusk, 5 night). */
    public float cycle;
    public int phaseA;
    public int phaseB = 1;
    /** 0 = pure phase A, 1 = pure phase B. */
    public float blend;
    /** 0..1, see {@link #NIGHT_AMOUNT}. */
    public float night;
    /** 0..1, see {@link #LIGHT_AMOUNT}. */
    public float lights;
    /** Muted grey that overcast weather pulls the scene towards; darker at night. */
    public int overcast = 0xFF7E8592;

    public final LinearGradient[] sky = new LinearGradient[PHASES];
    /** Unit-height vertical gradients from transparent to the phase's haze colour. */
    public final LinearGradient[] mist = new LinearGradient[PHASES];
    /** Unit-radius glow around the sun or the moon. */
    public final RadialGradient[] halo = new RadialGradient[PHASES];
    /** Unit-radius soft blobs of haze for drifting fog. */
    public final RadialGradient[] fogPuff = new RadialGradient[PHASES];
    public LinearGradient aurora;
    public LinearGradient terrainShade;
    public RadialGradient orbGlow;
    public RadialGradient goldGlow;
    public RadialGradient tealGlow;
    public RadialGradient lampGlow;

    public EnvironmentPalette() {
        update(0f);
    }

    /** Builds all cached shaders for the given virtual screen size. Called once per resize. */
    public void build(float vw, float vh) {
        for (int p = 0; p < PHASES; p++) {
            int[] k = KEY[p];
            sky[p] = new LinearGradient(0f, 0f, 0f, vh * 0.80f,
                    new int[]{k[SKY_TOP], k[SKY_MID], k[SKY_BOTTOM]},
                    new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP);
            int hz = k[HAZE] & 0x00FFFFFF;
            mist[p] = new LinearGradient(0f, 0f, 0f, 1f,
                    new int[]{hz, hz | 0x70000000, hz | 0xFF000000},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            int gl = k[GLOW] & 0x00FFFFFF;
            halo[p] = new RadialGradient(0f, 0f, 1f,
                    new int[]{gl | 0xC8000000, gl | 0x66000000, gl | 0x22000000, gl},
                    new float[]{0f, 0.10f, 0.38f, 1f}, Shader.TileMode.CLAMP);
            fogPuff[p] = new RadialGradient(0f, 0f, 1f,
                    new int[]{hz | 0x96000000, hz | 0x48000000, hz},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        }
        aurora = new LinearGradient(0f, vh * 0.04f, 0f, vh * 0.62f,
                new int[]{0x0040FFC0, 0x9040FFC0, 0x585C8CFF, 0x005C8CFF},
                new float[]{0f, 0.3f, 0.65f, 1f}, Shader.TileMode.CLAMP);
        terrainShade = new LinearGradient(0f, -260f, 0f, 760f,
                new int[]{0x00101830, 0x12101830, 0x50101830},
                new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP);
        orbGlow = new RadialGradient(0f, 0f, 1f,
                new int[]{0xE0FFFFFF, 0x7066FFE6, 0x0066FFE6},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP);
        goldGlow = new RadialGradient(0f, 0f, 1f,
                new int[]{0xC0FFF2B0, 0x60FFC040, 0x00FFC040},
                new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP);
        tealGlow = new RadialGradient(0f, 0f, 1f,
                new int[]{0xB0C8FFF4, 0x5040E8D8, 0x0040E8D8},
                new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP);
        lampGlow = new RadialGradient(0f, 0f, 1f,
                new int[]{0xD8FFE6B4, 0x66FFBE62, 0x00FFBE62},
                new float[]{0f, 0.28f, 1f}, Shader.TileMode.CLAMP);
    }

    public void update(float dt) {
        cycle += dt * PHASES / CYCLE_SECONDS;
        if (cycle >= PHASES) {
            cycle -= PHASES;
        }
        int a = (int) cycle;
        if (a >= PHASES) {
            a = PHASES - 1;
        }
        float f = cycle - a;
        // Each phase is held for a while, then eases into the next one.
        float t = (f - 0.45f) / 0.55f;
        if (t < 0f) {
            t = 0f;
        }
        t = t * t * (3f - 2f * t);
        phaseA = a;
        phaseB = (a + 1) % PHASES;
        blend = t;
        final int[] ka = KEY[phaseA];
        final int[] kb = KEY[phaseB];
        for (int i = 0; i < COLORS; i++) {
            color[i] = lerpColor(ka[i], kb[i], t);
        }
        night = NIGHT_AMOUNT[phaseA] + (NIGHT_AMOUNT[phaseB] - NIGHT_AMOUNT[phaseA]) * t;
        lights = LIGHT_AMOUNT[phaseA] + (LIGHT_AMOUNT[phaseB] - LIGHT_AMOUNT[phaseA]) * t;
        overcast = lerpColor(color[HAZE], lerpColor(0xFF7E8592, 0xFF1C2233, night), 0.6f);
    }

    /**
     * Greys and darkens the scenery colours for the current weather. Must be called right after
     * {@link #update(float)}, which restores the clean palette first. Cached gradients are not
     * affected - the renderer grades those with translucent overlays instead.
     */
    public void applyWeather(float cloud, float fog, float storm) {
        float k = 0.34f * cloud + 0.18f * fog;
        if (k > 0.6f) {
            k = 0.6f;
        }
        final float dark = 0.30f * storm;
        if (k < 0.004f && dark < 0.004f) {
            return;
        }
        // Under a heavy sky or in a storm the lanterns are lit even by day.
        float gloom = (cloud > 0.5f ? (cloud - 0.5f) * 0.7f : 0f) + 0.3f * storm;
        lights += (1f - lights) * (gloom > 1f ? 1f : gloom);
        final int tone = overcast;
        for (int j = 0; j < WEATHERED.length; j++) {
            int i = WEATHERED[j];
            int v = lerpColor(color[i], tone, k);
            color[i] = dark > 0f ? lerpColor(v, 0xFF10141F, dark) : v;
        }
    }

    /** Musical mode (0..3) matching the dominant phase. */
    public int musicPhase() {
        return MUSIC_PHASE[blend > 0.5f ? phaseB : phaseA];
    }

    public static int lerpColor(int c0, int c1, float t) {
        int a0 = c0 >>> 24, r0 = (c0 >> 16) & 0xFF, g0 = (c0 >> 8) & 0xFF, b0 = c0 & 0xFF;
        int a1 = c1 >>> 24, r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
        int a = a0 + (int) ((a1 - a0) * t);
        int r = r0 + (int) ((r1 - r0) * t);
        int g = g0 + (int) ((g1 - g0) * t);
        int b = b0 + (int) ((b1 - b0) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int withAlpha(int argb, int alpha) {
        if (alpha < 0) {
            alpha = 0;
        } else if (alpha > 255) {
            alpha = 255;
        }
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }
}
