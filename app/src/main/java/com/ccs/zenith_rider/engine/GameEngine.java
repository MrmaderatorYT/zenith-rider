package com.ccs.zenith_rider.engine;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import com.ccs.zenith_rider.audio.ProceduralAudioEngine;
import com.ccs.zenith_rider.graphics.EnvironmentPalette;
import com.ccs.zenith_rider.graphics.ParticlePool;
import com.ccs.zenith_rider.graphics.ProceduralRenderer;
import com.ccs.zenith_rider.graphics.WeatherSystem;
import com.ccs.zenith_rider.model.MissionManager;
import com.ccs.zenith_rider.model.Player;
import com.ccs.zenith_rider.model.UpgradeManager;
import com.ccs.zenith_rider.model.WorldObjects;
import com.ccs.zenith_rider.physics.FastRandom;
import com.ccs.zenith_rider.physics.PhysicsSystem;
import com.ccs.zenith_rider.physics.TerrainGenerator;
import com.ccs.zenith_rider.storage.SaveManager;
import com.ccs.zenith_rider.ui.HudAndMenuRenderer;
import com.ccs.zenith_rider.ui.Strings;

/**
 * Game state machine and rules. Owns every subsystem, converts physics events into score,
 * combo, Zenith Flow, missions, particles and sound, and drives the fixed-step simulation.
 *
 * update() and render() run on the game thread and do not allocate: all state lives in
 * primitive fields and preallocated pools.
 */
public final class GameEngine implements PhysicsSystem.Listener {

    public static final int ST_MENU = 0;
    public static final int ST_SHOP = 1;
    public static final int ST_MISSIONS = 2;
    public static final int ST_PERKS = 3;
    public static final int ST_PLAY = 4;
    public static final int ST_PAUSE = 5;
    public static final int ST_DYING = 6;
    public static final int ST_OVER = 7;

    public static final float FIXED_TIMESTEP = 1.0f / 60.0f;
    public static final float VIRTUAL_HEIGHT = 720f;

    public static final int TOUCH_DOWN = 0;
    public static final int TOUCH_UP = 1;
    public static final int KEY_BACK = 2;

    public static final int MAX_COMBO = 10;
    public static final int ORB_POINTS = 10;
    public static final int GAP_POINTS = 250;
    public static final int GOAL_POINTS = 1000;
    private static final float COMBO_TIME = 6f;
    private static final float START_X = 640f;
    private static final int EVENT_CAPACITY = 32;

    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_TEAL = 0xFF7CFFF0;
    private static final int COLOR_GOLD = 0xFFFFD86B;

    // ---- subsystems ----
    public final SaveManager save;
    public final Strings strings;
    public final UpgradeManager upgrades;
    public final MissionManager missions;
    public final WorldObjects objs = new WorldObjects();
    public final TerrainGenerator terrain = new TerrainGenerator(objs);
    public final PhysicsSystem physics = new PhysicsSystem(terrain, objs);
    public final Player player = new Player();
    public final ParticlePool particles = new ParticlePool();
    public final EnvironmentPalette env = new EnvironmentPalette();
    public final WeatherSystem weather = new WeatherSystem();
    public final ProceduralAudioEngine audio = new ProceduralAudioEngine();
    private final ProceduralRenderer renderer;
    private final HudAndMenuRenderer hud;
    private final FastRandom rng = new FastRandom((int) System.nanoTime());
    private final Paint fadePaint = new Paint();

    // ---- screen ----
    public int state = ST_MENU;
    public float vw = 1280f;
    public float vh = VIRTUAL_HEIGHT;
    private float scale = 1f;
    private boolean sized;

    // ---- run state (read by the HUD) ----
    public float distance;
    public int score;
    public int orbsRun;
    public int combo = 1;
    public int flipsRun;
    public boolean flowActive;
    public float flowMeter;
    public float flowTime;
    public float flowDuration = 7f;
    public float magnetTime;
    public float magnetDuration = 6f;
    public boolean doubleOrbs;
    // score breakdown shown on the results screen
    public int trickScore;
    public int gapsRun;
    public int goalsRun;
    public int bestCombo = 1;
    public float distPoints;
    /** Seconds since the results screen opened (drives its entrance animation). */
    public float overTime;
    private float trickAcc;
    private float hitStop;
    private float crashFlash;
    private float realDt;
    public float hoverTime;
    public float hoverDuration = 5f;
    /** Shop tab: 0 upgrades, 1 modifiers, 2 riders. */
    public int shopTab;
    private float riderSpeed = 1f;
    public boolean newBestDistance;
    public boolean newBestScore;
    public float bannerTime;
    public int bannerReward;
    public int hintId = -1;
    public float noFundsTimer;
    public float time;
    public int pressedBtn = -1;

    private float comboTimer;
    private float scoreAcc, flowDistAcc, glideDistAcc;
    private float hintTimer;
    private float dyingTimer;
    private float dyingTotal = 2.3f;
    private float fade;
    private float flowGlow;
    private float shake;
    private float speedFx;
    private float timeScale = 1f;
    private int stepCounter;

    // ---- camera ----
    private float camY, prevCamY;
    private float zoom = 0.9f, prevZoom = 0.9f;

    // ---- input queue (filled by the UI thread, drained by the game thread) ----
    private final Object inputLock = new Object();
    private final int[] evType = new int[EVENT_CAPACITY];
    private final float[] evX = new float[EVENT_CAPACITY];
    private final float[] evY = new float[EVENT_CAPACITY];
    private int evCount;
    private final int[] workType = new int[EVENT_CAPACITY];
    private final float[] workX = new float[EVENT_CAPACITY];
    private final float[] workY = new float[EVENT_CAPACITY];
    private boolean held;
    private boolean pressEdge;

