package com.ccs.zenith_rider.physics;

import com.ccs.zenith_rider.model.WorldObjects;

/**
 * Endless procedural terrain.
 *
 * Control points are produced every {@link #CS} world units from a descending base line plus
 * three harmonic waves, and the surface between them is a Catmull-Rom spline, which gives a
 * smooth height, slope and curvature at any x. Chasms are stored as [x0, x1] intervals where the
 * surface is not solid; each chasm is preceded by a kicker ramp and followed by a lower landing.
 * A second, lagging cursor decorates already generated terrain with orbs, crystals, updrafts and
 * rope lines ("rails" in the code) taken from the {@link WorldObjects} pools.
 */
public final class TerrainGenerator {

    /** Horizontal distance between spline control points. */
    public static final float CS = 160f;
    public static final int MAX_GAPS = 16;
    /** World units per meter. */
    public static final float UNITS_PER_METER = 20f;

    private static final int RING = 512;
    private static final int MASK = RING - 1;
    private static final float BASE_SLOPE = 0.14f;
    private static final float FULL_DIFFICULTY_X = UNITS_PER_METER * 5000f;
    private static final float GENERATE_AHEAD = 9000f;
    private static final int DECO_LAG = 16;

    private final float[] cy = new float[RING];

    public final float[] gapX0 = new float[MAX_GAPS];
    public final float[] gapX1 = new float[MAX_GAPS];
    public final boolean[] gapCounted = new boolean[MAX_GAPS];
    private final int[] gapIdx = new int[MAX_GAPS];
    private int gapHead;

    /** Launch ramps: curved decks standing on the snow that end with an abrupt drop. */
    public static final int MAX_RAMPS = 8;
    public final float[] rampX0 = new float[MAX_RAMPS];
    public final float[] rampX1 = new float[MAX_RAMPS];
    public final float[] rampH = new float[MAX_RAMPS];
    private int rampHead;

    /** Mixed into the renderer's scenery hashes so groves and landmarks differ from run to run. */
    public int scenerySeed;

    /** Wisps per flock; set by the engine from the Wisp Call upgrade before reset(). */
    public int wispFlock = 4;

    private int genIdx;
    private int decoIdx;
    private int nextGapIdx;
    private int nextDecoIdx;
    private int phantomIdx;
    private float pendingDrop;
    private float yOffset;
    private float phase1, phase2, phase3, phase4;

    private final FastRandom rng = new FastRandom(1);
    private final WorldObjects objs;

    public TerrainGenerator(WorldObjects objs) {
        this.objs = objs;
    }

    public void reset(int seed) {
        rng.setSeed(seed);
        scenerySeed = seed & 0x3FFF;
        phase1 = rng.range(0f, 6.2831855f);
        phase2 = rng.range(0f, 6.2831855f);
        phase3 = rng.range(0f, 6.2831855f);
        phase4 = rng.range(0f, 6.2831855f);
        yOffset = 0f;
        pendingDrop = 0f;
        genIdx = 0;
        decoIdx = 0;
        phantomIdx = -1;
        gapHead = 0;
        for (int k = 0; k < MAX_GAPS; k++) {
            gapX0[k] = -1e9f;
            gapX1[k] = -1e9f;
            gapIdx[k] = -100000;
            gapCounted[k] = true;
        }
        rampHead = 0;
        for (int r = 0; r < MAX_RAMPS; r++) {
            rampX0[r] = -1e9f;
            rampX1[r] = -1e9f;
            rampH[r] = 0f;
        }
        nextGapIdx = 36 + rng.nextInt(6);
        nextDecoIdx = 12;
        ensure(0f);
    }

    /** 0 at the start of a run, 1 after five kilometers. */
    public float difficultyAt(float x) {
        float d = x / FULL_DIFFICULTY_X;
        return d < 0f ? 0f : (d > 1f ? 1f : d);
    }

