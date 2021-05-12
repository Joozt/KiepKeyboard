package nl.joozt.kiep.keyboard;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class ReadTxtFileActivity extends AppCompatActivity {
    private static final String EXTRA_URL = "EXTRA_URL";

    private EditText editText;
    private FontSize fontSize;
    private ProgressBar loading;
    private TTS tts;
    private YesNo yesNo;

    public static void registerKeyPressListener(Context context, GlobalKeyPressListener keyPressListener) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String downloadUrl = preferences.getString(SettingsActivity.DOWNLOAD_TXT_URL, SettingsActivity.DOWNLOAD_TXT_URL_DEFAULT);
        if (!downloadUrl.isEmpty() && (downloadUrl.startsWith("http://") || downloadUrl.startsWith("https://"))) {
            String downloadKey = preferences.getString(SettingsActivity.DOWNLOAD_TXT_KEY, SettingsActivity.DOWNLOAD_TXT_KEY_DEFAULT);
            keyPressListener.addListener(downloadKey, () -> {
                Intent intent = new Intent(context, ReadTxtFileActivity.class);
                intent.putExtra(ReadTxtFileActivity.EXTRA_URL, downloadUrl);
                context.startActivity(intent);
                return true;
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fullscreen);
        findViewById(R.id.fab).setVisibility(View.GONE);

        FrameLayout background = findViewById(R.id.content);
        ProgressBar progressBar = findViewById(R.id.progressBar);
        new BatteryStatus(this, progressBar, background);

        loading = findViewById(R.id.loading);
        editText = findViewById(R.id.editText);
        editText.setShowSoftInputOnFocus(false);
        editText.setText("");

        GlobalKeyPressListener keyPressListener = new GlobalKeyPressListener(editText);
        fontSize = new FontSize(this, keyPressListener, editText);
        yesNo = new YesNo(this, keyPressListener, editText);
        tts = new TTS(this, keyPressListener, editText);

        downloadFile(getIntent().getStringExtra(EXTRA_URL));
    }

    @Override
    protected void onResume() {
        super.onResume();
        editText.requestFocus();
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
    protected void onPause() {
        super.onPause();
        fontSize.saveCurrentFontSize();
    }

    @Override
    protected void onDestroy() {
        tts.destroy();
        super.onDestroy();
    }

    private void popupSnackbar(@StringRes int resourceId) {
        Snackbar snackbar = Snackbar.make(editText, resourceId, Snackbar.LENGTH_INDEFINITE);
        snackbar.setAnchorView(R.id.progressBar);
        snackbar.show();
    }

    private void downloadFile(String url) {
        editText.setVisibility(View.GONE);
        loading.setVisibility(View.VISIBLE);
        popupSnackbar(R.string.downloading);

        if (url.endsWith("?dl=0")) {
            url = url.replace("?dl=0", "?dl=1");
        }

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(url).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                showFailure(R.string.unable_to_download);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (ResponseBody responseBody = response.body()) {
                    if (!response.isSuccessful()) {
                        showFailure(R.string.failed_request);
                    } else if (responseBody == null) {
                        showFailure(R.string.no_data);
                    } else {
                        showText(responseBody.string());
                    }
                }
            }
        });
    }

    private void showText(String text) {
        runOnUiThread(() -> {
            editText.setFilters(new InputFilter[]{});
            editText.setText(text);
            editText.setSelection(editText.getText().length());
            disableTyping();
            loading.setVisibility(View.GONE);
            editText.setVisibility(View.VISIBLE);
            editText.requestFocus();
            popupSnackbar(R.string.showing_downloaded);
        });
    }

    private void disableTyping() {
        InputFilter filter = (source, start, end, dest, dstart, dend) -> {
            for (int i = start; i < end; i++) {
                String character = Character.toString(source.charAt(i));
                if (character.equals(yesNo.getYesKey()) ||
                        character.equals(yesNo.getNoKey()) ||
                        character.equals(fontSize.getIncrementKey()) ||
                        character.equals(fontSize.getDecrementKey()) ||
                        character.equals(tts.getCharSpeak()) ||
                        character.equals("\n")) {
                    return null;
                }
            }
            return "";
        };
        editText.setFilters(new InputFilter[]{filter});
    }

    private void showFailure(@StringRes int resourceId) {
        runOnUiThread(() -> {
            loading.setVisibility(View.GONE);
            popupSnackbar(resourceId);
        });
    }
}