    private Runnable exitCallback;

    /** Preview aids set from launch extras: a frozen hour and weather, and a hidden menu. */
    private boolean atmosphereFrozen;
    private boolean demoMode;

    public GameEngine(Context context) {
        save = new SaveManager(context);
        strings = new Strings(context);
        if (save.language < 0) {
            save.language = Strings.deviceLanguage();
        }
        strings.lang = save.language;
        upgrades = new UpgradeManager(save);
        missions = new MissionManager(save);
        renderer = new ProceduralRenderer(env, weather, terrain, objs, particles, player, strings);
        // Every session opens at a different hour, never in the dead of night.
        env.cycle = rng.range(0f, 4.2f);
        env.update(0f);
        hud = new HudAndMenuRenderer();
        physics.setListener(this);
        audio.setMuted(!save.soundOn);
        fadePaint.setColor(0xFF070B24);
        enterAttract();
    }

    public void setExitCallback(Runnable callback) {
        exitCallback = callback;
    }

    /** Called from the game thread when the surface size is known or changes. */
    public void resize(int widthPx, int heightPx) {
        scale = heightPx / VIRTUAL_HEIGHT;
        vw = widthPx / scale;
        vh = VIRTUAL_HEIGHT;
        env.build(vw, vh);
        renderer.build(vw, vh);
        hud.layout(vw, vh);
        sized = true;
    }

    /**
     * Preview aid for screenshots and tuning (see AGENTS.md): pins the day cycle and the weather
     * and can hide the menu so the attract-mode world fills the screen.
     *
     * @param cycle       0..6 position in the day cycle, or a negative value to leave it running
     * @param weatherKind one of the WeatherSystem kinds, or a negative value to leave it running
     */
    public void debugAtmosphere(float cycle, int weatherKind, boolean demo) {
        if (cycle >= 0f) {
            env.cycle = cycle % EnvironmentPalette.PHASES;
            atmosphereFrozen = true;
        }
        if (weatherKind >= 0) {
            weather.force(weatherKind);
        }
        demoMode = demo;
        env.update(0f);
        env.applyWeather(weather.cloud, weather.fog, weather.storm);
    }

    /** Advances the day cycle and the weather and feeds both into the palette and the sound. */
    private void stepAtmosphere(float dt) {
        weather.update(dt, vw, vh);
        env.update(atmosphereFrozen ? 0f : dt);
        env.applyWeather(weather.cloud, weather.fog, weather.storm);
        if (weather.pollThunder()) {
            audio.playThunder();
        }
        float gale = weather.wind * 0.8f + weather.snow * 0.2f;
        audio.setWeather(weather.rain, gale > 1f ? 1f : gale);
        player.gust = weather.wind;
    }

    // ------------------------------------------------------------------ host lifecycle

    public void onHostResume() {
        audio.start();
    }

    public void onHostPause() {
        synchronized (inputLock) {
            if (state == ST_PLAY) {
                state = ST_PAUSE;
            }
            held = false;
        }
        audio.setRide(0f, 0f, 0f, 0f);
        audio.stop();
        save.save();
    }

    // ------------------------------------------------------------------ input

    /** Thread-safe: called from the UI thread with raw pixel coordinates. */
    public void queueEvent(int type, float px, float py) {
        synchronized (inputLock) {
            if (evCount < EVENT_CAPACITY) {
                evType[evCount] = type;
                evX[evCount] = px;
                evY[evCount] = py;
                evCount++;
            }
        }
    }

    public void processInput() {
        int n;
        synchronized (inputLock) {
            n = evCount;
            for (int i = 0; i < n; i++) {
                workType[i] = evType[i];
                workX[i] = evX[i];
                workY[i] = evY[i];
            }
            evCount = 0;
        }
        for (int i = 0; i < n; i++) {
            float x = workX[i] / scale;
            float y = workY[i] / scale;
            switch (workType[i]) {
                case TOUCH_DOWN:
                    if (state == ST_PLAY) {
                        if (hud.hit(state, x, y) == HudAndMenuRenderer.B_PAUSE) {
                            state = ST_PAUSE;
                            held = false;
                            audio.playUi();
                        } else {
                            held = true;
                            pressEdge = true;
                        }
                    } else if (state != ST_DYING) {
                        pressedBtn = hud.hit(state, x, y);
                    }
                    break;
                case TOUCH_UP:
                    held = false;
                    if (state != ST_PLAY && state != ST_DYING && pressedBtn >= 0
                            && hud.hit(state, x, y) == pressedBtn) {
                        onButton(pressedBtn);
                    }
                    pressedBtn = -1;
                    break;
                default:
                    onBack();
                    break;
            }
        }
    }

    private void onBack() {
        switch (state) {
            case ST_PLAY:
                state = ST_PAUSE;
                held = false;
                break;
            case ST_PAUSE:
                state = ST_PLAY;
                break;
            case ST_SHOP:
            case ST_MISSIONS:
            case ST_PERKS:
                state = ST_MENU;
                break;
            case ST_OVER:
                leaveResults();
                enterAttract();
                break;
            case ST_MENU:
                if (exitCallback != null) {
                    exitCallback.run();
                }
                break;
            default:
                break;
        }
    }

