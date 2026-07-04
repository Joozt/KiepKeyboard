package nl.joozt.kiep.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.util.Log;
import android.widget.EditText;

import androidx.preference.PreferenceManager;

import java.util.Locale;

public class TTS {
    private static final String TAG = TTS.class.getSimpleName();

    private TextToSpeech textToSpeech;
    private Analytics analytics = null;
    private final String charSpeak;
    private final String speakAllKey;

    public TTS(Context context, GlobalKeyPressListener keyPressListener, final EditText editText) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean ttsEnabled = preferences.getBoolean(SettingsActivity.ENABLE_TTS, SettingsActivity.ENABLE_TTS_DEFAULT);
        if (!ttsEnabled) {
            charSpeak = "";
            speakAllKey = "";
            return;
        }

        final float rate = preferences.getInt(SettingsActivity.TTS_RATE, SettingsActivity.TTS_RATE_DEFAULT) / 100f;
        final float pitch = preferences.getInt(SettingsActivity.TTS_PITCH, SettingsActivity.TTS_PITCH_DEFAULT) / 100f;

        // Initialize TextToSpeech with the proper language setting (based on Locale)
        textToSpeech = new TextToSpeech(context,
                new OnInitListener() {

                    @Override
                    public void onInit(int status) {
                        // Check if successfully loaded
                        if (status == TextToSpeech.SUCCESS) {
                            // Define the language to be used
                            int result = textToSpeech.setLanguage(Locale.getDefault());
                            // Check if language is loaded OK yes/no
                            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                                Log.e(TAG, "Language is not supported");
                            } else {
                                Log.i(TAG, "TextToSpeech is Initialized");
                            }
                            textToSpeech.setSpeechRate(rate);
                            textToSpeech.setPitch(pitch);
                        } else {
                            // Display TTS is not able to initialize
                            Log.e(TAG, "Failed to Initialize");
                        }
                    }
                });

        charSpeak = preferences.getString(SettingsActivity.TTS_KEY, SettingsActivity.TTS_KEY_DEFAULT);
        keyPressListener.addListener(charSpeak, () -> {
            speak(getTextToSpeak(editText));
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

    public String getCharSpeak() {
        return charSpeak;
    }

    public String getSpeakAllKey() {
        return speakAllKey;
    }

    public void setAnalytics(Analytics analytics) {
        this.analytics = analytics;
    }

    // Speak functionality
    public void speak(String text) {
        // If no argument, return
        if (textToSpeech == null || text == null || text.isEmpty()) {
            return;
        }

        // Add message to logging
        Log.i(TAG, "Speak: " + text);

        if (analytics != null) {
            analytics.logTts(text.split("\\s+").length);
        }

        // Speak text
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    public void destroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
    }

    private String getTextToSpeak(EditText editText) {
        return sentenceAt(editText.getText().toString(), editText.getSelectionStart());
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
