package com.ccs.zenith_rider.graphics;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * Fixed-capacity particle pool stored as primitive arrays. Live particles occupy the first
 * {@code count} slots; a dead particle is replaced by the last live one (swap-remove), so both
 * spawning and freeing are O(1) and never allocate.
 */
public final class ParticlePool {

    public static final int MAX = 720;

    public static final int DUST = 0;
    public static final int SPARK = 1;
    public static final int SHARD = 2;
    public static final int WISP = 3;
    public static final int GLOW = 4;

    private final float[] x = new float[MAX];
    private final float[] y = new float[MAX];
    private final float[] vx = new float[MAX];
    private final float[] vy = new float[MAX];
    private final float[] life = new float[MAX];
    private final float[] maxLife = new float[MAX];
    private final float[] size = new float[MAX];
    private final float[] gravity = new float[MAX];
    private final int[] color = new int[MAX];
    private final int[] type = new int[MAX];
    private int count;

    public void clear() {
        count = 0;
    }

    public int count() {
        return count;
    }

    public void spawn(int kind, float px, float py, float pvx, float pvy,
                      float lifeSeconds, float radius, int argb, float grav) {
        if (count >= MAX) {
            return;
        }
        int i = count++;
        type[i] = kind;
        x[i] = px;
        y[i] = py;
        vx[i] = pvx;
        vy[i] = pvy;
        life[i] = lifeSeconds;
        maxLife[i] = lifeSeconds;
        size[i] = radius;
        color[i] = argb;
        gravity[i] = grav;
    }

    public void update(float dt) {
        int i = 0;
        while (i < count) {
            life[i] -= dt;
            if (life[i] <= 0f) {
                int last = --count;
                if (i != last) {
                    type[i] = type[last];
                    x[i] = x[last];
                    y[i] = y[last];
                    vx[i] = vx[last];
                    vy[i] = vy[last];
                    life[i] = life[last];
                    maxLife[i] = maxLife[last];
                    size[i] = size[last];
                    color[i] = color[last];
                    gravity[i] = gravity[last];
                }
                continue;
            }
            vy[i] += gravity[i] * dt;
            if (type[i] == DUST) {
                vx[i] *= 1f - 1.6f * dt;
                vy[i] *= 1f - 1.6f * dt;
            } else if (type[i] == WISP) {
                vx[i] = (float) Math.sin(life[i] * 5f + x[i] * 0.02f) * 46f;
            }
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
            i++;
        }
    }

    /**
     * Draws all particles relative to the camera. {@code ahead} is the time since the last physics
     * step and is used to extrapolate positions for smooth motion on high refresh-rate screens.
     */
    public void draw(Canvas canvas, Paint fill, Paint stroke, float camX, float camY,
                     float left, float right, float ahead) {
        for (int i = 0; i < count; i++) {
            float px = x[i] + vx[i] * ahead;
            if (px < left || px > right) {
                continue;
            }
            float py = y[i] + vy[i] * ahead;
            float k = life[i] / maxLife[i];
            int base = color[i];
            int alpha = (int) ((base >>> 24) * (k > 1f ? 1f : k));
            int argb = (alpha << 24) | (base & 0x00FFFFFF);
            float sx = px - camX;
            float sy = py - camY;
            switch (type[i]) {
                case SPARK: {
                    stroke.setColor(argb);
                    stroke.setStrokeWidth(size[i]);
                    canvas.drawLine(sx, sy, sx - vx[i] * 0.035f, sy - vy[i] * 0.035f, stroke);
                    break;
                }
                case SHARD: {
                    float a = life[i] * 9f + size[i];
                    float dx = (float) Math.cos(a) * size[i];
                    float dy = (float) Math.sin(a) * size[i];
                    stroke.setColor(argb);
                    stroke.setStrokeWidth(size[i] * 0.7f);
                    canvas.drawLine(sx - dx, sy - dy, sx + dx, sy + dy, stroke);
                    break;
                }
                case DUST: {
                    fill.setColor(argb);
                    canvas.drawCircle(sx, sy, size[i] * (1.6f - 0.6f * k), fill);
                    break;
                }
                default: {
                    fill.setColor(argb);
                    canvas.drawCircle(sx, sy, size[i] * (0.4f + 0.6f * k), fill);
                    break;
                }
            }
        }
    }
}
