package com.ccs.zenith_rider.graphics;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;

import com.ccs.zenith_rider.model.Player;
import com.ccs.zenith_rider.model.WorldObjects;
import com.ccs.zenith_rider.physics.FastRandom;
import com.ccs.zenith_rider.physics.TerrainGenerator;
import com.ccs.zenith_rider.ui.Strings;
import com.ccs.zenith_rider.ui.TextBuf;

/**
 * Draws the whole world procedurally with Canvas primitives - there is not a single bitmap asset.
 *
 * The picture is built for atmosphere. Back to front: sky gradient, stars and aurora, the sun or
 * the moon with a wide soft glow, lenticular clouds, then five ridges that grow darker towards
 * the viewer with a band of mist between every two of them (aerial perspective), ancient
 * structures whose lanterns come on at dusk, the weather (cloud grading, fog, snow, rain,
 * lightning), the gameplay slope with its groves, landmarks, rope lines, crystals, orbs and the rider,
 * and finally dark foreground firs. A handful of Paths, one Matrix and a few Paints are reused
 * for everything; nothing is allocated while drawing.
 */
public final class ProceduralRenderer {

    /** Screen anchor of the camera focus point, as fractions of the virtual screen. */
    public static final float ANCHOR_X = 0.30f;
    public static final float ANCHOR_Y = 0.60f;

    private static final int STAR_GROUPS = 3;
    private static final int STARS_PER_GROUP = 44;
    private static final int CLOUDS = 7;
    private static final int PRECIP = 150;
    private static final int FOG_PUFFS = 6;
    private static final int MOTES = 16;
    private static final float TERRAIN_STEP = 20f;
    private static final float PI = 3.1415927f;

    /** Sun and moon elevation over the day cycle as (time, elevation) pairs; 1 is the zenith. */
    private static final float[] SUN_KEYS = {
            -0.6f, -0.30f, 0f, 0.10f, 1f, 1.00f, 2f, 0.50f, 3f, 0.07f, 3.5f, -0.30f};
    private static final float[] MOON_KEYS = {
            3.6f, -0.20f, 4.3f, 0.45f, 5f, 0.95f, 5.7f, 0.45f, 6.2f, -0.20f};

    private final EnvironmentPalette env;
    private final WeatherSystem weather;
    private final TerrainGenerator terrain;
    private final WorldObjects objs;
    private final ParticlePool particles;
    private final Player player;
    private final Strings strings;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path rimLow = new Path();
    private final Path rimMid = new Path();
    private final Path rimHigh = new Path();
    private final Path moonPath = new Path();
    private final Matrix matrix = new Matrix();
    private final TextBuf buf = new TextBuf();

    private final float[] starPts = new float[STAR_GROUPS * STARS_PER_GROUP * 2];
    private final float[] cloudX = new float[CLOUDS];
    private final float[] cloudY = new float[CLOUDS];
    private final float[] cloudScale = new float[CLOUDS];
    private final float[] cloudSpeed = new float[CLOUDS];
    /** Seeds for the stateless precipitation, fog and firefly animations. */
    private final float[] seedX = new float[PRECIP];
    private final float[] seedY = new float[PRECIP];
    private final float[] seedA = new float[PRECIP];

    private float vw, vh;
    /** Where along each ridge this session looks; different every launch. */
    private final float[] ridgeShift = new float[5];

    // Sun, moon and the light they cast; recomputed at the start of every frame.
    private float sunX, sunY, sunElev, sunVis;
    private float moonX, moonY, moonVis;
    private float lightX, lightY = -1f, lightPower = 1f;

    public ProceduralRenderer(EnvironmentPalette env, WeatherSystem weather, TerrainGenerator terrain,
                              WorldObjects objs, ParticlePool particles, Player player, Strings strings) {
        this.env = env;
        this.weather = weather;
        this.terrain = terrain;
        this.objs = objs;
        this.particles = particles;
        this.player = player;
        this.strings = strings;
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        shaderPaint.setStyle(Paint.Style.FILL);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        FastRandom r = new FastRandom((int) System.nanoTime() | 1);
        for (int i = 0; i < ridgeShift.length; i++) {
            ridgeShift[i] = r.range(0f, 6000f);
        }
    }

    /** Prepares size dependent data. Called once per surface resize, never per frame. */
    public void build(float width, float height) {
        vw = width;
        vh = height;
        FastRandom r = new FastRandom(90210);
        for (int i = 0; i < STAR_GROUPS * STARS_PER_GROUP; i++) {
            starPts[i * 2] = r.range(0f, width);
            starPts[i * 2 + 1] = r.range(0f, height * 0.60f);
        }
        for (int i = 0; i < CLOUDS; i++) {
            cloudX[i] = (i + r.nextFloat() * 0.7f) / CLOUDS;
            cloudY[i] = height * r.range(0.07f, 0.36f);
            cloudScale[i] = r.range(0.75f, 1.6f);
            cloudSpeed[i] = r.range(3f, 9f);
        }
        for (int i = 0; i < PRECIP; i++) {
            seedX[i] = r.nextFloat();
            seedY[i] = r.nextFloat();
            seedA[i] = r.nextFloat();
        }
        // Crescent moon: a disc with an offset disc cut out of it, built once.
        Path cut = new Path();
        moonPath.rewind();
        moonPath.addCircle(0f, 0f, 22f, Path.Direction.CW);
        cut.addCircle(9f, -6f, 19f, Path.Direction.CW);
        moonPath.op(cut, Path.Op.DIFFERENCE);
    }

    // ------------------------------------------------------------------ small helpers

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float keyLerp(float t, float[] keys) {
        if (t <= keys[0]) {
            return keys[1];
        }
        for (int i = 0; i + 3 < keys.length; i += 2) {
            if (t <= keys[i + 2]) {
                float u = (t - keys[i]) / (keys[i + 2] - keys[i]);
                return keys[i + 1] + (keys[i + 3] - keys[i + 1]) * u;
            }
        }
        return keys[keys.length - 1];
    }

    private static float tri(float u) {
        float f = u - (float) Math.floor(u);
        return 1f - Math.abs(2f * f - 1f);
    }

    /** Where the sun and the moon are, and which way their light falls on the slope. */
    private void updateCelestial() {
        final float t = env.cycle;
        final float ts = t > 4.6f ? t - EnvironmentPalette.PHASES : t;
        sunElev = keyLerp(ts, SUN_KEYS);
        sunX = vw * (0.16f + 0.68f * clamp01((ts + 0.6f) / 4.1f));
        sunY = vh * (0.46f - 0.34f * sunElev);
        sunVis = clamp01((sunElev + 0.12f) / 0.2f);

        final float tm = t < 1.5f ? t + EnvironmentPalette.PHASES : t;
        final float moonElev = keyLerp(tm, MOON_KEYS);
        moonX = vw * (0.24f + 0.54f * clamp01((tm - 3.6f) / 2.6f));
        moonY = vh * (0.46f - 0.34f * moonElev);
        moonVis = clamp01((moonElev + 0.05f) / 0.2f);

        float bodyX;
        float elev;
        if (sunVis > 0.05f) {
            bodyX = sunX;
            elev = sunElev;
            lightPower = 0.45f + 0.55f * sunVis;
        } else if (moonVis > 0.05f) {
            bodyX = moonX;
            elev = moonElev;
            lightPower = 0.30f + 0.30f * moonVis;
        } else {
            bodyX = vw * ANCHOR_X;
            elev = 1f;
            lightPower = 0.32f;
        }
        float lx = (bodyX / vw - ANCHOR_X) * 2.4f;
        float ly = -(0.30f + 0.9f * (elev > 0f ? elev : 0f));
        float len = (float) Math.sqrt(lx * lx + ly * ly);
        lightX = lx / len;
        lightY = ly / len;
        lightPower *= 1f - 0.5f * weather.cloud;
    }

    private void drawGlow(Canvas c, Shader shader, float x, float y, float radius, float alpha) {
        if (alpha <= 0.004f) {
            return;
        }
        shaderPaint.setColor(0xFFFFFFFF);
        shaderPaint.setShader(shader);
        shaderPaint.setAlpha((int) (alpha > 1f ? 255f : alpha * 255f));
        c.save();
        c.translate(x, y);
        c.scale(radius, radius);
        c.drawCircle(0f, 0f, 1f, shaderPaint);
        c.restore();
        shaderPaint.setShader(null);
    }

    /**
     * A band of mist: transparent at {@code top}, the full haze colour {@code height} lower and
     * all the way down from there. Drawn after each ridge so the next one rises out of it.
     */
    private void mist(Canvas c, float top, float height, float alpha) {
        if (alpha <= 0.004f) {
            return;
        }
        if (alpha > 1f) {
            alpha = 1f;
        }
        final float reach = (vh - top) / height + 0.1f;
        shaderPaint.setColor(0xFFFFFFFF);
        c.save();
        c.translate(0f, top);
        c.scale(1f, height);
        shaderPaint.setShader(env.mist[env.phaseA]);
        shaderPaint.setAlpha((int) (alpha * (1f - env.blend) * 255f));
        c.drawRect(0f, 0f, vw, reach, shaderPaint);
        if (env.blend > 0.004f) {
            shaderPaint.setShader(env.mist[env.phaseB]);
            shaderPaint.setAlpha((int) (alpha * env.blend * 255f));
            c.drawRect(0f, 0f, vw, reach, shaderPaint);
        }
        c.restore();
        shaderPaint.setShader(null);
    }

    // ------------------------------------------------------------------ frame

