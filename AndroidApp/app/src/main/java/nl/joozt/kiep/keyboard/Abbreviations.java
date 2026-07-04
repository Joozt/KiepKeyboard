package nl.joozt.kiep.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

import androidx.preference.PreferenceManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static nl.joozt.kiep.keyboard.GlobalKeyPressListener.FunctionKey.BACKSPACE;

public class Abbreviations implements TextWatcher {
    private static final String END_CHARS = " .,\n?!:;";
    private final Map<String, String> abbreviations;
    private final EditText editText;
    private UndoInfo undoInfo = null;

    public Abbreviations(Context context, GlobalKeyPressListener keyPressListener, EditText editText) {
        this.editText = editText;

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        abbreviations = parse(preferences.getString(SettingsActivity.ABBREVIATIONS, SettingsActivity.ABBREVIATIONS_DEFAULT));

        if (!abbreviations.isEmpty()) {
            this.editText.addTextChangedListener(this);
            keyPressListener.addListener(BACKSPACE, () -> undo(editText));
        }
    }

    /**
     * Parses AutoHotKey-style "::abbr::expansion" lines (one per line) into a
     * lookup map with lowercased abbreviation keys.
     */
    static Map<String, String> parse(String abbreviationsString) {
        Map<String, String> abbreviations = new HashMap<>();
        Matcher matcher = Pattern.compile("::(\\w+?)::(.+)").matcher(abbreviationsString);
        while (matcher.find()) {
            abbreviations.put(Objects.requireNonNull(matcher.group(1)).toLowerCase(), Objects.requireNonNull(matcher.group(2)).trim());
        }
        return abbreviations;
    }

    /**
     * Decides whether the text ends with a just-completed abbreviation (a
     * known word followed by an end character) and returns what to replace,
     * or null when nothing applies.
     */
    static Expansion findExpansion(String text, Map<String, String> abbreviations) {
        if (!endsWithEndChar(text)) {
            return null;
        }

        String trimmedText = text.substring(0, text.length() - 1);
        int indexOfLastSpace = trimmedText.lastIndexOf(" ");
        int indexOfLastEnter = trimmedText.lastIndexOf("\n");
        int indexOfLastWord = Math.max(indexOfLastSpace, indexOfLastEnter) + 1;
        String lastWord = trimmedText.substring(indexOfLastWord);
        String replacement = abbreviations.get(lastWord.toLowerCase());
        if (replacement == null) {
            return null;
        }
        return new Expansion(indexOfLastWord, indexOfLastWord + lastWord.length(), lastWord, replacement);
    }

    private static boolean endsWithEndChar(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        String lastChar = text.substring(text.length() - 1);
        return END_CHARS.contains(lastChar);
    }

    @Override
    public void afterTextChanged(Editable s) {
        undoInfo = null;

        Expansion expansion = findExpansion(s.toString(), abbreviations);
        if (expansion != null) {
            editText.removeTextChangedListener(this);
            s.replace(expansion.start, expansion.end, expansion.replacement);
            undoInfo = new UndoInfo(expansion.start, expansion.start + expansion.replacement.length(), expansion.originalWord);
            editText.addTextChangedListener(this);
        }
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

    static class Expansion {
        final int start;
        final int end;
        final String originalWord;
        final String replacement;

        Expansion(int start, int end, String originalWord, String replacement) {
            this.start = start;
            this.end = end;
            this.originalWord = originalWord;
            this.replacement = replacement;
        }
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