    private void onButton(int id) {
        audio.playUi();
        switch (id) {
            case HudAndMenuRenderer.B_PLAY:
                if (upgrades.ownsAnyPerk()) {
                    state = ST_PERKS;
                } else {
                    startRun();
                }
                break;
            case HudAndMenuRenderer.B_SHOP:
                state = ST_SHOP;
                break;
            case HudAndMenuRenderer.B_MISSIONS:
                state = ST_MISSIONS;
                break;
            case HudAndMenuRenderer.B_LANG:
                strings.lang = strings.lang == Strings.LANG_EN ? Strings.LANG_UK : Strings.LANG_EN;
                save.language = strings.lang;
                save.save();
                break;
            case HudAndMenuRenderer.B_SOUND:
            case HudAndMenuRenderer.B_PAUSE_SOUND:
                save.soundOn = !save.soundOn;
                audio.setMuted(!save.soundOn);
                save.save();
                break;
            case HudAndMenuRenderer.B_BACK:
            case HudAndMenuRenderer.B_SHOP_BACK:
                state = ST_MENU;
                break;
            case HudAndMenuRenderer.B_START:
                startRun();
                break;
            case HudAndMenuRenderer.B_RESUME:
                state = ST_PLAY;
                break;
            case HudAndMenuRenderer.B_QUIT:
                creditRun();
                leaveResults();
                enterAttract();
                break;
            case HudAndMenuRenderer.B_RETRY:
                leaveResults();
                if (upgrades.ownsAnyPerk()) {
                    enterAttract();
                    state = ST_PERKS;
                } else {
                    startRun();
                }
                break;
            case HudAndMenuRenderer.B_MENU:
                leaveResults();
                enterAttract();
                break;
            default:
                if (id >= HudAndMenuRenderer.B_TAB0 && id < HudAndMenuRenderer.B_TAB0 + HudAndMenuRenderer.TABS) {
                    shopTab = id - HudAndMenuRenderer.B_TAB0;
                    hud.setShopTab(shopTab);
                } else if (id >= HudAndMenuRenderer.B_CARD0 && id < HudAndMenuRenderer.B_CARD0 + HudAndMenuRenderer.CARDS) {
                    onShopCard(id - HudAndMenuRenderer.B_CARD0);
                } else if (id >= HudAndMenuRenderer.B_SEL0 && id < HudAndMenuRenderer.B_SEL0 + SaveManager.PERK_COUNT) {
                    upgrades.toggleSelected(id - HudAndMenuRenderer.B_SEL0);
                }
                break;
        }
    }

    private void onShopCard(int i) {
        boolean ok = true;
        if (shopTab == 0 && i == SaveManager.UPGRADE_COUNT) {
            if (upgrades.flowOwned()) {
                return;
            }
            ok = upgrades.buyFlow();
        } else if (shopTab == 0) {
            if (upgrades.upgradeCost(i) < 0) {
                return;
            }
            ok = upgrades.buyUpgrade(i);
        } else if (shopTab == 1) {
            ok = upgrades.buyPerk(i);
        } else if (upgrades.riderOwned(i)) {
            upgrades.selectRider(i);
            applyRider();
            return;
        } else {
            ok = upgrades.buyRider(i);
            if (ok) {
                applyRider();
            }
        }
        if (ok) {
            audio.playTrick();
        } else {
            noFundsTimer = 1.6f;
        }
    }

    // ------------------------------------------------------------------ run lifecycle

    private void resetWorld() {
        particles.clear();
        terrain.wispFlock = upgrades.wispFlock();
        objs.clear();
        terrain.reset(rng.nextInt());
        player.reset(START_X, terrain.heightAt(START_X));
        player.gravityScale = 1f;
        player.shield = false;
        player.tether = false;
        hoverTime = 0f;
        player.scarfTarget = 1f;
        player.scarfScale = 1f;
        player.scarfGlow = 0f;
        applyRider();
        player.glideSink = upgrades.glideSink();
        camY = prevCamY = player.y;
        zoom = prevZoom = 0.9f;
        flowActive = false;
        flowMeter = 0f;
        flowTime = 0f;
        magnetTime = 0f;
        combo = 1;
        comboTimer = 0f;
        hintId = -1;
        held = false;
        pressEdge = false;
    }

    /** Copies the selected rider's traits into the player. */
    private void applyRider() {
        int r = upgrades.rider();
        riderSpeed = UpgradeManager.RIDER_SPEED[r];
        player.rotSpeed = upgrades.rotationSpeed() * UpgradeManager.RIDER_ROTATION[r];
        player.accelMul = UpgradeManager.RIDER_ACCEL[r];
        player.boostMul = UpgradeManager.RIDER_BOOST[r];
        player.smashCrystals = UpgradeManager.RIDER_SMASH[r];
        player.doubleJump = UpgradeManager.RIDER_DOUBLE_JUMP[r];
        player.bodyScale = UpgradeManager.RIDER_SCALE[r];
        player.scarfColor = UpgradeManager.RIDER_SCARF[r];
    }

    /** Menu background: an immortal autopilot rider cruising through the world. */
    private void enterAttract() {
        resetWorld();
        player.godMode = true;
        upgrades.clearActive();
        doubleOrbs = false;
        state = ST_MENU;
        fade = 1f;
    }

    private void startRun() {
        upgrades.consumeSelected();
        resetWorld();
        player.godMode = false;
        player.shield = upgrades.active[UpgradeManager.PERK_SHIELD];
        player.tether = upgrades.active[UpgradeManager.PERK_TETHER];
        doubleOrbs = upgrades.active[UpgradeManager.PERK_DOUBLE];
        player.gravityScale = upgrades.active[UpgradeManager.PERK_LUNAR] ? 0.62f : 1f;
        distance = 0f;
        score = 0;
        orbsRun = 0;
        flipsRun = 0;
        scoreAcc = 0f;
        distPoints = 0f;
        trickScore = 0;
        trickAcc = 0f;
        gapsRun = 0;
        goalsRun = 0;
        bestCombo = 1;
        flowDistAcc = 0f;
        glideDistAcc = 0f;
        newBestDistance = false;
        newBestScore = false;
        bannerTime = 0f;
        missions.onRunStart();
        if (upgrades.active[UpgradeManager.PERK_INSTANT]) {
            startFlow();
        }
        save.save();
        state = ST_PLAY;
        fade = 1f;
    }

