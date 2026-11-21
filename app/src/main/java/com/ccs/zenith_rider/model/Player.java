package com.ccs.zenith_rider.model;

/**
 * State of the glider acrobat: rigid body, trick bookkeeping, the Verlet scarf chain
 * and the luminous trail history. Every array is allocated once.
 */
public final class Player {

    public static final int SCARF_NODES = 8;
    public static final float SCARF_SEGMENT = 11f;
    public static final int TRAIL_POINTS = 28;

    // ---- rigid body (world units, y grows downwards) ----
    public float x, y;
    public float prevX, prevY;
    public float vx, vy;
    public float angle, prevAngle;
    public float angVel;
    /** Backward rotation accumulated during the current air time, radians. */
    public float spin;
    public float speed;

    // ---- state flags ----
    public boolean grounded;
    public int railIdx = -1;
    public boolean gliding;
    public boolean inUpdraft;
    public boolean doomed;
    public int doomGap = -1;
    public boolean dead;

    // ---- timers ----
    public float airTime;
    public float coyote;
    public float jumpBuffer;
    public float jumpLock;
    public float invuln;
    public float wingOpen, prevWingOpen;
    public float clock;

    // ---- animation rig (driven by PhysicsSystem, read by the renderer) ----
    /** Leg compression: negative = stretched, 0 = standing, 1 = deep crouch. Spring driven. */
    public float crouch, crouchVel;
    /** 1 while tucked into a flip. */
    public float tuck;
    /** 0 on the ground, 1 in the air (blends arm poses). */
    public float airPose;
    /** Joint positions in rider-local coordinates (origin at the board, y up is negative). */
    public float hipX, hipY, shoulderX, shoulderY, headX, headY;

    // ---- per-run configuration written by the engine ----
    public float targetSpeed = 620f;
    public float gravityScale = 1f;
    public float rotSpeed = 7.6f;
    public float glideSink = 200f;
    public float magnetRadius = 84f;
    public boolean flowActive;
    public boolean shield;
    /** Orbs can be taken only while the run is live (never after a crash). */
    public boolean collecting;
    /** Chasm Tether modifier: one rescue from a chasm. */
    public boolean tether;
    /** Hover pickup active: the board floats above the surface and over crystals. */
    public boolean hover;
    public float hoverLift;
    /** Scarf length multiplier: grows with the combo. Eased towards the target every step. */
    public float scarfScale = 1f;
    public float scarfTarget = 1f;
    /** 0..1: how close the scarf is to igniting Zenith Flow (tints it gold). */
    public float scarfGlow;
    /** Strength of the weather's wind, 0..1: makes the scarf fly flatter and flutter harder. */
    public float gust;
    /** After a crash (x, y) is the centre of mass and the body tumbles around it. */
    public boolean ragdoll;
    public float settle;
    // board that flies off on a crash
    public boolean boardLoose;
    public float boardX, boardY, boardVx, boardVy, boardAngle, boardSpin;
    // rider traits
    public float accelMul = 1f;
    public float boostMul = 1f;
    public boolean smashCrystals;
    public boolean doubleJump;
    public boolean usedAirJump;
    /** Seconds left of the dash that follows a landed trick: faster, and crystals shatter. */
    public float dashTime;
    /** A rope grind happened in this air time, so the next landing on the snow counts as a trick. */
    public boolean trickAir;
    public float bodyScale = 1f;
    public int scarfColor;
    /** Attract-mode rider: can never die. */
    public boolean godMode;

    // ---- scarf (Verlet chain) ----
    public final float[] scarfX = new float[SCARF_NODES];
    public final float[] scarfY = new float[SCARF_NODES];
    private final float[] scarfOldX = new float[SCARF_NODES];
    private final float[] scarfOldY = new float[SCARF_NODES];
    /** Node positions of the previous physics step, used for render interpolation. */
    public final float[] scarfPrevX = new float[SCARF_NODES];
    public final float[] scarfPrevY = new float[SCARF_NODES];

    // ---- trail ring buffer ----
    public final float[] trailX = new float[TRAIL_POINTS];
    public final float[] trailY = new float[TRAIL_POINTS];
    public int trailHead;
    public int trailCount;

    public void reset(float startX, float startY) {
        x = prevX = startX;
        y = prevY = startY;
        vx = 420f;
        vy = 0f;
        angle = prevAngle = 0f;
        angVel = 0f;
        spin = 0f;
        speed = 420f;
        grounded = true;
        railIdx = -1;
        gliding = false;
        inUpdraft = false;
        doomed = false;
        doomGap = -1;
        dead = false;
        airTime = 0f;
        coyote = 0f;
        jumpBuffer = 0f;
        jumpLock = 0f;
        invuln = 0f;
        wingOpen = prevWingOpen = 0f;
        hover = false;
        hoverLift = 0f;
        boardLoose = false;
        ragdoll = false;
        settle = 0f;
        usedAirJump = false;
        dashTime = 0f;
        trickAir = false;
        crouch = 0.25f;
        crouchVel = 0f;
        tuck = 0f;
        airPose = 0f;
        computeRig();
        flowActive = false;
        trailHead = 0;
        trailCount = 0;
        for (int i = 0; i < SCARF_NODES; i++) {
            float sx = startX - 6f - i * SCARF_SEGMENT;
            float sy = startY - 62f + i * 1.5f;
            scarfX[i] = scarfOldX[i] = scarfPrevX[i] = sx;
            scarfY[i] = scarfOldY[i] = scarfPrevY[i] = sy;
        }
    }

