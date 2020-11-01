package nl.joozt.kiep.keyboard;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Color;
import android.media.SoundPool;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.widget.EditText;

public class YesNo implements TextWatcher {
    private final Context context;
    private final EditText editText;
    private final SoundPool soundPool;
    private final int noSoundId;
    private final int yesSoundId;
    private int currentStreamId = 0;

    public YesNo(Context context, EditText editText) {
        this.context = context;
        this.editText = editText;

        soundPool = new SoundPool.Builder().setMaxStreams(3).build();
        noSoundId = loadWav("no");
        yesSoundId = loadWav("yes");

        this.editText.addTextChangedListener(this);
    }

    private int loadWav(String wavName) {
        int resourceId = context.getResources().getIdentifier(wavName, "raw", context.getPackageName());
        return soundPool.load(context, resourceId, 1);
    }

    @Override
    public void afterTextChanged(Editable s) {
        if (containsCharacter(s, "[")) {
            playSound(noSoundId);
            highlightBackground(Color.parseColor("#be0000"));
        }
        if (containsCharacter(s, "]")) {
            playSound(yesSoundId);
            highlightBackground(Color.parseColor("#009800"));
        }
    }

    private boolean containsCharacter(Editable editable, String character) {
        if (editable.toString().contains(character)) {
            Editable ab = new SpannableStringBuilder(editable.toString().replace(character, ""));
            editable.replace(0, editable.length(), ab);
            return true;
        }
        return false;
    }

    private void playSound(int soundId) {
        if (currentStreamId != 0) {
            soundPool.stop(currentStreamId);
            currentStreamId = 0;
        }
        currentStreamId = soundPool.play(soundId, 1, 1, 1, 0, 1);
    }

    private void highlightBackground(final int highlightColor) {
        ObjectAnimator animator = ObjectAnimator.ofArgb(editText, "backgroundColor", Color.BLACK, highlightColor);
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

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {

    }
}