    /** Adds the orbs of the current run to the wallet and updates records. */
    private void creditRun() {
        save.orbs += orbsRun;
        if (distance > save.bestDistance) {
            newBestDistance = save.bestDistance > 0f;
            save.bestDistance = distance;
        }
        if (score > save.bestScore) {
            newBestScore = save.bestScore > 0;
            save.bestScore = score;
        }
        save.totalRuns++;
        save.save();
    }

    private void finishRun() {
        score = totalScore();
        overTime = 0f;
        creditRun();
        audio.setRide(0f, 0f, 0f, 0f);
        state = ST_OVER;
    }

    private void leaveResults() {
        missions.replaceCompleted();
        upgrades.clearActive();
        save.save();
    }

    private void startFlow() {
        flowActive = true;
        flowDuration = upgrades.flowDuration();
        flowTime = flowDuration;
        flowMeter = 1f;
        audio.playFlow();
        objs.spawnPopup(player.x + 40f, player.y - 150f, Strings.POP_FLOW, 0, COLOR_GOLD);
        for (int i = 0; i < 26; i++) {
            float a = rng.range(0f, 6.2831855f);
            float sp = rng.range(180f, 520f);
            particles.spawn(ParticlePool.SPARK, player.centerX(), player.centerY(),
                    (float) Math.cos(a) * sp + player.vx * 0.6f, (float) Math.sin(a) * sp,
                    rng.range(0.4f, 0.8f), 4f, COLOR_GOLD, 300f);
        }
    }

    private void endFlow() {
        flowActive = false;
        flowMeter = 0f;
        flowTime = 0f;
        // The scarf burns out: the combo has to be built up again.
        combo = 1;
        comboTimer = 0f;
    }

    /** Sum of all score components of the current run. */
    public int totalScore() {
        return (int) distPoints + trickScore + orbsRun * ORB_POINTS + gapsRun * GAP_POINTS
                + goalsRun * GOAL_POINTS;
    }

    /** Fraction shown by the Zenith Flow bar. */
    public float flowFraction() {
        if (flowActive) {
            float f = flowTime / flowDuration;
            return f > 1f ? 1f : f;
        }
        return flowMeter > 1f ? 1f : flowMeter;
    }

    // ------------------------------------------------------------------ update

    public void update(float dt) {
        if (!sized) {
            return;
        }
        if (fade > 0f) {
            fade -= dt * 2.2f;
        }
        if (noFundsTimer > 0f) {
            noFundsTimer -= dt;
        }
        if (bannerTime > 0f) {
            bannerTime -= dt;
        }
        float glowTarget = flowActive ? 1f : 0f;
        flowGlow += (glowTarget - flowGlow) * Math.min(1f, 5f * dt);
        realDt = dt;
        shake -= shake * Math.min(1f, 7f * dt);
        if (crashFlash > 0f) {
            crashFlash -= dt * 3.2f;
        }
        float fxTarget = 0f;
        if (state == ST_PLAY) {
            fxTarget = flowActive || player.dashTime > 0f ? 1f : (player.speed - 800f) / 350f;
            if (fxTarget < 0f) {
                fxTarget = 0f;
            } else if (fxTarget > 1f) {
                fxTarget = 1f;
            }
        }
        speedFx += (fxTarget - speedFx) * Math.min(1f, 4f * dt);
        timeScale = 1f;

        switch (state) {
            case ST_MENU:
            case ST_SHOP:
            case ST_MISSIONS:
            case ST_PERKS:
                time += dt;
                stepAtmosphere(dt);
                simulate(dt, true);
                break;
            case ST_PLAY:
                time += dt;
                stepAtmosphere(dt);
                simulate(dt, false);
                break;
            case ST_DYING:
                // Brief slow motion right after the impact, then the tumble plays out.
                // Hit-stop on impact, then slow motion that eases smoothly back to full speed.
                if (hitStop > 0f) {
                    hitStop -= dt;
                    timeScale = 0.03f;
                } else {
                    float t = (dyingTotal - dyingTimer) / 0.55f;
                    if (t > 1f) {
                        t = 1f;
                    }
                    t = t * t * (3f - 2f * t);
                    timeScale = 0.22f + 0.78f * t;
                }
                time += dt * timeScale;
                stepAtmosphere(dt);
                simulate(dt * timeScale, false);
                dyingTimer -= dt;
                // Results appear once the body has come to rest (or after a hard time limit).
                if (dyingTimer <= 0f || player.settle > 0.75f) {
                    finishRun();
                }
                break;
            case ST_OVER:
                overTime += dt;
                time += dt;
                stepAtmosphere(dt);
                particles.update(dt);
                objs.updatePopups(dt);
                break;
            default:
                // ST_PAUSE: the world is frozen.
                audio.setRide(0f, 0f, 0f, 0f);
                break;
        }
    }

    private void simulate(float dt, boolean attract) {
        stepCounter++;
        terrain.ensure(player.x);
        objs.recycle(player.x - 2600f);

        float d = terrain.difficultyAt(player.x);
        float base = (attract ? 560f : 620f + 380f * d) * riderSpeed;
        player.hover = !attract && hoverTime > 0f;
        player.targetSpeed = flowActive ? base * 1.22f : base;
        player.flowActive = flowActive;
        // The magnet only exists while its power-up runs; the upgrade widens that pull.
        player.magnetRadius = magnetTime > 0f ? 380f + 28f * upgrades.level(UpgradeManager.UPG_MAGNET) : 0f;
        player.collecting = !attract && state == ST_PLAY;

        boolean h = held;
        boolean e = pressEdge;
        if (attract) {
            h = false;
            e = autopilot();
        }
        pressEdge = false;
        physics.step(player, dt, h, e);

        if (!attract && !player.dead) {
            runLogic(dt);
        }
        emitParticles();
        particles.update(dt);
        objs.updatePopups(dt);
        updateCamera(dt);
        updateAudio(attract);
    }

