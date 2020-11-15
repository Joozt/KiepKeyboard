package nl.joozt.kiep.keyboard;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.SoundPool;
import android.widget.EditText;

import androidx.preference.PreferenceManager;

public class YesNo {
    private final Context context;
    private final EditText editText;
    private final SoundPool soundPool;
    private final int noSoundId;
    private final int yesSoundId;
    private Analytics analytics = null;

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
            return;
        }

        String noKey = preferences.getString(SettingsActivity.NO_KEY, SettingsActivity.NO_KEY_DEFAULT);
        keyPressListener.addListener(noKey, () -> {
            playSound(noSoundId);
            if (highlightEnabled) {
                highlightBackground(Color.parseColor("#be0000"));
            }
            if (analytics != null) {
                analytics.logNo();
            }
        });

        String yesKey = preferences.getString(SettingsActivity.YES_KEY, SettingsActivity.YES_KEY_DEFAULT);
        keyPressListener.addListener(yesKey, () -> {
            playSound(yesSoundId);
            if (highlightEnabled) {
                highlightBackground(Color.parseColor("#009800"));
            }
            if (analytics != null) {
                analytics.logYes();
            }
        });
    }

    public void setAnalytics(Analytics analytics) {
        this.analytics = analytics;
    }

    private int loadWav(String wavName) {
        int resourceId = context.getResources().getIdentifier(wavName, "raw", context.getPackageName());
        return soundPool.load(context, resourceId, 1);
    }

    private void playSound(int soundId) {
        soundPool.play(soundId, 1, 1, 1, 0, 1);
    }

    private void highlightBackground(final int highlightColor) {
        int currentBackgroundColor = ((ColorDrawable) editText.getBackground()).getColor();
        ObjectAnimator animator = ObjectAnimator.ofArgb(editText, "backgroundColor", currentBackgroundColor, highlightColor);
        animator.setDuration(250);
        animator.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                ObjectAnimator.ofArgb(editText, "backgroundColor", highlightColor, Color.TRANSPARENT)
                        .setDuration(250)
                        .start();
            }

            @Override
            public void onAnimationStart(Animator animation) {
            }

            @Override
            public void onAnimationCancel(Animator animation) {
            }

            @Override
            public void onAnimationRepeat(Animator animation) {
            }
        });
        animator.start();
    }
}
