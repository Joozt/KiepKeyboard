package nl.joozt.kiep.keyboard;

import android.annotation.SuppressLint;
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
    private final Context context;
    private final EditText editText;
    private final SoundPool soundPool;
    private final int noSoundId;
    private final int yesSoundId;
    private Analytics analytics = null;
    private final String noKey;
    private final String yesKey;

    public YesNo(Context context, GlobalKeyPressListener keyPressListener, EditText editText) {
        this.context = context;
        this.editText = editText;

        soundPool = new SoundPool.Builder().setMaxStreams(1).build();
        noSoundId = loadWav("no");
        yesSoundId = loadWav("yes");

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
            playSound(noSoundId);
            if (highlightEnabled) {
                highlightBackground(Color.parseColor("#be0000"));
            }
            if (analytics != null) {
                analytics.logNo();
            }
            return true;
        });

        yesKey = preferences.getString(SettingsActivity.YES_KEY, SettingsActivity.YES_KEY_DEFAULT);
        keyPressListener.addListener(yesKey, () -> {
            playSound(yesSoundId);
            if (highlightEnabled) {
                highlightBackground(Color.parseColor("#009800"));
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

    public String getNoKey() {
        return noKey;
    }

    public String getYesKey() {
        return yesKey;
    }

    private int loadWav(String wavName) {
        @SuppressLint("DiscouragedApi") int resourceId = context.getResources().getIdentifier(wavName, "raw", context.getPackageName());
        return soundPool.load(context, resourceId, 1);
    }

    private void playSound(int soundId) {
        soundPool.play(soundId, 1, 1, 1, 0, 1);
    }

    private void highlightBackground(final int highlightColor) {
        int currentBackgroundColor = Color.TRANSPARENT;
        if (editText.getBackground() instanceof ColorDrawable) {
            currentBackgroundColor = ((ColorDrawable) editText.getBackground()).getColor();
        }

        animateBackgroundColorManually(editText, currentBackgroundColor, highlightColor, 250);
        new Handler(Looper.getMainLooper()).postDelayed(() ->
                animateBackgroundColorManually(editText, highlightColor, Color.TRANSPARENT, 250), 400);
    }

    public static void animateBackgroundColorManually(final View view, final int startColor, final int endColor, final long duration) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final long startTime = System.currentTimeMillis();

        handler.post(new Runnable() {
            @Override
            public void run() {
                float fraction = Math.min((float) (System.currentTimeMillis() - startTime) / duration, 1.0f);
                view.setBackgroundColor(ColorUtils.blendARGB(startColor, endColor, fraction));

                if (fraction < 1.0f) {
                    handler.post(this);
                }
            }
        });
    }
}
