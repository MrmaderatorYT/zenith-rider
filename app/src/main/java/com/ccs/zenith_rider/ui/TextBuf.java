package com.ccs.zenith_rider.ui;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * Reusable character buffer used to compose dynamic HUD text (numbers, labels)
 * without creating String objects inside the render loop.
 */
public final class TextBuf {

    private static final int CAPACITY = 192;

    public final char[] chars = new char[CAPACITY];
    public int length;

    public TextBuf clear() {
        length = 0;
        return this;
    }

    public TextBuf add(String s) {
        int n = s.length();
        if (length + n > CAPACITY) {
            n = CAPACITY - length;
        }
        if (n > 0) {
            s.getChars(0, n, chars, length);
            length += n;
        }
        return this;
    }

    public TextBuf add(char c) {
        if (length < CAPACITY) {
            chars[length++] = c;
        }
        return this;
    }

    public TextBuf add(int value) {
        if (value < 0) {
            add('-');
            value = -value;
        }
        int digits = 1;
        int probe = value;
        while (probe >= 10) {
            probe /= 10;
            digits++;
        }
        if (length + digits > CAPACITY) {
            return this;
        }
        int pos = length + digits - 1;
        do {
            chars[pos--] = (char) ('0' + (value % 10));
            value /= 10;
        } while (value > 0);
        length += digits;
        return this;
    }

    public void draw(Canvas canvas, float x, float y, Paint paint) {
        canvas.drawText(chars, 0, length, x, y, paint);
    }

    public float width(Paint paint) {
        return paint.measureText(chars, 0, length);
    }
}
