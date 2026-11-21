package com.ccs.zenith_rider.physics;

import com.ccs.zenith_rider.model.Player;
import com.ccs.zenith_rider.model.WorldObjects;

/**
 * Fixed-step rider physics: sliding on the spline terrain, lift-off on crests and kickers,
 * jumping, backflips, energy-wing gliding, updrafts, rope grinding, chasms and all
 * collisions with crystals and orbs. Gameplay consequences are reported through {@link Listener}.
 */
public final class PhysicsSystem {

    public static final int CRASH_LANDING = 0;
    public static final int CRASH_CRYSTAL = 1;
    public static final int CRASH_CHASM = 2;

    /** How a crystal was destroyed. */
    public static final int SMASH_FLOW = 0;
    public static final int SMASH_SHIELD = 1;
    public static final int SMASH_STOMP = 2;
    public static final int SMASH_DASH = 3;
    private static final float STOMP_BOUNCE = 470f;

    /** The dash that follows a landed trick: its length, the speed kick and the raised cruise. */
    public static final float DASH_TIME = 1.0f;
    private static final float DASH_KICK = 110f;
    private static final float DASH_CRUISE = 1.15f;

    public static final float GRAVITY = 1700f;
    public static final float JUMP_IMPULSE = 640f;
    private static final float MIN_SPEED = 260f;
    private static final float LAND_TOLERANCE = 1.05f;
    private static final float FLIP_TOLERANCE = 1.0f;
    private static final float PI = 3.1415927f;
    private static final float TWO_PI = 6.2831855f;
    private static final float PICKUP_RADIUS = 46f;

    /** Callbacks implemented by the game engine. None of them may allocate. */
    public interface Listener {
        void onJump();

        void onLand(int flips, float impact);

        void onRailStart();

        void onOrbCollected(int orbIndex);

        void onCrystalSmashed(int crystalIndex, int how);

        void onShieldSave(int cause);

        void onGapCleared();

        void onCrash(int cause);

        /** The tumbling body hit the snow with the given normal speed. */
        void onTumble(float impact);

        /** A trick was landed on the snow and the one-second dash began. */
        void onTrickBoost();
    }

    private final TerrainGenerator terrain;
    private final WorldObjects objs;
    private Listener listener;

