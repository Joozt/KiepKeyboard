package nl.joozt.kiep.keyboard;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.EditText;

import java.util.Locale;

public class TTS implements TextWatcher {
    // Parameters used in the function
    // - character to select speak
    public static final String CHAR_SPEAK = "\\";
    private static final String TAG = TTS.class.getSimpleName();

    private TextToSpeech textToSpeech;
    private final EditText editText;

    public TTS(Context context, EditText editText) {
        // Define variables to be used
        this.editText = editText;

        // Add a listener to the text
        this.editText.addTextChangedListener(this);

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
    }

    // Speak functionality with alternative pronunciation
    public void speak(String text) {
        // If no argument, return
        if (textToSpeech == null || text == null || text.length() == 0) {
            return;
        }

        // Add message to logging
        Log.i(TAG, "Speak: " + text);

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

    // Check for last character and replace it if it is the one we're looking for
    private boolean containsCharacter(Editable editable, String character) {
        if (editable.toString().contains(character)) {
            Editable ab = new SpannableStringBuilder(editable.toString().replace(character, ""));
            editable.replace(0, editable.length(), ab);
            return true;
        }
        return false;
    }

    @Override
    public void afterTextChanged(Editable s) {
        if (containsCharacter(s, CHAR_SPEAK)) {
            // Get the total text from the edit box and trim leading / trailing spaces
            String complete_text = this.editText.getText().toString();
            complete_text = complete_text.trim();

            // Remove the last character if it is dot, ! or ?
            if (complete_text.charAt(complete_text.length() - 1) == '.' || complete_text.charAt(complete_text.length() - 1) == '!' || complete_text.charAt(complete_text.length() - 1) == '?')
                complete_text = complete_text.substring(0, complete_text.length() - 1);

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
                this.speak(last_sentence_dot);
            if (length_expl < length_dot && length_expl < length_ques && length_expl < length_tot)
                // Speak last_sentence_expl
                this.speak(last_sentence_expl);
            if (length_ques < length_dot && length_ques < length_expl && length_ques < length_tot)
                // Speak last_sentence_ques
                this.speak(last_sentence_ques);
            if (length_tot <= length_dot && length_tot <= length_expl && length_tot <= length_ques)
                // Speak the total buffer
                this.speak(complete_text);
        }
    }

    // Default overrides to make the TextWatcher work properly
    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }
}
