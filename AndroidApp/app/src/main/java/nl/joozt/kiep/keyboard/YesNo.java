package nl.joozt.kiep.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;

import androidx.core.graphics.ColorUtils;
import androidx.preference.PreferenceManager;

public class YesNo {
    private final EditText editText;
    private final SoundPool soundPool;
    private final int noSoundId;
    private final int yesSoundId;
    private Analytics analytics = null;
    private Runnable onKeyPressed = null;
    private final String noKey;
    private final String yesKey;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable resetBackgroundRunnable = null;

    public YesNo(Context context, GlobalKeyPressListener keyPressListener, EditText editText) {
        this.editText = editText;

        soundPool = new SoundPool.Builder().setMaxStreams(1).build();
        noSoundId = soundPool.load(context, R.raw.no, 1);
        yesSoundId = soundPool.load(context, R.raw.yes, 1);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        final boolean highlightEnabled = preferences.getBoolean(SettingsActivity.ENABLE_HIGHLIGHT, SettingsActivity.ENABLE_HIGHLIGHT_DEFAULT);
        boolean yesNoEnabled = preferences.getBoolean(SettingsActivity.ENABLE_YES_NO, SettingsActivity.ENABLE_YES_NO_DEFAULT);
        if (!yesNoEnabled) {
            noKey = "";
            yesKey = "";
            return;
        }

        noKey = preferences.getString(SettingsActivity.NO_KEY, SettingsActivity.NO_KEY_DEFAULT);
        keyPressListener.addListener(noKey, () -> {
            if (onKeyPressed != null) {
                onKeyPressed.run();
            }
            playSound(noSoundId);
            if (highlightEnabled) {
                highlightBackground(context.getColor(R.color.no_red));
            }
            if (analytics != null) {
                analytics.logNo();
            }
            return true;
        });

        yesKey = preferences.getString(SettingsActivity.YES_KEY, SettingsActivity.YES_KEY_DEFAULT);
        keyPressListener.addListener(yesKey, () -> {
            if (onKeyPressed != null) {
                onKeyPressed.run();
            }
            playSound(yesSoundId);
            if (highlightEnabled) {
                highlightBackground(context.getColor(R.color.yes_green));
            }
            if (analytics != null) {
                analytics.logYes();
            }
            return true;
        });
    }

    public void setAnalytics(Analytics analytics) {
        this.analytics = analytics;
    }

    /**
     * Runs before the yes/no sound plays - lets the activity compose other
     * features (e.g. interrupt ongoing speech) without YesNo knowing them.
     */
    public void setOnKeyPressed(Runnable onKeyPressed) {
        this.onKeyPressed = onKeyPressed;
    }

    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        soundPool.release();
    }

    public String getNoKey() {
        return noKey;
    }

    public String getYesKey() {
        return yesKey;
    }

    private void playSound(int soundId) {
        soundPool.play(soundId, 1, 1, 1, 0, 1);
    }

    private void highlightBackground(final int highlightColor) {
        int currentBackgroundColor = Color.TRANSPARENT;
        if (editText.getBackground() instanceof ColorDrawable) {
            currentBackgroundColor = ((ColorDrawable) editText.getBackground()).getColor();
        }

        // Highlight
        animateBackgroundColorManually(editText, currentBackgroundColor, highlightColor, 250);

        // Revert highlight
        if (resetBackgroundRunnable != null) {
            handler.removeCallbacks(resetBackgroundRunnable);
        }
        resetBackgroundRunnable = () -> animateBackgroundColorManually(editText, highlightColor, Color.TRANSPARENT, 250);
        handler.postDelayed(resetBackgroundRunnable, 600);
    }

    public static void animateBackgroundColorManually(final View view, final int startColor, final int endColor, final long duration) {
        final long startTime = System.currentTimeMillis();

        // Manual animation on purpose: it must run even when system animations
        // are disabled on the device. postOnAnimation paces it at vsync
        // instead of a busy handler loop.
        view.postOnAnimation(new Runnable() {
            @Override
            public void run() {
                float fraction = Math.min((float) (System.currentTimeMillis() - startTime) / duration, 1.0f);
                view.setBackgroundColor(ColorUtils.blendARGB(startColor, endColor, fraction));

                if (fraction < 1.0f) {
                    view.postOnAnimation(this);
                }
            }
        });
    }
}