    public void draw(Canvas c, float camX, float camY, float zoom, float alpha, float stepDt,
                     float time, float flowGlow, boolean shield, float speedFx) {
        updateCelestial();
        final float clearSky = clamp01(1f - weather.cloud * 1.25f);

        // ---- sky ----
        drawSky(c);
        if (env.night * clearSky > 0.01f) {
            drawStars(c, time, clearSky);
            drawAurora(c, time, clearSky);
            if (env.night > 0.5f) {
                drawShootingStar(c, time, clearSky);
            }
        }
        drawSun(c);
        drawMoon(c);
        drawClouds(c, camX, time);
        drawBirds(c, camX, time);

        // ---- five ridges, each one rising out of the mist that veils the one behind it ----
        final int far = env.color[EnvironmentPalette.FAR];
        final int mid = env.color[EnvironmentPalette.MID];
        final float veil = 0.25f * weather.fog + 0.10f * weather.cloud;
        final int skyBehind = gradeLikeSky(EnvironmentPalette.lerpColor(
                env.color[EnvironmentPalette.SKY_MID], env.color[EnvironmentPalette.SKY_BOTTOM], 0.55f));
        drawRidge(c, 0, camX * 0.010f + ridgeShift[0], vh * 0.63f,
                EnvironmentPalette.lerpColor(skyBehind, far, 0.42f), 0f, 0f, 0f);
        mist(c, vh * 0.48f, vh * 0.22f, 0.44f + veil);
        drawRidge(c, 1, camX * 0.028f + ridgeShift[1], vh * 0.67f, far, 0f, 0f, 0f);
        mist(c, vh * 0.55f, vh * 0.20f, 0.50f + veil);
        drawSunBloom(c);
        drawRidge(c, 2, camX * 0.055f + ridgeShift[2], vh * 0.73f,
                EnvironmentPalette.lerpColor(far, mid, 0.38f), 6f, 12f, 3.2f);
        mist(c, vh * 0.63f, vh * 0.19f, 0.52f + veil);
        final int hills = EnvironmentPalette.lerpColor(far, mid, 0.70f);
        drawRidge(c, 3, camX * 0.10f + ridgeShift[3], vh * 0.80f, hills, 13f, 26f, 5.5f);
        drawRidgeLandmarks(c, 3, camX * 0.10f + ridgeShift[3], vh * 0.80f, hills, 640f, 0.5f, time);
        mist(c, vh * 0.71f, vh * 0.20f, 0.50f + veil);
        drawRidge(c, 4, camX * 0.18f + ridgeShift[4], vh * 0.89f, mid, 24f, 50f, 9f);
        drawRidgeLandmarks(c, 4, camX * 0.18f + ridgeShift[4], vh * 0.89f, mid, 780f, 0.85f, time);
        mist(c, vh * 0.80f, vh * 0.22f, 0.46f + veil);

        // ---- weather behind the slope ----
        drawBackGrade(c);
        drawLightning(c);
        drawFog(c, camX, time, false);
        drawPrecipitation(c, camX, time, false);
        drawMotes(c, camX, time, clearSky);

        // ---- the slope and everything on it ----
        final float px = player.prevX + (player.x - player.prevX) * alpha;
        final float py = player.prevY + (player.y - player.prevY) * alpha;
        final float pa = player.prevAngle + (player.angle - player.prevAngle) * alpha;
        final float wing = player.prevWingOpen + (player.wingOpen - player.prevWingOpen) * alpha;

        final float left = camX - vw * ANCHOR_X / zoom - 60f;
        final float right = camX + vw * (1f - ANCHOR_X) / zoom + 60f;
        final float top = camY - vh * ANCHOR_Y / zoom - 60f;
        final float bottom = camY + vh * (1f - ANCHOR_Y) / zoom + 60f;

        c.save();
        c.translate(vw * ANCHOR_X, vh * ANCHOR_Y);
        c.scale(zoom, zoom);
        drawUpdrafts(c, camX, camY, left, right, top, bottom, time);
        drawGroves(c, camX, camY, left, right, true);
        drawLandmarks(c, camX, camY, left, right, time);
        drawGroves(c, camX, camY, left, right, false);
        drawTerrain(c, camX, camY, left, right, bottom);
        drawChasmMist(c, camX, camY, left, right, bottom);
        drawSurfaceDetails(c, camX, camY, left, right, time);
        drawRamps(c, camX, camY, left, right, time);
        drawRails(c, camX, camY, left, right, time);
        drawCrystals(c, camX, camY, left, right, time);
        drawOrbs(c, camX, camY, left, right, time);
        drawTrail(c, camX, camY, flowGlow);
        drawScarf(c, camX, camY, alpha);
        if (player.boardLoose) {
            drawLooseBoard(c, camX, camY);
        }
        drawPlayer(c, px - camX, py - camY, pa, wing, flowGlow, shield, time);
        particles.draw(c, fill, stroke, camX, camY, left, right, alpha * stepDt);
        drawPopups(c, camX, camY, zoom);
        c.restore();

        // ---- in front of the slope ----
        drawForeground(c, camX);
        drawFog(c, camX, time, true);
        drawPrecipitation(c, camX, time, true);
        drawFrontGrade(c);
        if (speedFx > 0.02f) {
            drawSpeedLines(c, time, speedFx);
        }
        if (flowGlow > 0.01f) {
            fill.setColor(EnvironmentPalette.withAlpha(0xFFFFD86B, (int) (20f * flowGlow)));
            c.drawRect(0f, 0f, vw, vh, fill);
        }
    }

    // ------------------------------------------------------------------ sky

    private void drawSky(Canvas c) {
        shaderPaint.setColor(0xFFFFFFFF);
        shaderPaint.setShader(env.sky[env.phaseA]);
        c.drawRect(0f, 0f, vw, vh, shaderPaint);
        if (env.blend > 0.004f) {
            shaderPaint.setShader(env.sky[env.phaseB]);
            shaderPaint.setAlpha((int) (env.blend * 255f));
            c.drawRect(0f, 0f, vw, vh, shaderPaint);
        }
        shaderPaint.setShader(null);
        // Cloud cover greys the sky; a storm darkens it.
        float grey = 0.52f * weather.cloud + 0.20f * weather.fog;
        if (grey > 0.004f) {
            fill.setColor(EnvironmentPalette.withAlpha(env.overcast, (int) (255f * (grey > 0.8f ? 0.8f : grey))));
            c.drawRect(0f, 0f, vw, vh, fill);
        }
        if (weather.storm > 0.004f) {
            fill.setColor(EnvironmentPalette.withAlpha(0xFF12162A, (int) (255f * 0.38f * weather.storm)));
            c.drawRect(0f, 0f, vw, vh, fill);
        }
    }

    /** Applies to a solid colour the grading that {@link #drawSky} lays over the sky gradient. */
    private int gradeLikeSky(int color) {
        float grey = 0.52f * weather.cloud + 0.20f * weather.fog;
        int out = EnvironmentPalette.lerpColor(color, env.overcast, grey > 0.8f ? 0.8f : grey);
        return EnvironmentPalette.lerpColor(out, 0xFF12162A, 0.38f * weather.storm);
    }

    private void drawStars(Canvas c, float time, float clearSky) {
        stroke.setStrokeWidth(2.3f);
        for (int g = 0; g < STAR_GROUPS; g++) {
            float twinkle = 0.55f + 0.45f * (float) Math.sin(time * (1.3f + g * 0.9f) + g * 2.1f);
            stroke.setColor(EnvironmentPalette.withAlpha(0xFFFFFFFF,
                    (int) (env.night * clearSky * twinkle * 230f)));
            c.drawPoints(starPts, g * STARS_PER_GROUP * 2, STARS_PER_GROUP * 2, stroke);
        }
    }

    private void drawAurora(Canvas c, float time, float clearSky) {
        float strength = 0.55f * clamp01((env.night - 0.45f) / 0.55f) * clearSky;
        if (strength < 0.02f) {
            return;
        }
        shaderPaint.setColor(0xFFFFFFFF);
        shaderPaint.setShader(env.aurora);
        for (int b = 0; b < 3; b++) {
            float base = vh * (0.05f + 0.06f * b);
            path.rewind();
            float x = -40f;
            path.moveTo(x, auroraY(x, base, time, b));
            for (x = 0f; x <= vw + 40f; x += 40f) {
                path.lineTo(x, auroraY(x, base, time, b));
            }
            for (x = vw + 40f; x >= -40f; x -= 80f) {
                path.lineTo(x, auroraY(x, base, time, b) + vh * 0.17f
                        + 30f * (float) Math.sin(x * 0.006f + time * 0.5f + b));
            }
            path.close();
            shaderPaint.setAlpha((int) (strength * (110f - 26f * b)));
            c.drawPath(path, shaderPaint);
        }
        shaderPaint.setShader(null);
    }

    private static float auroraY(float x, float base, float time, int band) {
        return base + 44f * (float) Math.sin(x * 0.0042f + time * 0.33f + band * 1.9f)
                + 16f * (float) Math.sin(x * 0.011f - time * 0.21f + band);
    }

    private void drawShootingStar(Canvas c, float time, float clearSky) {
        float t = time / 9f;
        int n = (int) t;
        float u = (t - n) / 0.1f;
        if (u >= 1f) {
            return;
        }
        float sx = vw * (0.25f + 0.6f * FastRandom.hash(n * 3 + 1));
        float sy = vh * (0.06f + 0.22f * FastRandom.hash(n * 5 + 2));
        float hx = sx - 420f * u;
        float hy = sy + 170f * u;
        float fadeK = (u < 0.2f ? u / 0.2f : (1f - u) / 0.8f) * env.night * clearSky;
        stroke.setStrokeWidth(2.6f);
        stroke.setColor(EnvironmentPalette.withAlpha(0xFFFFFFFF, (int) (fadeK * 230f)));
        c.drawLine(hx, hy, hx + 80f, hy - 32f, stroke);
        stroke.setColor(EnvironmentPalette.withAlpha(0xFFFFFFFF, (int) (fadeK * 90f)));
        c.drawLine(hx + 80f, hy - 32f, hx + 190f, hy - 77f, stroke);
    }

    /** The sun: a small disc inside a very wide glow, with an ice halo and sundogs when high. */
    private void drawSun(Canvas c) {
        if (sunVis <= 0.01f) {
            return;
        }
        final float clear = 1f - 0.85f * weather.cloud;
        final float a = sunVis * clear;
        final float r = vh * 0.80f;
        drawGlow(c, env.halo[env.phaseA], sunX, sunY, r, a * (1f - env.blend));
        drawGlow(c, env.halo[env.phaseB], sunX, sunY, r, a * env.blend);

        float ring = clamp01((sunElev - 0.30f) / 0.30f) * clamp01(1f - weather.cloud * 2.5f);
        if (ring > 0.02f) {
            final int rim = env.color[EnvironmentPalette.RIM];
            stroke.setColor(EnvironmentPalette.withAlpha(rim, (int) (14f * ring)));
            stroke.setStrokeWidth(14f);
            c.drawCircle(sunX, sunY, 150f, stroke);
            stroke.setColor(EnvironmentPalette.withAlpha(rim, (int) (30f * ring)));
            stroke.setStrokeWidth(4f);
            c.drawCircle(sunX, sunY, 150f, stroke);
            drawGlow(c, env.halo[env.phaseA], sunX - 150f, sunY, 30f, 0.55f * ring);
            drawGlow(c, env.halo[env.phaseA], sunX + 150f, sunY, 30f, 0.55f * ring);
        }
        float disc = (sunVis * 1.2f > 1f ? 1f : sunVis * 1.2f) * (0.35f + 0.65f * clear);
        fill.setColor(EnvironmentPalette.withAlpha(env.color[EnvironmentPalette.SUN], (int) (255f * disc)));
        c.drawCircle(sunX, sunY, 25f + 7f * (1f - clamp01(sunElev)), fill);
    }

    /** Second, fainter pass of the sun glow drawn over the far ridges: light spilling across them. */
    private void drawSunBloom(Canvas c) {
        final float clear = 1f - 0.85f * weather.cloud;
        if (sunVis > 0.01f) {
            float a = 0.34f * sunVis * clear;
            drawGlow(c, env.halo[env.phaseA], sunX, sunY, vh * 0.50f, a * (1f - env.blend));
            drawGlow(c, env.halo[env.phaseB], sunX, sunY, vh * 0.50f, a * env.blend);
        } else if (moonVis > 0.01f) {
            drawGlow(c, env.halo[EnvironmentPalette.NIGHT], moonX, moonY, vh * 0.34f, 0.22f * moonVis * clear);
        }
    }

    private void drawMoon(Canvas c) {
        if (moonVis <= 0.01f) {
            return;
        }
        final float clear = 1f - 0.85f * weather.cloud;
        drawGlow(c, env.halo[EnvironmentPalette.NIGHT], moonX, moonY, vh * 0.42f, 0.6f * moonVis * clear);
        fill.setColor(EnvironmentPalette.withAlpha(0xFFEAF2FF, (int) (255f * moonVis * (0.4f + 0.6f * clear))));
        c.save();
        c.translate(moonX, moonY);
        c.rotate(-24f);
        c.drawPath(moonPath, fill);
        c.restore();
    }

    /**
     * Lenticular clouds: each one a few long, thin, overlapping lenses. They are translucent, so
     * the overlaps read as soft layers. Heavy weather adds a slow deck of wide lenses on top.
     */
    private void drawClouds(Canvas c, float camX, float time) {
        final float cover = weather.cloud;
        final float loop = vw + 1100f;
        final int tone = env.color[EnvironmentPalette.CLOUD];
        fill.setColor(EnvironmentPalette.withAlpha(tone, (int) (38f + 46f * cover)));
        for (int i = 0; i < CLOUDS; i++) {
            float m = cloudX[i] * loop - camX * 0.02f - time * cloudSpeed[i];
            m -= loop * (float) Math.floor(m / loop);
            float sx = m - 550f;
            float sy = cloudY[i];
            float s = cloudScale[i] * (1f + 0.5f * cover);
            float w = 340f * s;
            float h = 24f * s;
            c.drawOval(sx, sy, sx + w, sy + h, fill);
            c.drawOval(sx + w * 0.10f, sy - h * 0.55f, sx + w * 0.78f, sy + h * 0.45f, fill);
            c.drawOval(sx + w * 0.34f, sy - h * 1.00f, sx + w * 0.92f, sy - h * 0.05f, fill);
            c.drawOval(sx - w * 0.14f, sy + h * 0.35f, sx + w * 0.56f, sy + h * 1.10f, fill);
        }
        if (cover > 0.25f) {
            final float k = (cover - 0.25f) / 0.75f;
            final float span = vw + 1800f;
            for (int j = 0; j < 5; j++) {
                float m = j * 0.27f * span - time * (6f + 3f * j) - camX * 0.015f;
                m -= span * (float) Math.floor(m / span);
                float x = m - 900f;
                float y = vh * (-0.04f + 0.065f * j);
                float w = 1200f + 220f * j;
                float h = 120f + 20f * j;
                fill.setColor(EnvironmentPalette.withAlpha(tone, (int) (k * (70f - 7f * j))));
                c.drawOval(x, y, x + w, y + h, fill);
            }
        }
    }

