package nl.joozt.kiep.keyboard;

import android.view.Window;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Hides the system bars for the fullscreen typing screens. Replaces the
 * deprecated SYSTEM_UI_FLAG_* immersive flags, which are unreliable under the
 * edge-to-edge enforcement of Android 15+.
 */
final class ImmersiveMode {
    private ImmersiveMode() {
    }

    static void apply(Window window) {
        WindowCompat.setDecorFitsSystemWindows(window, false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
}
