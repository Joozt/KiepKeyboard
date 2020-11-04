package nl.joozt.kiep.keyboard;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.media.SoundPool;
import android.widget.EditText;

public class YesNo {
    private final Context context;
    private final EditText editText;
    private final SoundPool soundPool;
    private final int noSoundId;
    private final int yesSoundId;

    public YesNo(Context context, EditText editText) {
        this.context = context;
        this.editText = editText;

        soundPool = new SoundPool.Builder().setMaxStreams(1).build();
        noSoundId = loadWav("no");
        yesSoundId = loadWav("yes");

        KeyPressListener.listen(editText, "[", new KeyPressListener.OnKeyPressListener() {
            @Override
            public void onKeyPress() {
                playSound(noSoundId);
                highlightBackground(Color.parseColor("#be0000"));
            }
        });

        KeyPressListener.listen(editText, "]", new KeyPressListener.OnKeyPressListener() {
            @Override
            public void onKeyPress() {
                playSound(yesSoundId);
                highlightBackground(Color.parseColor("#009800"));
            }
        });
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
                ObjectAnimator.ofArgb(editText, "backgroundColor", highlightColor, Color.BLACK)
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