    /** Keeps terrain generated far enough ahead of world position {@code x}. */
    public void ensure(float x) {
        while ((genIdx - 3) * CS < x + GENERATE_AHEAD) {
            generateNext();
        }
        while (decoIdx < genIdx - DECO_LAG) {
            decorate(decoIdx);
            decoIdx++;
        }
    }

    private float rawHeight(int i) {
        float x = i * CS;
        // Slow modulation alternates calm plateaus with rough, wavy stretches.
        float mod = 0.70f + 0.30f * (float) Math.sin(x * 0.00023f + phase4 * 1.7f);
        float amp = (0.75f + 0.30f * difficultyAt(x)) * mod;
        float h = 110f * (float) Math.sin(x * 0.0011f + phase1)
                + 62f * (float) Math.sin(x * 0.00243f + phase2)
                + 26f * (float) Math.sin(x * 0.0052f + phase3);
        // Very long wave: broad valleys and ridges under the small relief.
        float big = 230f * (float) Math.sin(x * 0.00039f + phase4);
        return BASE_SLOPE * x + amp * h + big + yOffset;
    }

    private void generateNext() {
        final int i = genIdx;
        if (i == phantomIdx) {
            // Invisible control point inside the chasm: continues the kicker so the lip keeps
            // pointing upwards; the landing side starts one control point later, lower down.
            cy[i & MASK] = rawHeight(i);
            yOffset += pendingDrop;
            pendingDrop = 0f;
            genIdx++;
            return;
        }
        float y = rawHeight(i);
        if (i == nextGapIdx && i >= 4) {
            float d = difficultyAt(i * CS);
            // The take-off is a launch ramp standing at the edge instead of a snow bump.
            addRamp(i * CS + 7f - 300f, i * CS + 7f, 92f);
            float width = 470f + 260f * d + rng.range(0f, 120f);
            int k = gapHead;
            gapHead = (gapHead + 1) % MAX_GAPS;
            gapX0[k] = i * CS + 6f;
            gapX1[k] = gapX0[k] + width;
            gapIdx[k] = i;
            gapCounted[k] = false;
            phantomIdx = i + 1;
            pendingDrop = 110f + rng.range(0f, 50f);
            int spanCtrl = (int) (width / CS) + 2;
            nextGapIdx = i + spanCtrl + (int) (34f - 20f * d) + rng.nextInt(14);
        }
        cy[i & MASK] = y;
        genIdx++;
    }

    // ------------------------------------------------------------------ queries

    private void addRamp(float x0, float x1, float height) {
        int r = rampHead;
        rampHead = (rampHead + 1) % MAX_RAMPS;
        rampX0[r] = x0;
        rampX1[r] = x1;
        rampH[r] = height;
    }

    /** Index of the ramp whose deck covers x, or -1. */
    public int rampAt(float x) {
        for (int r = 0; r < MAX_RAMPS; r++) {
            if (x >= rampX0[r] && x <= rampX1[r]) {
                return r;
            }
        }
        return -1;
    }

    /** True when a ramp ends inside (a, b]: a rider crossing it flies off the deck. */
    public boolean rampEndBetween(float a, float b) {
        for (int r = 0; r < MAX_RAMPS; r++) {
            if (a < rampX1[r] && b >= rampX1[r] && a >= rampX0[r]) {
                return true;
            }
        }
        return false;
    }

    /** Ridable surface: the snow, or the deck of a ramp where one stands. */
    public float heightAt(float x) {
        int r = rampAt(x);
        if (r < 0) {
            return groundAt(x);
        }
        float u = (x - rampX0[r]) / (rampX1[r] - rampX0[r]);
        return groundAt(x) - rampH[r] * u * (float) Math.sqrt(u);
    }

    /** dy/dx of the ridable surface. Positive means it descends to the right. */
    public float slopeAt(float x) {
        int r = rampAt(x);
        if (r < 0) {
            return groundSlopeAt(x);
        }
        float len = rampX1[r] - rampX0[r];
        float u = (x - rampX0[r]) / len;
        return groundSlopeAt(x) - 1.5f * rampH[r] * (float) Math.sqrt(u) / len;
    }

