package com.ccs.zenith_rider;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import com.ccs.zenith_rider.engine.GameView;

/** Single full-screen landscape activity that hosts the game surface. */
public class MainActivity extends Activity {

    private GameView gameView;

    private final Runnable finishRunnable = new Runnable() {
        @Override
        public void run() {
            finish();
        }
    };

    /** Invoked from the game thread when the player backs out of the main menu. */
    private final Runnable exitCallback = new Runnable() {
        @Override
        public void run() {
            runOnUiThread(finishRunnable);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        WindowManager.LayoutParams lp = window.getAttributes();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        // Ask for the fastest display mode at the current resolution (90 / 120 Hz panels).
        Display display = getWindowManager().getDefaultDisplay();
        Display.Mode current = display.getMode();
        Display.Mode best = current;
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                    && mode.getPhysicalHeight() == current.getPhysicalHeight()
                    && mode.getRefreshRate() > best.getRefreshRate()) {
                best = mode;
            }
        }
        lp.preferredDisplayModeId = best.getModeId();
        window.setAttributes(lp);

        gameView = new GameView(this, exitCallback);
        // Preview aid: `am start ... --ef zr_cycle 3 --ei zr_weather 6 --ez zr_demo true` opens the
        // game at a fixed hour and weather with the menu hidden. Without extras nothing changes.
        Intent intent = getIntent();
        if (intent != null && (intent.hasExtra("zr_cycle") || intent.hasExtra("zr_weather")
                || intent.hasExtra("zr_demo"))) {
            gameView.debugAtmosphere(intent.getFloatExtra("zr_cycle", -1f),
                    intent.getIntExtra("zr_weather", -1), intent.getBooleanExtra("zr_demo", false));
        }
        setContentView(gameView);
        hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        gameView.onHostResume();
    }

    @Override
    protected void onPause() {
        gameView.onHostPause();
        super.onPause();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Back is handled by the game: pause, close a menu, or leave from the main menu.
        gameView.onBackPressed();
    }
}
