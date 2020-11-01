package nl.joozt.kiep.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

public class FullscreenActivity extends AppCompatActivity {
    public static final String KIEP_KEYBOARD = "KiepKeyboard";
    private EditText editText;

    @Override
    @SuppressLint("SourceLockedOrientationActivity")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fullscreen);

        this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        editText = findViewById(R.id.editText);
        editText.setShowSoftInputOnFocus(false);

        new YesNo(this, editText);
    }

    @Override
    protected void onResume() {
        super.onResume();

        findViewById(R.id.content).setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        SharedPreferences settings = getSharedPreferences(KIEP_KEYBOARD, Context.MODE_PRIVATE);
        String text = settings.getString("Text", KIEP_KEYBOARD);
        editText.setText(text);
        if (text.equals(KIEP_KEYBOARD)) {
            editText.selectAll();
        } else {
            editText.setSelection(editText.getText().length());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        SharedPreferences settings = getSharedPreferences(KIEP_KEYBOARD, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = settings.edit();
        editor.putString("Text", editText.getText().toString());
        editor.commit();
    }
}
