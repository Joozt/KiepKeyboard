package nl.joozt.kiep.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

import androidx.preference.PreferenceManager;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static nl.joozt.kiep.keyboard.GlobalKeyPressListener.FunctionKey.BACKSPACE;

public class Abbreviations implements TextWatcher {
    private static final String END_CHARS = " .,\n?!:;";
    private final Map<String, String> abbreviations = new HashMap<>();
    private final EditText editText;
    private UndoInfo undoInfo = null;

    public Abbreviations(Context context, GlobalKeyPressListener keyPressListener, EditText editText) {
        this.editText = editText;

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String abbreviationsString = preferences.getString(SettingsActivity.ABBREVIATIONS, SettingsActivity.ABBREVIATIONS_DEFAULT);

        Matcher matcher = Pattern.compile("::(\\w+?)::(.+)").matcher(abbreviationsString);
        while (matcher.find()) {
            abbreviations.put(matcher.group(1).toLowerCase(), matcher.group(2).trim());
        }

        if (!abbreviations.isEmpty()) {
            this.editText.addTextChangedListener(this);
            keyPressListener.addListener(BACKSPACE, () -> undo(editText));
        }
    }

    @Override
    public void afterTextChanged(Editable s) {
        String text = s.toString();
        undoInfo = null;

        if (!endsWithEndChar(text)) {
            return;
        }

        String trimmedText = text.substring(0, text.length() - 1);
        int indexOfLastSpace = trimmedText.lastIndexOf(" ");
        int indexOfLastEnter = trimmedText.lastIndexOf("\n");
        int indexOfLastWord = Math.max(indexOfLastSpace, indexOfLastEnter) + 1;
        String lastWord = trimmedText.substring(indexOfLastWord);
        String replacement = abbreviations.get(lastWord.toLowerCase());
        if (replacement != null) {
            editText.removeTextChangedListener(this);
            s.replace(indexOfLastWord, indexOfLastWord + lastWord.length(), replacement);
            undoInfo = new UndoInfo(indexOfLastWord, indexOfLastWord + replacement.length(), lastWord);
            editText.addTextChangedListener(this);
        }
    }

    private boolean endsWithEndChar(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        String lastChar = text.substring(text.length() - 1);
        return END_CHARS.contains(lastChar);
    }

    private boolean undo(EditText editText) {
        if (undoInfo == null) {
            return false;
        }

        editText.removeTextChangedListener(this);
        editText.getText().replace(undoInfo.startIndex, undoInfo.endIndex, undoInfo.originalWord);
        undoInfo = null;
        editText.addTextChangedListener(this);
        return true;
    }

    private static class UndoInfo {
        int startIndex;
        int endIndex;
        String originalWord;

        public UndoInfo(int startIndex, int endIndex, String originalWord) {
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            this.originalWord = originalWord;
        }
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }
}