    /** A small flock gliding across the sky in fair daylight. */
    private void drawBirds(Canvas c, float camX, float time) {
        float vis = (1f - env.night) * (1f - Math.max(weather.rain, weather.snow)) * (1f - weather.fog);
        if (vis < 0.05f) {
            return;
        }
        final float loop = vw + 500f;
        float m = time * 46f - camX * 0.06f;
        m -= loop * (float) Math.floor(m / loop);
        float fx = m - 250f;
        float fy = vh * 0.22f + 26f * (float) Math.sin(time * 0.4f);
        stroke.setColor(EnvironmentPalette.withAlpha(env.color[EnvironmentPalette.SILHOUETTE], (int) (150f * vis)));
        stroke.setStrokeWidth(2.2f);
        for (int i = 0; i < 5; i++) {
            float bx = fx - i * 30f + (i % 2) * 8f;
            float by = fy + (i % 2 == 0 ? i * 9f : -i * 8f);
            float flap = 6f * (float) Math.sin(time * 8f + i * 1.3f);
            c.drawLine(bx, by, bx - 9f, by - 3f - flap, stroke);
            c.drawLine(bx, by, bx + 9f, by - 3f - flap, stroke);
        }
    }

    // ------------------------------------------------------------------ ridges

    /** Height profile of ridge {@code kind}: 0 a distant massif ... 4 the nearest rolling hills. */
    private static float ridgeHeight(int kind, float wx) {
        switch (kind) {
            case 0: {
                // one great peak with concave flanks, broken by shoulders and small teeth
                float t = tri(wx * 0.00048f + 0.15f);
                return 240f * t * (float) Math.sqrt(t) + 64f * tri(wx * 0.0019f + 0.3f) * t
                        + 16f * tri(wx * 0.0071f);
            }
            case 1:
                return 150f * tri(wx * 0.0016f) + 66f * tri(wx * 0.0041f + 0.37f)
                        + 20f * tri(wx * 0.0113f + 0.8f);
            case 2:
                return 96f * tri(wx * 0.0019f + 0.6f) + 46f * (float) Math.sin(wx * 0.0043f)
                        + 14f * tri(wx * 0.0127f);
            case 3:
                return 60f * (float) Math.sin(wx * 0.0026f) + 34f * (float) Math.sin(wx * 0.0063f + 1.1f)
                        + 12f * (float) Math.sin(wx * 0.015f + 0.4f);
            default:
                return 46f * (float) Math.sin(wx * 0.0021f + 2f) + 26f * (float) Math.sin(wx * 0.0057f + 0.3f)
                        + 8f * (float) Math.sin(wx * 0.019f);
        }
    }

    /**
     * One ridge silhouette. When a tree height range is given, patches of tiny firs are worked
     * into the outline itself, so a forested crest costs no extra draw call.
     */
    private void drawRidge(Canvas c, int kind, float off, float baseY, int color,
                           float treeMin, float treeMax, float treeHalf) {
        final float step = kind >= 3 ? 16f : 12f;
        int n = (int) Math.floor(off / step);
        float wx = n * step;
        path.rewind();
        path.moveTo(-step * 2f, vh + 2f);
        for (; wx - off <= vw + step; wx += step, n++) {
            float x = wx - off;
            float y = baseY - ridgeHeight(kind, wx);
            boolean tree = false;
            if (treeMax > 0f && FastRandom.hash((n >> 3) * 31 + kind * 7) > 0.40f
                    && FastRandom.hash(n * 17 + kind * 3) > 0.42f) {
                float th = treeMin + (treeMax - treeMin) * FastRandom.hash(n * 5 + 11);
                path.lineTo(x - treeHalf, y + 1f);
                path.lineTo(x, y - th);
                path.lineTo(x + treeHalf, y + 1f);
                tree = true;
            }
            if (!tree) {
                path.lineTo(x, y);
            }
        }
        path.lineTo(vw + step * 2f, vh + 2f);
        path.close();
        fill.setColor(color);
        c.drawPath(path, fill);
    }

