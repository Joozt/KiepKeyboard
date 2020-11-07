package nl.joozt.kiep.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;

public class FullscreenActivity extends AppCompatActivity {
    public static final String KIEP_KEYBOARD = "KiepKeyboard";
    private EditText editText;
    private TTS tts;

    @Override
    @SuppressLint("SourceLockedOrientationActivity")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Set the content full screen & landscape
        setContentView(R.layout.activity_fullscreen);
        this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // Get the information of the editor where text is located
        editText = findViewById(R.id.editText);
        editText.setShowSoftInputOnFocus(false);

        Analytics analytics = new Analytics(this);

        // Define instances on the yes/no functionality
        YesNo yesNo = new YesNo(this, editText);
        yesNo.setAnalytics(analytics);

        // Define instances of the TTS functionality
        tts = new TTS(this, editText);
        tts.setAnalytics(analytics);

        FrameLayout frameLayout = findViewById(R.id.content);
        ProgressBar progressBar = findViewById(R.id.progressBar);
        new BatteryStatus(this, progressBar, frameLayout);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Set properties of the UI
        findViewById(R.id.content).setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

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
    }

    @Override
    @SuppressLint("ApplySharedPref")
    protected void onPause() {
        super.onPause();

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
