package com.ccs.zenith_rider.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;

import com.ccs.zenith_rider.engine.GameEngine;
import com.ccs.zenith_rider.model.MissionManager;
import com.ccs.zenith_rider.model.UpgradeManager;
import com.ccs.zenith_rider.storage.SaveManager;

/**
 * In-game HUD and every menu screen (main menu, shop, missions, pre-run modifiers, pause and
 * results), drawn directly on the Canvas in virtual 720p coordinates. The same rectangle table
 * is used for drawing buttons and for touch hit-testing. Dynamic text goes through a reusable
 * char buffer, so drawing never allocates.
 */
public final class HudAndMenuRenderer {

    public static final int B_PLAY = 0;
    public static final int B_SHOP = 1;
    public static final int B_MISSIONS = 2;
    public static final int B_LANG = 3;
    public static final int B_SOUND = 4;
    public static final int B_BACK = 5;
    public static final int B_CARD0 = 6;   // ..11 shop grid cards
    public static final int B_TAB0 = 12;   // ..14 shop tabs
    public static final int B_SEL0 = 15;   // ..19 pre-run modifier cards
    public static final int B_START = 20;
    public static final int B_PAUSE = 21;
    public static final int B_RESUME = 22;
    public static final int B_QUIT = 23;
    public static final int B_RETRY = 24;
    public static final int B_MENU = 25;
    public static final int B_PAUSE_SOUND = 26;
    public static final int B_SHOP_BACK = 27;
    public static final int BUTTONS = 28;

    public static final int CARDS = 6;
    public static final int TABS = 3;
    private static final int[] TAB_CARDS = {SaveManager.UPGRADE_COUNT + 1, SaveManager.PERK_COUNT, UpgradeManager.RIDER_COUNT};
    private static final float BAR_H = 80f;
    private float rowH = 96f, listX = 120f, listW = 1000f;
    private static final float PRICE_W = 214f;

    /** Shop tab currently shown (set by the engine): decides which cards can be hit. */
    public int shopTab;

    public void setShopTab(int tab) {
        shopTab = tab;
        layoutShop();
    }

    /** Shop list: one row per item, the price box on the right is the button. */
    private void layoutShop() {
        final float side = 90f;
        int n = TAB_CARDS[shopTab];
        float top = 98f;
        float avail = vh - BAR_H - 14f - top;
        float gap = 9f;
        rowH = (avail - (n - 1) * gap) / n;
        listX = side + 30f;
        listW = vw - 2f * side - 60f;
        for (int i = 0; i < CARDS; i++) {
            set(B_CARD0 + i, listX + listW - PRICE_W, top + i * (rowH + gap), PRICE_W, rowH);
        }
    }

    private static final int WHITE = 0xFFFFFFFF;
    private static final int TEAL = 0xFF40E8D8;
    private static final int GOLD = 0xFFFFD86B;
    private static final int INK = 0xFF0B1230;
    private static final int PANEL = 0x8A0B1230;
    private static final int DIM_TEXT = 0xB4FFFFFF;

    private final float[] bx = new float[BUTTONS];
    private final float[] by = new float[BUTTONS];
    private final float[] bw = new float[BUTTONS];
    private final float[] bh = new float[BUTTONS];

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Light face for the in-run read-outs, so the HUD sits quietly on top of the scenery. */
    private final Paint thin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path path2 = new Path();
    private final TextBuf buf = new TextBuf();

    private float vw = 1280f;
    private float vh = 720f;

    public HudAndMenuRenderer() {
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        titlePaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        titlePaint.setLetterSpacing(0.16f);
        thin.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        thin.setLetterSpacing(0.03f);
    }

    // ------------------------------------------------------------------ layout & hit testing

    private void set(int id, float x, float y, float w, float h) {
        bx[id] = x;
        by[id] = y;
        bw[id] = w;
        bh[id] = h;
    }

    public void layout(float width, float height) {
        vw = width;
        vh = height;
        final float cx = width * 0.5f;
        final float side = 90f;

        set(B_PLAY, side, 262f, 380f, 100f);
        set(B_SHOP, side, 382f, 380f, 74f);
        set(B_MISSIONS, side, 474f, 380f, 74f);
        set(B_LANG, width - side - 176f, 40f, 176f, 68f);
        set(B_SOUND, width - side - 176f - 88f, 40f, 68f, 68f);
        set(B_BACK, side, 36f, 150f, 64f);

        // shop: three tabs on top, list rows, bottom bar with Back
        for (int t = 0; t < TABS; t++) {
            set(B_TAB0 + t, cx - 366f + t * 248f, 22f, 236f, 58f);
        }
        layoutShop();
        set(B_SHOP_BACK, 0f, height - BAR_H, 300f, BAR_H);
        float perkW = (width - 2f * side - 4f * 16f) / SaveManager.PERK_COUNT;
        for (int i = 0; i < SaveManager.PERK_COUNT; i++) {
            set(B_SEL0 + i, side + i * (perkW + 16f), 150f, perkW, 390f);
        }
        set(B_START, cx - 200f, 580f, 400f, 92f);
        set(B_PAUSE, width - side - 76f, 26f, 76f, 76f);
        set(B_RESUME, cx - 210f, 210f, 420f, 88f);
        set(B_PAUSE_SOUND, cx - 210f, 314f, 420f, 72f);
        set(B_QUIT, cx - 210f, 402f, 420f, 72f);
        set(B_RETRY, width - 400f, height - BAR_H, 400f, BAR_H);
        set(B_MENU, 0f, height - BAR_H, 340f, BAR_H);
    }

    private boolean visible(int id, int state) {
        switch (state) {
            case GameEngine.ST_MENU:
                return id <= B_SOUND;
            case GameEngine.ST_SHOP:
                return id == B_SHOP_BACK || (id >= B_TAB0 && id < B_TAB0 + TABS)
                        || (id >= B_CARD0 && id < B_CARD0 + TAB_CARDS[shopTab]);
            case GameEngine.ST_MISSIONS:
                return id == B_BACK;
            case GameEngine.ST_PERKS:
                return id == B_BACK || id == B_START || (id >= B_SEL0 && id < B_START);
            case GameEngine.ST_PLAY:
                return id == B_PAUSE;
            case GameEngine.ST_PAUSE:
                return id == B_RESUME || id == B_QUIT || id == B_PAUSE_SOUND;
            case GameEngine.ST_OVER:
                return id == B_RETRY || id == B_MENU;
            default:
                return false;
        }
    }