    /** Height of the bare snow surface (ignores ramps). */
    public float groundAt(float x) {
        float fx = x / CS;
        int i = (int) fx;
        if (i < 1) {
            i = 1;
        }
        float t = fx - i;
        if (t < 0f) {
            t = 0f;
        }
        if (i > genIdx - 3) {
            i = genIdx - 3;
            t = 1f;
        }
        float p0 = cy[(i - 1) & MASK];
        float p1 = cy[i & MASK];
        float p2 = cy[(i + 1) & MASK];
        float p3 = cy[(i + 2) & MASK];
        float t2 = t * t;
        float t3 = t2 * t;
        return 0.5f * ((2f * p1) + (-p0 + p2) * t
                + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2
                + (-p0 + 3f * p1 - 3f * p2 + p3) * t3);
    }

    private float groundSlopeAt(float x) {
        float fx = x / CS;
        int i = (int) fx;
        if (i < 1) {
            i = 1;
        }
        float t = fx - i;
        if (t < 0f) {
            t = 0f;
        }
        if (i > genIdx - 3) {
            i = genIdx - 3;
            t = 1f;
        }
        float p0 = cy[(i - 1) & MASK];
        float p1 = cy[i & MASK];
        float p2 = cy[(i + 1) & MASK];
        float p3 = cy[(i + 2) & MASK];
        float d = 0.5f * ((-p0 + p2)
                + 2f * (2f * p0 - 5f * p1 + 4f * p2 - p3) * t
                + 3f * (-p0 + 3f * p1 - 3f * p2 + p3) * t * t);
        return d / CS;
    }

    /** Second derivative of the surface. Positive on crests (y grows downwards). */
    public float curvatureAt(float x) {
        if (rampAt(x) >= 0) {
            return 0f;
        }
        float fx = x / CS;
        int i = (int) fx;
        if (i < 1) {
            i = 1;
        }
        float t = fx - i;
        if (t < 0f) {
            t = 0f;
        }
        if (i > genIdx - 3) {
            i = genIdx - 3;
            t = 1f;
        }
        float p0 = cy[(i - 1) & MASK];
        float p1 = cy[i & MASK];
        float p2 = cy[(i + 1) & MASK];
        float p3 = cy[(i + 2) & MASK];
        float d = 0.5f * (2f * (2f * p0 - 5f * p1 + 4f * p2 - p3)
                + 6f * (-p0 + 3f * p1 - 3f * p2 + p3) * t);
        return d / (CS * CS);
    }

    /** Index of the chasm that contains x, or -1 when the ground is solid there. */
    public int gapAt(float x) {
        for (int k = 0; k < MAX_GAPS; k++) {
            if (x > gapX0[k] && x < gapX1[k]) {
                return k;
            }
        }
        return -1;
    }

    /** True when any chasm overlaps the interval [a, b]. */
    public boolean gapNear(float a, float b) {
        for (int k = 0; k < MAX_GAPS; k++) {
            if (gapX0[k] < b && gapX1[k] > a) {
                return true;
            }
        }
        return false;
    }

    /** Surface height at the landing edge of chasm k. */
    public float gapLandingY(int k) {
        return heightAt(gapX1[k]);
    }

    // ------------------------------------------------------------------ decoration

