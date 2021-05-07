package nl.joozt.kiep.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

public class FullscreenActivity extends AppCompatActivity implements SharedPreferences.OnSharedPreferenceChangeListener {
    public static final String KIEP_KEYBOARD = "KiepKeyboard";
    private EditText editText;
    private TTS tts;
    private UpdateCheck updateCheck;
    private FontSize fontSize;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this);

        setContentView(R.layout.activity_fullscreen);

        configureSettingsFab();

        // Get the information of the editor where text is located
        editText = findViewById(R.id.editText);
        editText.setShowSoftInputOnFocus(false);

        Analytics analytics = new Analytics(this);
        GlobalKeyPressListener keyPressListener = new GlobalKeyPressListener(editText);

        fontSize = new FontSize(this, keyPressListener, editText);

        new Abbreviations(this, keyPressListener, editText);

        // Define instances of the yes/no functionality
        YesNo yesNo = new YesNo(this, keyPressListener, editText);
        yesNo.setAnalytics(analytics);

        // Define instances of the TTS functionality
        tts = new TTS(this, keyPressListener, editText);
        tts.setAnalytics(analytics);

        FrameLayout frameLayout = findViewById(R.id.content);
        ProgressBar progressBar = findViewById(R.id.progressBar);
        new BatteryStatus(this, progressBar, frameLayout);

        updateCheck = new UpdateCheck(this);
    }

    private void configureSettingsFab() {
        View fab = findViewById(R.id.fab);
        fab.setAlpha(0.25f);
        fab.setOnClickListener(view -> {
            if (fab.getAlpha() < 0.8) {
                fab.setAlpha(1.0f);
                new Handler().postDelayed(() -> view.animate().alpha(0.25f).setDuration(1000), 3000);
            } else {
                fab.setAlpha(0.25f);
                startActivity(new Intent(this, SettingsActivity.class));
            }
        });

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        final boolean settingsButtonEnabled = preferences.getBoolean(SettingsActivity.ENABLE_SETTINGS_BUTTON, SettingsActivity.ENABLE_SETTINGS_BUTTON_DEFAULT);
        if (!settingsButtonEnabled) {
            fab.setVisibility(View.GONE);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        this.recreate(); // Reload everything when one of the settings has changed
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Get preferences with previously stored information
        SharedPreferences settings = getSharedPreferences(KIEP_KEYBOARD, Context.MODE_PRIVATE);
        String text = settings.getString("Text", KIEP_KEYBOARD);

        // Set the text back in the editor
        editText.setText(text);

        if (text.equals(KIEP_KEYBOARD)) {

            // If default text, select all and overwrite when start typing
            editText.selectAll();
        } else {

            // Set the cursor to the end
            editText.setSelection(editText.getText().length());
        }
        editText.requestFocus();

        updateCheck.checkForUpdate();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override
    @SuppressLint("ApplySharedPref")
    protected void onPause() {
        super.onPause();

        fontSize.saveCurrentFontSize();

        // Get settings and define preferences to store the text when minimizing / onPause
        SharedPreferences settings = getSharedPreferences(KIEP_KEYBOARD, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = settings.edit();

        // Store the result in the preferences
        editor.putString("Text", editText.getText().toString());
        editor.commit();
    }

    @Override
    protected void onDestroy() {
        tts.destroy();
        super.onDestroy();
    }
}
