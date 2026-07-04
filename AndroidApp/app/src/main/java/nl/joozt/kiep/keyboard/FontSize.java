package nl.joozt.kiep.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;
import android.widget.EditText;

import androidx.preference.PreferenceManager;

public class FontSize {
    private static final int STEP_SP = 10;
    private static final int MIN_SP = 10;
    private static final int MAX_SP = 400;

    private final SharedPreferences preferences;
    private final EditText editText;
    private final String decrementKey;
    private final String incrementKey;
    private int fontSizeSp;

    public FontSize(Context context, GlobalKeyPressListener keyPressListener, final EditText editText) {
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.editText = editText;

        // Track the size in sp directly instead of deriving it back from
        // pixels; DisplayMetrics.scaledDensity is deprecated (API 34) and the
        // px->sp round-trip is wrong under non-linear font scaling anyway.
        fontSizeSp = preferences.getInt(SettingsActivity.FONT_SIZE, SettingsActivity.FONT_SIZE_DEFAULT);
        applyFontSize();

        decrementKey = preferences.getString(SettingsActivity.FONT_SIZE_DECREMENT_KEY, SettingsActivity.FONT_SIZE_DECREMENT_KEY_DEFAULT);
        keyPressListener.addListener(decrementKey, () -> {
            if (fontSizeSp - STEP_SP >= MIN_SP) {
                fontSizeSp -= STEP_SP;
                applyFontSize();
            }
            return true;
        });

        incrementKey = preferences.getString(SettingsActivity.FONT_SIZE_INCREMENT_KEY, SettingsActivity.FONT_SIZE_INCREMENT_KEY_DEFAULT);
        keyPressListener.addListener(incrementKey, () -> {
            if (fontSizeSp + STEP_SP <= MAX_SP) {
                fontSizeSp += STEP_SP;
                applyFontSize();
            }
            return true;
        });
    }

    private void applyFontSize() {
        editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
    }

    public String getDecrementKey() {
        return decrementKey;
    }

    public String getIncrementKey() {
        return incrementKey;
    }

    public void saveCurrentFontSize() {
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt(SettingsActivity.FONT_SIZE, fontSizeSp);
        editor.apply();
    }
}