    /**
     * Ancient towers, arches and sun rings standing on a ridge, in the ridge's own colour. Their
     * windows and lanterns light up as the day ends. Everything stands on the ground: nothing in
     * this world levitates.
     */
    private void drawRidgeLandmarks(Canvas c, int kind, float off, float baseY, int color,
                                    float cell, float scale, float time) {
        final int k0 = (int) Math.floor((off - 200f) / cell);
        final int k1 = (int) Math.floor((off + vw + 200f) / cell);
        final float lit = env.lights;
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1 && lit < 0.03f) {
                break;
            }
            if (pass == 0) {
                path.rewind();
            }
            for (int k = k0; k <= k1; k++) {
                float h = FastRandom.hash(k * 7 + 13 + kind * 101);
                if (h < 0.34f) {
                    continue;
                }
                float cxw = k * cell + cell * 0.5f + (FastRandom.hash(k * 3 + 5 + kind) - 0.5f) * cell * 0.5f;
                float sx = cxw - off;
                float by = baseY - ridgeHeight(kind, cxw);
                float s = scale * (0.8f + FastRandom.hash(k + 91) * 0.5f);
                float flicker = lit * (0.86f + 0.14f * (float) Math.sin(time * 2.7f + k * 1.9f));
                if (h < 0.58f) {
                    if (pass == 0) {
                        addTower(sx, by, s);
                    } else {
                        lamp(c, sx, by - 136f * s, 6f * s, flicker);
                    }
                } else if (h < 0.78f) {
                    if (pass == 0) {
                        addArch(sx, by, s);
                    } else {
                        lamp(c, sx, by - 70f * s - 44f * s, 5f * s, flicker);
                    }
                } else if (h < 0.92f) {
                    if (pass == 0) {
                        addRing(sx, by, s);
                    } else {
                        lamp(c, sx, by - 68f * s, 5f * s, flicker);
                    }
                } else {
                    if (pass == 0) {
                        addTower(sx - 34f * s, by, s * 0.8f);
                        addTower(sx + 34f * s, by, s * 1.1f);
                    } else {
                        lamp(c, sx - 34f * s, by - 136f * s * 0.8f, 5f * s, flicker);
                        lamp(c, sx + 34f * s, by - 136f * s * 1.1f, 6f * s, flicker);
                    }
                }
            }
            if (pass == 0) {
                fill.setColor(color);
                c.drawPath(path, fill);
            }
        }
    }

    /** A lit window: a warm dot inside a soft glow. */
    private void lamp(Canvas c, float x, float y, float r, float lit) {
        drawGlow(c, env.lampGlow, x, y, r * 6f, 0.8f * lit);
        fill.setColor(EnvironmentPalette.withAlpha(env.color[EnvironmentPalette.LIGHT], (int) (255f * (lit > 1f ? 1f : lit))));
        c.drawCircle(x, y, r * 0.55f, fill);
    }

    private void addTower(float x, float baseY, float s) {
        float top = baseY - 120f * s;
        path.moveTo(x - 17f * s, baseY + 30f);
        path.lineTo(x - 12f * s, top);
        path.lineTo(x - 19f * s, top - 8f * s);
        path.lineTo(x - 19f * s, top - 24f * s);
        path.lineTo(x - 8f * s, top - 24f * s);
        path.lineTo(x, top - 46f * s);
        path.lineTo(x + 8f * s, top - 24f * s);
        path.lineTo(x + 19f * s, top - 24f * s);
        path.lineTo(x + 19f * s, top - 8f * s);
        path.lineTo(x + 12f * s, top);
        path.lineTo(x + 17f * s, baseY + 30f);
        path.close();
    }

    private void addArch(float x, float baseY, float s) {
        float outer = 58f * s;
        float inner = 36f * s;
        float spring = baseY - 70f * s;
        path.moveTo(x - outer, baseY + 30f);
        path.lineTo(x - outer, spring);
        path.arcTo(x - outer, spring - outer, x + outer, spring + outer, 180f, 180f, false);
        path.lineTo(x + outer, baseY + 30f);
        path.lineTo(x + inner, baseY + 30f);
        path.lineTo(x + inner, spring);
        path.arcTo(x - inner, spring - inner, x + inner, spring + inner, 0f, -180f, false);
        path.lineTo(x - inner, baseY + 30f);
        path.close();
    }

    /** A stone hoop on a plinth; the inner circle is wound the other way so it stays open. */
    private void addRing(float x, float baseY, float s) {
        final float r = 52f * s;
        final float cy = baseY - r - 16f * s;
        path.moveTo(x - 26f * s, baseY + 30f);
        path.lineTo(x - 16f * s, cy + r - 6f * s);
        path.lineTo(x + 16f * s, cy + r - 6f * s);
        path.lineTo(x + 26f * s, baseY + 30f);
        path.close();
        path.addCircle(x, cy, r, Path.Direction.CW);
        path.addCircle(x, cy, r - 11f * s, Path.Direction.CCW);
    }

    // ------------------------------------------------------------------ weather

    /** Overcast and fog take the contrast out of the distance before the slope is drawn. */
    private void drawBackGrade(Canvas c) {
        float a = 0.14f * weather.cloud + 0.24f * weather.fog;
        if (a > 0.004f) {
            fill.setColor(EnvironmentPalette.withAlpha(env.overcast, (int) (255f * a)));
            c.drawRect(0f, 0f, vw, vh, fill);
        }
        if (weather.storm > 0.004f) {
            fill.setColor(EnvironmentPalette.withAlpha(0xFF12162A, (int) (255f * 0.16f * weather.storm)));
            c.drawRect(0f, 0f, vw, vh, fill);
        }
    }

    private void drawFrontGrade(Canvas c) {
        float a = 0.10f * weather.fog + 0.03f * weather.cloud;
        if (a > 0.004f) {
            fill.setColor(EnvironmentPalette.withAlpha(env.overcast, (int) (255f * a)));
            c.drawRect(0f, 0f, vw, vh, fill);
        }
        if (weather.flash > 0.01f) {
            fill.setColor(EnvironmentPalette.withAlpha(0xFFDCE6FF, (int) (weather.flash * 34f)));
            c.drawRect(0f, 0f, vw, vh, fill);
        }
    }

    private void drawLightning(Canvas c) {
        if (weather.flash > 0.01f) {
            fill.setColor(EnvironmentPalette.withAlpha(0xFFDCE6FF, (int) (weather.flash * 105f)));
            c.drawRect(0f, 0f, vw, vh, fill);
        }
        if (weather.boltLife <= 0f) {
            return;
        }
        path.rewind();
        path.moveTo(weather.boltX[0], weather.boltY[0]);
        for (int i = 1; i < WeatherSystem.BOLT_POINTS; i++) {
            path.lineTo(weather.boltX[i], weather.boltY[i]);
        }
        path.moveTo(weather.branchX[0], weather.branchY[0]);
        for (int i = 1; i < WeatherSystem.BRANCH_POINTS; i++) {
            path.lineTo(weather.branchX[i], weather.branchY[i]);
        }
        stroke.setColor(0x66BFD2FF);
        stroke.setStrokeWidth(11f);
        c.drawPath(path, stroke);
        stroke.setColor(0xFFFFFFFF);
        stroke.setStrokeWidth(3f);
        c.drawPath(path, stroke);
    }

    /** Wide, soft banks of fog drifting across the scene; half behind the slope, half in front. */
    private void drawFog(Canvas c, float camX, float time, boolean front) {
        final float amount = weather.fog;
        if (amount < 0.03f) {
            return;
        }
        final float span = vw + 1400f;
        shaderPaint.setColor(0xFFFFFFFF);
        for (int i = front ? 1 : 0; i < FOG_PUFFS; i += 2) {
            float m = seedX[i] * span - time * (14f + 9f * i) - camX * (front ? 0.22f : 0.08f);
            m -= span * (float) Math.floor(m / span);
            float x = m - 700f;
            float y = vh * (front ? 0.62f + 0.3f * seedY[i] : 0.34f + 0.4f * seedY[i]);
            float a = amount * (front ? 0.42f : 0.62f);
            c.save();
            c.translate(x, y);
            c.scale(560f + 220f * seedA[i], 110f + 60f * seedA[i]);
            shaderPaint.setShader(env.fogPuff[env.phaseA]);
            shaderPaint.setAlpha((int) (a * (1f - env.blend) * 255f));
            c.drawCircle(0f, 0f, 1f, shaderPaint);
            if (env.blend > 0.004f) {
                shaderPaint.setShader(env.fogPuff[env.phaseB]);
                shaderPaint.setAlpha((int) (a * env.blend * 255f));
                c.drawCircle(0f, 0f, 1f, shaderPaint);
            }
            c.restore();
        }
        shaderPaint.setShader(null);
    }

    /**
     * Snow and rain. Both are stateless: every flake's position is a function of time, the
     * camera and a fixed seed, wrapped around the screen, so there is nothing to simulate or
     * store. Three depths give parallax; the nearest is drawn in front of the slope.
     */
    private void drawPrecipitation(Canvas c, float camX, float time, boolean front) {
        final float snow = weather.snow;
        final float rain = weather.rain;
        final float wind = weather.wind;
        final float w = vw + 80f;
        final float h = vh + 60f;
        if (snow > 0.02f) {
            final int limit = (int) (PRECIP * (snow > 1f ? 1f : snow));
            final boolean streak = wind > 0.6f;
            final int tone = EnvironmentPalette.lerpColor(env.color[EnvironmentPalette.RIM], 0xFFFFFFFF, 0.5f);
            for (int i = 0; i < limit; i++) {
                int depth = i % 3;
                if ((depth == 2) != front) {
                    continue;
                }
                float ds = 0.55f + 0.45f * depth;
                float fall = (70f + 60f * seedA[i]) * ds * (1f + 0.6f * wind);
                float drift = (40f + 300f * wind) * ds;
                float x = seedX[i] * w - time * drift - camX * 0.25f * ds;
                x -= w * (float) Math.floor(x / w);
                x += (float) Math.sin(time * (0.8f + seedA[i]) + seedA[i] * 40f) * 14f * ds - 40f;
                float y = seedY[i] * h + time * fall;
                y -= h * (float) Math.floor(y / h);
                y -= 30f;
                float r = (1.5f + 1.7f * seedA[i]) * ds;
                int a = depth == 2 ? 120 : (depth == 1 ? 185 : 140);
                if (streak) {
                    stroke.setColor(EnvironmentPalette.withAlpha(tone, a - 30));
                    stroke.setStrokeWidth(r * 1.15f);
                    c.drawLine(x, y, x + drift * 0.035f, y - fall * 0.035f, stroke);
                } else {
                    fill.setColor(EnvironmentPalette.withAlpha(tone, a));
                    c.drawCircle(x, y, r, fill);
                }
            }
        }
        if (rain > 0.02f) {
            final int limit = (int) (PRECIP * (rain > 1f ? 1f : rain));
            final float slant = -(0.20f + 0.28f * wind);
            final int tone = EnvironmentPalette.lerpColor(env.color[EnvironmentPalette.HAZE], 0xFFFFFFFF, 0.45f);
            for (int i = 0; i < limit; i++) {
                int depth = i % 3;
                if ((depth == 2) != front) {
                    continue;
                }
                float ds = 0.6f + 0.4f * depth;
                float fall = (1500f + 600f * seedA[i]) * ds;
                float y = seedY[i] * h + time * fall;
                y -= h * (float) Math.floor(y / h);
                y -= 30f;
                float x = seedX[i] * w + y * slant - camX * 0.3f * ds;
                x -= w * (float) Math.floor(x / w);
                x -= 40f;
                float len = (26f + 30f * seedA[i]) * ds;
                stroke.setColor(EnvironmentPalette.withAlpha(tone, depth == 2 ? 110 : 80));
                stroke.setStrokeWidth(1.5f * ds);
                c.drawLine(x, y, x + len * slant, y + len, stroke);
            }
        }
    }

    /** Fireflies of warm light drifting over the snow on clear evenings and nights. */
    private void drawMotes(Canvas c, float camX, float time, float clearSky) {
        float vis = env.lights * clearSky * (1f - weather.fog);
        if (vis < 0.05f) {
            return;
        }
        final float w = vw + 120f;
        for (int i = 0; i < MOTES; i++) {
            float x = seedX[i] * w - camX * 0.45f + 50f * (float) Math.sin(time * 0.31f + i * 1.7f);
            x -= w * (float) Math.floor(x / w);
            x -= 60f;
            float y = vh * (0.46f + 0.36f * seedY[i]) + 20f * (float) Math.sin(time * 0.7f + i * 2.3f);
            float tw = 0.5f + 0.5f * (float) Math.sin(time * 1.9f + i * 3.1f);
            drawGlow(c, env.lampGlow, x, y, 11f, 0.7f * vis * tw);
        }
    }

    // ------------------------------------------------------------------ world layer

    private void drawTerrain(Canvas c, float camX, float camY, float left, float right, float bottom) {
        final float low = bottom + 80f - camY;
        float x = (float) Math.floor(left / TERRAIN_STEP) * TERRAIN_STEP;
        path.rewind();
        path.moveTo(x - camX, low);
        boolean inGap = false;
        int gap = -1;
        float lastX = x;
        for (; x <= right + TERRAIN_STEP; x += TERRAIN_STEP) {
            int k = terrain.gapAt(x);
            if (k >= 0) {
                if (!inGap) {
                    float gx = terrain.gapX0[k];
                    path.lineTo(gx - camX, terrain.groundAt(gx) - camY);
                    path.lineTo(gx - 8f - camX, low);
                    inGap = true;
                    gap = k;
                }
                lastX = x;
                continue;
            }
            if (inGap) {
                float gx = terrain.gapX1[gap];
                path.lineTo(gx + 8f - camX, low);
                path.lineTo(gx - camX, terrain.groundAt(gx) - camY);
                inGap = false;
            }
            path.lineTo(x - camX, terrain.groundAt(x) - camY);
            lastX = x;
        }
        path.lineTo(lastX - camX, low);
        path.close();

        // Lit snow on top, then three deeper tonal bands that follow the contour of the slope:
        // the same outline is redrawn shifted downwards in the shadow colour.
        final int snow = env.color[EnvironmentPalette.TERRAIN];
        final int shade = env.color[EnvironmentPalette.SHADE];
        fill.setColor(snow);
        c.drawPath(path, fill);
        c.save();
        c.translate(0f, 64f);
        fill.setColor(EnvironmentPalette.lerpColor(snow, shade, 0.38f));
        c.drawPath(path, fill);
        c.translate(0f, 150f);
        fill.setColor(EnvironmentPalette.lerpColor(snow, shade, 0.72f));
        c.drawPath(path, fill);
        c.translate(0f, 260f);
        fill.setColor(shade);
        c.drawPath(path, fill);
        c.restore();
        shaderPaint.setColor(0xFFFFFFFF);
        shaderPaint.setShader(env.terrainShade);
        c.drawPath(path, shaderPaint);
        shaderPaint.setShader(null);

        // Jagged rock faces on both sides of every chasm.
        final int cliff = EnvironmentPalette.lerpColor(shade, env.color[EnvironmentPalette.SILHOUETTE], 0.55f);
        final int facet = EnvironmentPalette.lerpColor(cliff, snow, 0.24f);
        for (int k = 0; k < TerrainGenerator.MAX_GAPS; k++) {
            float x0 = terrain.gapX0[k];
            float x1 = terrain.gapX1[k];
            if (x1 < left - 120f || x0 > right + 120f) {
                continue;
            }
            drawCliff(c, x0 - camX, terrain.groundAt(x0) - camY, 1f, low, cliff, facet);
            drawCliff(c, x1 - camX, terrain.groundAt(x1) - camY, -1f, low, cliff, facet);
        }

        // Rim light: stretches of the surface that face the sun or the moon catch more of it.
        // Segments are sorted into three brightness buckets, one path each.
        rimLow.rewind();
        rimMid.rewind();
        rimHigh.rewind();
        float x0 = (float) Math.floor(left / TERRAIN_STEP) * TERRAIN_STEP;
        float y0 = terrain.groundAt(x0);
        int lastBucket = -1;
        for (float xs = x0 + TERRAIN_STEP; xs <= right + TERRAIN_STEP; xs += TERRAIN_STEP) {
            float y1 = terrain.groundAt(xs);
            if (terrain.gapNear(xs - TERRAIN_STEP, xs)) {
                lastBucket = -1;
            } else {
                float s = (y1 - y0) / TERRAIN_STEP;
                float b = (s * lightX - lightY) / (float) Math.sqrt(1f + s * s);
                int bucket = b < 0.38f ? 0 : (b < 0.72f ? 1 : 2);
                Path target = bucket == 0 ? rimLow : (bucket == 1 ? rimMid : rimHigh);
                if (bucket != lastBucket) {
                    target.moveTo(xs - TERRAIN_STEP - camX, y0 - camY);
                }
                target.lineTo(xs - camX, y1 - camY);
                lastBucket = bucket;
            }
            y0 = y1;
        }
        final int rim = env.color[EnvironmentPalette.RIM];
        stroke.setStrokeWidth(4.5f);
        stroke.setColor(EnvironmentPalette.withAlpha(rim, (int) (64f + 40f * lightPower)));
        c.drawPath(rimLow, stroke);
        stroke.setColor(EnvironmentPalette.withAlpha(rim, (int) (112f + 90f * lightPower)));
        c.drawPath(rimMid, stroke);
        stroke.setColor(EnvironmentPalette.withAlpha(rim, (int) (150f + 105f * lightPower)));
        c.drawPath(rimHigh, stroke);
    }

    /** One rock face. {@code dir} is +1 for a wall facing right, -1 for a wall facing left. */
    private void drawCliff(Canvas c, float ex, float ey, float dir, float low, int cliff, int facet) {
        path.rewind();
        path.moveTo(ex - 36f * dir, ey + 34f);
        path.lineTo(ex + 1f * dir, ey + 7f);
        path.lineTo(ex + 17f * dir, ey + 66f);
        path.lineTo(ex - 4f * dir, ey + 114f);
        path.lineTo(ex + 23f * dir, ey + 206f);
        path.lineTo(ex + 6f * dir, ey + 294f);
        path.lineTo(ex + 31f * dir, ey + 430f);
        path.lineTo(ex + 14f * dir, low);
        path.lineTo(ex - 96f * dir, low);
        path.lineTo(ex - 78f * dir, ey + 310f);
        path.lineTo(ex - 52f * dir, ey + 196f);
        path.lineTo(ex - 70f * dir, ey + 116f);
        path.close();
        fill.setColor(cliff);
        c.drawPath(path, fill);
        path.rewind();
        path.moveTo(ex + 1f * dir, ey + 7f);
        path.lineTo(ex + 17f * dir, ey + 66f);
        path.lineTo(ex - 4f * dir, ey + 114f);
        path.lineTo(ex - 34f * dir, ey + 74f);
        path.close();
        path.moveTo(ex + 23f * dir, ey + 206f);
        path.lineTo(ex + 6f * dir, ey + 294f);
        path.lineTo(ex - 40f * dir, ey + 250f);
        path.close();
        fill.setColor(facet);
        c.drawPath(path, fill);
    }

    /** Layered mist that fills the depth of every visible chasm and softens the rock faces. */
    private void drawChasmMist(Canvas c, float camX, float camY, float left, float right, float bottom) {
        final int haze = env.color[EnvironmentPalette.HAZE];
        for (int k = 0; k < TerrainGenerator.MAX_GAPS; k++) {
            float x0 = terrain.gapX0[k];
            float x1 = terrain.gapX1[k];
            if (x1 < left || x0 > right) {
                continue;
            }
            float ly = terrain.groundAt(x1);
            for (int b = 0; b < 5; b++) {
                fill.setColor(EnvironmentPalette.withAlpha(haze, 30 + b * 14));
                c.drawRect(x0 - 60f - camX, ly + 150f + b * 60f - camY, x1 + 60f - camX, bottom + 120f - camY, fill);
            }
        }
    }

    // ------------------------------------------------------------------ trees

    /**
     * Groves rooted in the snow behind the rider. Trees gather in patches with open slope in
     * between; a hazier, smaller back row gives the stand some depth. Three kinds: tiered firs,
     * firs leaning with the wind and bare frost trees. Everything comes from a hash of the cell
     * index and the run's scenery seed, so each run has its own forest.
     */
    private void drawGroves(Canvas c, float camX, float camY, float left, float right, boolean backRow) {
        final float cell = 300f;
        final int tree = env.color[EnvironmentPalette.TREE];
        final int color = backRow
                ? EnvironmentPalette.lerpColor(tree, env.color[EnvironmentPalette.HAZE], 0.48f) : tree;
        final int dust = EnvironmentPalette.lerpColor(color, env.color[EnvironmentPalette.TERRAIN], 0.34f);
        final int k0 = (int) Math.floor((left - 160f) / cell);
        final int k1 = (int) Math.floor((right + 160f) / cell);
        final int seed = terrain.scenerySeed;
        for (int k = k0; k <= k1; k++) {
            final int q = k + seed;
            if ((FastRandom.hash(q * 13 + 4) < 0.42f) != backRow) {
                continue;
            }
            boolean grove = FastRandom.hash((q >> 2) * 41 + 5) > 0.45f;
            if (FastRandom.hash(q * 23 + 9) < (grove ? 0.22f : 0.84f)) {
                continue;
            }
            float wx = k * cell + FastRandom.hash(q * 41 + 2) * cell * 0.8f;
            if (terrain.gapNear(wx - 90f, wx + 90f) || terrain.rampAt(wx) >= 0) {
                continue;
            }
            float bx = wx - camX;
            float by = terrain.groundAt(wx) - camY;
            float size = (110f + 190f * FastRandom.hash(q * 3 + 8)) * (backRow ? 0.72f : 1f);
            float species = FastRandom.hash(q * 7 + 1);
            if (species < 0.62f) {
                drawFir(c, bx, by, size, color, dust);
            } else if (species < 0.86f) {
                drawLeaningFir(c, bx, by, size, color, dust);
            } else {
                drawFrostTree(c, bx, by, size, color);
            }
        }
    }

    /** Appends the four stacked tiers of a fir to {@link #path}. */
    private void addFirTiers(float bx, float by, float s, float widen) {
        for (int i = 0; i < 4; i++) {
            float yb = by - s * (0.08f + 0.20f * i);
            float w = s * (0.20f - 0.038f * i) * widen;
            path.moveTo(bx - w, yb);
            path.lineTo(bx, yb - s * 0.36f);
            path.lineTo(bx + w, yb);
            path.close();
        }
    }

    private void drawFir(Canvas c, float bx, float by, float s, int color, int dust) {
        fill.setColor(color);
        c.drawRect(bx - s * 0.022f, by - s * 0.14f, bx + s * 0.022f, by + 14f, fill);
        path.rewind();
        addFirTiers(bx, by, s, 1f);
        c.drawPath(path, fill);
        // snow resting on the lit side of every tier
        final float dir = lightX >= 0f ? 1f : -1f;
        path.rewind();
        for (int i = 0; i < 4; i++) {
            float yb = by - s * (0.08f + 0.20f * i);
            float w = s * (0.20f - 0.038f * i);
            path.moveTo(bx, yb - s * 0.36f);
            path.lineTo(bx + dir * w, yb);
            path.lineTo(bx + dir * w * 0.42f, yb - s * 0.035f);
            path.close();
        }
        fill.setColor(dust);
        c.drawPath(path, fill);
    }

    /** A fir bent by the prevailing wind: the same tiered tree, sheared downwind. */
    private void drawLeaningFir(Canvas c, float bx, float by, float s, int color, int dust) {
        c.save();
        c.translate(bx, by);
        c.skew(0.24f, 0f);
        drawFir(c, 0f, 0f, s, color, dust);
        c.restore();
    }

    private void drawFrostTree(Canvas c, float bx, float by, float s, int color) {
        final float topX = bx - s * 0.05f;
        final float topY = by - s * 0.72f;
        stroke.setColor(color);
        stroke.setStrokeWidth(Math.max(2.6f, s * 0.034f));
        c.drawLine(bx, by + 12f, topX, topY, stroke);
        stroke.setStrokeWidth(Math.max(1.7f, s * 0.018f));
        for (int j = 0; j < 5; j++) {
            float t = 0.30f + 0.14f * j;
            float px = bx + (topX - bx) * t;
            float py = by + (topY - by) * t;
            float side = (j & 1) == 0 ? 1f : -1f;
            float reach = s * 0.20f * (1f - t * 0.55f);
            float ex = px + side * reach;
            float ey = py - reach * 1.15f;
            c.drawLine(px, py, ex, ey, stroke);
            c.drawLine((px + ex) * 0.5f, (py + ey) * 0.5f, (px + ex) * 0.5f + side * reach * 0.08f,
                    (py + ey) * 0.5f - reach * 0.55f, stroke);
        }
    }

    // ------------------------------------------------------------------ landmarks on the slope

    /**
     * Relics of the Zenith standing on the slope behind the rider: an arch gate, a beacon
     * tower, a row of lantern posts or a sun ring. All of them are either far taller than the
     * rider or pencil-thin, stand on the ground behind the rider's line and never glow teal, so
     * they cannot be taken for a crystal barrier. Their lanterns are dark by day and glow warmly from
     * dusk till dawn.
     */
    private void drawLandmarks(Canvas c, float camX, float camY, float left, float right, float time) {
        final float cell = 2400f;
        // Weathered stone in a mid tone: clearly scenery, and distinct from the black foreground.
        final int body = EnvironmentPalette.lerpColor(env.color[EnvironmentPalette.SILHOUETTE],
                env.color[EnvironmentPalette.MID], 0.5f);
        final int facet = EnvironmentPalette.lerpColor(body, env.color[EnvironmentPalette.RIM], 0.18f);
        final int dark = EnvironmentPalette.lerpColor(env.color[EnvironmentPalette.SILHOUETTE],
                env.color[EnvironmentPalette.MID], 0.2f);
        final float lit = env.lights;
        final int seed = terrain.scenerySeed;
        final int k0 = (int) Math.floor((left - 500f) / cell);
        final int k1 = (int) Math.floor((right + 500f) / cell);
        for (int k = k0; k <= k1; k++) {
            final int q = k + seed;
            float h = FastRandom.hash(q * 53 + 17);
            if (h < 0.34f) {
                continue;
            }
            float wx = k * cell + 500f + FastRandom.hash(q * 29 + 3) * 1400f;
            if (terrain.gapNear(wx - 280f, wx + 280f) || terrain.rampAt(wx) >= 0
                    || terrain.rampAt(wx - 150f) >= 0 || terrain.rampAt(wx + 150f) >= 0) {
                continue;
            }
            float bx = wx - camX;
            float by = terrain.groundAt(wx) - camY;
            if (h < 0.54f) {
                drawArchGate(c, bx, by, body, facet, lit, time, k);
            } else if (h < 0.72f) {
                drawBeacon(c, bx, by, body, facet, lit, time, k);
            } else if (h < 0.88f) {
                drawLanternPosts(c, wx, camX, camY, dark, lit, time, k);
            } else {
                drawSunRing(c, bx, by, body, facet, lit, time, k);
            }
        }
    }

    private void drawArchGate(Canvas c, float bx, float by, int body, int facet, float lit, float time, int k) {
        final float outer = 96f;
        final float inner = 62f;
        final float spring = by - 150f;
        final float foot = by + 26f;
        path.rewind();
        path.moveTo(bx - outer, foot);
        path.lineTo(bx - outer, spring);
        path.arcTo(bx - outer, spring - outer, bx + outer, spring + outer, 180f, 180f, false);
        path.lineTo(bx + outer, foot);
        path.lineTo(bx + inner, foot);
        path.lineTo(bx + inner, spring);
        path.arcTo(bx - inner, spring - inner, bx + inner, spring + inner, 0f, -180f, false);
        path.lineTo(bx - inner, foot);
        path.close();
        path.addRect(bx - outer - 9f, spring - 7f, bx - inner + 9f, spring + 9f, Path.Direction.CW);
        path.addRect(bx + inner - 9f, spring - 7f, bx + outer + 9f, spring + 9f, Path.Direction.CW);
        fill.setColor(body);
        c.drawPath(path, fill);
        // the flank of each pillar that faces the light
        fill.setColor(facet);
        if (lightX >= 0f) {
            c.drawRect(bx - inner - 10f, spring + 9f, bx - inner, foot, fill);
            c.drawRect(bx + outer - 10f, spring + 9f, bx + outer, foot, fill);
        } else {
            c.drawRect(bx - outer, spring + 9f, bx - outer + 10f, foot, fill);
            c.drawRect(bx + inner, spring + 9f, bx + inner + 10f, foot, fill);
        }
        // keystone lantern
        final float ky = spring - outer + 18f;
        final float flicker = 0.88f + 0.12f * (float) Math.sin(time * 3.1f + k);
        if (lit > 0.03f) {
            drawGlow(c, env.lampGlow, bx, ky, 92f, 0.8f * lit * flicker);
        }
        path.rewind();
        path.moveTo(bx, ky - 11f);
        path.lineTo(bx + 7f, ky);
        path.lineTo(bx, ky + 11f);
        path.lineTo(bx - 7f, ky);
        path.close();
        fill.setColor(EnvironmentPalette.lerpColor(facet, env.color[EnvironmentPalette.LIGHT], lit));
        c.drawPath(path, fill);
        // a single ribbon tied under the keystone streams in the wind
        drawStreamer(c, bx - 10f, spring - inner + 2f, 9f, time, k);
    }

    private void drawBeacon(Canvas c, float bx, float by, int body, int facet, float lit, float time, int k) {
        final float top = by - 236f;
        final float foot = by + 26f;
        path.rewind();
        path.moveTo(bx - 25f, foot);
        path.lineTo(bx - 15f, top);
        path.lineTo(bx + 15f, top);
        path.lineTo(bx + 25f, foot);
        path.close();
        path.addRect(bx - 25f, top - 11f, bx + 25f, top, Path.Direction.CW);
        path.addRect(bx - 13f, top - 46f, bx + 13f, top - 11f, Path.Direction.CW);
        path.moveTo(bx - 21f, top - 46f);
        path.lineTo(bx, top - 84f);
        path.lineTo(bx + 21f, top - 46f);
        path.close();
        fill.setColor(body);
        c.drawPath(path, fill);
        stroke.setColor(body);
        stroke.setStrokeWidth(3f);
        c.drawLine(bx, top - 84f, bx, top - 106f, stroke);
        // lit flank of the shaft
        final float dir = lightX >= 0f ? 1f : -1f;
        path.rewind();
        path.moveTo(bx + dir * 25f, foot);
        path.lineTo(bx + dir * 15f, top);
        path.lineTo(bx + dir * 7f, top);
        path.lineTo(bx + dir * 15f, foot);
        path.close();
        fill.setColor(facet);
        c.drawPath(path, fill);
        // lantern room and window slits
        final float flicker = 0.85f + 0.15f * (float) Math.sin(time * 2.3f + k);
        if (lit > 0.03f) {
            drawGlow(c, env.lampGlow, bx, top - 28f, 140f, 0.85f * lit * flicker);
        }
        fill.setColor(EnvironmentPalette.lerpColor(env.color[EnvironmentPalette.SILHOUETTE],
                env.color[EnvironmentPalette.LIGHT], lit));
        c.drawRect(bx - 7f, top - 40f, bx + 7f, top - 17f, fill);
        c.drawRect(bx - 2.5f, top + 60f, bx + 2.5f, top + 78f, fill);
        c.drawRect(bx - 2.5f, top + 132f, bx + 2.5f, top + 150f, fill);
    }

    /** Three slender posts with hanging lanterns that sway in the wind, marking the trail. */
    private void drawLanternPosts(Canvas c, float wx, float camX, float camY, int body,
                                  float lit, float time, int k) {
        final int glass = EnvironmentPalette.lerpColor(env.color[EnvironmentPalette.SILHOUETTE],
                env.color[EnvironmentPalette.LIGHT], lit);
        for (int j = 0; j < 3; j++) {
            float ox = wx + (j - 1) * 150f;
            float x = ox - camX;
            float y = terrain.groundAt(ox) - camY;
            float h = 116f + 18f * FastRandom.hash(k * 3 + j);
            // post bending into a crook at the top
            stroke.setColor(body);
            stroke.setStrokeWidth(4f);
            path.rewind();
            path.moveTo(x, y + 8f);
            path.lineTo(x, y - h);
            path.quadTo(x, y - h - 22f, x - 20f, y - h - 16f);
            c.drawPath(path, stroke);
            // lantern on a short cord
            float swing = (2.5f + 7f * weather.wind) * (float) Math.sin(time * (2.1f + 2f * weather.wind) + k + j * 1.3f);
            float lx = x - 20f + swing;
            float ly = y - h + 6f;
            stroke.setStrokeWidth(1.8f);
            c.drawLine(x - 20f, y - h - 16f, lx, ly - 9f, stroke);
            float flicker = 0.86f + 0.14f * (float) Math.sin(time * 3.4f + k + j * 1.7f);
            if (lit > 0.03f) {
                drawGlow(c, env.lampGlow, lx, ly, 58f, 0.85f * lit * flicker);
            }
            fill.setColor(body);
            c.drawRect(lx - 6.5f, ly - 10f, lx + 6.5f, ly - 7f, fill);
            fill.setColor(glass);
            c.drawRoundRect(lx - 5f, ly - 7f, lx + 5f, ly + 8f, 3f, 3f, fill);
        }
    }

    /**
     * A sun ring: a tall hoop of stone on a plinth with a lantern hanging in its centre. The sky,
     * the sun and the moon show through it as the rider passes.
     */
    private void drawSunRing(Canvas c, float bx, float by, int body, int facet, float lit, float time, int k) {
        final float r = 106f;
        final float band = 20f;
        final float cy = by - r - 36f;
        path.rewind();
        path.moveTo(bx - 56f, by + 26f);
        path.lineTo(bx - 36f, by - 46f);
        path.lineTo(bx + 36f, by - 46f);
        path.lineTo(bx + 56f, by + 26f);
        path.close();
        fill.setColor(body);
        c.drawPath(path, fill);
        stroke.setColor(body);
        stroke.setStrokeWidth(band);
        c.drawCircle(bx, cy, r - band * 0.5f, stroke);
        // the rim that faces the light
        stroke.setColor(facet);
        stroke.setStrokeWidth(5f);
        c.drawArc(bx - r + 2.5f, cy - r + 2.5f, bx + r - 2.5f, cy + r - 2.5f,
                lightX >= 0f ? -78f : 102f, 156f, false, stroke);
        // lantern hanging from the crown of the ring, swaying in the wind
        final float swing = (3f + 9f * weather.wind) * (float) Math.sin(time * (1.7f + 2f * weather.wind) + k);
        final float lx = bx + swing;
        final float ly = cy + 4f;
        stroke.setColor(body);
        stroke.setStrokeWidth(2f);
        c.drawLine(bx, cy - r + band, lx, ly - 10f, stroke);
        final float flicker = 0.88f + 0.12f * (float) Math.sin(time * 3.1f + k);
        if (lit > 0.03f) {
            drawGlow(c, env.lampGlow, lx, ly, 104f, 0.85f * lit * flicker);
        }
        fill.setColor(body);
        c.drawRect(lx - 8f, ly - 12f, lx + 8f, ly - 8f, fill);
        fill.setColor(EnvironmentPalette.lerpColor(env.color[EnvironmentPalette.SILHOUETTE],
                env.color[EnvironmentPalette.LIGHT], lit));
        c.drawRoundRect(lx - 6f, ly - 8f, lx + 6f, ly + 10f, 3.5f, 3.5f, fill);
    }

    /** A ribbon of six tapering segments streaming to the left; wilder in strong wind. */
    private void drawStreamer(Canvas c, float x, float y, float seg, float time, float phase) {
        final float gust = weather.wind;
        stroke.setColor(env.color[EnvironmentPalette.SCARF]);
        float px = x;
        float py = y;
        for (int j = 1; j <= 6; j++) {
            float nx = x - j * seg;
            float ny = y + j * (1.4f + 2.4f * (1f - gust))
                    + (float) Math.sin(time * (7.5f + 4f * gust) - j * 0.9f + phase) * (1.3f * j) * (0.7f + gust);
            stroke.setStrokeWidth(6.5f - j * 0.7f);
            c.drawLine(px, py, nx, ny, stroke);
            px = nx;
            py = ny;
        }
    }

    // ------------------------------------------------------------------ ramps and small details

    /** Launch ramps: a curved plank deck on braced supports with a snow-dusted top edge. */
    private void drawRamps(Canvas c, float camX, float camY, float left, float right, float time) {
        final int wood = EnvironmentPalette.lerpColor(env.color[EnvironmentPalette.SILHOUETTE],
                env.color[EnvironmentPalette.MID], 0.30f);
        for (int r = 0; r < TerrainGenerator.MAX_RAMPS; r++) {
            float x0 = terrain.rampX0[r];
            float x1 = terrain.rampX1[r];
            if (x1 < left || x0 > right) {
                continue;
            }
            float len = x1 - x0;
            // supports and diagonal braces
            stroke.setColor(wood);
            stroke.setStrokeWidth(5.5f);
            float prevX = 0f, prevBase = 0f;
            for (int j = 0; j < 4; j++) {
                float xs = x0 + len * (0.4f + 0.2f * j);
                float topY = terrain.heightAt(xs) - camY;
                float baseY = terrain.groundAt(xs) + 8f - camY;
                c.drawLine(xs - camX, topY, xs - camX, baseY, stroke);
                if (j > 0) {
                    c.drawLine(prevX - camX, prevBase, xs - camX, topY + 4f, stroke);
                }
                prevX = xs;
                prevBase = baseY;
            }
            // deck: dark planks with a bright snow-dusted top edge
            float px = x0 - camX;
            float py = terrain.heightAt(x0) - camY;
            for (int j = 1; j <= 12; j++) {
                float xs = x0 + len * j / 12f;
                float nx = xs - camX;
                float ny = terrain.heightAt(xs) - camY;
                stroke.setColor(wood);
                stroke.setStrokeWidth(11f);
                c.drawLine(px, py + 4f, nx, ny + 4f, stroke);
                stroke.setColor(env.color[EnvironmentPalette.RIM]);
                stroke.setStrokeWidth(4f);
                c.drawLine(px, py - 1f, nx, ny - 1f, stroke);
                px = nx;
                py = ny;
            }
        }
    }

    /**
     * Light, unmistakably harmless details on the snow: swaying frost grass, twinkling glints and,
     * about once in 700 m, a slim wind pole with a streamer. Nothing here resembles a crystal.
     */
    private void drawSurfaceDetails(Canvas c, float camX, float camY, float left, float right, float time) {
        final float cell = 140f;
        final int dark = env.color[EnvironmentPalette.SILHOUETTE];
        final int blade = EnvironmentPalette.withAlpha(
                EnvironmentPalette.lerpColor(env.color[EnvironmentPalette.SHADE], dark, 0.45f), 175);
        final int glint = env.color[EnvironmentPalette.RIM];
        int k0 = (int) Math.floor(left / cell);
        int k1 = (int) Math.floor(right / cell);
        for (int k = k0; k <= k1; k++) {
            float tw = (float) Math.sin(time * 2.6f + k * 2.3f);
            if (tw > 0.55f) {
                float gx = k * cell + FastRandom.hash(k * 13 + 1) * cell;
                if (terrain.gapAt(gx) < 0) {
                    float gy = terrain.groundAt(gx) + 12f + FastRandom.hash(k * 29 + 5) * 26f;
                    float sz = 6f * (tw - 0.55f) / 0.45f;
                    stroke.setColor(EnvironmentPalette.withAlpha(glint, (int) (220f * (tw - 0.55f) / 0.45f)));
                    stroke.setStrokeWidth(1.8f);
                    c.drawLine(gx - sz - camX, gy - camY, gx + sz - camX, gy - camY, stroke);
                    c.drawLine(gx - camX, gy - sz - camY, gx - camX, gy + sz - camY, stroke);
                }
            }
            float h = FastRandom.hash(k * 31 + 7);
            if (h < 0.55f) {
                continue;
            }
            float wx = k * cell + FastRandom.hash(k * 17 + 3) * cell * 0.8f;
            if (terrain.gapNear(wx - 40f, wx + 40f) || terrain.rampAt(wx) >= 0) {
                continue;
            }
            float bx = wx - camX;
            float by = terrain.groundAt(wx) - camY;
            if (h < 0.99f) {
                float sway = (4f + 5f * weather.wind) * (float) Math.sin(time * (2.4f + 3f * weather.wind) + k)
                        - 4f * weather.wind;
                stroke.setColor(blade);
                stroke.setStrokeWidth(2f);
                for (int j = -1; j <= 1; j++) {
                    float len = 9f + 8f * FastRandom.hash(k * 5 + j);
                    c.drawLine(bx + j * 5f, by + 2f, bx + j * 8f + sway, by - len, stroke);
                }
            } else {
                float topY = by - 62f - 14f * FastRandom.hash(k * 7 + 11);
                stroke.setColor(dark);
                stroke.setStrokeWidth(3f);
                c.drawLine(bx, by + 4f, bx, topY, stroke);
                drawStreamer(c, bx, topY + 3f, 9f, time, k);
            }
        }
    }

    private void drawUpdrafts(Canvas c, float camX, float camY, float left, float right,
                              float top, float bottom, float time) {
        stroke.setStrokeWidth(6f);
        stroke.setColor(EnvironmentPalette.withAlpha(env.color[EnvironmentPalette.RIM], 42));
        for (int u = 0; u < WorldObjects.MAX_UPDRAFTS; u++) {
            if (!objs.upActive[u]) {
                continue;
            }
            float ux = objs.upX[u];
            float half = objs.upHalf[u];
            if (ux + half < left || ux - half > right) {
                continue;
            }
            float y1 = Math.min(objs.upBase[u], bottom);
            float y0 = Math.max(objs.upBase[u] - WorldObjects.UPDRAFT_HEIGHT, top);
            if (y1 <= y0) {
                continue;
            }
            for (int l = 0; l < 4; l++) {
                float lx = ux + (l - 1.5f) * half * 0.5f;
                path.rewind();
                float yy = (float) Math.floor(y1 / 48f) * 48f;
                boolean first = true;
                for (; yy >= y0; yy -= 48f) {
                    float wob = 16f * (float) Math.sin(yy * 0.012f + time * 4.2f + l * 1.7f + u);
                    if (first) {
                        path.moveTo(lx + wob - camX, yy - camY);
                        first = false;
                    } else {
                        path.lineTo(lx + wob - camX, yy - camY);
                    }
                }
                c.drawPath(path, stroke);
            }
        }
    }

    // ------------------------------------------------------------------ gameplay objects

    /**
     * Rope lines: a taut rope between two wooden posts that the rider can land on and grind.
     * Tassels sway under it, a lantern on each post is lit from dusk, and a pale highlight keeps
     * the rope readable against a dark sky. (The pools and the physics call these "rails".)
     */
    private void drawRails(Canvas c, float camX, float camY, float left, float right, float time) {
        final int wood = EnvironmentPalette.lerpColor(env.color[EnvironmentPalette.SILHOUETTE],
                env.color[EnvironmentPalette.MID], 0.30f);
        final int rope = EnvironmentPalette.lerpColor(env.color[EnvironmentPalette.SILHOUETTE],
                env.color[EnvironmentPalette.MID], 0.12f);
        final int shine = EnvironmentPalette.withAlpha(env.color[EnvironmentPalette.RIM], 150);
        final float lit = env.lights;
        final int glass = EnvironmentPalette.lerpColor(env.color[EnvironmentPalette.SILHOUETTE],
                env.color[EnvironmentPalette.LIGHT], lit);
        for (int r = 0; r < WorldObjects.MAX_RAILS; r++) {
            if (!objs.railActive[r] || objs.railX1[r] < left - 80f || objs.railX0[r] > right + 80f) {
                continue;
            }
            final float x0 = objs.railX0[r] - camX;
            final float y0 = objs.railY0[r] - camY;
            final float x1 = objs.railX1[r] - camX;
            final float y1 = objs.railY1[r] - camY;
            // a post with a guy rope and a lantern at each end
            for (int e = 0; e < 2; e++) {
                final float wx = e == 0 ? objs.railX0[r] : objs.railX1[r];
                if (terrain.gapAt(wx) >= 0) {
                    continue;
                }
                final float px = wx - camX;
                final float top = (e == 0 ? y0 : y1) - 20f;
                final float foot = terrain.groundAt(wx) + 3f - camY;
                final float out = e == 0 ? -1f : 1f;
                stroke.setColor(wood);
                if (terrain.gapAt(wx + out * 54f) < 0) {
                    stroke.setStrokeWidth(2.2f);
                    c.drawLine(px, top + 10f, px + out * 54f,
                            terrain.groundAt(wx + out * 54f) + 2f - camY, stroke);
                }
                stroke.setStrokeWidth(7f);
                c.drawLine(px, foot, px, top, stroke);
                final float flicker = 0.86f + 0.14f * (float) Math.sin(time * 3.3f + r * 2.1f + e);
                if (lit > 0.03f) {
                    drawGlow(c, env.lampGlow, px, top - 9f, 46f, 0.85f * lit * flicker);
                }
                fill.setColor(wood);
                c.drawRect(px - 7f, top - 19f, px + 7f, top - 16f, fill);
                fill.setColor(glass);
                c.drawRoundRect(px - 5f, top - 16f, px + 5f, top - 2f, 3f, 3f, fill);
            }
            // the rope itself, with a pale highlight along its upper edge
            stroke.setColor(rope);
            stroke.setStrokeWidth(4f);
            c.drawLine(x0, y0, x1, y1, stroke);
            stroke.setColor(shine);
            stroke.setStrokeWidth(1.4f);
            c.drawLine(x0, y0 - 1.6f, x1, y1 - 1.6f, stroke);
            // tassels
            final float len = objs.railX1[r] - objs.railX0[r];
            final int count = (int) (len / 120f);
            stroke.setColor(rope);
            stroke.setStrokeWidth(2f);
            for (int j = 1; j <= count; j++) {
                final float u = j / (float) (count + 1);
                final float tx = x0 + (x1 - x0) * u;
                final float ty = y0 + (y1 - y0) * u;
                final float drop = 9f + 9f * FastRandom.hash(j * 31 + r * 7);
                final float sway = (2f + 7f * weather.wind) * (float) Math.sin(time * 2.6f + j * 1.7f + r)
                        - 5f * weather.wind;
                c.drawLine(tx, ty + 2f, tx + sway, ty + 2f + drop, stroke);
            }
        }
    }

    private void drawCrystals(Canvas c, float camX, float camY, float left, float right, float crystalTime) {
        final int color = env.color[EnvironmentPalette.CRYSTAL];
        for (int i = 0; i < WorldObjects.MAX_CRYSTALS; i++) {
            if (!objs.cryActive[i] || objs.cryX[i] < left || objs.cryX[i] > right) {
                continue;
            }
            float bx = objs.cryX[i] - camX;
            float by = objs.cryY[i] - camY;
            float h = objs.cryH[i];
            float s = objs.crySeed[i];
            float tip = bx + 2f + 8f * s;
            float halo = 0.6f + 0.4f * (float) Math.sin(crystalTime * 4f + s * 20f);
            drawGlow(c, env.tealGlow, bx, by - h * 0.5f, h * 1.25f, 0.75f * halo);
            path.rewind();
            path.moveTo(bx - 28f, by + 8f);
            path.lineTo(bx - 14f, by - h * 0.60f);
            path.lineTo(bx - 5f, by - h * 0.44f);
            path.lineTo(tip, by - h);
            path.lineTo(bx + 16f, by - h * 0.52f);
            path.lineTo(bx + 22f, by - h * 0.66f);
            path.lineTo(bx + 30f, by + 8f);
            path.close();
            fill.setColor(EnvironmentPalette.withAlpha(color, 238));
            c.drawPath(path, fill);
            stroke.setColor(0x90FFFFFF);
            stroke.setStrokeWidth(2f);
            c.drawPath(path, stroke);
            path.rewind();
            path.moveTo(tip, by - h);
            path.lineTo(bx + 16f, by - h * 0.52f);
            path.lineTo(bx + 8f, by + 8f);
            path.lineTo(bx + 1f, by - h * 0.40f);
            path.close();
            fill.setColor(0x58FFFFFF);
            c.drawPath(path, fill);
            float tw = 0.5f + 0.5f * (float) Math.sin(crystalTime * 4f + s * 20f);
            drawGlow(c, env.orbGlow, tip, by - h, 16f + 10f * tw, 0.55f * tw);
        }
    }

    private void drawOrbs(Canvas c, float camX, float camY, float left, float right, float time) {
        for (int i = 0; i < WorldObjects.MAX_ORBS; i++) {
            if (!objs.orbActive[i] || objs.orbX[i] < left || objs.orbX[i] > right) {
                continue;
            }
            float ox = objs.orbX[i] - camX;
            float oy = objs.orbY[i] - camY + 5f * (float) Math.sin(time * 3.2f + objs.orbPhase[i]);
            if (objs.orbType[i] == WorldObjects.ORB_MAGNET) {
                drawGlow(c, env.goldGlow, ox, oy, 54f, 1f);
                stroke.setColor(0xFFFFE08A);
                stroke.setStrokeWidth(7f);
                c.drawArc(ox - 13f, oy - 13f, ox + 13f, oy + 13f, 0f, 180f, false, stroke);
                c.drawLine(ox - 13f, oy, ox - 13f, oy - 12f, stroke);
                c.drawLine(ox + 13f, oy, ox + 13f, oy - 12f, stroke);
                stroke.setColor(0xFFFFFFFF);
                c.drawLine(ox - 13f, oy - 13f, ox - 13f, oy - 16f, stroke);
                c.drawLine(ox + 13f, oy - 13f, ox + 13f, oy - 16f, stroke);
            } else if (objs.orbType[i] == WorldObjects.ORB_HOVER) {
                // Zephyr Crest: two stacked wing chevrons in a teal halo.
                drawGlow(c, env.tealGlow, ox, oy, 56f, 1f);
                stroke.setColor(0xFFFFFFFF);
                stroke.setStrokeWidth(5f);
                c.drawLine(ox - 17f, oy + 2f, ox, oy - 11f, stroke);
                c.drawLine(ox, oy - 11f, ox + 17f, oy + 2f, stroke);
                stroke.setColor(0xFF7CFFF0);
                c.drawLine(ox - 12f, oy + 13f, ox, oy + 3f, stroke);
                c.drawLine(ox, oy + 3f, ox + 12f, oy + 13f, stroke);
            } else if (objs.orbType[i] == WorldObjects.ORB_WISP) {
                // Light wisp: a small golden flame that flickers and darts about.
                float wy = oy + 7f * (float) Math.sin(time * 6.5f + objs.orbPhase[i] * 3f);
                float flick = 0.75f + 0.25f * (float) Math.sin(time * 17f + objs.orbPhase[i] * 7f);
                drawGlow(c, env.goldGlow, ox, wy, 26f * flick, 0.8f);
                path.rewind();
                path.moveTo(ox, wy - 15f * flick);
                path.quadTo(ox + 9f, wy - 2f, ox, wy + 8f);
                path.quadTo(ox - 9f, wy - 2f, ox, wy - 15f * flick);
                path.close();
                fill.setColor(0xFFFFF3C4);
                c.drawPath(path, fill);
            } else {
                // energy sphere: a small golden coin-like orb
                drawGlow(c, env.goldGlow, ox, oy, 27f + 4f * (float) Math.sin(time * 5f + objs.orbPhase[i]), 1f);
                fill.setColor(0xFFFFD86B);
                c.drawCircle(ox, oy, 8f, fill);
                fill.setColor(0xFFFFF3C4);
                c.drawCircle(ox - 2f, oy - 2f, 3.4f, fill);
            }
        }
    }

    // ------------------------------------------------------------------ rider

    private void drawTrail(Canvas c, float camX, float camY, float flowGlow) {
        final float dash = player.ragdoll ? 0f : clamp01(player.dashTime * 2.5f);
        float strength = Math.max(Math.max(env.night, flowGlow), dash);
        int count = player.trailCount;
        if (strength < 0.03f || count < 2) {
            return;
        }
        int color = flowGlow >= strength ? 0xFFFFD86B
                : (dash >= strength ? env.color[EnvironmentPalette.RIM] : env.color[EnvironmentPalette.ACCENT]);
        final int n = Player.TRAIL_POINTS;
        int idx = (player.trailHead - count + n) % n;
        float prevX = player.trailX[idx] - camX;
        float prevY = player.trailY[idx] - camY;
        for (int j = 1; j < count; j++) {
            idx = (idx + 1) % n;
            float x = player.trailX[idx] - camX;
            float y = player.trailY[idx] - camY;
            float k = j / (float) count;
            stroke.setColor(EnvironmentPalette.withAlpha(color, (int) (150f * k * strength)));
            stroke.setStrokeWidth(3f + 9f * k);
            c.drawLine(prevX, prevY, x, y, stroke);
            prevX = x;
            prevY = y;
        }
    }

    private void drawScarf(Canvas c, float camX, float camY, float alpha) {
        int scarf = player.scarfColor != 0 ? player.scarfColor : env.color[EnvironmentPalette.SCARF];
        if (player.scarfGlow > 0.01f) {
            scarf = EnvironmentPalette.lerpColor(scarf, 0xFFFFE08A, player.scarfGlow * 0.85f);
        }
        stroke.setColor(scarf);
        final int n = Player.SCARF_NODES;
        float x0 = player.scarfPrevX[0] + (player.scarfX[0] - player.scarfPrevX[0]) * alpha - camX;
        float y0 = player.scarfPrevY[0] + (player.scarfY[0] - player.scarfPrevY[0]) * alpha - camY;
        for (int i = 1; i < n; i++) {
            float x1 = player.scarfPrevX[i] + (player.scarfX[i] - player.scarfPrevX[i]) * alpha - camX;
            float y1 = player.scarfPrevY[i] + (player.scarfY[i] - player.scarfPrevY[i]) * alpha - camY;
            stroke.setStrokeWidth(9.5f - 6f * i / (float) n);
            c.drawLine(x0, y0, x1, y1, stroke);
            x0 = x1;
            y0 = y1;
        }
    }

    private void drawPlayer(Canvas c, float x, float y, float angle, float wing, float flowGlow,
                            boolean shield, float time) {
        matrix.reset();
        matrix.setTranslate(x, y);
        matrix.preRotate(angle * 57.29578f);
        c.save();
        c.concat(matrix);
        if (player.hoverLift > 2f) {
            // cushion of light under the hovering board
            c.save();
            c.scale(1f, 0.4f);
            drawGlow(c, env.tealGlow, 0f, 40f, 62f, player.hoverLift / 48f);
            c.restore();
        }
        c.scale(player.bodyScale, player.bodyScale);
        if (player.ragdoll) {
            // the pivot is the centre of mass, not the board
            c.translate(0f, 34f);
        }

        if (flowGlow > 0.01f) {
            float pulse = 0.85f + 0.15f * (float) Math.sin(time * 9f);
            drawGlow(c, env.goldGlow, 0f, -34f, 120f * pulse, flowGlow);
            drawGlow(c, env.tealGlow, 0f, -34f, 70f, flowGlow * 0.8f);
        }
        if (shield) {
            stroke.setColor(EnvironmentPalette.withAlpha(env.color[EnvironmentPalette.ACCENT],
                    (int) (150f + 70f * (float) Math.sin(time * 6f))));
            stroke.setStrokeWidth(3.5f);
            c.drawCircle(0f, -34f, 54f, stroke);
            fill.setColor(EnvironmentPalette.withAlpha(env.color[EnvironmentPalette.ACCENT], 26));
            c.drawCircle(0f, -34f, 54f, fill);
        }

        // ---- dash after a landed trick: a bow wave of air ahead of the rider ----
        final float dash = player.ragdoll ? 0f : clamp01(player.dashTime * 2.5f);
        if (dash > 0.02f) {
            final int air = env.color[EnvironmentPalette.RIM];
            stroke.setColor(EnvironmentPalette.withAlpha(air, (int) (215f * dash)));
            stroke.setStrokeWidth(3.2f);
            c.drawArc(14f, -74f, 62f, 8f, -62f, 124f, false, stroke);
            stroke.setColor(EnvironmentPalette.withAlpha(air, (int) (115f * dash)));
            stroke.setStrokeWidth(2.2f);
            c.drawArc(32f, -66f, 78f, 0f, -52f, 104f, false, stroke);
        }

        // ---- energy wing ----
        if (wing > 0.03f) {
            int wingColor = EnvironmentPalette.lerpColor(env.color[EnvironmentPalette.ACCENT], 0xFFFFD86B, flowGlow * 0.6f);
            c.save();
            c.translate(0f, -46f);
            c.scale(wing, 0.35f + 0.65f * wing);
            path.rewind();
            path.moveTo(0f, 0f);
            path.lineTo(-98f, -54f);
            path.lineTo(-72f, -16f);
            path.lineTo(-116f, 6f);
            path.lineTo(-62f, 16f);
            path.lineTo(-10f, 13f);
            path.close();
            path.moveTo(6f, 2f);
            path.lineTo(62f, -30f);
            path.lineTo(46f, 4f);
            path.lineTo(10f, 11f);
            path.close();
            fill.setColor(EnvironmentPalette.withAlpha(wingColor, 130));
            c.drawPath(path, fill);
            stroke.setColor(0xD8FFFFFF);
            stroke.setStrokeWidth(2.2f);
            c.drawPath(path, stroke);
            stroke.setColor(EnvironmentPalette.withAlpha(wingColor, 220));
            c.drawLine(0f, 0f, -98f, -54f, stroke);
            c.drawLine(-10f, 8f, -116f, 6f, stroke);
            c.restore();
        }

        final int body = env.color[EnvironmentPalette.SILHOUETTE];
        if (!player.boardLoose) {
            drawBoard(c, body);
        }

        // ---- articulated body: joints come from the spring-driven rig in Player ----
        final float hipX = player.hipX, hipY = player.hipY;
        final float shX = player.shoulderX, shY = player.shoulderY;
        final float k = player.crouch;
        final float tuck = player.tuck;
        final float air = player.airPose;
        float backFootX = -12f, backFootY = -7f, frontFootX = 13f, frontFootY = -8f;
        // Rag-doll secondary motion: limbs lag behind the spin and are flung outwards by it.
        float lag = -player.angVel * 0.07f;
        if (lag > 0.9f) {
            lag = 0.9f;
        } else if (lag < -0.9f) {
            lag = -0.9f;
        }
        float fling = (player.angVel < 0f ? -player.angVel : player.angVel) / 9f;
        if (fling > 1f) {
            fling = 1f;
        }
        final float lagC = (float) Math.cos(lag), lagS = (float) Math.sin(lag);
        if (player.ragdoll) {
            float lx = -6f - 9f * fling, ly = 25f;
            backFootX = hipX + lx * lagC - ly * lagS;
            backFootY = hipY + lx * lagS + ly * lagC;
            lx = 7f + 10f * fling;
            ly = 24f;
            frontFootX = hipX + lx * lagC - ly * lagS;
            frontFootY = hipY + lx * lagS + ly * lagC;
        }
        final float backKneeX = (backFootX + hipX) * 0.5f + 4f + 7f * k;
        final float backKneeY = (backFootY + hipY) * 0.5f - 1f;
        final float frontKneeX = (frontFootX + hipX) * 0.5f + 6f + 8f * k;
        final float frontKneeY = (frontFootY + hipY) * 0.5f - 1f;

        stroke.setColor(body);
        stroke.setStrokeWidth(6.5f);
        c.drawLine(backFootX, backFootY, backKneeX, backKneeY, stroke);
        c.drawLine(backKneeX, backKneeY, hipX, hipY, stroke);
        c.drawLine(frontFootX, frontFootY, frontKneeX, frontKneeY, stroke);
        c.drawLine(frontKneeX, frontKneeY, hipX, hipY, stroke);
        stroke.setStrokeWidth(12.5f);
        c.drawLine(hipX, hipY, shX, shY, stroke);

        // hands: blend ground swing -> balancing in the air -> tucked to the knees -> wing grip
        float swing = (float) Math.sin(time * 7f);
        float backHandX = shX - 13f - 3f * swing, backHandY = shY + 14f;
        float frontHandX = shX + 13f + 2f * swing, frontHandY = shY + 13f;
        float bal = (float) Math.sin(time * 9f);
        backHandX += (shX - 18f - backHandX) * air;
        backHandY += (shY - 5f + 4f * bal - backHandY) * air;
        frontHandX += (shX + 16f - frontHandX) * air;
        frontHandY += (shY - 8f - 3f * bal - frontHandY) * air;
        backHandX += (backKneeX - backHandX) * tuck;
        backHandY += (backKneeY - backHandY) * tuck;
        frontHandX += (frontKneeX - frontHandX) * tuck;
        frontHandY += (frontKneeY - frontHandY) * tuck;
        backHandX += (shX - 21f - backHandX) * wing;
        backHandY += (shY - 7f - backHandY) * wing;
        frontHandX += (shX + 17f - frontHandX) * wing;
        frontHandY += (shY + 4f - frontHandY) * wing;
        if (player.ragdoll) {
            float lx = -15f - 7f * fling, ly = -5f - 13f * fling;
            backHandX = shX + lx * lagC - ly * lagS;
            backHandY = shY + lx * lagS + ly * lagC;
            lx = 16f + 7f * fling;
            ly = -3f - 13f * fling;
            frontHandX = shX + lx * lagC - ly * lagS;
            frontHandY = shY + lx * lagS + ly * lagC;
        }
        stroke.setStrokeWidth(5f);
        float elbowX = (shX + backHandX) * 0.5f - 2f, elbowY = (shY + backHandY) * 0.5f + 3f;
        c.drawLine(shX, shY, elbowX, elbowY, stroke);
        c.drawLine(elbowX, elbowY, backHandX, backHandY, stroke);
        elbowX = (shX + frontHandX) * 0.5f + 1f;
        elbowY = (shY + frontHandY) * 0.5f + 3f;
        c.drawLine(shX, shY, elbowX, elbowY, stroke);
        c.drawLine(elbowX, elbowY, frontHandX, frontHandY, stroke);

        final float headX = player.headX, headY = player.headY;
        fill.setColor(body);
        c.drawCircle(headX, headY, 8.6f, fill);
        // hood tail
        path.rewind();
        path.moveTo(headX - 6f, headY - 6f);
        path.lineTo(headX - 17f, headY - 1f);
        path.lineTo(headX - 7f, headY + 6f);
        path.close();
        c.drawPath(path, fill);

        c.restore();
    }

    private void drawBoard(Canvas c, int body) {
        path.rewind();
        path.moveTo(-36f, -3f);
        path.quadTo(0f, 4f, 38f, -5f);
        path.lineTo(43f, -11f);
        path.quadTo(0f, -2f, -36f, -8f);
        path.close();
        fill.setColor(body);
        c.drawPath(path, fill);
        stroke.setColor(EnvironmentPalette.withAlpha(env.color[EnvironmentPalette.ACCENT], 200));
        stroke.setStrokeWidth(1.8f);
        c.drawLine(-32f, -3f, 36f, -5f, stroke);
    }

    /** The board after a crash: it has its own position and spin. */
    private void drawLooseBoard(Canvas c, float camX, float camY) {
        matrix.reset();
        matrix.setTranslate(player.boardX - camX, player.boardY - camY);
        matrix.preRotate(player.boardAngle * 57.29578f);
        c.save();
        c.concat(matrix);
        drawBoard(c, env.color[EnvironmentPalette.SILHOUETTE]);
        c.restore();
    }

    // ------------------------------------------------------------------ overlays

    private void drawPopups(Canvas c, float camX, float camY, float zoom) {
        for (int i = 0; i < WorldObjects.MAX_POPUPS; i++) {
            if (!objs.popActive[i]) {
                continue;
            }
            float k = objs.popLife[i] / WorldObjects.POPUP_LIFE;
            int alpha = (int) (255f * (k > 0.6f ? 1f : k / 0.6f));
            float pop = k > 0.86f ? 1f + 0.5f * (k - 0.86f) / 0.14f : 1f;
            text.setTextSize(30f * pop / zoom);
            buf.clear();
            if (objs.popLabel[i] >= 0) {
                buf.add(strings.get(objs.popLabel[i]));
            }
            if (objs.popValue[i] > 0) {
                if (objs.popLabel[i] >= 0) {
                    buf.add(' ');
                }
                buf.add('+').add(objs.popValue[i]);
            }
            float tx = objs.popX[i] - camX;
            float ty = objs.popY[i] - camY;
            text.setColor(EnvironmentPalette.withAlpha(0xFF10182C, alpha / 2));
            buf.draw(c, tx + 2f / zoom, ty + 2f / zoom, text);
            text.setColor(EnvironmentPalette.withAlpha(objs.popColor[i], alpha));
            buf.draw(c, tx, ty, text);
        }
    }

    /** Horizontal wind streaks at high speed and during Zenith Flow. */
    private void drawSpeedLines(Canvas c, float time, float strength) {
        final float loop = vw + 500f;
        stroke.setStrokeWidth(2.5f);
        for (int i = 0; i < 9; i++) {
            float m = time * (1500f + 260f * (i % 3)) + i * 377f;
            m -= loop * (float) Math.floor(m / loop);
            float x = vw + 200f - m;
            float y = vh * (0.08f + 0.86f * FastRandom.hash(i * 19 + 4));
            float len = 120f + 140f * FastRandom.hash(i * 7 + 9);
            stroke.setColor(EnvironmentPalette.withAlpha(env.color[EnvironmentPalette.RIM],
                    (int) (strength * (40f + 12f * (i % 4)))));
            c.drawLine(x, y, x + len, y, stroke);
        }
    }

    /** Dark firs sweeping past close to the camera, their feet lost in ground fog. */
    private void drawForeground(Canvas c, float camX) {
        final float off = camX * 1.35f;
        final float cell = 1300f;
        final int k0 = (int) Math.floor((off - 400f) / cell);
        final int k1 = (int) Math.floor((off + vw + 400f) / cell);
        final float base = vh + 34f;
        path.rewind();
        for (int k = k0; k <= k1; k++) {
            float h = FastRandom.hash(k * 11 + 3);
            if (h < 0.40f) {
                continue;
            }
            float sx = k * cell + FastRandom.hash(k * 5 + 1) * 700f - off;
            float s = 190f + h * 80f;
            addFirTiers(sx, base, s, 1.15f);
            if (h > 0.72f) {
                addFirTiers(sx + s * 0.44f, base + 12f, s * 0.64f, 1.15f);
            }
        }
        fill.setColor(EnvironmentPalette.withAlpha(env.color[EnvironmentPalette.FORE], 242));
        c.drawPath(path, fill);
        mist(c, vh - 170f, 170f, 0.34f + 0.30f * weather.fog);
    }
}