    /** Returns the id of the button under the point for the given screen, or -1. */
    public int hit(int state, float x, float y) {
        for (int id = 0; id < BUTTONS; id++) {
            if (visible(id, state) && x >= bx[id] - 8f && x <= bx[id] + bw[id] + 8f
                    && y >= by[id] - 8f && y <= by[id] + bh[id] + 8f) {
                return id;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ primitives

    private void label(Canvas c, String s, float x, float y, float size, int color, Paint.Align align) {
        text.setTextSize(size);
        text.setTextAlign(align);
        text.setColor(color);
        c.drawText(s, x, y, text);
    }

    private void labelBuf(Canvas c, float x, float y, float size, int color, Paint.Align align) {
        text.setTextSize(size);
        text.setTextAlign(align);
        text.setColor(color);
        buf.draw(c, x, y, text);
    }

    /** Text with a soft dark drop shadow, readable over both snow and sky. */
    /** Buffer text in the light face with a faint shadow. */
    private void thinBuf(Canvas c, float x, float y, float size, int color, Paint.Align align) {
        thin.setTextSize(size);
        thin.setTextAlign(align);
        thin.setColor(0x660B1230);
        buf.draw(c, x + 1.5f, y + 2f, thin);
        thin.setColor(color);
        buf.draw(c, x, y, thin);
    }

    private void shadowBuf(Canvas c, float x, float y, float size, int color, Paint.Align align) {
        labelBuf(c, x + 2f, y + 2.5f, size, 0x8C0B1230, align);
        labelBuf(c, x, y, size, color, align);
    }

    private void shadowLabel(Canvas c, String s, float x, float y, float size, int color, Paint.Align align) {
        label(c, s, x + 2f, y + 2.5f, size, 0x8C0B1230, align);
        label(c, s, x, y, size, color, align);
    }

    private void panel(Canvas c, float x, float y, float w, float h, int color) {
        fill.setColor(color);
        c.drawRoundRect(x, y, x + w, y + h, 22f, 22f, fill);
    }

    private void outline(Canvas c, float x, float y, float w, float h, int color, float width) {
        stroke.setColor(color);
        stroke.setStrokeWidth(width);
        c.drawRoundRect(x, y, x + w, y + h, 22f, 22f, stroke);
    }

    private void orbIcon(Canvas c, float x, float y, float r) {
        fill.setColor(0x40FFD86B);
        c.drawCircle(x, y, r * 1.7f, fill);
        fill.setColor(GOLD);
        c.drawCircle(x, y, r, fill);
        fill.setColor(0xFFFFF3C4);
        c.drawCircle(x - r * 0.25f, y - r * 0.25f, r * 0.42f, fill);
    }

    /** Draws a wrapped paragraph without allocating; returns the number of lines drawn. */
    private int wrapped(Canvas c, String s, float x, float y, float maxW, float lineH,
                        float size, int color) {
        text.setTextSize(size);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(color);
        final int n = s.length();
        int lineStart = 0;
        int lastSpace = -1;
        int lines = 0;
        for (int i = 0; i <= n; i++) {
            if (i == n || s.charAt(i) == ' ') {
                float w = text.measureText(s, lineStart, i);
                if (w > maxW && lastSpace > lineStart) {
                    c.drawText(s, lineStart, lastSpace, x, y + lines * lineH, text);
                    lines++;
                    lineStart = lastSpace + 1;
                }
                lastSpace = i;
            }
        }
        if (lineStart < n) {
            c.drawText(s, lineStart, n, x, y + lines * lineH, text);
            lines++;
        }
        return lines;
    }

    private static final int STYLE_PRIMARY = 0;
    private static final int STYLE_SECONDARY = 1;
    private static final int STYLE_DISABLED = 2;

    private void buttonBox(Canvas c, GameEngine g, int id, int style) {
        boolean pressed = g.pressedBtn == id;
        float x = bx[id], y = by[id], w = bw[id], h = bh[id];
        if (pressed) {
            x += 3f;
            y += 3f;
            w -= 6f;
            h -= 6f;
        }
        float r = Math.min(h * 0.5f, 26f);
        if (style == STYLE_PRIMARY) {
            fill.setColor(pressed ? 0xFFD3E0E8 : 0xF2FFFFFF);
        } else if (style == STYLE_SECONDARY) {
            fill.setColor(pressed ? 0xB80B1230 : 0x8A0B1230);
        } else {
            fill.setColor(0x90303850);
        }
        c.drawRoundRect(x, y, x + w, y + h, r, r, fill);
        if (style == STYLE_SECONDARY) {
            stroke.setColor(0x30FFFFFF);
            stroke.setStrokeWidth(1.5f);
            c.drawRoundRect(x, y, x + w, y + h, r, r, stroke);
        }
    }

    private void button(Canvas c, GameEngine g, int id, String caption, int style, float size) {
        buttonBox(c, g, id, style);
        int color = style == STYLE_PRIMARY ? INK : (style == STYLE_DISABLED ? 0x90FFFFFF : WHITE);
        label(c, caption, bx[id] + bw[id] * 0.5f, by[id] + bh[id] * 0.5f + size * 0.35f, size, color,
                Paint.Align.CENTER);
    }

    private void speakerIcon(Canvas c, float cx, float cy, boolean on, int color) {
        fill.setColor(color);
        path.rewind();
        path.moveTo(cx - 16f, cy - 7f);
        path.lineTo(cx - 8f, cy - 7f);
        path.lineTo(cx + 3f, cy - 16f);
        path.lineTo(cx + 3f, cy + 16f);
        path.lineTo(cx - 8f, cy + 7f);
        path.lineTo(cx - 16f, cy + 7f);
        path.close();
        c.drawPath(path, fill);
        stroke.setColor(color);
        stroke.setStrokeWidth(3.2f);
        if (on) {
            c.drawArc(cx - 4f, cy - 9f, cx + 12f, cy + 9f, -55f, 110f, false, stroke);
            c.drawArc(cx - 6f, cy - 16f, cx + 20f, cy + 16f, -55f, 110f, false, stroke);
        } else {
            c.drawLine(cx + 9f, cy - 7f, cx + 21f, cy + 7f, stroke);
            c.drawLine(cx + 21f, cy - 7f, cx + 9f, cy + 7f, stroke);
        }
    }

    private void dim(Canvas c, int color) {
        fill.setColor(color);
        c.drawRect(0f, 0f, vw, vh, fill);
    }

    // ------------------------------------------------------------------ entry point

    public void draw(Canvas c, GameEngine g) {
        switch (g.state) {
            case GameEngine.ST_MENU:
                drawMenu(c, g);
                break;
            case GameEngine.ST_SHOP:
                drawShop(c, g);
                break;
            case GameEngine.ST_MISSIONS:
                drawMissions(c, g);
                break;
            case GameEngine.ST_PERKS:
                drawPerks(c, g);
                break;
            case GameEngine.ST_PLAY:
            case GameEngine.ST_DYING:
                drawHud(c, g);
                break;
            case GameEngine.ST_PAUSE:
                drawHud(c, g);
                drawPause(c, g);
                break;
            default:
                drawOver(c, g);
                break;
        }
    }

    // ------------------------------------------------------------------ HUD

    private void drawHud(Canvas c, GameEngine g) {
        final Strings s = g.strings;
        final float side = 90f;

        // distance and score
        buf.clear().add((int) g.distance).add(' ').add(s.get(Strings.METERS));
        thinBuf(c, side, 74f, 56f, WHITE, Paint.Align.LEFT);
        buf.clear().add(s.get(Strings.SCORE)).add(' ').add(g.score);
        thinBuf(c, side + 3f, 106f, 23f, 0xD0FFFFFF, Paint.Align.LEFT);

        // active modifiers
        float ix = side + 14f;
        final float iy = 142f;
        if (g.player.shield) {
            stroke.setColor(TEAL);
            stroke.setStrokeWidth(3.5f);
            c.drawCircle(ix, iy, 13f, stroke);
            fill.setColor(0x5040E8D8);
            c.drawCircle(ix, iy, 13f, fill);
            ix += 42f;
        }
        if (g.doubleOrbs) {
            label(c, "x2", ix - 12f, iy + 9f, 26f, GOLD, Paint.Align.LEFT);
            ix += 48f;
        }
        if (g.magnetTime > 0f) {
            stroke.setColor(0x60FFFFFF);
            stroke.setStrokeWidth(4f);
            c.drawCircle(ix, iy, 13f, stroke);
            stroke.setColor(GOLD);
            c.drawArc(ix - 13f, iy - 13f, ix + 13f, iy + 13f, -90f,
                    360f * g.magnetTime / g.magnetDuration, false, stroke);
            fill.setColor(GOLD);
            c.drawCircle(ix, iy, 5f, fill);
            ix += 42f;
        }
        if (g.player.tether) {
            stroke.setColor(TEAL);
            stroke.setStrokeWidth(3.5f);
            c.drawLine(ix, iy - 14f, ix, iy + 4f, stroke);
            c.drawArc(ix - 9f, iy - 6f, ix + 9f, iy + 13f, 0f, 180f, false, stroke);
            ix += 42f;
        }
        if (g.hoverTime > 0f) {
            stroke.setColor(0x60FFFFFF);
            stroke.setStrokeWidth(4f);
            c.drawCircle(ix, iy, 13f, stroke);
            stroke.setColor(TEAL);
            c.drawArc(ix - 13f, iy - 13f, ix + 13f, iy + 13f, -90f,
                    360f * g.hoverTime / g.hoverDuration, false, stroke);
            c.drawLine(ix - 6f, iy + 3f, ix, iy - 4f, stroke);
            c.drawLine(ix, iy - 4f, ix + 6f, iy + 3f, stroke);
        }

        // Zenith Flow meter
        final float cx = vw * 0.5f;
        final float barW = 300f, barH = 9f, barX = cx - barW * 0.5f, barY = 52f;
        final boolean showFlow = g.upgrades.flowOwned() || g.flowActive;
        float f = showFlow ? g.flowFraction() : 0f;
        int flowColor = g.flowActive ? GOLD : TEAL;
        if (showFlow) {
            shadowLabel(c, s.get(Strings.FLOW), cx, 38f, 19f, g.flowActive ? GOLD : DIM_TEXT, Paint.Align.CENTER);
            fill.setColor(0x80101A40);
            c.drawRoundRect(barX - 3f, barY - 3f, barX + barW + 3f, barY + barH + 3f, 11f, 11f, fill);
        }
        if (f > 0.01f) {
            if (g.flowActive) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(g.time * 10f);
                fill.setColor(0x40FFD86B + ((int) (pulse * 60f) << 24));
                c.drawRoundRect(barX - 8f, barY - 8f, barX + barW * f + 8f, barY + barH + 8f, 16f, 16f, fill);
            }
            fill.setColor(flowColor);
            c.drawRoundRect(barX, barY, barX + Math.max(barH, barW * f), barY + barH, 8f, 8f, fill);
        }
        if (g.combo > 1) {
            buf.clear().add('x').add(g.combo);
            thinBuf(c, barX + barW + 22f, barY + 20f, 42f, GOLD, Paint.Align.LEFT);
        }

        // orbs collected in this run
        float ox = bx[B_PAUSE] - 30f;
        buf.clear().add(g.orbsRun);
        thinBuf(c, ox, 80f, 44f, WHITE, Paint.Align.RIGHT);
        orbIcon(c, ox - buf.width(thin) - 26f, 65f, 10f);

        // pause button
        if (g.state == GameEngine.ST_PLAY) {
            float px = bx[B_PAUSE] + bw[B_PAUSE] * 0.5f;
            float py = by[B_PAUSE] + bh[B_PAUSE] * 0.5f;
            fill.setColor(0x380B1230);
            c.drawCircle(px, py, 34f, fill);
            fill.setColor(WHITE);
            c.drawRoundRect(px - 12f, py - 14f, px - 4f, py + 14f, 3f, 3f, fill);
            c.drawRoundRect(px + 4f, py - 14f, px + 12f, py + 14f, 3f, 3f, fill);
        }

        // mission complete banner
        if (g.bannerTime > 0f) {
            float k = g.bannerTime > 3f ? (3.4f - g.bannerTime) / 0.4f : (g.bannerTime < 0.4f ? g.bannerTime / 0.4f : 1f);
            float y = 96f - 60f * (1f - k);
            buf.clear().add(s.get(Strings.MISSION_DONE)).add("  +").add(g.bannerReward);
            text.setTextSize(28f);
            float w = buf.width(text) + 110f;
            fill.setColor(((int) (220f * k) << 24) | 0x0C1434);
            c.drawRoundRect(cx - w * 0.5f, y, cx + w * 0.5f, y + 56f, 28f, 28f, fill);
            labelBuf(c, cx - 16f, y + 38f, 28f, ((int) (255f * k) << 24) | 0xFFD86B, Paint.Align.CENTER);
            orbIcon(c, cx + w * 0.5f - 38f, y + 28f, 9f);
        }

        // tutorial hint
        if (g.hintId >= 0 && g.state == GameEngine.ST_PLAY) {
            String hint = s.hint(g.hintId);
            text.setTextSize(30f);
            float w = text.measureText(hint) + 130f;
            float y = vh - 118f;
            fill.setColor(0xD00C1434);
            c.drawRoundRect(cx - w * 0.5f, y, cx + w * 0.5f, y + 70f, 35f, 35f, fill);
            stroke.setColor(0x9040E8D8);
            stroke.setStrokeWidth(2.5f);
            c.drawRoundRect(cx - w * 0.5f, y, cx + w * 0.5f, y + 70f, 35f, 35f, stroke);
            // pulsing touch indicator
            float pulse = 0.5f + 0.5f * (float) Math.sin(g.time * 6f);
            float tx = cx - w * 0.5f + 42f;
            stroke.setColor(((int) (255f * (1f - pulse)) << 24) | 0x40E8D8);
            stroke.setStrokeWidth(3f);
            c.drawCircle(tx, y + 35f, 10f + 14f * pulse, stroke);
            fill.setColor(WHITE);
            c.drawCircle(tx, y + 35f, 9f, fill);
            label(c, hint, cx + 30f, y + 46f, 30f, WHITE, Paint.Align.CENTER);
        }
    }

    // ------------------------------------------------------------------ main menu

    private void rankLine(TextBuf b, GameEngine g) {
        int rank = g.missions.rank();
        int nameIdx = Math.min(rank - 1, Strings.RANK_NAMES - 1);
        b.clear().add(g.strings.get(Strings.RANK)).add(' ').add(rank).add("  ·  ")
                .add(g.strings.get(Strings.RANK_NAME + nameIdx));
    }

    private void drawMenu(Canvas c, GameEngine g) {
        final Strings s = g.strings;
        final float side = 90f;
        dim(c, 0x24060A24);

        titlePaint.setTextSize(98f);
        titlePaint.setTextAlign(Paint.Align.LEFT);
        titlePaint.setColor(0x700B1230);
        c.drawText(s.get(Strings.TITLE), side + 3f, 176f, titlePaint);
        titlePaint.setColor(WHITE);
        c.drawText(s.get(Strings.TITLE), side, 172f, titlePaint);
        rankLine(buf, g);
        shadowBuf(c, side + 6f, 222f, 26f, 0xFFE8F6FF, Paint.Align.LEFT);

        button(c, g, B_PLAY, s.get(Strings.PLAY), STYLE_PRIMARY, 44f);
        button(c, g, B_SHOP, s.get(Strings.SHOP), STYLE_SECONDARY, 30f);
        button(c, g, B_MISSIONS, s.get(Strings.MISSIONS), STYLE_SECONDARY, 30f);

        // language toggle: the active language is highlighted
        buttonBox(c, g, B_LANG, STYLE_SECONDARY);
        float lx = bx[B_LANG] + bw[B_LANG] * 0.5f;
        float ly = by[B_LANG] + bh[B_LANG] * 0.5f + 11f;
        boolean uk = s.lang == Strings.LANG_UK;
        label(c, "UA", lx - 22f, ly, 30f, uk ? TEAL : 0x80FFFFFF, Paint.Align.RIGHT);
        label(c, "/", lx, ly, 30f, 0x80FFFFFF, Paint.Align.CENTER);
        label(c, "EN", lx + 22f, ly, 30f, uk ? 0x80FFFFFF : TEAL, Paint.Align.LEFT);

        buttonBox(c, g, B_SOUND, STYLE_SECONDARY);
        speakerIcon(c, bx[B_SOUND] + bw[B_SOUND] * 0.5f - 3f, by[B_SOUND] + bh[B_SOUND] * 0.5f,
                g.save.soundOn, g.save.soundOn ? WHITE : 0xFFFF8A8A);

        // records panel
        final float pw = 470f, ph = 286f;
        final float px = vw - side - pw, py = 262f;
        panel(c, px, py, pw, ph, PANEL);
        float rowY = py + 62f;
        label(c, s.get(Strings.BEST_DIST), px + 34f, rowY, 25f, DIM_TEXT, Paint.Align.LEFT);
        buf.clear().add((int) g.save.bestDistance).add(' ').add(s.get(Strings.METERS));
        labelBuf(c, px + pw - 34f, rowY, 32f, WHITE, Paint.Align.RIGHT);
        rowY += 82f;
        label(c, s.get(Strings.BEST_SCORE), px + 34f, rowY, 25f, DIM_TEXT, Paint.Align.LEFT);
        buf.clear().add(g.save.bestScore);
        labelBuf(c, px + pw - 34f, rowY, 32f, WHITE, Paint.Align.RIGHT);
        rowY += 82f;
        label(c, s.get(Strings.ORBS), px + 34f, rowY, 25f, DIM_TEXT, Paint.Align.LEFT);
        buf.clear().add(g.save.orbs);
        labelBuf(c, px + pw - 34f, rowY, 32f, GOLD, Paint.Align.RIGHT);
        text.setTextSize(32f);
        orbIcon(c, px + pw - 34f - buf.width(text) - 24f, rowY - 11f, 9f);
    }

    // ------------------------------------------------------------------ shop

    private void header(Canvas c, GameEngine g, String title) {
        final Strings s = g.strings;
        button(c, g, B_BACK, s.get(Strings.BACK), STYLE_SECONDARY, 26f);
        label(c, title, vw * 0.5f, 84f, 44f, WHITE, Paint.Align.CENTER);
        buf.clear().add(g.save.orbs);
        labelBuf(c, vw - 90f, 82f, 38f, GOLD, Paint.Align.RIGHT);
        text.setTextSize(38f);
        orbIcon(c, vw - 90f - buf.width(text) - 26f, 69f, 10f);
    }

    // ------------------------------------------------------------------ shared chrome

    /** Bright frosted backdrop over the frozen world, tinted by the current sky. */
    private void frost(Canvas c, GameEngine g, float k) {
        int sky = g.env.color[com.ccs.zenith_rider.graphics.EnvironmentPalette.SKY_MID];
        int tint = com.ccs.zenith_rider.graphics.EnvironmentPalette.lerpColor(sky, 0xFFFFFFFF, 0.30f);
        fill.setColor(((int) (178f * k) << 24) | (tint & 0x00FFFFFF));
        c.drawRect(0f, 0f, vw, vh, fill);
        fill.setColor(((int) (70f * k) << 24) | 0x0B1230);
        c.drawRect(0f, 0f, vw, vh, fill);
    }

    private static int fadeColor(int color, float k) {
        int a = (int) ((color >>> 24) * (k < 0f ? 0f : (k > 1f ? 1f : k)));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private void bottomBar(Canvas c) {
        fill.setColor(0xF2161D2B);
        c.drawRect(0f, vh - BAR_H, vw, vh, fill);
    }

    private void barBalance(Canvas c, GameEngine g, float rightX) {
        buf.clear().add(g.save.orbs);
        labelBuf(c, rightX, vh - BAR_H * 0.5f + 12f, 34f, GOLD, Paint.Align.RIGHT);
        text.setTextSize(34f);
        float w = buf.width(text);
        fill.setColor(GOLD);
        c.drawCircle(rightX - w - 26f, vh - BAR_H * 0.5f, 12f, fill);
        fill.setColor(0xFF161D2B);
        c.drawCircle(rightX - w - 26f, vh - BAR_H * 0.5f, 5f, fill);
    }

    // ------------------------------------------------------------------ procedural icons

    /** Item icons for the shop list, drawn from primitives around (x, y). */
    private void itemIcon(Canvas c, int tab, int i, float x, float y) {
        stroke.setStrokeWidth(5f);
        if (tab == 2) {
            int scarf = UpgradeManager.RIDER_SCARF[i];
            if (scarf == 0) {
                scarf = 0xFFE23D4E;
            }
            float sc = UpgradeManager.RIDER_SCALE[i];
            fill.setColor(0x38FFFFFF);
            c.drawCircle(x, y, 32f, fill);
            fill.setColor(INK);
            c.drawCircle(x + 2f, y - 8f, 11f * sc, fill);
            stroke.setColor(INK);
            stroke.setStrokeWidth(13f * sc);
            c.drawLine(x, y + 8f, x - 2f, y + 22f, stroke);
            stroke.setColor(scarf);
            stroke.setStrokeWidth(6f);
            c.drawLine(x + 6f, y + 3f, x - 12f, y + 4f, stroke);
            c.drawLine(x - 12f, y + 4f, x - 26f, y - 2f, stroke);
            return;
        }
        // soft tile behind every icon
        fill.setColor(0x24FFFFFF);
        c.drawRoundRect(x - 37f, y - 37f, x + 37f, y + 37f, 14f, 14f, fill);
        int id = tab * 10 + i;
        switch (id) {
            case 0: { // Sphere Magnet: red horseshoe, white poles, an orb being pulled in
                stroke.setColor(0xFFE8505B);
                stroke.setStrokeWidth(11f);
                c.drawArc(x - 15f, y - 8f, x + 15f, y + 22f, 0f, 180f, false, stroke);
                c.drawLine(x - 15f, y + 7f, x - 15f, y - 6f, stroke);
                c.drawLine(x + 15f, y + 7f, x + 15f, y - 6f, stroke);
                stroke.setColor(0xFFFF8E95);
                stroke.setStrokeWidth(3f);
                c.drawArc(x - 19f, y - 12f, x + 11f, y + 18f, 100f, 70f, false, stroke);
                stroke.setColor(WHITE);
                stroke.setStrokeWidth(11f);
                c.drawLine(x - 15f, y - 10f, x - 15f, y - 14f, stroke);
                c.drawLine(x + 15f, y - 10f, x + 15f, y - 14f, stroke);
                orbIcon(c, x, y - 24f, 5f);
                stroke.setColor(0xB0FFFFFF);
                stroke.setStrokeWidth(2.2f);
                c.drawLine(x - 7f, y - 16f, x - 4f, y - 10f, stroke);
                c.drawLine(x + 7f, y - 16f, x + 4f, y - 10f, stroke);
                break;
            }
            case 1: { // Zenith Wings: three layered feather blades on each side
                for (int j = 2; j >= 0; j--) {
                    fill.setColor(j == 0 ? 0xFFB8FFF6 : (j == 1 ? TEAL : 0xFF1FA89C));
                    path.rewind();
                    for (int side = -1; side <= 1; side += 2) {
                        path.moveTo(x + side * 2f, y + 4f + j * 3f);
                        path.lineTo(x + side * (33f - j * 5f), y - 22f + j * 13f);
                        path.lineTo(x + side * (22f - j * 4f), y + 2f + j * 8f);
                        path.lineTo(x + side * 4f, y + 14f + j * 4f);
                        path.close();
                    }
                    c.drawPath(path, fill);
                }
                fill.setColor(WHITE);
                c.drawCircle(x, y + 8f, 5f, fill);
                break;
            }
            case 2: { // Flow Resonator: faceted crystal sending out waves
                path.rewind();
                path.moveTo(x, y - 28f);
                path.lineTo(x + 12f, y);
                path.lineTo(x, y + 28f);
                path.lineTo(x - 12f, y);
                path.close();
                fill.setColor(GOLD);
                c.drawPath(path, fill);
                path.rewind();
                path.moveTo(x, y - 28f);
                path.lineTo(x, y + 28f);
                path.lineTo(x - 12f, y);
                path.close();
                fill.setColor(0x70FFFFFF);
                c.drawPath(path, fill);
                stroke.setColor(0xFFFFE9A8);
                stroke.setStrokeWidth(3.2f);
                c.drawArc(x - 22f, y - 14f, x + 22f, y + 14f, -40f, 80f, false, stroke);
                c.drawArc(x - 22f, y - 14f, x + 22f, y + 14f, 140f, 80f, false, stroke);
                stroke.setColor(0x90FFE9A8);
                c.drawArc(x - 31f, y - 21f, x + 31f, y + 21f, -36f, 72f, false, stroke);
                c.drawArc(x - 31f, y - 21f, x + 31f, y + 21f, 144f, 72f, false, stroke);
                break;
            }
            case 3: { // Zephyr Crest: a feather with a lift arrow
                path.rewind();
                path.moveTo(x - 20f, y + 24f);
                path.quadTo(x - 26f, y - 14f, x + 12f, y - 29f);
                path.quadTo(x + 14f, y + 6f, x - 20f, y + 24f);
                path.close();
                fill.setColor(0xFF9CFFF2);
                c.drawPath(path, fill);
                path.rewind();
                path.moveTo(x - 20f, y + 24f);
                path.quadTo(x - 26f, y - 14f, x + 12f, y - 29f);
                path.lineTo(x - 20f, y + 24f);
                path.close();
                fill.setColor(0xFF4FD8CC);
                c.drawPath(path, fill);
                stroke.setColor(WHITE);
                stroke.setStrokeWidth(2.6f);
                c.drawLine(x - 23f, y + 28f, x + 10f, y - 25f, stroke);
                stroke.setColor(0x800B1230);
                stroke.setStrokeWidth(1.8f);
                c.drawLine(x - 6f, y + 1f, x + 4f, y + 4f, stroke);
                c.drawLine(x - 1f, y - 8f, x + 8f, y - 6f, stroke);
                stroke.setColor(WHITE);
                stroke.setStrokeWidth(4f);
                c.drawLine(x + 16f, y + 24f, x + 23f, y + 15f, stroke);
                c.drawLine(x + 23f, y + 15f, x + 30f, y + 24f, stroke);
                break;
            }
            case 5: { // Zenith Flow unlock: a long glowing scarf curling into a wing
                stroke.setColor(GOLD);
                stroke.setStrokeWidth(6f);
                path.rewind();
                path.moveTo(x + 24f, y - 22f);
                path.quadTo(x - 6f, y - 30f, x - 14f, y - 6f);
                path.quadTo(x - 20f, y + 14f, x + 4f, y + 12f);
                path.quadTo(x + 22f, y + 10f, x + 26f, y + 26f);
                c.drawPath(path, stroke);
                stroke.setColor(0xFFFFF3C4);
                stroke.setStrokeWidth(2.4f);
                c.drawPath(path, stroke);
                fill.setColor(WHITE);
                c.drawCircle(x + 24f, y - 22f, 5.5f, fill);
                path.rewind();
                path.moveTo(x - 30f, y + 26f);
                path.lineTo(x - 8f, y + 18f);
                path.lineTo(x - 18f, y + 30f);
                path.close();
                fill.setColor(TEAL);
                c.drawPath(path, fill);
                break;
            }
            case 4: { // Wisp Call: layered flame with sparks
                path.rewind();
                path.moveTo(x, y - 30f);
                path.quadTo(x + 26f, y - 2f, x + 4f, y + 26f);
                path.quadTo(x - 26f, y + 22f, x - 14f, y - 4f);
                path.quadTo(x - 6f, y - 6f, x, y - 30f);
                path.close();
                fill.setColor(0xFFFFB347);
                c.drawPath(path, fill);
                path.rewind();
                path.moveTo(x + 1f, y - 12f);
                path.quadTo(x + 14f, y + 6f, x + 2f, y + 22f);
                path.quadTo(x - 13f, y + 16f, x + 1f, y - 12f);
                path.close();
                fill.setColor(0xFFFFF3C4);
                c.drawPath(path, fill);
                fill.setColor(GOLD);
                c.drawCircle(x - 24f, y - 16f, 3f, fill);
                c.drawCircle(x + 24f, y - 20f, 2.4f, fill);
                c.drawCircle(x + 27f, y + 8f, 2f, fill);
                break;
            }
            case 10: { // Aegis Shield: two-tone shield with a star
                path.rewind();
                path.moveTo(x, y - 31f);
                path.lineTo(x + 26f, y - 21f);
                path.lineTo(x + 22f, y + 8f);
                path.lineTo(x, y + 31f);
                path.lineTo(x - 22f, y + 8f);
                path.lineTo(x - 26f, y - 21f);
                path.close();
                fill.setColor(0xFF178F86);
                c.drawPath(path, fill);
                path.rewind();
                path.moveTo(x, y - 24f);
                path.lineTo(x + 20f, y - 16f);
                path.lineTo(x + 17f, y + 6f);
                path.lineTo(x, y + 24f);
                path.lineTo(x - 17f, y + 6f);
                path.lineTo(x - 20f, y - 16f);
                path.close();
                fill.setColor(TEAL);
                c.drawPath(path, fill);
                path.rewind();
                path.moveTo(x, y - 24f);
                path.lineTo(x, y + 24f);
                path.lineTo(x - 17f, y + 6f);
                path.lineTo(x - 20f, y - 16f);
                path.close();
                fill.setColor(0x55FFFFFF);
                c.drawPath(path, fill);
                rowIcon(c, 4, x, y - 2f, WHITE);
                break;
            }
            case 11: { // Double Orbs
                orbIcon(c, x - 13f, y - 10f, 11f);
                orbIcon(c, x + 9f, y + 2f, 11f);
                label(c, "x2", x + 31f, y + 31f, 21f, GOLD, Paint.Align.RIGHT);
                break;
            }
            case 12: { // Lunar Gravity: crescent with stars
                path.rewind();
                path.addCircle(x - 3f, y + 2f, 25f, Path.Direction.CW);
                path2.rewind();
                path2.addCircle(x + 9f, y - 6f, 21f, Path.Direction.CW);
                path.op(path2, Path.Op.DIFFERENCE);
                fill.setColor(0xFFEAF1FF);
                c.drawPath(path, fill);
                stroke.setColor(GOLD);
                stroke.setStrokeWidth(2.6f);
                c.drawLine(x + 12f, y - 12f, x + 22f, y - 12f, stroke);
                c.drawLine(x + 17f, y - 17f, x + 17f, y - 7f, stroke);
                c.drawLine(x + 22f, y + 8f, x + 28f, y + 8f, stroke);
                c.drawLine(x + 25f, y + 5f, x + 25f, y + 11f, stroke);
                break;
            }
            case 13: { // Instant Zenith: bolt inside a charged ring
                stroke.setColor(0x80FFD86B);
                stroke.setStrokeWidth(3.5f);
                c.drawCircle(x, y, 29f, stroke);
                path.rewind();
                path.moveTo(x + 8f, y - 28f);
                path.lineTo(x - 15f, y + 4f);
                path.lineTo(x - 2f, y + 4f);
                path.lineTo(x - 8f, y + 28f);
                path.lineTo(x + 15f, y - 6f);
                path.lineTo(x + 2f, y - 6f);
                path.close();
                fill.setColor(GOLD);
                c.drawPath(path, fill);
                path.rewind();
                path.moveTo(x + 8f, y - 28f);
                path.lineTo(x - 15f, y + 4f);
                path.lineTo(x - 2f, y + 4f);
                path.lineTo(x + 2f, y - 6f);
                path.close();
                fill.setColor(0x80FFFFFF);
                c.drawPath(path, fill);
                break;
            }
            default: { // Chasm Tether: carabiner on a coiled rope
                stroke.setColor(0xFFE6B877);
                stroke.setStrokeWidth(4.5f);
                c.drawOval(x - 20f, y + 4f, x + 20f, y + 18f, stroke);
                c.drawOval(x - 20f, y + 12f, x + 20f, y + 26f, stroke);
                c.drawLine(x, y + 4f, x, y - 8f, stroke);
                stroke.setColor(TEAL);
                stroke.setStrokeWidth(5.5f);
                c.drawRoundRect(x - 10f, y - 30f, x + 10f, y - 6f, 10f, 10f, stroke);
                stroke.setColor(WHITE);
                stroke.setStrokeWidth(3f);
                c.drawLine(x + 10f, y - 24f, x + 10f, y - 13f, stroke);
                break;
            }
        }
    }

    // ------------------------------------------------------------------ shop

    private void tab(Canvas c, GameEngine g, int id, String caption, boolean active) {
        float x = bx[id], y = by[id], w = bw[id], h = bh[id];
        fill.setColor(active ? 0xF2161D2B : (g.pressedBtn == id ? 0x900B1230 : 0x580B1230));
        c.drawRoundRect(x, y, x + w, y + h, 12f, 12f, fill);
        label(c, caption, x + w * 0.5f, y + h * 0.5f + 8f, 22f, active ? WHITE : 0xE0FFFFFF, Paint.Align.CENTER);
        if (active) {
            fill.setColor(TEAL);
            c.drawRoundRect(x + 24f, y + h - 7f, x + w - 24f, y + h - 3f, 2f, 2f, fill);
        }
    }

    /** Price box on the right of a row: cost on top, action below. */
    private void priceBox(Canvas c, GameEngine g, int id, int cost, String action, boolean enabled) {
        float x = bx[id], y = by[id], w = bw[id], h = bh[id];
        boolean pressed = g.pressedBtn == id;
        fill.setColor(pressed ? 0xFF24345C : (enabled ? 0xE6161D2B : 0x70161D2B));
        c.drawRoundRect(x, y, x + w, y + h, 10f, 10f, fill);
        float cxb = x + w * 0.5f;
        float cyb = y + h * 0.5f;
        if (cost < 0) {
            label(c, action, cxb, cyb + 8f, 22f, enabled ? WHITE : 0xB0FFFFFF, Paint.Align.CENTER);
            return;
        }
        boolean afford = g.save.orbs >= cost;
        buf.clear().add(cost);
        labelBuf(c, cxb + 12f, cyb - 2f, 26f, afford ? GOLD : 0xFFFF9A9A, Paint.Align.CENTER);
        float half = buf.width(text) * 0.5f;
        fill.setColor(afford ? GOLD : 0xFFFF9A9A);
        c.drawCircle(cxb + 12f - half - 17f, cyb - 11f, 9f, fill);
        fill.setColor(0xFF161D2B);
        c.drawCircle(cxb + 12f - half - 17f, cyb - 11f, 3.6f, fill);
        label(c, action, cxb, cyb + 26f, 20f, afford ? WHITE : 0xB0FFFFFF, Paint.Align.CENTER);
    }

    private void drawShop(Canvas c, GameEngine g) {
        final Strings s = g.strings;
        final UpgradeManager up = g.upgrades;
        final int tab = g.shopTab;
        frost(c, g, 1f);

        tab(c, g, B_TAB0, s.get(Strings.UPGRADES), tab == 0);
        tab(c, g, B_TAB0 + 1, s.get(Strings.PERKS), tab == 1);
        tab(c, g, B_TAB0 + 2, s.get(Strings.RIDERS), tab == 2);

        final int count = TAB_CARDS[tab];
        final float bodyW = listW - PRICE_W - 8f;
        final boolean tall = rowH > 90f;
        for (int i = 0; i < count; i++) {
            int id = B_CARD0 + i;
            float x = listX;
            float y = by[id];
            boolean current = tab == 2 && up.rider() == i;
            fill.setColor(current ? 0xC8125A66 : 0x9A0B1230);
            c.drawRoundRect(x, y, x + bodyW, y + rowH, 10f, 10f, fill);
            float mid = y + rowH * 0.5f;
            itemIcon(c, tab, i, x + 56f, mid);
            float tx = x + 118f;
            float nameY = y + (tall ? 36f : 31f);
            float descY = y + (tall ? 66f : 55f);
            String name;
            String desc;
            final boolean flowRow = tab == 0 && i == SaveManager.UPGRADE_COUNT;
            if (flowRow) {
                name = s.get(Strings.FLOW_UNLOCK_NAME);
                desc = s.get(Strings.FLOW_UNLOCK_DESC);
            } else if (tab == 0) {
                name = s.upgradeName(i);
                desc = s.upgradeDesc(i);
            } else if (tab == 1) {
                name = s.perkName(i);
                desc = s.perkDesc(i);
            } else {
                name = s.get(Strings.RIDER_NAME + i);
                desc = s.get(Strings.RIDER_DESC + i);
            }
            label(c, name, tx, nameY, 26f, WHITE, Paint.Align.LEFT);
            float nameW = text.measureText(name);
            if (flowRow) {
                // one-time unlock: no level bar
            } else if (tab == 0) {
                // level as a segmented bar next to the name
                int lvl = up.level(i);
                for (int p = 0; p < UpgradeManager.MAX_LEVEL; p++) {
                    fill.setColor(p < lvl ? WHITE : 0x50FFFFFF);
                    float sx = tx + nameW + 22f + p * 40f;
                    c.drawRect(sx, nameY - 13f, sx + 36f, nameY - 6f, fill);
                }
            } else if (tab == 1) {
                buf.clear().add('x').add(up.perkCount(i));
                labelBuf(c, tx + nameW + 18f, nameY, 22f, GOLD, Paint.Align.LEFT);
            }
            wrapped(c, desc, tx, descY, bodyW - 140f, 21f, 18f, 0xD8FFFFFF);

            if (flowRow) {
                if (up.flowOwned()) {
                    priceBox(c, g, id, -1, s.get(Strings.COMPLETE), false);
                } else {
                    priceBox(c, g, id, UpgradeManager.FLOW_COST, s.get(Strings.PURCHASE), true);
                }
            } else if (tab == 0) {
                int cost = up.upgradeCost(i);
                if (cost < 0) {
                    priceBox(c, g, id, -1, s.get(Strings.COMPLETE), false);
                } else {
                    priceBox(c, g, id, cost, s.get(Strings.UPGRADE), true);
                }
            } else if (tab == 1) {
                priceBox(c, g, id, up.perkCost(i), s.get(Strings.PURCHASE), true);
            } else if (current) {
                priceBox(c, g, id, -1, s.get(Strings.SELECTED), false);
            } else if (up.riderOwned(i)) {
                priceBox(c, g, id, -1, s.get(Strings.SELECT), true);
            } else {
                priceBox(c, g, id, UpgradeManager.RIDER_COST[i], s.get(Strings.PURCHASE), true);
            }
        }

        bottomBar(c);
        float by0 = vh - BAR_H * 0.5f;
        if (g.pressedBtn == B_SHOP_BACK) {
            fill.setColor(0x30FFFFFF);
            c.drawRect(0f, vh - BAR_H, bw[B_SHOP_BACK], vh, fill);
        }
        path.rewind();
        path.moveTo(96f, by0 - 11f);
        path.lineTo(122f, by0 - 11f);
        path.lineTo(109f, by0 + 12f);
        path.close();
        fill.setColor(WHITE);
        c.drawPath(path, fill);
        label(c, s.get(Strings.BACK), 144f, by0 + 10f, 28f, WHITE, Paint.Align.LEFT);
        barBalance(c, g, vw - 90f);

        if (g.noFundsTimer > 0f) {
            String msg = s.get(Strings.NO_ORBS);
            text.setTextSize(26f);
            float w = text.measureText(msg) + 70f;
            float cx = vw * 0.5f;
            fill.setColor(0xF0B03050);
            c.drawRoundRect(cx - w * 0.5f, vh - BAR_H + 14f, cx + w * 0.5f, vh - 14f, 26f, 26f, fill);
            label(c, msg, cx, by0 + 9f, 26f, WHITE, Paint.Align.CENTER);
        }
    }

    // ------------------------------------------------------------------ pre-run modifiers

    private void drawPerks(Canvas c, GameEngine g) {
        final Strings s = g.strings;
        final UpgradeManager up = g.upgrades;
        dim(c, 0xA6060A24);
        button(c, g, B_BACK, s.get(Strings.BACK), STYLE_SECONDARY, 24f);
        label(c, s.get(Strings.CHOOSE_PERKS), vw * 0.5f + 80f, 82f, 30f, WHITE, Paint.Align.CENTER);

        for (int i = 0; i < SaveManager.PERK_COUNT; i++) {
            int id = B_SEL0 + i;
            float x = bx[id], y = by[id], w = bw[id], h = bh[id];
            boolean selected = up.selected[i];
            boolean owned = up.perkCount(i) > 0;
            panel(c, x, y, w, h, selected ? 0xE0124A5C : (g.pressedBtn == id ? 0xE01E2C66 : PANEL));
            if (selected) {
                outline(c, x, y, w, h, TEAL, 4f);
            }
            int lines = wrapped(c, s.perkName(i), x + 18f, y + 48f, w - 36f, 30f, 25f,
                    owned ? WHITE : 0x80FFFFFF);
            wrapped(c, s.perkDesc(i), x + 18f, y + 60f + lines * 30f, w - 36f, 25f, 19f,
                    owned ? DIM_TEXT : 0x70FFFFFF);
            buf.clear().add(s.get(Strings.OWNED)).add(": ").add(up.perkCount(i));
            labelBuf(c, x + 18f, y + h - 84f, 21f, owned ? GOLD : 0x80FFFFFF, Paint.Align.LEFT);
            String tag = selected ? s.get(Strings.SELECTED) : (owned ? s.get(Strings.SELECT) : s.get(Strings.NONE_OWNED));
            label(c, tag, x + w * 0.5f, y + h - 30f, 21f, selected ? TEAL : (owned ? WHITE : 0x80FFFFFF),
                    Paint.Align.CENTER);
        }
        button(c, g, B_START, s.get(Strings.START), STYLE_PRIMARY, 38f);
    }

    // ------------------------------------------------------------------ missions

    private void missionText(GameEngine g, int slot) {
        int type = g.missions.type(slot);
        buf.clear().add(g.strings.missionPrefix(type)).add(g.missions.target(slot))
                .add(g.strings.missionSuffix(type));
    }

    private void missionRow(Canvas c, GameEngine g, int slot, float x, float y, float w, boolean compact) {
        final MissionManager m = g.missions;
        boolean done = m.done(slot);
        float h = compact ? 46f : 118f;
        panel(c, x, y, w, h, done ? 0xD0124A5C : PANEL);
        float textSize = compact ? 21f : 27f;
        float ty = compact ? y + 31f : y + 46f;

        // check mark or bullet
        float ix = x + 30f;
        float iy = compact ? y + 23f : y + 37f;
        if (done) {
            stroke.setColor(TEAL);
            stroke.setStrokeWidth(4f);
            c.drawLine(ix - 9f, iy, ix - 2f, iy + 8f, stroke);
            c.drawLine(ix - 2f, iy + 8f, ix + 11f, iy - 8f, stroke);
        } else {
            fill.setColor(0x80FFFFFF);
            c.drawCircle(ix, iy, 5f, fill);
        }
        missionText(g, slot);
        labelBuf(c, x + 56f, ty, textSize, done ? TEAL : WHITE, Paint.Align.LEFT);

        buf.clear().add(m.progress(slot)).add('/').add(m.target(slot));
        if (compact) {
            labelBuf(c, x + w - 24f, ty, 21f, done ? TEAL : GOLD, Paint.Align.RIGHT);
            return;
        }
        labelBuf(c, x + w - 150f, y + 46f, 24f, done ? TEAL : DIM_TEXT, Paint.Align.RIGHT);
        buf.clear().add('+').add(m.reward(slot));
        labelBuf(c, x + w - 54f, y + 46f, 26f, GOLD, Paint.Align.RIGHT);
        orbIcon(c, x + w - 34f, y + 37f, 8f);

        float barX = x + 56f, barY = y + 76f, barW = w - 112f;
        fill.setColor(0x50FFFFFF);
        c.drawRoundRect(barX, barY, barX + barW, barY + 12f, 6f, 6f, fill);
        float f = m.target(slot) > 0 ? m.progress(slot) / (float) m.target(slot) : 0f;
        if (f > 0f) {
            fill.setColor(done ? TEAL : GOLD);
            c.drawRoundRect(barX, barY, barX + Math.max(12f, barW * Math.min(1f, f)), barY + 12f, 6f, 6f, fill);
        }
    }

    private void drawMissions(Canvas c, GameEngine g) {
        final Strings s = g.strings;
        dim(c, 0xA6060A24);
        header(c, g, s.get(Strings.MISSIONS));
        final float cx = vw * 0.5f;
        rankLine(buf, g);
        labelBuf(c, cx, 150f, 28f, GOLD, Paint.Align.CENTER);
        int into = g.missions.missionsIntoRank();
        for (int p = 0; p < MissionManager.MISSIONS_PER_RANK; p++) {
            fill.setColor(p < into ? GOLD : 0x50FFFFFF);
            float px = cx - 66f + p * 46f;
            c.drawRoundRect(px, 168f, px + 40f, 178f, 5f, 5f, fill);
        }
        float w = Math.min(1000f, vw - 180f);
        for (int i = 0; i < MissionManager.SLOTS; i++) {
            missionRow(c, g, i, cx - w * 0.5f, 208f + i * 140f, w, false);
        }
    }

    // ------------------------------------------------------------------ pause

    private void drawPause(Canvas c, GameEngine g) {
        final Strings s = g.strings;
        final float cx = vw * 0.5f;
        dim(c, 0xB0060A24);
        label(c, s.get(Strings.PAUSED), cx, 150f, 62f, WHITE, Paint.Align.CENTER);
        button(c, g, B_RESUME, s.get(Strings.RESUME), STYLE_PRIMARY, 36f);

        buttonBox(c, g, B_PAUSE_SOUND, STYLE_SECONDARY);
        float sy = by[B_PAUSE_SOUND] + bh[B_PAUSE_SOUND] * 0.5f;
        boolean on = g.save.soundOn;
        speakerIcon(c, bx[B_PAUSE_SOUND] + 56f, sy, on, on ? WHITE : 0xFFFF8A8A);
        buf.clear().add(s.get(Strings.SOUND)).add(": ").add(s.get(on ? Strings.ON : Strings.OFF));
        labelBuf(c, cx + 20f, sy + 10f, 28f, WHITE, Paint.Align.CENTER);

        button(c, g, B_QUIT, s.get(Strings.QUIT), STYLE_SECONDARY, 28f);

        float w = Math.min(860f, vw - 180f);
        for (int i = 0; i < MissionManager.SLOTS; i++) {
            missionRow(c, g, i, cx - w * 0.5f, 506f + i * 56f, w, true);
        }
    }

    // ------------------------------------------------------------------ results

    private void rowIcon(Canvas c, int kind, float x, float y, int color) {
        fill.setColor(color);
        stroke.setColor(color);
        switch (kind) {
            case 0: // distance: twin peaks
                path.rewind();
                path.moveTo(x - 20f, y + 11f);
                path.lineTo(x - 6f, y - 11f);
                path.lineTo(x + 2f, y + 1f);
                path.lineTo(x + 9f, y - 7f);
                path.lineTo(x + 21f, y + 11f);
                path.close();
                c.drawPath(path, fill);
                break;
            case 1: // tricks: the board
                stroke.setStrokeWidth(8f);
                c.drawLine(x - 14f, y + 10f, x + 14f, y - 10f, stroke);
                break;
            case 2: // orbs
                c.drawCircle(x, y, 12f, fill);
                fill.setColor(0xFF1B2440);
                c.drawCircle(x, y, 4.5f, fill);
                break;
            case 3: // chasm: two ledges
                path.rewind();
                path.moveTo(x - 20f, y - 9f);
                path.lineTo(x - 5f, y - 9f);
                path.lineTo(x - 11f, y + 11f);
                path.close();
                path.moveTo(x + 20f, y - 9f);
                path.lineTo(x + 5f, y - 9f);
                path.lineTo(x + 11f, y + 11f);
                path.close();
                c.drawPath(path, fill);
                break;
            default: // goals: star
                path.rewind();
                for (int k = 0; k < 10; k++) {
                    float r = (k & 1) == 0 ? 14f : 6f;
                    float a = -1.5708f + k * 0.62832f;
                    float px = x + r * (float) Math.cos(a);
                    float py = y + r * (float) Math.sin(a);
                    if (k == 0) {
                        path.moveTo(px, py);
                    } else {
                        path.lineTo(px, py);
                    }
                }
                path.close();
                c.drawPath(path, fill);
                break;
        }
    }

    /** One breakdown row; the value text must already be in {@code buf}. */
    private void scoreRow(Canvas c, GameEngine g, int index, int icon, float x, float y, float w) {
        float k = (g.overTime - 0.25f - index * 0.11f) / 0.3f;
        if (k <= 0f) {
            return;
        }
        if (k > 1f) {
            k = 1f;
        }
        float e = 1f - (1f - k) * (1f - k);
        float dx = (1f - e) * 46f;
        fill.setColor(fadeColor((index & 1) == 0 ? 0x8C0B1230 : 0x660B1230, e));
        c.drawRect(x + dx, y, x + w + dx, y + 56f, fill);
        rowIcon(c, icon, x + 44f + dx, y + 28f, fadeColor(WHITE, e));
        labelBuf(c, x + w - 30f + dx, y + 38f, 28f, fadeColor(0xFFE8F2FF, e), Paint.Align.RIGHT);
    }

    private void drawOver(Canvas c, GameEngine g) {
        final Strings s = g.strings;
        final float cx = vw * 0.5f;
        float in = g.overTime * 3f;
        if (in > 1f) {
            in = 1f;
        }
        frost(c, g, in);

        titlePaint.setTextSize(58f);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setLetterSpacing(0.06f);
        titlePaint.setColor(fadeColor(0x700B1230, in));
        c.drawText(s.get(Strings.YOUR_SCORE), cx + 2f, 86f - (1f - in) * 20f + 3f, titlePaint);
        titlePaint.setColor(fadeColor(WHITE, in));
        c.drawText(s.get(Strings.YOUR_SCORE), cx, 86f - (1f - in) * 20f, titlePaint);
        titlePaint.setLetterSpacing(0.16f);

        final float w = Math.min(920f, vw - 240f);
        final float x = cx - w * 0.5f;
        float y = 122f;
        final float step = 58f;
        final float labelX = x + 92f;

        // labels are drawn after each row background so they slide with it
        for (int i = 0; i < 5; i++) {
            float k = (g.overTime - 0.25f - i * 0.11f) / 0.3f;
            if (k > 1f) {
                k = 1f;
            }
            float e = k <= 0f ? 0f : 1f - (1f - k) * (1f - k);
            float dx = (1f - e) * 46f;
            float ry = y + i * step;
            switch (i) {
                case 0:
                    buf.clear().add((int) g.distance).add(' ').add(s.get(Strings.METERS));
                    scoreRow(c, g, i, 0, x, ry, w);
                    buf.clear().add(s.get(Strings.DIST_TRAVELLED));
                    break;
                case 1:
                    buf.clear().add(g.trickScore);
                    scoreRow(c, g, i, 1, x, ry, w);
                    buf.clear().add(s.get(Strings.TRICK_SCORE)).add("  ·  ").add(s.get(Strings.BEST_COMBO))
                            .add(" x").add(g.bestCombo);
                    break;
                case 2:
                    buf.clear().add(g.orbsRun).add(" × ").add(GameEngine.ORB_POINTS);
                    scoreRow(c, g, i, 2, x, ry, w);
                    buf.clear().add(s.get(Strings.COLLECTED));
                    break;
                case 3:
                    buf.clear().add(g.gapsRun).add(" × ").add(GameEngine.GAP_POINTS);
                    scoreRow(c, g, i, 3, x, ry, w);
                    buf.clear().add(s.get(Strings.CHASMS_CLEARED));
                    break;
                default:
                    buf.clear().add(g.goalsRun).add(" × ").add(GameEngine.GOAL_POINTS);
                    scoreRow(c, g, i, 4, x, ry, w);
                    buf.clear().add(s.get(Strings.GOALS_DONE));
                    break;
            }
            if (e > 0f) {
                labelBuf(c, labelX + dx, ry + 38f, 27f, fadeColor(WHITE, e), Paint.Align.LEFT);
            }
        }

        // total: counts up once the rows are in
        float tk = (g.overTime - 0.95f) / 0.3f;
        if (tk > 0f) {
            if (tk > 1f) {
                tk = 1f;
            }
            float ty = y + 5f * step + 4f;
            fill.setColor(fadeColor(0xEE161D2B, tk));
            c.drawRect(x, ty, x + w, ty + 76f, fill);
            label(c, s.get(Strings.TOTAL), x + 34f, ty + 51f, 36f, fadeColor(WHITE, tk), Paint.Align.LEFT);
            float ck = (g.overTime - 1.05f) / 0.9f;
            if (ck < 0f) {
                ck = 0f;
            } else if (ck > 1f) {
                ck = 1f;
            }
            ck = 1f - (1f - ck) * (1f - ck) * (1f - ck);
            buf.clear().add((int) (g.score * ck));
            labelBuf(c, x + w - 30f, ty + 53f, 42f, fadeColor(WHITE, tk), Paint.Align.RIGHT);

            if ((g.newBestScore || g.newBestDistance) && g.overTime > 2f) {
                float pk = (g.overTime - 2f) / 0.35f;
                if (pk > 1f) {
                    pk = 1f;
                }
                float pulse = 1f + 0.08f * (float) Math.sin(g.time * 5f);
                float mx = cx;
                float my = ty + 76f + 30f;
                rowIcon(c, 4, mx - 150f, my, fadeColor(GOLD, pk));
                label(c, s.get(Strings.NEW_RECORD), mx + 10f, my + 9f, 26f * pulse, fadeColor(GOLD, pk),
                        Paint.Align.CENTER);
            }
        }

        // bottom bar: Home | wallet | Play Again
        bottomBar(c);
        float by0 = vh - BAR_H * 0.5f;
        if (g.pressedBtn == B_MENU) {
            fill.setColor(0x30FFFFFF);
            c.drawRect(0f, vh - BAR_H, bw[B_MENU], vh, fill);
        }
        if (g.pressedBtn == B_RETRY) {
            fill.setColor(0x30FFFFFF);
            c.drawRect(bx[B_RETRY], vh - BAR_H, vw, vh, fill);
        }
        // house icon
        path.rewind();
        path.moveTo(92f, by0 - 2f);
        path.lineTo(110f, by0 - 18f);
        path.lineTo(128f, by0 - 2f);
        path.lineTo(123f, by0 - 2f);
        path.lineTo(123f, by0 + 15f);
        path.lineTo(97f, by0 + 15f);
        path.lineTo(97f, by0 - 2f);
        path.close();
        fill.setColor(WHITE);
        c.drawPath(path, fill);
        label(c, s.get(Strings.HOME), 148f, by0 + 11f, 30f, WHITE, Paint.Align.LEFT);

        barBalance(c, g, cx + 60f);

        // circular arrow icon
        float ax = vw - 108f;
        stroke.setColor(WHITE);
        stroke.setStrokeWidth(5f);
        c.drawArc(ax - 16f, by0 - 16f, ax + 16f, by0 + 16f, -60f, 300f, false, stroke);
        path.rewind();
        path.moveTo(ax + 2f, by0 - 26f);
        path.lineTo(ax + 16f, by0 - 15f);
        path.lineTo(ax + 1f, by0 - 6f);
        path.close();
        fill.setColor(WHITE);
        c.drawPath(path, fill);
        label(c, s.get(Strings.PLAY_AGAIN), ax - 36f, by0 + 11f, 30f, WHITE, Paint.Align.RIGHT);
    }
}