    public PhysicsSystem(TerrainGenerator terrain, WorldObjects objs) {
        this.terrain = terrain;
        this.objs = objs;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public static float wrapAngle(float a) {
        while (a > PI) {
            a -= TWO_PI;
        }
        while (a < -PI) {
            a += TWO_PI;
        }
        return a;
    }

    /** Advances the rider by one fixed step. */
    public void step(Player p, float dt, boolean held, boolean pressedEdge) {
        p.savePrev();
        if (p.dead) {
            stepDead(p, dt);
            updatePose(p, dt);
            p.updateScarf(dt);
            clampScarf(p);
            p.pushTrail();
            return;
        }
        if (p.invuln > 0f) {
            p.invuln -= dt;
        }
        if (p.dashTime > 0f) {
            p.dashTime -= dt;
        }
        if (pressedEdge) {
            p.jumpBuffer = 0.12f;
        } else if (p.jumpBuffer > 0f) {
            p.jumpBuffer -= dt;
        }
        if (p.jumpLock > 0f) {
            p.jumpLock -= dt;
        }
        if (p.grounded || p.railIdx >= 0) {
            p.coyote = 0.09f;
        } else if (p.coyote > 0f) {
            p.coyote -= dt;
        }
        if (p.grounded || p.railIdx >= 0) {
            p.usedAirJump = false;
        }
        if (p.jumpBuffer > 0f && p.coyote > 0f && p.jumpLock <= 0f && !p.doomed) {
            jump(p);
        } else if (pressedEdge && p.doubleJump && !p.usedAirJump && !p.grounded && p.railIdx < 0
                && p.coyote <= 0f && !p.doomed) {
            // Second jump in mid-air.
            p.usedAirJump = true;
            p.jumpBuffer = 0f;
            p.vy = (p.vy < 0f ? p.vy * 0.4f : 0f) - JUMP_IMPULSE * 0.86f;
            p.airTime = 0f;
            p.crouchVel -= 7f;
            listener.onJump();
        }
        float liftTarget = p.hover ? 48f : 0f;
        p.hoverLift += (liftTarget - p.hoverLift) * Math.min(1f, 6f * dt);

        final float g = GRAVITY * p.gravityScale;
        p.inUpdraft = false;
        if (p.railIdx >= 0) {
            stepRail(p, dt, g);
        } else if (p.grounded) {
            stepGround(p, dt, g);
        } else {
            stepAir(p, dt, g, held);
        }

        float wingTarget = p.gliding ? 1f : 0f;
        p.wingOpen += (wingTarget - p.wingOpen) * Math.min(1f, 12f * dt);

        if (!p.dead) {
            collideCrystals(p);
        }
        if (!p.dead) {
            if (p.collecting) {
                collectOrbs(p);
            }
            checkGapsCleared(p);
        }
        updatePose(p, dt);
        p.updateScarf(dt);
        clampScarf(p);
        p.pushTrail();
    }

    // ------------------------------------------------------------------ animation pose

    /** Drives the spring-based crouch / tuck / air pose used by the renderer's skeleton. */
    private void updatePose(Player p, float dt) {
        float target;
        float tuckTarget = 0f;
        float airTarget;
        if (p.dead) {
            target = 0.5f;
            airTarget = 1f;
        } else if (p.railIdx >= 0) {
            target = 0.5f;
            airTarget = 0f;
        } else if (p.grounded) {
            float sp = (p.speed - 300f) / 900f;
            if (sp < 0f) {
                sp = 0f;
            } else if (sp > 1f) {
                sp = 1f;
            }
            target = 0.22f + 0.2f * sp + 0.04f * (float) Math.sin(p.clock * 9f);
            airTarget = 0f;
        } else {
            airTarget = 1f;
            boolean flipping = p.angVel < -2.5f;
            tuckTarget = flipping ? 1f : 0f;
            target = p.gliding ? 0.35f : (flipping ? 0.9f : -0.12f);
        }
        p.crouchVel += ((target - p.crouch) * 220f - p.crouchVel * 22f) * dt;
        p.crouch += p.crouchVel * dt;
        if (p.crouch < -0.25f) {
            p.crouch = -0.25f;
        } else if (p.crouch > 1.1f) {
            p.crouch = 1.1f;
        }
        p.tuck += (tuckTarget - p.tuck) * Math.min(1f, 10f * dt);
        p.airPose += (airTarget - p.airPose) * Math.min(1f, 9f * dt);
        p.computeRig();
    }

    /** Keeps the scarf from sinking into the snow. */
    private void clampScarf(Player p) {
        for (int i = 1; i < Player.SCARF_NODES; i++) {
            float sx = p.scarfX[i];
            if (terrain.gapAt(sx) >= 0) {
                continue;
            }
            float gy = terrain.heightAt(sx) - 3f;
            if (p.scarfY[i] > gy) {
                p.scarfY[i] = gy;
            }
        }
    }

    /**
     * How far the body reaches below the board origin when the rider is rotated by {@code rel}
     * relative to the surface: nothing when upright, the full body height when upside down.
     */
    private static float bodyExtent(float rel) {
        float c = (float) Math.cos(rel);
        float s = (float) Math.sin(rel);
        float ext = (c < 0f ? -68f * c : 0f) + 16f * (s < 0f ? -s : s);
        return ext;
    }

    // ------------------------------------------------------------------ jump

    private void jump(Player p) {
        float ty;
        if (p.railIdx >= 0) {
            int r = p.railIdx;
            float dx = objs.railX1[r] - objs.railX0[r];
            float dy = objs.railY1[r] - objs.railY0[r];
            ty = dy / (float) Math.sqrt(dx * dx + dy * dy);
        } else {
            float slope = terrain.slopeAt(p.x);
            ty = slope / (float) Math.sqrt(1f + slope * slope);
        }
        // Uphill surfaces (ty < 0) work as ramps and give a stronger jump.
        float boost = 1f - ty * 1.1f;
        if (boost < 0.8f) {
            boost = 0.8f;
        } else if (boost > 1.5f) {
            boost = 1.5f;
        }
        float impulse = JUMP_IMPULSE * boost * (p.hover ? 1.12f : 1f);
        float vy = p.vy - impulse;
        float minUp = -impulse * 0.62f;
        if (vy > minUp) {
            vy = minUp;
        }
        p.vy = vy;
        if (p.railIdx >= 0) {
            // Jumping off a rope is a trick: landing it on the snow starts a dash.
            p.trickAir = true;
        }
        p.grounded = false;
        p.railIdx = -1;
        p.coyote = 0f;
        p.jumpBuffer = 0f;
        p.jumpLock = 0.18f;
        p.airTime = 0f;
        p.spin = 0f;
        p.angVel = 0f;
        p.crouchVel -= 7f;
        listener.onJump();
    }

    private void leaveGround(Player p) {
        p.grounded = false;
        p.airTime = 0f;
        p.spin = 0f;
        p.angVel = 0f;
    }

    // ------------------------------------------------------------------ ground

    private void stepGround(Player p, float dt, float g) {
        float slope = terrain.slopeAt(p.x);
        float inv = 1f / (float) Math.sqrt(1f + slope * slope);
        float tx = inv;
        float ty = slope * inv;

        p.vy += g * dt;
        float s = p.vx * tx + p.vy * ty;
        float target = p.dashTime > 0f ? p.targetSpeed * DASH_CRUISE : p.targetSpeed;
        s += (target - s) * (s < target ? 1.1f * p.accelMul : 0.5f) * dt;
        if (s < MIN_SPEED) {
            s = MIN_SPEED;
        }
        p.vx = s * tx;
        p.vy = s * ty;
        p.speed = s;

        float nx = p.x + p.vx * dt;
        float curvature = terrain.curvatureAt(p.x);
        boolean overGap = terrain.gapAt(nx) >= 0;
        // Lift-off: the centripetal acceleration needed to follow a crest exceeds gravity.
        boolean liftOff = curvature > 0f && s * s * curvature * inv * inv * inv > g * inv * 1.05f;
        if (overGap || liftOff || terrain.rampEndBetween(p.x, nx)) {
            p.x = nx;
            p.y += p.vy * dt;
            leaveGround(p);
            return;
        }
        p.x = nx;
        p.y = terrain.heightAt(nx) - p.hoverLift;
        float aim = (float) Math.atan(slope);
        p.angle += wrapAngle(aim - p.angle) * Math.min(1f, 16f * dt);
        p.gliding = false;
    }

    // ------------------------------------------------------------------ rail

    private void stepRail(Player p, float dt, float g) {
        int r = p.railIdx;
        float dx = objs.railX1[r] - objs.railX0[r];
        float dy = objs.railY1[r] - objs.railY0[r];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float tx = dx / len;
        float ty = dy / len;
        float s = p.speed;
        s += g * ty * dt + 60f * dt;
        float target = p.targetSpeed * 1.08f;
        if (s < target) {
            s += (target - s) * 1.4f * dt;
        }
        p.speed = s;
        p.vx = s * tx;
        p.vy = s * ty;
        p.x += p.vx * dt;
        p.y = objs.railY(r, p.x);
        float aim = (float) Math.atan2(dy, dx);
        p.angle += wrapAngle(aim - p.angle) * Math.min(1f, 16f * dt);
        p.gliding = false;
        if (p.x >= objs.railX1[r] || !objs.railActive[r]) {
            p.railIdx = -1;
            p.trickAir = true;
            leaveGround(p);
        }
    }

    // ------------------------------------------------------------------ air

    private void stepAir(Player p, float dt, float g, boolean held) {
        p.airTime += dt;
        boolean canAct = held && p.airTime > 0.1f && !p.doomed;
        p.gliding = canAct && p.flowActive;

        if (p.gliding) {
            // Energy wing: weak gravity, sink speed converges to the glide sink rate.
            p.vy += g * 0.3f * dt;
            if (p.vy > p.glideSink) {
                p.vy += (p.glideSink - p.vy) * Math.min(1f, 7f * dt);
            }
            if (p.vx < p.targetSpeed) {
                p.vx += (p.targetSpeed - p.vx) * 1.5f * dt;
            }
            float aim = (float) Math.atan2(p.vy, p.vx) * 0.7f;
            p.angle += wrapAngle(aim - p.angle) * Math.min(1f, 9f * dt);
            p.angVel = 0f;
        } else {
            p.vy += g * dt;
            if (canAct) {
                p.angVel += (-p.rotSpeed - p.angVel) * Math.min(1f, 10f * dt);
            } else {
                p.angVel += (0f - p.angVel) * Math.min(1f, 8f * dt);
                // Gentle self-levelling towards the surface below makes landings forgiving.
                float aim = terrain.gapAt(p.x) >= 0 ? 0.1f : (float) Math.atan(terrain.slopeAt(p.x)) * 0.6f;
                float level = wrapAngle(aim - p.angle) * Math.min(1f, 1.8f * dt);
                p.angle += level;
                if (level < 0f) {
                    p.spin -= level;
                }
            }
            float rot = p.angVel * dt;
            p.angle += rot;
            p.spin += rot < 0f ? -rot : rot;
        }

        // Updraft columns push the rider upwards; the wing catches far more lift.
        for (int u = 0; u < WorldObjects.MAX_UPDRAFTS; u++) {
            if (!objs.upActive[u]) {
                continue;
            }
            float dx = p.x - objs.upX[u];
            if (dx > -objs.upHalf[u] && dx < objs.upHalf[u]
                    && p.y < objs.upBase[u] && p.y > objs.upBase[u] - WorldObjects.UPDRAFT_HEIGHT) {
                p.vy -= (p.gliding ? 3300f : 2300f) * dt;
                if (p.vy < -640f) {
                    p.vy = -640f;
                }
                p.inUpdraft = true;
            }
        }
        if (p.vy > 1700f) {
            p.vy = 1700f;
        }

        p.x += p.vx * dt;
        p.y += p.vy * dt;
        p.speed = (float) Math.sqrt(p.vx * p.vx + p.vy * p.vy);

        if (p.doomed) {
            stepDoomed(p);
            return;
        }

        // ---- landing on a rope line (only from above, while descending) ----
        if (p.vy > 0f) {
            for (int r = 0; r < WorldObjects.MAX_RAILS; r++) {
                if (!objs.railActive[r] || p.x < objs.railX0[r] || p.x > objs.railX1[r]) {
                    continue;
                }
                float ry = objs.railY(r, p.x);
                float prevRy = objs.railY(r, p.prevX);
                if (p.prevY <= prevRy + 6f && p.y >= ry) {
                    float dx = objs.railX1[r] - objs.railX0[r];
                    float dy = objs.railY1[r] - objs.railY0[r];
                    float len = (float) Math.sqrt(dx * dx + dy * dy);
                    float railAngle = (float) Math.atan2(dy, dx);
                    float diff = wrapAngle(p.angle - railAngle);
                    if (diff > LAND_TOLERANCE || diff < -LAND_TOLERANCE) {
                        if (!survive(p, CRASH_LANDING)) {
                            return;
                        }
                        diff = 0f;
                    }
                    int flips = (int) ((p.spin + FLIP_TOLERANCE) / TWO_PI);
                    float s = (p.vx * dx + p.vy * dy) / len;
                    if (s < 420f) {
                        s = 420f;
                    }
                    p.speed = s + flips * 40f * p.boostMul;
                    p.y = ry;
                    p.railIdx = r;
                    p.gliding = false;
                    p.angVel = 0f;
                    p.angle = railAngle + diff;
                    p.prevAngle = p.angle;
                    p.spin = 0f;
                    listener.onLand(flips, 0f);
                    listener.onRailStart();
                    return;
                }
            }
        }

        // ---- ground / chasm ----
        int gap = terrain.gapAt(p.x);
        if (gap >= 0) {
            if (p.vy > 0f && p.y > terrain.gapLandingY(gap) + 40f) {
                p.doomed = true;
                p.doomGap = gap;
                stepDoomed(p);
            }
            return;
        }
        float gy = terrain.heightAt(p.x) - p.hoverLift;
        if (p.invuln > 0f && p.vy < 0f) {
            return;
        }
        if (p.y >= gy) {
            land(p, gy);
            return;
        }
        // Head or back hits the snow before the board does: that is a crash, not a landing.
        float aim = (float) Math.atan(terrain.slopeAt(p.x));
        float rel = wrapAngle(p.angle - aim);
        if ((rel > 1.3f || rel < -1.3f) && p.y + bodyExtent(rel) >= gy) {
            if (survive(p, CRASH_LANDING)) {
                p.angle = aim;
                p.prevAngle = aim;
            }
        }
    }

    private void land(Player p, float gy) {
        float slope = terrain.slopeAt(p.x);
        float aim = (float) Math.atan(slope);
        float diff = wrapAngle(p.angle - aim);
        if (diff > LAND_TOLERANCE || diff < -LAND_TOLERANCE) {
            if (!survive(p, CRASH_LANDING)) {
                return;
            }
            diff = 0f;
        }
        int flips = (int) ((p.spin + FLIP_TOLERANCE) / TWO_PI);
        float inv = 1f / (float) Math.sqrt(1f + slope * slope);
        float tx = inv;
        float ty = slope * inv;
        float s = p.vx * tx + p.vy * ty;
        float impact = -p.vx * ty + p.vy * tx;
        if (s < 300f) {
            s = 300f;
        }
        // A landed trick - a flip, or coming off a rope - kicks the rider forward into a dash.
        final boolean dash = flips > 0 || p.trickAir;
        p.trickAir = false;
        if (dash) {
            s += (DASH_KICK + 30f * (flips > 1 ? flips - 1 : 0)) * p.boostMul;
            p.dashTime = DASH_TIME;
        }
        p.vx = s * tx;
        p.vy = s * ty;
        p.speed = s;
        p.y = gy;
        p.grounded = true;
        p.gliding = false;
        p.angVel = 0f;
        p.angle = aim + diff;
        p.prevAngle = p.angle;
        p.spin = 0f;
        p.crouchVel += 2f + (impact > 1000f ? 1000f : (impact < 0f ? 0f : impact)) * 0.008f;
        listener.onLand(flips, impact);
        if (dash) {
            listener.onTrickBoost();
        }
    }

    /** The rider dropped below the landing edge of a chasm. */
    private void stepDoomed(Player p) {
        int gap = p.doomGap;
        float wall = terrain.gapX1[gap] - 40f;
        if (p.x > wall) {
            p.x = wall;
            p.vx = 0f;
        }
        float edge = terrain.gapLandingY(gap);
        if (p.godMode || p.tether) {
            survive(p, CRASH_CHASM);
            return;
        }
        if (p.y > edge + 280f) {
            kill(p, CRASH_CHASM);
        }
    }

    /**
     * Resolves a fatal event. Returns true when the rider survives (attract mode, invulnerability
     * or the Aegis Shield); otherwise marks the rider dead and reports the crash.
     */
    private boolean survive(Player p, int cause) {
        boolean free = p.godMode || (p.invuln > 0f && cause != CRASH_CHASM);
        boolean protectedOnce = cause == CRASH_CHASM ? p.tether : p.shield;
        if (!free && !protectedOnce) {
            kill(p, cause);
            return false;
        }
        if (!free) {
            if (cause == CRASH_CHASM) {
                p.tether = false;
            } else {
                p.shield = false;
            }
            p.invuln = 2.2f;
            listener.onShieldSave(cause);
        }
        if (cause == CRASH_CHASM) {
            // The shield throws the rider back up out of the chasm.
            p.doomed = false;
            p.doomGap = -1;
            p.vy = -1320f;
            if (p.vx < 540f) {
                p.vx = 540f;
            }
            p.angle = 0f;
            p.prevAngle = 0f;
            p.angVel = 0f;
            p.spin = 0f;
            p.airTime = 0f;
            if (p.invuln < 1.6f) {
                p.invuln = 1.6f;
            }
        } else if (cause == CRASH_LANDING) {
            p.angVel = 0f;
            p.spin = 0f;
        }
        return true;
    }

    // ------------------------------------------------------------------ collisions

    private void collideCrystals(Player p) {
        for (int c = 0; c < WorldObjects.MAX_CRYSTALS; c++) {
            if (!objs.cryActive[c] || p.hoverLift > 24f) {
                continue;
            }
            float dx = p.x - objs.cryX[c];
            if (dx > -34f && dx < 34f && p.y > objs.cryY[c] - objs.cryH[c] + 12f) {
                // Coming down onto the crystal from the air: it shatters under the board and
                // gives a small bounce. Only running into its side on the snow is fatal.
                boolean airborne = !p.grounded && p.railIdx < 0;
                boolean fromAbove = airborne && p.vy > 0f
                        && (p.prevY <= objs.cryY[c] - objs.cryH[c] * 0.35f || p.y <= objs.cryY[c] - objs.cryH[c] * 0.35f);
                if (p.flowActive || (p.smashCrystals && p.speed > 560f)) {
                    listener.onCrystalSmashed(c, SMASH_FLOW);
                } else if (p.dashTime > 0f) {
                    listener.onCrystalSmashed(c, SMASH_DASH);
                } else if (fromAbove) {
                    p.vy = -STOMP_BOUNCE;
                    p.gliding = false;
                    listener.onCrystalSmashed(c, SMASH_STOMP);
                } else if (survive(p, CRASH_CRYSTAL)) {
                    listener.onCrystalSmashed(c, SMASH_SHIELD);
                } else {
                    return;
                }
            }
        }
    }

    /**
     * Orbs are taken only by touching them. The magnet power-up (p.magnetRadius > 0) also pulls
     * orbs within its radius towards the rider; without it nothing moves.
     */
    private void collectOrbs(Player p) {
        final float cx = p.centerX();
        final float cy = p.centerY();
        final float radius = p.magnetRadius;
        final float reach = radius > PICKUP_RADIUS ? radius : PICKUP_RADIUS;
        final float pick2 = PICKUP_RADIUS * PICKUP_RADIUS;
        for (int o = 0; o < WorldObjects.MAX_ORBS; o++) {
            if (!objs.orbActive[o]) {
                continue;
            }
            float dx = cx - objs.orbX[o];
            if (dx > reach || dx < -reach) {
                continue;
            }
            float dy = cy - objs.orbY[o];
            float d2 = dx * dx + dy * dy;
            if (d2 < pick2) {
                listener.onOrbCollected(o);
            } else if (radius > 0f && d2 < radius * radius) {
                float d = (float) Math.sqrt(d2);
                float stepLen = 760f * (1f - d / radius) + 0.9f * Math.abs(p.vx);
                // frame-rate independent pull: the orb reaches the rider in a few steps
                stepLen = stepLen * (1f / 60f);
                if (stepLen > d) {
                    stepLen = d;
                }
                objs.orbX[o] += dx / d * stepLen;
                objs.orbY[o] += dy / d * stepLen;
            }
        }
    }

    private void checkGapsCleared(Player p) {
        if (p.doomed) {
            return;
        }
        for (int k = 0; k < TerrainGenerator.MAX_GAPS; k++) {
            if (!terrain.gapCounted[k] && p.x > terrain.gapX1[k]) {
                terrain.gapCounted[k] = true;
                listener.onGapCleared();
            }
        }
    }

    // ------------------------------------------------------------------ crash tumble

    /** Turns the rider into a tumbling body that rotates around its centre of mass. */
    private void kill(Player p, int cause) {
        float cx = p.centerX();
        float cy = p.centerY();
        p.dead = true;
        p.gliding = false;
        p.ragdoll = true;
        p.grounded = false;
        p.railIdx = -1;
        p.settle = 0f;
        p.dashTime = 0f;
        p.trickAir = false;
        p.x = cx;
        p.y = cy;
        p.prevX = cx;
        p.prevY = cy;
        listener.onCrash(cause);
    }

    /** Detaches the board from the rider and sends it spinning. */
    public void releaseBoard(Player p) {
        p.boardLoose = true;
        p.boardX = p.x;
        p.boardY = p.y - 6f;
        p.boardVx = p.vx * 1.15f + 90f;
        p.boardVy = p.vy - 420f;
        p.boardAngle = p.angle;
        p.boardSpin = 13f;
    }

    private void stepBoard(Player p, float dt) {
        p.boardVy += GRAVITY * dt;
        p.boardX += p.boardVx * dt;
        p.boardY += p.boardVy * dt;
        p.boardAngle += p.boardSpin * dt;
        if (terrain.gapAt(p.boardX) >= 0) {
            return;
        }
        float gy = terrain.heightAt(p.boardX) - 5f;
        if (p.boardY >= gy && p.boardY < gy + 140f) {
            p.boardY = gy;
            if (p.boardVy > 0f) {
                p.boardVy = -p.boardVy * 0.42f;
                p.boardSpin *= 0.55f;
                p.boardVx *= 0.8f;
            }
            if (p.boardVy > -90f) {
                p.boardVy = 0f;
                p.boardVx -= p.boardVx * Math.min(1f, 1.8f * dt);
                float aim = (float) Math.atan(terrain.slopeAt(p.boardX));
                p.boardSpin = 0f;
                p.boardAngle += wrapAngle(aim - p.boardAngle) * Math.min(1f, 10f * dt);
            }
        }
    }

    /**
     * Rag-doll tumble. (x, y) is the centre of mass; the body is a capsule whose reach towards
     * the snow depends on its rotation, so it cartwheels: every contact bounces it, converts
     * sliding speed into spin (rolling) and sprays snow, until friction lays it flat.
     */
    private void stepDead(Player p, float dt) {
        if (p.boardLoose) {
            stepBoard(p, dt);
        }
        p.gliding = false;
        p.wingOpen += (0f - p.wingOpen) * Math.min(1f, 12f * dt);
        p.vy += GRAVITY * dt;
        if (p.vy > 1700f) {
            p.vy = 1700f;
        }
        p.x += p.vx * dt;
        p.y += p.vy * dt;
        p.angle += p.angVel * dt;
        p.speed = (float) Math.sqrt(p.vx * p.vx + p.vy * p.vy);
        p.grounded = false;
        if (p.doomed) {
            if (p.doomGap >= 0) {
                float wall = terrain.gapX1[p.doomGap] - 40f;
                if (p.x > wall) {
                    p.x = wall;
                    p.vx = 0f;
                }
            }
            return;
        }
        int gap = terrain.gapAt(p.x);
        if (gap >= 0) {
            if (p.y > terrain.gapLandingY(gap) + 40f) {
                p.doomed = true;
                p.doomGap = gap;
            }
            return;
        }
        float slope = terrain.slopeAt(p.x);
        float aim = (float) Math.atan(slope);
        float rel = wrapAngle(p.angle - aim);
        float c = (float) Math.cos(rel);
        float reach = 13f + 25f * (c < 0f ? -c : c);
        float contact = terrain.heightAt(p.x) - reach;
        if (p.y < contact) {
            return;
        }
        p.y = contact;
        float inv = 1f / (float) Math.sqrt(1f + slope * slope);
        float tx = inv;
        float ty = slope * inv;
        float vt = p.vx * tx + p.vy * ty;
        float vn = -p.vx * ty + p.vy * tx;
        if (vn > 110f) {
            // a real impact: bounce, lose speed, and turn sliding into rolling
            listener.onTumble(vn);
            vn = -vn * 0.36f;
            vt *= 0.80f;
            float roll = vt / 34f;
            if (roll > 12f) {
                roll = 12f;
            }
            p.angVel += (roll - p.angVel) * 0.6f;
        } else {
            if (vn > 0f) {
                vn = 0f;
            }
            p.grounded = true;
            float abs = vt < 0f ? -vt : vt;
            if (abs > 200f) {
                // still fast: keeps rolling while friction bleeds the speed off
                vt -= vt * Math.min(1f, 2.0f * dt);
                float roll = vt / 34f;
                if (roll > 12f) {
                    roll = 12f;
                }
                p.angVel += (roll - p.angVel) * Math.min(1f, 4f * dt);
            } else {
                // slow: skid to a halt and settle lying flat on the snow
                vt -= vt * Math.min(1f, 7f * dt);
                p.angVel += (0f - p.angVel) * Math.min(1f, 9f * dt);
                float lie = rel < 0f ? -1.5708f : 1.5708f;
                p.angle += (lie - rel) * Math.min(1f, 7f * dt);
                p.settle += dt;
            }
        }
        p.vx = vt * tx - vn * ty;
        p.vy = vt * ty + vn * tx;
    }
}
