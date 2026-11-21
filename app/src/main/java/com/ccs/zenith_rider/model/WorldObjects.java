package com.ccs.zenith_rider.model;

/**
 * Fixed-capacity object pools (structure-of-arrays) for everything that lives in the world:
 * energy orbs, crystal barriers, updraft columns, light rails and floating score popups.
 * Slots are recycled through "active" flags; nothing is ever allocated after construction.
 */
public final class WorldObjects {

    public static final int MAX_ORBS = 160;
    public static final int MAX_CRYSTALS = 24;
    public static final int MAX_UPDRAFTS = 8;
    public static final int MAX_RAILS = 8;
    public static final int MAX_POPUPS = 16;

    public static final int ORB_NORMAL = 0;
    public static final int ORB_MAGNET = 1;
    public static final int ORB_HOVER = 2;
    public static final int ORB_WISP = 3;

    public static final float UPDRAFT_HEIGHT = 1100f;

    // ---- orbs ----
    public final float[] orbX = new float[MAX_ORBS];
    public final float[] orbY = new float[MAX_ORBS];
    public final float[] orbPhase = new float[MAX_ORBS];
    public final int[] orbType = new int[MAX_ORBS];
    public final boolean[] orbActive = new boolean[MAX_ORBS];
    private int orbCursor;

    // ---- crystals ----
    public final float[] cryX = new float[MAX_CRYSTALS];
    public final float[] cryY = new float[MAX_CRYSTALS];
    public final float[] cryH = new float[MAX_CRYSTALS];
    public final float[] crySeed = new float[MAX_CRYSTALS];
    public final boolean[] cryActive = new boolean[MAX_CRYSTALS];

    // ---- updrafts ----
    public final float[] upX = new float[MAX_UPDRAFTS];
    public final float[] upHalf = new float[MAX_UPDRAFTS];
    public final float[] upBase = new float[MAX_UPDRAFTS];
    public final boolean[] upActive = new boolean[MAX_UPDRAFTS];

    // ---- light rails ----
    public final float[] railX0 = new float[MAX_RAILS];
    public final float[] railY0 = new float[MAX_RAILS];
    public final float[] railX1 = new float[MAX_RAILS];
    public final float[] railY1 = new float[MAX_RAILS];
    public final boolean[] railActive = new boolean[MAX_RAILS];

    // ---- popups ----
    public final float[] popX = new float[MAX_POPUPS];
    public final float[] popY = new float[MAX_POPUPS];
    public final float[] popLife = new float[MAX_POPUPS];
    public final int[] popValue = new int[MAX_POPUPS];
    public final int[] popLabel = new int[MAX_POPUPS];
    public final int[] popColor = new int[MAX_POPUPS];
    public final boolean[] popActive = new boolean[MAX_POPUPS];
    private int popCursor;

    public static final float POPUP_LIFE = 1.3f;

    public void clear() {
        for (int i = 0; i < MAX_ORBS; i++) {
            orbActive[i] = false;
        }
        for (int i = 0; i < MAX_CRYSTALS; i++) {
            cryActive[i] = false;
        }
        for (int i = 0; i < MAX_UPDRAFTS; i++) {
            upActive[i] = false;
        }
        for (int i = 0; i < MAX_RAILS; i++) {
            railActive[i] = false;
        }
        for (int i = 0; i < MAX_POPUPS; i++) {
            popActive[i] = false;
        }
    }

    public int spawnOrb(float x, float y, int type) {
        for (int n = 0; n < MAX_ORBS; n++) {
            int i = (orbCursor + n) % MAX_ORBS;
            if (!orbActive[i]) {
                orbActive[i] = true;
                orbX[i] = x;
                orbY[i] = y;
                orbType[i] = type;
                orbPhase[i] = x * 0.013f;
                orbCursor = (i + 1) % MAX_ORBS;
                return i;
            }
        }
        return -1;
    }

    public int spawnCrystal(float x, float groundY, float height, float seed) {
        for (int i = 0; i < MAX_CRYSTALS; i++) {
            if (!cryActive[i]) {
                cryActive[i] = true;
                cryX[i] = x;
                cryY[i] = groundY;
                cryH[i] = height;
                crySeed[i] = seed;
                return i;
            }
        }
        return -1;
    }

    public int spawnUpdraft(float x, float halfWidth, float baseY) {
        for (int i = 0; i < MAX_UPDRAFTS; i++) {
            if (!upActive[i]) {
                upActive[i] = true;
                upX[i] = x;
                upHalf[i] = halfWidth;
                upBase[i] = baseY;
                return i;
            }
        }
        return -1;
    }

    public int spawnRail(float x0, float y0, float x1, float y1) {
        for (int i = 0; i < MAX_RAILS; i++) {
            if (!railActive[i]) {
                railActive[i] = true;
                railX0[i] = x0;
                railY0[i] = y0;
                railX1[i] = x1;
                railY1[i] = y1;
                return i;
            }
        }
        return -1;
    }

    /** Height of rail {@code i} at world x (the line is extrapolated outside its ends). */
    public float railY(int i, float x) {
        float u = (x - railX0[i]) / (railX1[i] - railX0[i]);
        return railY0[i] + (railY1[i] - railY0[i]) * u;
    }

    public void spawnPopup(float x, float y, int label, int value, int color) {
        int i = popCursor;
        popCursor = (popCursor + 1) % MAX_POPUPS;
        popActive[i] = true;
        popX[i] = x;
        popY[i] = y;
        popLabel[i] = label;
        popValue[i] = value;
        popColor[i] = color;
        popLife[i] = POPUP_LIFE;
    }

    public void updatePopups(float dt) {
        for (int i = 0; i < MAX_POPUPS; i++) {
            if (popActive[i]) {
                popLife[i] -= dt;
                popY[i] -= 70f * dt;
                if (popLife[i] <= 0f) {
                    popActive[i] = false;
                }
            }
        }
    }

    /** Returns everything that scrolled out behind {@code leftX} to the pools. */
    public void recycle(float leftX) {
        for (int i = 0; i < MAX_ORBS; i++) {
            if (orbActive[i] && orbX[i] < leftX) {
                orbActive[i] = false;
            }
        }
        for (int i = 0; i < MAX_CRYSTALS; i++) {
            if (cryActive[i] && cryX[i] < leftX) {
                cryActive[i] = false;
            }
        }
        for (int i = 0; i < MAX_UPDRAFTS; i++) {
            if (upActive[i] && upX[i] + upHalf[i] < leftX) {
                upActive[i] = false;
            }
        }
        for (int i = 0; i < MAX_RAILS; i++) {
            if (railActive[i] && railX1[i] < leftX) {
                railActive[i] = false;
            }
        }
    }
}
