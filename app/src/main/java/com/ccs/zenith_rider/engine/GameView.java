package com.ccs.zenith_rider.engine;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * SurfaceView hosting the game thread.
 *
 * The loop uses a fixed physics step (1/60 s) driven by a time accumulator and renders with the
 * leftover fraction as an interpolation factor, so motion stays smooth on 90 Hz and 120 Hz
 * panels. Rendering goes through a hardware-accelerated Canvas; buffer posting throttles the
 * loop to the display refresh rate. Nothing is allocated inside run().
 */
public final class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    private static final float FIXED_TIMESTEP = GameEngine.FIXED_TIMESTEP;
    private static final int MAX_STEPS_PER_FRAME = 5;

    private final GameEngine engine;
    private final Object threadLock = new Object();
    private Thread thread;
    private volatile boolean running;
    private volatile boolean surfaceReady;
    private boolean resumed;
    private volatile int surfaceW, surfaceH;
    private boolean hardwareCanvas = true;

    public GameView(Context context, Runnable exitCallback) {
        super(context);
        engine = new GameEngine(context);
        engine.setExitCallback(exitCallback);
        getHolder().addCallback(this);
        setFocusable(true);
        setKeepScreenOn(true);
    }

    // ------------------------------------------------------------------ lifecycle

    public void onHostResume() {
        resumed = true;
        engine.onHostResume();
        startLoop();
    }

    public void onHostPause() {
        resumed = false;
        stopLoop();
        engine.onHostPause();
    }

    /** Forwards the preview extras of the launch intent to the engine (see AGENTS.md). */
    public void debugAtmosphere(float cycle, int weatherKind, boolean demo) {
        engine.debugAtmosphere(cycle, weatherKind, demo);
    }

    public void onBackPressed() {
        engine.queueEvent(GameEngine.KEY_BACK, 0f, 0f);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        startLoop();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceW = width;
        surfaceH = height;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        stopLoop();
    }

    private void startLoop() {
        synchronized (threadLock) {
            if (thread != null || !resumed || !surfaceReady) {
                return;
            }
            running = true;
            thread = new Thread(this, "ZenithGameLoop");
            thread.start();
        }
    }

    private void stopLoop() {
        Thread t;
        synchronized (threadLock) {
            running = false;
            t = thread;
            thread = null;
        }
        if (t != null) {
            try {
                t.join(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = event.getActionIndex();
                engine.queueEvent(GameEngine.TOUCH_DOWN, event.getX(idx), event.getY(idx));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                engine.queueEvent(GameEngine.TOUCH_UP, event.getX(), event.getY());
                return true;
            }
            default:
                return true;
        }
    }

    // ------------------------------------------------------------------ game loop

    @Override
    public void run() {
        final SurfaceHolder holder = getHolder();
        long last = System.nanoTime();
        float accumulator = 0f;
        int knownW = 0, knownH = 0;

        while (running) {
            if (!surfaceReady || surfaceW == 0 || surfaceH == 0) {
                sleepQuietly(8L);
                last = System.nanoTime();
                continue;
            }
            int w = surfaceW, h = surfaceH;
            if (w != knownW || h != knownH) {
                knownW = w;
                knownH = h;
                engine.resize(w, h);
            }

            long now = System.nanoTime();
            float frame = (now - last) * 1e-9f;
            last = now;
            if (frame > 0.1f) {
                frame = 0.1f;
            }

            engine.processInput();

            accumulator += frame;
            int steps = 0;
            while (accumulator >= FIXED_TIMESTEP && steps < MAX_STEPS_PER_FRAME) {
                engine.update(FIXED_TIMESTEP);
                accumulator -= FIXED_TIMESTEP;
                steps++;
            }
            if (steps == MAX_STEPS_PER_FRAME && accumulator > FIXED_TIMESTEP) {
                accumulator = 0f;
            }
            float alpha = accumulator / FIXED_TIMESTEP;

            Canvas canvas = null;
            final Surface surface = holder.getSurface();
            try {
                if (hardwareCanvas) {
                    try {
                        canvas = surface.lockHardwareCanvas();
                    } catch (RuntimeException e) {
                        hardwareCanvas = false;
                    }
                }
                if (canvas == null && !hardwareCanvas) {
                    canvas = holder.lockCanvas();
                }
                if (canvas != null) {
                    engine.render(canvas, alpha);
                }
            } finally {
                if (canvas != null) {
                    try {
                        if (hardwareCanvas) {
                            surface.unlockCanvasAndPost(canvas);
                        } else {
                            holder.unlockCanvasAndPost(canvas);
                        }
                    } catch (RuntimeException ignored) {
                        // The surface disappeared while the frame was being drawn.
                    }
                }
            }
            if (canvas == null) {
                sleepQuietly(4L);
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
