package nl.joozt.kiep.keyboard;

import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.widget.TextView;

public class KeyPressListener implements TextWatcher {
    private final CharSequence listenKeys;
    private final OnKeyPressListener listener;

    public interface OnKeyPressListener {
        void onKeyPress();
    }

    public static void listen(TextView textView, CharSequence listenKeys, OnKeyPressListener listener) {
        new KeyPressListener(textView, listenKeys, listener);
    }

    public KeyPressListener(TextView textView, CharSequence listenKeys, OnKeyPressListener listener) {
        this.listenKeys = listenKeys;
        this.listener = listener;

        textView.addTextChangedListener(this);
    }

    @Override
    public void afterTextChanged(Editable s) {
        if (containsCharacter(s, listenKeys)) {
            listener.onKeyPress();
        }
    }

    private static boolean containsCharacter(Editable editable, CharSequence sequence) {
        if (editable.toString().contains(sequence)) {
            Editable ab = new SpannableStringBuilder(editable.toString().replace(sequence, ""));
            editable.replace(0, editable.length(), ab);
            return true;
        }
        return false;
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {

    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {

    }
}
