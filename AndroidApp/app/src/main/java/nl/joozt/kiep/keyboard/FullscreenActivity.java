package nl.joozt.kiep.keyboard;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
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
    private BatteryStatus batteryStatus;
    private YesNo yesNo;
    private boolean settingsChanged = false;

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
        yesNo = new YesNo(this, keyPressListener, editText);
        yesNo.setAnalytics(analytics);

        // Define instances of the TTS functionality
        tts = new TTS(this, keyPressListener, editText);
        tts.setAnalytics(analytics);

        FrameLayout background = findViewById(R.id.content);
        ProgressBar progressBar = findViewById(R.id.progressBar);
        batteryStatus = new BatteryStatus(this, progressBar, background);

        ReadTxtFileActivity.registerKeyPressListener(this, keyPressListener);

        updateCheck = new UpdateCheck(this);
    }

    private void configureSettingsFab() {
        View fab = findViewById(R.id.fab);
        Runnable dimFab = () -> fab.animate().alpha(0.25f).setDuration(1000);
        fab.setAlpha(0.25f);
        fab.setOnClickListener(view -> {
            fab.removeCallbacks(dimFab); // A pending dim must not race the second tap
            if (fab.getAlpha() < 0.8) {
                fab.setAlpha(1.0f);
                fab.postDelayed(dimFab, 3000);
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
        // Font size is applied live by the hotkeys and merely persisted in
        // onPause; recreating for it would be pointless.
        if (!SettingsActivity.FONT_SIZE.equals(key)) {
            settingsChanged = true;
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Reload everything once when returning from Settings, instead of
        // recreating for every individual change while Settings was open.
        if (settingsChanged) {
            settingsChanged = false;
            recreate();
        }
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
        if (hasFocus) {
            ImmersiveMode.apply(getWindow());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        fontSize.saveCurrentFontSize();

        // Get settings and define preferences to store the text when minimizing / onPause
        SharedPreferences settings = getSharedPreferences(KIEP_KEYBOARD, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = settings.edit();

        // Store the result in the preferences
        editor.putString("Text", editText.getText().toString());
        editor.apply();
    }

    @Override
    protected void onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);
        tts.destroy();
        batteryStatus.destroy();
        yesNo.destroy();
        updateCheck.destroy();
        super.onDestroy();
    }
}