    public void savePrev() {
        prevX = x;
        prevY = y;
        prevAngle = angle;
        prevWingOpen = wingOpen;
    }

    /** Recomputes the joint positions from the pose parameters. */
    public void computeRig() {
        float k = crouch;
        float lean = 0.22f + 0.45f * k + 0.45f * tuck + 0.7f * wingOpen;
        hipX = -2f - 3f * k;
        hipY = -31f + 12f * k + 5f * tuck;
        float sl = (float) Math.sin(lean);
        float cl = (float) Math.cos(lean);
        shoulderX = hipX + sl * 23f;
        shoulderY = hipY - cl * 23f;
        headX = shoulderX + sl * 9f + 2f;
        headY = shoulderY - cl * 9f - 2f;
    }

    /** World x of the neck, where the scarf is tied. */
    public float neckX() {
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        float ly = shoulderY - 3f + (ragdoll ? 34f : 0f);
        return x + (((shoulderX - 2f) * c) - (ly * s)) * bodyScale;
    }

    public float neckY() {
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        float ly = shoulderY - 3f + (ragdoll ? 34f : 0f);
        return y + (((shoulderX - 2f) * s) + (ly * c)) * bodyScale;
    }

    /** World position of the body centre (used for pickups, glow and the trail). */
    public float centerX() {
        return ragdoll ? x : x + 34f * (float) Math.sin(angle);
    }

    public float centerY() {
        return ragdoll ? y : y - 34f * (float) Math.cos(angle);
    }

    /**
     * Integrates the scarf: Verlet step with wind (opposite to the rider velocity),
     * a travelling flutter wave, light gravity, then distance constraints.
     */
    public void updateScarf(float dt) {
        clock += dt;
        if (clock > 1000f) {
            clock -= 1000f;
        }
        scarfScale += (scarfTarget - scarfScale) * Math.min(1f, 3.5f * dt);
        final float segment = SCARF_SEGMENT * scarfScale;
        final float dt2 = dt * dt;
        final float flutter = (260f + speed * 1.1f) * (1f + 1.4f * gust);
        final float windX = -vx * 2.4f - 260f - 2600f * gust;
        final float windY = -vy * 1.4f + 520f;
        for (int i = 0; i < SCARF_NODES; i++) {
            scarfPrevX[i] = scarfX[i];
            scarfPrevY[i] = scarfY[i];
        }
        for (int i = 1; i < SCARF_NODES; i++) {
            float velX = (scarfX[i] - scarfOldX[i]) * 0.92f;
            float velY = (scarfY[i] - scarfOldY[i]) * 0.92f;
            scarfOldX[i] = scarfX[i];
            scarfOldY[i] = scarfY[i];
            float wave = (float) Math.sin(clock * 13f - i * 0.95f) * flutter * (0.35f + i * 0.12f);
            scarfX[i] += velX + windX * dt2;
            scarfY[i] += velY + (windY + wave) * dt2;
        }
        final float ax = neckX();
        final float ay = neckY();
        for (int iter = 0; iter < 4; iter++) {
            scarfX[0] = ax;
            scarfY[0] = ay;
            for (int i = 1; i < SCARF_NODES; i++) {
                float dx = scarfX[i] - scarfX[i - 1];
                float dy = scarfY[i] - scarfY[i - 1];
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist < 0.0001f) {
                    dist = 0.0001f;
                    dx = -0.0001f;
                }
                float diff = (dist - segment) / dist;
                if (i == 1) {
                    scarfX[i] -= dx * diff;
                    scarfY[i] -= dy * diff;
                } else {
                    scarfX[i] -= dx * diff * 0.5f;
                    scarfY[i] -= dy * diff * 0.5f;
                    scarfX[i - 1] += dx * diff * 0.5f;
                    scarfY[i - 1] += dy * diff * 0.5f;
                }
            }
        }
        scarfX[0] = ax;
        scarfY[0] = ay;
        scarfOldX[0] = ax;
        scarfOldY[0] = ay;
    }

    public void pushTrail() {
        trailX[trailHead] = centerX();
        trailY[trailHead] = centerY();
        trailHead = (trailHead + 1) % TRAIL_POINTS;
        if (trailCount < TRAIL_POINTS) {
            trailCount++;
        }
    }
}
