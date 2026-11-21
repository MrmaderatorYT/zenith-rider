package com.ccs.zenith_rider.graphics;

import com.ccs.zenith_rider.physics.FastRandom;

/**
 * Dynamic weather. A small weighted state machine picks the next kind of weather every half a
 * minute or so, and six continuous quantities (cloud cover, snowfall, rain, fog, wind, storm)
 * ease towards that kind's targets, so every change is gradual. Storms throw lightning: the
 * bolt is generated once into preallocated arrays and the thunder follows after a delay.
 *
 * Weather is atmosphere only - it never changes the rules of the run.
 */
public final class WeatherSystem {

    public static final int CLEAR = 0;
    public static final int FAIR = 1;
    public static final int SNOW = 2;
    public static final int BLIZZARD = 3;
    public static final int FOG = 4;
    public static final int RAIN = 5;
    public static final int STORM = 6;
    public static final int KINDS = 7;

    public static final int BOLT_POINTS = 12;
    public static final int BRANCH_POINTS = 5;

    /** Targets per kind: cloud, snow, rain, fog, wind, storm. */
    private static final float[][] TARGET = {
            {0.00f, 0.00f, 0.00f, 0.00f, 0.05f, 0f},
            {0.30f, 0.00f, 0.00f, 0.05f, 0.15f, 0f},
            {0.65f, 0.75f, 0.00f, 0.10f, 0.25f, 0f},
            {0.90f, 1.00f, 0.00f, 0.45f, 1.00f, 0f},
            {0.50f, 0.00f, 0.00f, 1.00f, 0.05f, 0f},
            {0.85f, 0.00f, 0.75f, 0.15f, 0.40f, 0f},
            {1.00f, 0.00f, 1.00f, 0.20f, 0.75f, 1f}
    };
    private static final float[] WEIGHT = {0.30f, 0.20f, 0.20f, 0.05f, 0.10f, 0.09f, 0.06f};

    // ---- current, eased values (0..1) ----
    public float cloud;
    public float snow;
    public float rain;
    public float fog;
    public float wind;
    public float storm;

    public int kind = CLEAR;

    // ---- lightning ----
    /** Brightness of the current flash, 1 at the strike, fading to 0. */
    public float flash;
    /** Seconds the bolt itself stays visible. */
    public float boltLife;
    public final float[] boltX = new float[BOLT_POINTS];
    public final float[] boltY = new float[BOLT_POINTS];
    public final float[] branchX = new float[BRANCH_POINTS];
    public final float[] branchY = new float[BRANCH_POINTS];

    private float timeLeft = 26f;
    private float strikeIn = 3f;
    private float reflashIn = -1f;
    private float thunderIn = -1f;
    private int thunderCount;
    private int thunderSeen;
    private boolean locked;

    private final FastRandom rng = new FastRandom((int) System.nanoTime() | 1);

    public void update(float dt, float vw, float vh) {
        if (!locked) {
            timeLeft -= dt;
            if (timeLeft <= 0f) {
                pickNext();
            }
        }
        final float[] t = TARGET[kind];
        final float k = Math.min(1f, dt * 0.22f);
        cloud += (t[0] - cloud) * k;
        snow += (t[1] - snow) * k;
        rain += (t[2] - rain) * k;
        fog += (t[3] - fog) * k;
        wind += (t[4] - wind) * k;
        storm += (t[5] - storm) * k;

        if (storm > 0.5f) {
            strikeIn -= dt;
            if (strikeIn <= 0f) {
                strike(vw, vh);
                strikeIn = rng.range(3.5f, 9f);
            }
        }
        if (reflashIn > 0f) {
            reflashIn -= dt;
            if (reflashIn <= 0f) {
                flash = 0.75f;
            }
        }
        if (flash > 0f) {
            flash -= dt * 3.4f;
            if (flash < 0f) {
                flash = 0f;
            }
        }
        if (boltLife > 0f) {
            boltLife -= dt;
        }
        if (thunderIn > 0f) {
            thunderIn -= dt;
            if (thunderIn <= 0f) {
                thunderCount++;
            }
        }
    }

    private void pickNext() {
        int next = kind;
        for (int guard = 0; guard < 8 && next == kind; guard++) {
            float r = rng.nextFloat();
            float acc = 0f;
            next = CLEAR;
            for (int i = 0; i < KINDS; i++) {
                acc += WEIGHT[i];
                if (r < acc) {
                    next = i;
                    break;
                }
            }
        }
        kind = next;
        // Violent weather passes more quickly than calm weather.
        timeLeft = kind == STORM || kind == BLIZZARD ? rng.range(22f, 36f) : rng.range(30f, 62f);
    }

    private void strike(float vw, float vh) {
        flash = 1f;
        boltLife = 0.22f;
        reflashIn = 0.11f;
        thunderIn = rng.range(0.25f, 1.1f);
        float x = vw * rng.range(0.12f, 0.88f);
        float y = -12f;
        final float stepY = vh * rng.range(0.40f, 0.56f) / (BOLT_POINTS - 1);
        final float drift = rng.range(-14f, 14f);
        for (int i = 0; i < BOLT_POINTS; i++) {
            boltX[i] = x;
            boltY[i] = y;
            x += drift + rng.range(-44f, 44f);
            y += stepY * rng.range(0.7f, 1.3f);
        }
        float bx = boltX[5];
        float by = boltY[5];
        final float dir = rng.nextFloat() < 0.5f ? -1f : 1f;
        for (int j = 0; j < BRANCH_POINTS; j++) {
            branchX[j] = bx;
            branchY[j] = by;
            bx += dir * rng.range(18f, 46f);
            by += rng.range(14f, 36f);
        }
    }

    /** True once for every thunderclap that is due. */
    public boolean pollThunder() {
        if (thunderSeen != thunderCount) {
            thunderSeen = thunderCount;
            return true;
        }
        return false;
    }

    /** Jumps straight to a kind of weather and keeps it (used for previews and screenshots). */
    public void force(int forcedKind) {
        if (forcedKind < 0 || forcedKind >= KINDS) {
            return;
        }
        kind = forcedKind;
        locked = true;
        final float[] t = TARGET[kind];
        cloud = t[0];
        snow = t[1];
        rain = t[2];
        fog = t[3];
        wind = t[4];
        storm = t[5];
        strikeIn = 1.2f;
    }
}