    private boolean autopilot() {
        if (!player.grounded && player.railIdx < 0) {
            return false;
        }
        float look = player.speed * 0.2f;
        if (terrain.gapAt(player.x + look) >= 0 && terrain.gapAt(player.x) < 0) {
            return true;
        }
        float reach = player.speed * 0.24f;
        for (int c = 0; c < WorldObjects.MAX_CRYSTALS; c++) {
            if (objs.cryActive[c]) {
                float dx = objs.cryX[c] - player.x;
                if (dx > 0f && dx < reach) {
                    return true;
                }
            }
        }
        return false;
    }

    private void runLogic(float dt) {
        float meters = (player.x - player.prevX) / TerrainGenerator.UNITS_PER_METER;
        if (meters < 0f) {
            meters = 0f;
        }
        distance = (player.x - START_X) / TerrainGenerator.UNITS_PER_METER;
        distPoints += meters * (flowActive ? 2f : 1f);
        if (player.railIdx >= 0) {
            trickAcc += 40f * dt * combo;
            comboTimer = COMBO_TIME;
        }
        int add = (int) trickAcc;
        if (add > 0) {
            trickScore += add;
            trickAcc -= add;
        }
        score = totalScore();
        missions.reach(MissionManager.DIST_RUN, (int) distance);
        missions.reach(MissionManager.SCORE_RUN, score);

        if (flowActive) {
            flowDistAcc += meters;
            int m = (int) flowDistAcc;
            if (m > 0) {
                missions.add(MissionManager.FLOW_DIST, m);
                flowDistAcc -= m;
            }
            flowTime -= dt;
            if (flowTime <= 0f) {
                endFlow();
            }
        } else if (upgrades.flowOwned() && combo >= UpgradeManager.FLOW_COMBO) {
            // the scarf has reached full length: it ignites Zenith Flow
            startFlow();
        }
        // The combo is what charges everything: scarf length, its glow and the HUD bar.
        float charge = (combo - 1) / (float) (UpgradeManager.FLOW_COMBO - 1);
        if (charge > 1f) {
            charge = 1f;
        }
        flowMeter = flowActive ? 1f : charge;
        player.scarfTarget = 1f + 1.25f * charge;
        player.scarfGlow = upgrades.flowOwned() || flowActive ? charge : 0f;
        if (player.gliding) {
            glideDistAcc += meters;
            int m = (int) glideDistAcc;
            if (m > 0) {
                missions.add(MissionManager.GLIDE_DIST, m);
                glideDistAcc -= m;
            }
        }
        if (combo > 1) {
            comboTimer -= dt;
            if (comboTimer <= 0f) {
                combo = 1;
            }
        }
        if (magnetTime > 0f) {
            magnetTime -= dt;
        }
        if (hoverTime > 0f) {
            hoverTime -= dt;
        }
        updateHints(dt);

        int slot = missions.pollCompleted();
        if (slot >= 0) {
            bannerTime = 3.4f;
            bannerReward = missions.reward(slot);
            goalsRun++;
            audio.playFlow();
        }
    }

    private void bumpCombo(int amount) {
        combo += amount;
        if (combo > MAX_COMBO) {
            combo = MAX_COMBO;
        }
        comboTimer = COMBO_TIME;
        if (combo > bestCombo) {
            bestCombo = combo;
        }
        missions.reach(MissionManager.COMBO, combo);
    }

    // ------------------------------------------------------------------ tutorial hints

    private void completeHint(int id) {
        save.tutorialMask |= 1 << id;
        if (hintId == id) {
            hintId = -1;
        }
    }

    private void updateHints(float dt) {
        final int mask = save.tutorialMask;
        if (hintId >= 0) {
            if (hintTimer > 0f) {
                hintTimer -= dt;
                if (hintTimer <= 0f) {
                    completeHint(hintId);
                }
            }
            return;
        }
        if ((mask & 1) == 0) {
            hintId = 0;
            hintTimer = 0f;
        } else if ((mask & 2) == 0) {
            hintId = 1;
            hintTimer = 0f;
        } else if ((mask & 4) == 0) {
            hintId = 2;
            hintTimer = 5f;
        } else if (flowActive && (mask & 8) == 0) {
            hintId = 3;
            hintTimer = 6f;
        } else if ((mask & 16) == 0 && railAhead()) {
            hintId = 4;
            hintTimer = 4.5f;
        } else if ((mask & 32) == 0 && updraftAhead()) {
            hintId = 5;
            hintTimer = 4.5f;
        }
    }

