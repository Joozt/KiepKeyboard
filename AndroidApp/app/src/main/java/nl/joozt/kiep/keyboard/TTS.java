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
        keyPressListener.addListener(speakAllKey, () -> {
            speak(editText.getText().toString().trim());
            return true;
        });
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
        return lastSentence(editText.getText().toString());
    }

    /**
     * Returns the last sentence of the buffer: the text after the last
     * '.', '!' or '?' (ignoring one trailing terminator), or the whole
     * trimmed buffer when it contains no terminator.
     */
    static String lastSentence(String text) {
        // Trim leading / trailing spaces
        String complete_text = text.trim();

        // Nothing to speak on an empty buffer (charAt below would crash)
        if (complete_text.isEmpty()) {
            return "";
        }

        // Remove the last character if it is dot, ! or ?
        if (complete_text.charAt(complete_text.length() - 1) == '.' || complete_text.charAt(complete_text.length() - 1) == '!' || complete_text.charAt(complete_text.length() - 1) == '?') {
            complete_text = complete_text.substring(0, complete_text.length() - 1);
        }

        // Search for line ending characters, like dot, ! or ?
        String last_sentence_dot = complete_text.substring(complete_text.lastIndexOf(".") + 1);
        String last_sentence_expl = complete_text.substring(complete_text.lastIndexOf("!") + 1);
        String last_sentence_ques = complete_text.substring(complete_text.lastIndexOf("?") + 1);

        // Check for the shortest string length and play that case
        int length_tot = complete_text.length();
        int length_dot = last_sentence_dot.length();
        int length_expl = last_sentence_expl.length();
        int length_ques = last_sentence_ques.length();

        // Check the minimal length
        if (length_dot < length_expl && length_dot < length_ques && length_dot < length_tot)
            // Speak last_sentence_dot
            return last_sentence_dot;
        if (length_expl < length_dot && length_expl < length_ques && length_expl < length_tot)
            // Speak last_sentence_expl
            return last_sentence_expl;
        if (length_ques < length_dot && length_ques < length_expl && length_ques < length_tot)
            // Speak last_sentence_ques
            return last_sentence_ques;
        if (length_tot <= length_dot && length_tot <= length_expl && length_tot <= length_ques)
            // Speak the total buffer
            return complete_text;

        return "";
    }
}
