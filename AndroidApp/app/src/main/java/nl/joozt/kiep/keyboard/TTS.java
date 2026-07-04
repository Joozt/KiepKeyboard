package nl.joozt.kiep.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.EditText;

import androidx.preference.PreferenceManager;

import java.util.Locale;

public class TTS {
    private static final String TAG = TTS.class.getSimpleName();

    private TextToSpeech textToSpeech;
    private Analytics analytics = null;
    private final String speakKey;
    private final String speakAllKey;

    public TTS(Context context, GlobalKeyPressListener keyPressListener, final EditText editText) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (!preferences.getBoolean(SettingsActivity.ENABLE_TTS, SettingsActivity.ENABLE_TTS_DEFAULT)) {
            speakKey = "";
            speakAllKey = "";
            return;
        }

        // The engine speaks the device language, at the configured rate and pitch
        textToSpeech = new TextToSpeech(context, status -> {
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "Failed to initialize");
                return;
            }
            int result = textToSpeech.setLanguage(Locale.getDefault());
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language is not supported");
            }
            textToSpeech.setSpeechRate(preferences.getInt(SettingsActivity.TTS_RATE, SettingsActivity.TTS_RATE_DEFAULT) / 100f);
            textToSpeech.setPitch(preferences.getInt(SettingsActivity.TTS_PITCH, SettingsActivity.TTS_PITCH_DEFAULT) / 100f);
        });

        speakKey = preferences.getString(SettingsActivity.TTS_KEY, SettingsActivity.TTS_KEY_DEFAULT);
        keyPressListener.addListener(speakKey, () -> {
            speak(sentenceAt(editText.getText().toString(), editText.getSelectionStart()));
            return true;
        });

        speakAllKey = preferences.getString(SettingsActivity.TTS_SPEAK_ALL_KEY, SettingsActivity.TTS_SPEAK_ALL_KEY_DEFAULT);
        if (!speakAllKey.isEmpty()) {
            keyPressListener.addListener(speakAllKey, () -> {
                speak(editText.getText().toString().trim());
                return true;
            });
        }
    }

    public String getSpeakKey() {
        return speakKey;
    }

    public String getSpeakAllKey() {
        return speakAllKey;
    }

    public void setAnalytics(Analytics analytics) {
        this.analytics = analytics;
    }

    public void speak(String text) {
        if (textToSpeech == null || text.isEmpty()) {
            return;
        }

        Log.i(TAG, "Speak: " + text);
        if (analytics != null) {
            analytics.logTts(text.split("\\s+").length);
        }
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    public void stopSpeaking() {
        if (textToSpeech != null) {
            textToSpeech.stop();
        }
    }

    public void destroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
    }

    /**
     * Returns the last sentence of the buffer. Sentences end with '.', '!',
     * '?' or a newline.
     */
    static String lastSentence(String text) {
        return sentenceAt(text, text.length());
    }

    /**
     * Returns the sentence under the cursor, trimmed. When nothing lies
     * between the cursor and the next sentence terminator (end of buffer,
     * trailing whitespace, an empty line), it falls back to the sentence
     * ending at the cursor - so a cursor at the end of the buffer yields the
     * last sentence.
     */
    static String sentenceAt(String text, int cursor) {
        int position = (cursor < 0 || cursor > text.length()) ? text.length() : cursor;

        // Scan forward to the end of the sentence the cursor is in; if that
        // part has any content, the cursor is inside this sentence
        int end = position;
        boolean contentAhead = false;
        while (end < text.length() && !isTerminator(text.charAt(end))) {
            contentAhead |= !Character.isWhitespace(text.charAt(end));
            end++;
        }

        if (!contentAhead) {
            // Nothing under or ahead of the cursor in this sentence: step
            // back over whitespace and one terminator to the sentence that
            // ends here, and find its end
            while (position > 0 && Character.isWhitespace(text.charAt(position - 1))) {
                position--;
            }
            if (position > 0 && isTerminator(text.charAt(position - 1))) {
                position--;
            }
            end = position;
            while (end < text.length() && !isTerminator(text.charAt(end))) {
                end++;
            }
        }

        int start = position;
        while (start > 0 && !isTerminator(text.charAt(start - 1))) {
            start--;
        }
        return text.substring(start, end).trim();
    }

    private static boolean isTerminator(char character) {
        return character == '.' || character == '!' || character == '?' || character == '\n';
    }
}
