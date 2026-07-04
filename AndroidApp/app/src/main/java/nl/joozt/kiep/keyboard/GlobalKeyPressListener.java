package nl.joozt.kiep.keyboard;

import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GlobalKeyPressListener {
    // A key can carry multiple commands on purpose (e.g. the speak key also
    // sending to an external display); all listeners fire in registration
    // order. Unintended conflicts are prevented by the Settings validation.
    private final Map<CharSequence, List<OnKeyPressListener>> charListeners = new HashMap<>();
    private final Map<Integer, List<OnKeyPressListener>> functionKeyListeners = new HashMap<>();

    public interface OnKeyPressListener {
        boolean onKeyPress();
    }

    /**
     * Function keys can be tested in Android Emulator, by running 'adb shell input keyevent [keycode]'
     */
    @SuppressWarnings("unused")
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

        public static FunctionKey fromString(String key) {
            return FunctionKey.valueOf(key.toUpperCase().replace(" ", "_"));
        }

        FunctionKey(int keyCode) {
            this.keyCode = keyCode;
        }
    }

    public GlobalKeyPressListener(TextView textView) {
        textView.addTextChangedListener(new TextWatcher() {
            private int pendingCommandIndex = -1;
            private List<OnKeyPressListener> pendingListeners = null;

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Only a single inserted character can be a command key press.
                // Programmatic changes (setText, abbreviation expansions,
                // loaded files) insert more than one character and are left
                // alone.
                if (count != 1) {
                    return;
                }
                List<OnKeyPressListener> listeners = charListeners.get(Character.toString(s.charAt(start)));
                if (listeners != null) {
                    pendingCommandIndex = start;
                    pendingListeners = listeners;
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (pendingListeners == null) {
                    return;
                }
                List<OnKeyPressListener> listeners = pendingListeners;
                int index = pendingCommandIndex;
                pendingListeners = null;
                pendingCommandIndex = -1;

                // Remove just the typed command character (bypassing filters),
                // keeping the cursor where it was. The nested text-change this
                // triggers is a deletion, so it cannot re-enter this branch.
                InputFilter[] filters = s.getFilters();
                s.setFilters(new InputFilter[]{});
                s.delete(index, index + 1);
                s.setFilters(filters);

                for (OnKeyPressListener listener : listeners) {
                    listener.onKeyPress();
                }
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }
        });

        textView.setOnKeyListener((v, keyCode, event) -> {
            List<OnKeyPressListener> listeners = functionKeyListeners.get(keyCode);
            if (event.getAction() == KeyEvent.ACTION_DOWN && listeners != null) {
                boolean consumed = false;
                for (OnKeyPressListener listener : listeners) {
                    consumed |= listener.onKeyPress();
                }
                return consumed;
            }
            return false;
        });
    }

    public void addListener(CharSequence charSequence, OnKeyPressListener listener) {
        try {
            FunctionKey functionKey = FunctionKey.fromString(charSequence.toString());
            addListener(functionKey, listener);
        } catch (IllegalArgumentException e) {
            charListeners.computeIfAbsent(charSequence, key -> new ArrayList<>()).add(listener);
        }
    }

    public void addListener(FunctionKey functionKey, OnKeyPressListener listener) {
        functionKeyListeners.computeIfAbsent(functionKey.keyCode, key -> new ArrayList<>()).add(listener);
    }
}
