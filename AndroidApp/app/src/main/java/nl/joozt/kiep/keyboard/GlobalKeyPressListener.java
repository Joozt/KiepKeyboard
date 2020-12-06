package nl.joozt.kiep.keyboard;

import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

public class GlobalKeyPressListener {
    private final Map<CharSequence, OnKeyPressListener> charListeners = new HashMap<>();
    private final Map<Integer, OnKeyPressListener> functionKeyListeners = new HashMap<>();

    public interface OnKeyPressListener {
        boolean onKeyPress();
    }

    /**
     * Function keys can be tested in Android Emulator, by running 'adb shell input keyevent [keycode]'
     */
    public enum FunctionKey {
        F1(KeyEvent.KEYCODE_F1),
        F2(KeyEvent.KEYCODE_F2),
        F3(KeyEvent.KEYCODE_F3),
        F4(KeyEvent.KEYCODE_F4),
        F5(KeyEvent.KEYCODE_F5),
        F6(KeyEvent.KEYCODE_F6),
        F7(KeyEvent.KEYCODE_F7),
        F8(KeyEvent.KEYCODE_F8),
        F9(KeyEvent.KEYCODE_F9),
        F10(KeyEvent.KEYCODE_F10),
        F11(KeyEvent.KEYCODE_F11),
        F12(KeyEvent.KEYCODE_F12),
        SCROLL_LOCK(KeyEvent.KEYCODE_SCROLL_LOCK),
        BREAK(KeyEvent.KEYCODE_BREAK),
        BACKSPACE(KeyEvent.KEYCODE_DEL);

        private final int keyCode;

        public static GlobalKeyPressListener.FunctionKey fromString(String key) {
            return GlobalKeyPressListener.FunctionKey.valueOf(key.toUpperCase().replace(" ", "_"));
        }

        FunctionKey(int keyCode) {
            this.keyCode = keyCode;
        }
    }

    public GlobalKeyPressListener(TextView textView) {
        textView.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                for (CharSequence charSequence : charListeners.keySet()) {
                    if (containsCharSequence(s, charSequence)) {
                        charListeners.get(charSequence).onKeyPress();
                    }
                }
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        });

        textView.setOnKeyListener((v, keyCode, event) -> {
            OnKeyPressListener listener = functionKeyListeners.get(keyCode);
            if (event.getAction() == KeyEvent.ACTION_DOWN && listener != null) {
                return listener.onKeyPress();
            }
            return false;
        });
    }

    private static boolean containsCharSequence(Editable editable, CharSequence sequence) {
        if (editable.toString().contains(sequence)) {
            Editable ab = new SpannableStringBuilder(editable.toString().replace(sequence, ""));
            editable.replace(0, editable.length(), ab);
            return true;
        }
        return false;
    }

    public void addListener(CharSequence charSequence, OnKeyPressListener listener) {
        try {
            FunctionKey functionKey = FunctionKey.fromString(charSequence.toString());
            addListener(functionKey, listener);
        } catch (IllegalArgumentException e) {
            charListeners.put(charSequence, listener);
        }
    }

    public void addListener(FunctionKey functionKey, OnKeyPressListener listener) {
        functionKeyListeners.put(functionKey.keyCode, listener);
    }
}