    private boolean railAhead() {
        for (int r = 0; r < WorldObjects.MAX_RAILS; r++) {
            if (objs.railActive[r]) {
                float dx = objs.railX0[r] - player.x;
                if (dx > 0f && dx < 1300f) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean updraftAhead() {
        for (int u = 0; u < WorldObjects.MAX_UPDRAFTS; u++) {
            if (objs.upActive[u]) {
                float dx = objs.upX[u] - player.x;
                if (dx > 0f && dx < 1300f) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ particles, camera, audio

    private void emitParticles() {
        final int snow = EnvironmentPalette.withAlpha(env.color[EnvironmentPalette.RIM], 170);
        if (!player.dead) {
            if (player.grounded && player.hoverLift > 8f) {
                if ((stepCounter & 1) == 0) {
                    particles.spawn(ParticlePool.GLOW, player.x + rng.range(-24f, 24f), player.y + 6f,
                            -player.speed * 0.2f, rng.range(40f, 160f), 0.4f, rng.range(3f, 5f), COLOR_TEAL, 0f);
                }
            } else if (player.grounded) {
                particles.spawn(ParticlePool.DUST, player.x - 18f, player.y - 2f,
                        -player.speed * 0.12f + rng.range(-40f, 40f), rng.range(-150f, -20f),
                        rng.range(0.35f, 0.7f), rng.range(3f, 7f), snow, 260f);
                if (player.speed > 700f) {
                    particles.spawn(ParticlePool.DUST, player.x - 26f, player.y - 2f,
                            -player.speed * 0.2f, rng.range(-220f, -60f),
                            rng.range(0.3f, 0.6f), rng.range(2f, 5f), snow, 260f);
                }
            } else if (player.railIdx >= 0) {
                for (int i = 0; i < 2; i++) {
                    particles.spawn(ParticlePool.SPARK, player.x - 6f, player.y,
                            -player.speed * 0.3f + rng.range(-120f, 120f), rng.range(-380f, 60f),
                            rng.range(0.2f, 0.45f), 2.6f, snow, 1100f);
                }
            }
            if (player.dashTime > 0f && player.grounded) {
                // snow torn off the slope by the dash
                particles.spawn(ParticlePool.SPARK, player.x - 22f, player.y - rng.range(2f, 26f),
                        -player.speed * rng.range(0.3f, 0.6f), rng.range(-90f, 20f),
                        rng.range(0.18f, 0.34f), 3f, snow, 120f);
            }
            if (flowActive) {
                particles.spawn(ParticlePool.GLOW, player.centerX() + rng.range(-20f, 20f),
                        player.centerY() + rng.range(-30f, 30f),
                        player.vx * 0.5f + rng.range(-60f, 60f), player.vy * 0.5f + rng.range(-60f, 60f),
                        rng.range(0.3f, 0.6f), rng.range(3f, 6f), COLOR_GOLD, 0f);
            }
            if (player.gliding && (stepCounter & 1) == 0) {
                float c = (float) Math.cos(player.angle);
                float s = (float) Math.sin(player.angle);
                particles.spawn(ParticlePool.GLOW, player.x - 98f * c + 100f * s, player.y - 98f * s - 100f * c,
                        player.vx * 0.3f, player.vy * 0.3f, 0.5f, 4f, COLOR_TEAL, 0f);
            }
        }
        if (player.dead && player.grounded && player.speed > 90f) {
            particles.spawn(ParticlePool.DUST, player.x + rng.range(-14f, 14f), terrain.heightAt(player.x),
                    -player.vx * 0.2f + rng.range(-50f, 50f), rng.range(-170f, -30f),
                    rng.range(0.35f, 0.8f), rng.range(4f, 9f), snow, 300f);
        }
        // rising wisps inside updraft columns
        if ((stepCounter & 1) == 0) {
            for (int u = 0; u < WorldObjects.MAX_UPDRAFTS; u++) {
                if (objs.upActive[u] && objs.upX[u] - player.x < 2400f) {
                    particles.spawn(ParticlePool.WISP,
                            objs.upX[u] + rng.range(-objs.upHalf[u], objs.upHalf[u]),
                            objs.upBase[u] - rng.range(0f, 760f),
                            0f, rng.range(-380f, -220f), rng.range(0.9f, 1.6f),
                            rng.range(3f, 5.5f), 0xA0FFFFFF, 0f);
                }
            }
        }
        // a few specks of ice dust hanging in the air; real snowfall comes from the weather
        if ((stepCounter % 7) == 0) {
            particles.spawn(ParticlePool.GLOW, player.x + rng.range(-500f, 2200f),
                    camY + rng.range(-620f, 320f), rng.range(-120f, -30f) - 220f * weather.wind,
                    rng.range(10f, 60f), rng.range(1.8f, 3.2f), rng.range(1.4f, 2.6f),
                    EnvironmentPalette.withAlpha(env.color[EnvironmentPalette.RIM], 120), 0f);
        }
    }

    private void updateCamera(float dt) {
        float ground = terrain.heightAt(player.x);
        float alt = ground - player.y;
        if (alt < 0f) {
            alt = 0f;
        }
        float targetY = player.y + alt * 0.42f;
        if (player.dead) {
            // Camera work uses real time so slow motion does not freeze it.
            dt = realDt;
            if (player.doomed && player.doomGap >= 0) {
                // The camera stays at the edge and watches the rider fall away.
                float hold = terrain.gapLandingY(player.doomGap) + 60f;
                if (targetY > hold) {
                    targetY = hold;
                }
            } else {
                targetY = player.y - 10f;
            }
        }
        prevCamY = camY;
        camY += (targetY - camY) * Math.min(1f, 5.5f * dt);
        float zt = 0.92f - alt / 1500f - (player.speed - 600f) / 3200f;
        if (zt < 0.5f) {
            zt = 0.5f;
        } else if (zt > 0.92f) {
            zt = 0.92f;
        }
        if (player.dead && !player.doomed) {
            zt = 1.2f;
        }
        prevZoom = zoom;
        zoom += (zt - zoom) * Math.min(1f, (player.dead ? 2.6f : 2.2f) * dt);
    }

    private void updateAudio(boolean attract) {
        float sp = (player.speed - 300f) / 900f;
        if (sp < 0f) {
            sp = 0f;
        } else if (sp > 1f) {
            sp = 1f;
        }
        float slide = 0f, rail = 0f, wind = 0f;
        if (!player.dead) {
            if (player.grounded) {
                slide = 0.35f + 0.65f * sp;
            } else if (player.railIdx >= 0) {
                rail = 1f;
            } else {
                float spin = player.angVel < 0f ? -player.angVel : player.angVel;
                wind = 0.14f + spin / 11f + (player.gliding ? 0.45f : 0f);
                if (wind > 1f) {
                    wind = 1f;
                }
            }
        }
        if (attract) {
            slide *= 0.35f;
            rail *= 0.35f;
            wind *= 0.35f;
        }
        audio.setRide(slide, sp, rail, wind);
        float alt = (terrain.heightAt(player.x) - player.y) / 600f;
        if (alt < 0f) {
            alt = 0f;
        } else if (alt > 1f) {
            alt = 1f;
        }
        audio.setWorld(alt, env.musicPhase(), flowGlow);
    }

    // ------------------------------------------------------------------ physics events

    private boolean playing() {
        return state == ST_PLAY;
    }

    @Override
    public void onJump() {
        final int snow = EnvironmentPalette.withAlpha(env.color[EnvironmentPalette.RIM], 190);
        for (int i = 0; i < 6; i++) {
            particles.spawn(ParticlePool.DUST, player.x + rng.range(-16f, 16f), player.y,
                    rng.range(-160f, 60f), rng.range(-160f, -30f), rng.range(0.3f, 0.6f),
                    rng.range(3f, 7f), snow, 300f);
        }
        if (playing()) {
            audio.playJump();
            completeHint(0);
        }
    }

    @Override
    public void onLand(int flips, float impact) {
        final int snow = EnvironmentPalette.withAlpha(env.color[EnvironmentPalette.RIM], 190);
        int puffs = 6 + (int) (impact * 0.012f);
        if (puffs > 16) {
            puffs = 16;
        }
        for (int i = 0; i < puffs; i++) {
            particles.spawn(ParticlePool.DUST, player.x + rng.range(-20f, 20f), player.y,
                    rng.range(-240f, 240f), rng.range(-200f, -30f), rng.range(0.35f, 0.75f),
                    rng.range(3f, 8f), snow, 300f);
        }
        if (!playing()) {
            return;
        }
        if (impact > 650f && shake < 5f) {
            shake = 5f;
        }
        audio.playLand();
        if (flips > 0) {
            flipsRun += flips;
            int points = 100 * flips * combo * (flowActive ? 2 : 1);
            trickScore += points;
            bumpCombo(flips);
            missions.add(MissionManager.FLIPS_RUN, flips);
            objs.spawnPopup(player.x, player.y - 120f, Strings.POP_FLIP, points, COLOR_WHITE);
            audio.playTrick();
            completeHint(1);
        }
    }

    @Override
    public void onRailStart() {
        if (!playing()) {
            return;
        }
        bumpCombo(1);
        int points = 50 * combo;
        trickScore += points;
        missions.add(MissionManager.RAILS, 1);
        objs.spawnPopup(player.x, player.y - 120f, Strings.POP_RAIL, points, COLOR_WHITE);
        completeHint(4);
    }

    @Override
    public void onOrbCollected(int orbIndex) {
        float ox = objs.orbX[orbIndex];
        float oy = objs.orbY[orbIndex];
        int type = objs.orbType[orbIndex];
        objs.orbActive[orbIndex] = false;
        for (int i = 0; i < 5; i++) {
            particles.spawn(ParticlePool.GLOW, ox, oy, player.vx * 0.7f + rng.range(-150f, 150f),
                    rng.range(-190f, 110f), rng.range(0.25f, 0.5f), rng.range(2.5f, 5f),
                    type == WorldObjects.ORB_HOVER ? COLOR_TEAL : COLOR_GOLD, 0f);
        }
        if (!playing()) {
            return;
        }
        if (type == WorldObjects.ORB_MAGNET) {
            magnetDuration = upgrades.magnetDuration();
            magnetTime = magnetDuration;
            objs.spawnPopup(ox, oy - 60f, Strings.POP_MAGNET, 0, COLOR_GOLD);
            audio.playTrick();
            return;
        }
        if (type == WorldObjects.ORB_HOVER) {
            hoverDuration = upgrades.hoverDuration();
            hoverTime = hoverDuration;
            objs.spawnPopup(ox, oy - 60f, Strings.POP_HOVER, 0, COLOR_TEAL);
            audio.playTrick();
            return;
        }
        if (type == WorldObjects.ORB_WISP) {
            // Rushing through a wisp: a small speed burst, score and a kept-alive combo.
            trickScore += 25 * combo;
            comboTimer = COMBO_TIME;
            if (player.speed < 1350f) {
                player.vx *= 1.05f;
                player.vy *= 1.05f;
            }
            audio.playOrb();
            return;
        }
        int amount = doubleOrbs ? 2 : 1;
        orbsRun += amount;
        missions.add(MissionManager.ORBS, amount);
        audio.playOrb();
    }

    @Override
    public void onCrystalSmashed(int crystalIndex, int how) {
        float cx = objs.cryX[crystalIndex];
        float cy = objs.cryY[crystalIndex];
        float ch = objs.cryH[crystalIndex];
        objs.cryActive[crystalIndex] = false;
        int color = env.color[EnvironmentPalette.CRYSTAL];
        for (int i = 0; i < 22; i++) {
            particles.spawn(ParticlePool.SHARD, cx + rng.range(-20f, 20f), cy - rng.range(0f, ch),
                    player.vx * rng.range(0.3f, 1.1f) + rng.range(-200f, 200f), rng.range(-620f, -80f),
                    rng.range(0.5f, 1.1f), rng.range(4f, 10f), i % 3 == 0 ? COLOR_WHITE : color, 1500f);
        }
        if (!playing()) {
            return;
        }
        if (shake < 9f) {
            shake = 9f;
        }
        audio.playShatter();
        if (how != PhysicsSystem.SMASH_SHIELD) {
            bumpCombo(1);
            int points = (how == PhysicsSystem.SMASH_STOMP ? 100 : 150) * combo;
            trickScore += points;
            if (flowActive) {
                flowTime += 0.4f;
            }
            missions.add(MissionManager.CRYSTALS, 1);
            objs.spawnPopup(cx, cy - ch - 50f, Strings.POP_SMASH, points, COLOR_GOLD);
        }
    }

    @Override
    public void onShieldSave(int cause) {
        objs.spawnPopup(player.x, player.y - 140f, Strings.POP_SHIELD, 0, COLOR_TEAL);
        audio.playFlow();
        for (int i = 0; i < 24; i++) {
            float a = i * 0.2618f;
            particles.spawn(ParticlePool.SPARK, player.centerX(), player.centerY(),
                    (float) Math.cos(a) * 420f + player.vx * 0.5f, (float) Math.sin(a) * 420f,
                    0.5f, 4f, COLOR_TEAL, 0f);
        }
    }

    @Override
    public void onGapCleared() {
        if (!playing()) {
            return;
        }
        gapsRun++;
        missions.add(MissionManager.GAPS, 1);
        objs.spawnPopup(player.x, player.y - 150f, Strings.POP_GAP, GAP_POINTS, COLOR_WHITE);
    }

    @Override
    public void onCrash(int cause) {
        if (state != ST_PLAY) {
            return;
        }
        state = ST_DYING;
        dyingTotal = cause == PhysicsSystem.CRASH_CHASM ? 1.7f : 4.0f;
        dyingTimer = dyingTotal;
        shake = cause == PhysicsSystem.CRASH_CHASM ? 8f : 20f;
        hitStop = cause == PhysicsSystem.CRASH_CHASM ? 0f : 0.07f;
        crashFlash = 0f;
        physics.releaseBoard(player);
        held = false;
        endFlow();
        audio.playThud();
        if (cause != PhysicsSystem.CRASH_CHASM) {
            // Keep most of the momentum: the body is thrown forward and starts to cartwheel.
            player.vx *= 0.78f;
            player.vy = (player.vy < 0f ? player.vy * 0.5f : 0f) - 210f;
            player.angVel = 9f;
            final int snow = EnvironmentPalette.withAlpha(env.color[EnvironmentPalette.RIM], 220);
            for (int i = 0; i < 26; i++) {
                particles.spawn(ParticlePool.DUST, player.x + rng.range(-24f, 24f), player.y - rng.range(0f, 40f),
                        rng.range(-320f, 420f), rng.range(-420f, -40f), rng.range(0.5f, 1.1f),
                        rng.range(4f, 10f), snow, 420f);
            }
        }
    }

    @Override
    public void onTrickBoost() {
        final int snow = EnvironmentPalette.withAlpha(env.color[EnvironmentPalette.RIM], 215);
        for (int i = 0; i < 14; i++) {
            particles.spawn(ParticlePool.SPARK, player.x - 10f, player.y - rng.range(2f, 34f),
                    -player.speed * rng.range(0.25f, 0.65f), rng.range(-130f, 40f),
                    rng.range(0.22f, 0.5f), 3.4f, snow, 180f);
        }
        if (!playing()) {
            return;
        }
        audio.playDash();
        // One-time hint explaining what the dash does.
        if (hintId < 0 && (save.tutorialMask & (1 << 6)) == 0) {
            hintId = 6;
            hintTimer = 4.5f;
        }
    }

    @Override
    public void onTumble(float impact) {
        final int snow = EnvironmentPalette.withAlpha(env.color[EnvironmentPalette.RIM], 215);
        float gy = terrain.heightAt(player.x);
        int n = 8 + (int) (impact * 0.02f);
        if (n > 22) {
            n = 22;
        }
        for (int i = 0; i < n; i++) {
            particles.spawn(ParticlePool.DUST, player.x + rng.range(-22f, 22f), gy - rng.range(0f, 10f),
                    player.vx * rng.range(0.2f, 0.7f) + rng.range(-120f, 120f), rng.range(-360f, -60f),
                    rng.range(0.45f, 1.0f), rng.range(4f, 10f), snow, 520f);
        }
        if (state == ST_DYING) {
            float s = impact * 0.02f;
            if (s > 12f) {
                s = 12f;
            }
            if (shake < s) {
                shake = s;
            }
            audio.playLand();
        }
    }

    // ------------------------------------------------------------------ render

    public void render(Canvas canvas, float alpha) {
        if (!sized) {
            return;
        }
        boolean moving = state != ST_PAUSE && state != ST_OVER;
        float a = moving ? alpha : 1f;
        float camX = player.prevX + (player.x - player.prevX) * a;
        float cy = prevCamY + (camY - prevCamY) * a;
        float z = prevZoom + (zoom - prevZoom) * a;

        if (shake > 0.3f) {
            camX += shake * (float) Math.sin(time * 83f);
            cy += shake * (float) Math.cos(time * 67f);
        }

        canvas.save();
        canvas.scale(scale, scale);
        renderer.draw(canvas, camX, cy, z, a, moving ? FIXED_TIMESTEP * timeScale : 0f, time, flowGlow,
                player.shield && !player.godMode, speedFx);
        if (crashFlash > 0f) {
            fadePaint.setColor(0xFFFFFFFF);
            fadePaint.setAlpha((int) (255f * (crashFlash > 1f ? 1f : crashFlash)));
            canvas.drawRect(0f, 0f, vw, vh, fadePaint);
            fadePaint.setColor(0xFF070B24);
        }
        if (!(demoMode && state == ST_MENU)) {
            hud.draw(canvas, this);
        }
        if (fade > 0f) {
            fadePaint.setAlpha((int) (255f * (fade > 1f ? 1f : fade)));
            canvas.drawRect(0f, 0f, vw, vh, fadePaint);
        }
        canvas.restore();
    }
}