    private void decorate(int i) {
        final float x = i * CS;
        for (int k = 0; k < MAX_GAPS; k++) {
            if (gapIdx[k] == i) {
                decorateGap(k);
                int after = i + (int) ((gapX1[k] - x) / CS) + 3;
                if (after > nextDecoIdx) {
                    nextDecoIdx = after;
                }
                return;
            }
        }
        if (i < nextDecoIdx) {
            return;
        }
        if (gapNear(x - 450f, x + 950f)) {
            nextDecoIdx = i + 2;
            return;
        }
        final float d = difficultyAt(x);
        final float r = rng.nextFloat();
        int span;
        // Orbs are deliberately scarce: most slots on the open slope hold nothing but snow.
        if (x < 4200f) {
            span = x < 2400f ? orbTrio(x) : 3;
        } else if (r < 0.06f) {
            span = orbTrio(x);
        } else if (r < 0.43f) {
            span = 3;
        } else if (r < 0.63f + 0.12f * d) {
            span = crystals(x, d);
        } else if (r < 0.66f + 0.10f * d) {
            span = ramp(x);
        } else if (r < 0.78f + 0.06f * d) {
            span = rail(x);
        } else if (r < 0.89f) {
            span = updraft(x);
        } else if (r < 0.935f) {
            // A flock of light wisps: a loose swarm at uneven heights, so it cannot be taken
            // for a row of orbs.
            for (int j = 0; j < wispFlock; j++) {
                float ox = x + j * 92f + rng.range(-28f, 28f);
                objs.spawnOrb(ox, heightAt(ox) - 34f - rng.range(0f, 70f), WorldObjects.ORB_WISP);
            }
            span = (int) (wispFlock * 92f / CS) + 1;
        } else if (r < 0.965f) {
            objs.spawnOrb(x + 80f, heightAt(x + 80f) - 112f, WorldObjects.ORB_MAGNET);
            span = 1;
        } else if (r < 0.99f) {
            objs.spawnOrb(x + 80f, heightAt(x + 80f) - 112f, WorldObjects.ORB_HOVER);
            span = 1;
        } else {
            span = 1;
        }
        nextDecoIdx = i + span + 2 + (int) ((1f - d) * 3f) + rng.nextInt(3);
    }

    /** Three orbs: a short line just above the snow, or a low arc that asks for a jump. */
    private int orbTrio(float x) {
        final boolean arc = rng.nextFloat() < 0.5f;
        for (int j = 0; j < 3; j++) {
            float ox = x + 60f + j * 74f;
            float lift = arc ? 96f + (j == 1 ? 34f : 0f) : 46f;
            objs.spawnOrb(ox, heightAt(ox) - lift, WorldObjects.ORB_NORMAL);
        }
        return 3;
    }

    private int crystals(float x, float d) {
        float cx = x + 60f;
        objs.spawnCrystal(cx, heightAt(cx), 76f + rng.range(0f, 18f), rng.nextFloat());
        if (d > 0.3f && rng.nextFloat() < 0.5f) {
            float cx2 = cx + 110f;
            objs.spawnCrystal(cx2, heightAt(cx2), 70f + rng.range(0f, 16f), rng.nextFloat());
            cx += 55f;
        }
        // Now and then a single orb hangs right above the barrier as a dare.
        if (rng.nextFloat() < 0.12f) {
            objs.spawnOrb(cx, heightAt(cx) - 168f, WorldObjects.ORB_NORMAL);
        }
        return 3;
    }

    /** A rope line strung between two posts above the open slope. */
    private int rail(float x) {
        float x0 = x + 40f;
        float len = 760f + rng.range(0f, 600f);
        float x1 = x0 + len;
        // Both posts have to stand on solid ground.
        if (gapNear(x0 - 80f, x1 + 160f)) {
            return 3;
        }
        float y0 = heightAt(x0) - 90f;
        float y1 = heightAt(x1) - 150f;
        float lift = 0f;
        for (int s = 0; s <= 10; s++) {
            float u = s / 10f;
            float xs = x0 + len * u;
            float clearance = heightAt(xs) - (y0 + (y1 - y0) * u);
            float need = 56f - clearance;
            if (need > lift) {
                lift = need;
            }
        }
        if (lift > 30f) {
            return 3;
        }
        y0 -= lift;
        y1 -= lift;
        if (objs.spawnRail(x0, y0, x1, y1) < 0) {
            return 3;
        }
        if (rng.nextFloat() < 0.4f) {
            objs.spawnOrb(x0 + len * 0.36f, y0 + (y1 - y0) * 0.36f - 34f, WorldObjects.ORB_NORMAL);
            objs.spawnOrb(x0 + len * 0.64f, y0 + (y1 - y0) * 0.64f - 34f, WorldObjects.ORB_NORMAL);
        }
        return (int) (len / CS) + 2;
    }

    /** A free-standing launch ramp; sometimes three orbs hang along the flight of a good jump. */
    private int ramp(float x) {
        float x0 = x + 40f;
        float x1 = x0 + 280f;
        addRamp(x0, x1, 80f);
        if (rng.nextFloat() < 0.3f) {
            objs.spawnOrb(x1 + 230f, groundAt(x1 + 230f) - 330f, WorldObjects.ORB_NORMAL);
            objs.spawnOrb(x1 + 375f, groundAt(x1 + 375f) - 385f, WorldObjects.ORB_NORMAL);
            objs.spawnOrb(x1 + 520f, groundAt(x1 + 520f) - 345f, WorldObjects.ORB_NORMAL);
        }
        return 6;
    }

    private int updraft(float x) {
        float ux = x + 220f;
        objs.spawnUpdraft(ux, 115f, heightAt(ux) + 20f);
        if (rng.nextFloat() < 0.4f) {
            objs.spawnOrb(ux - 30f, heightAt(ux - 30f) - 300f, WorldObjects.ORB_NORMAL);
            objs.spawnOrb(ux + 50f, heightAt(ux + 50f) - 372f, WorldObjects.ORB_NORMAL);
        }
        return 3;
    }

    private void decorateGap(int k) {
        final float x0 = gapX0[k];
        final float x1 = gapX1[k];
        final float width = x1 - x0;
        final float lipY = heightAt(x0);
        final float landY = heightAt(x1);
        final float d = difficultyAt(x0);
        final float r = rng.nextFloat();
        if (r < 0.18f) {
            // three orbs at the top of the jump
            for (int j = 0; j < 3; j++) {
                float u = 0.36f + 0.14f * j;
                float ox = x0 + width * u;
                float oy = lipY + (landY - lipY) * u - 70f - 130f * (float) Math.sin(Math.PI * u);
                objs.spawnOrb(ox, oy, WorldObjects.ORB_NORMAL);
            }
        } else if (r >= 0.40f && r < 0.66f && d > 0.03f) {
            // a rope from the lip of the chasm to a post on the far side
            float rx0 = x0 - 30f;
            float ry0 = lipY - 66f;
            float rx1 = x1 + 340f;
            float ry1 = heightAt(rx1) - 120f;
            for (int pass = 0; pass < 3; pass++) {
                float minClear = 1e9f;
                for (int s = 0; s <= 8; s++) {
                    float xs = x1 + (rx1 - x1) * (s / 8f);
                    float u = (xs - rx0) / (rx1 - rx0);
                    float clearance = heightAt(xs) - (ry0 + (ry1 - ry0) * u);
                    if (clearance < minClear) {
                        minClear = clearance;
                    }
                }
                if (minClear >= 50f) {
                    break;
                }
                ry1 -= (50f - minClear) * 1.6f;
            }
            if (objs.spawnRail(rx0, ry0, rx1, ry1) >= 0 && rng.nextFloat() < 0.4f) {
                float len = rx1 - rx0;
                objs.spawnOrb(rx0 + len * 0.36f, ry0 + (ry1 - ry0) * 0.36f - 34f, WorldObjects.ORB_NORMAL);
                objs.spawnOrb(rx0 + len * 0.64f, ry0 + (ry1 - ry0) * 0.64f - 34f, WorldObjects.ORB_NORMAL);
            }
        } else if (r >= 0.66f && r < 0.86f) {
            float mid = (x0 + x1) * 0.5f;
            objs.spawnUpdraft(mid, width * 0.5f - 50f, landY + 320f);
            if (rng.nextFloat() < 0.4f) {
                for (int j = 0; j < 2; j++) {
                    float u = 0.4f + 0.2f * j;
                    float ox = x0 + width * u;
                    float oy = lipY + (landY - lipY) * u - 190f - 130f * (float) Math.sin(Math.PI * u);
                    objs.spawnOrb(ox, oy, WorldObjects.ORB_NORMAL);
                }
            }
        }
    }
}
