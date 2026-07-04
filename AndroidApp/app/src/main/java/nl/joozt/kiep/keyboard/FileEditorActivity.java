package nl.joozt.kiep.keyboard;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.google.android.material.snackbar.Snackbar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Edits one text file picked via the Storage Access Framework (the File, in
 * app terminology - typically living in Dropbox). See
 * docs/proposals/editable-files.md for the design.
 */
public class FileEditorActivity extends AppCompatActivity {
    private static final String TAG = FileEditorActivity.class.getSimpleName();
    private static final String BOM = "\uFEFF";

    // File writes are sparse (leaving the editor + a safety-net timer):
    // every write is a Dropbox upload, and frequent uploads overlapping
    // remote edits make Dropbox create "conflicted copy" files. Durability
    // between file writes comes from the local mirror instead.
    private static final long SAFETY_SAVE_INTERVAL_MS = 5 * 60 * 1000;
    private static final long MIRROR_DELAY_MS = 2000;

    private static final String MIRROR_PREFS = "FileEditor";
    private static final String MIRROR_URI = "MirrorUri";
    private static final String MIRROR_TEXT = "MirrorText";
    private static final String MIRROR_DIRTY = "MirrorDirty";

    private EditText editText;
    private ProgressBar loading;
    private FontSize fontSize;
    private Abbreviations abbreviations;
    private TTS tts;
    private YesNo yesNo;
    private BatteryStatus batteryStatus;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable mirrorRunnable = this::writeMirror;
    private final Runnable safetySaveRunnable = new Runnable() {
        @Override
        public void run() {
            saveFile();
            handler.postDelayed(this, SAFETY_SAVE_INTERVAL_MS);
        }
    };

    private Uri uri;
    private boolean crlf = false;
    private boolean readOnly = false;
    private long lastModifiedAtLoad = 0;
    private String lastSavedText = null; // null until the File is loaded
    private Snackbar saveFailedSnackbar = null;

    private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), pickedUri -> {
                if (pickedUri == null) {
                    finish(); // Nothing picked, nothing to edit
                    return;
                }
                getContentResolver().takePersistableUriPermission(pickedUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                        .putString(SettingsActivity.FILE_URI, pickedUri.toString())
                        .apply();
                uri = pickedUri;
                loadFile();
            });

    public static void registerKeyPressListener(Context context, GlobalKeyPressListener keyPressListener) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String openFileKey = preferences.getString(SettingsActivity.OPEN_FILE_KEY, SettingsActivity.OPEN_FILE_KEY_DEFAULT);
        keyPressListener.addListener(openFileKey, () -> {
            context.startActivity(new Intent(context, FileEditorActivity.class));
            return true;
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fullscreen);
        findViewById(R.id.fab).setVisibility(View.GONE);

        FrameLayout background = findViewById(R.id.content);
        ProgressBar progressBar = findViewById(R.id.progressBar);
        batteryStatus = new BatteryStatus(this, progressBar, background);

        loading = findViewById(R.id.loading);
        editText = findViewById(R.id.editText);
        editText.setShowSoftInputOnFocus(false);
        editText.setText("");

        GlobalKeyPressListener keyPressListener = new GlobalKeyPressListener(editText);
        fontSize = new FontSize(this, keyPressListener, editText);
        abbreviations = new Abbreviations(this, keyPressListener, editText);
        yesNo = new YesNo(this, keyPressListener, editText);
        tts = new TTS(this, keyPressListener, editText);

        // A yes/no answer interrupts ongoing speech
        yesNo.setOnKeyPressed(tts::stopSpeaking);

        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (lastSavedText == null || readOnly) {
                    return;
                }
                handler.removeCallbacks(mirrorRunnable);
                handler.postDelayed(mirrorRunnable, MIRROR_DELAY_MS);
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        });

        // Back/Esc always leads to the Notepad, also when the editor was
        // opened straight from another app ("Open with...")
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                openNotepadIfTaskRoot();
                finish();
            }
        });

        if (adoptIntentFile(getIntent())) {
            loadFile();
        } else {
            String uriString = PreferenceManager.getDefaultSharedPreferences(this)
                    .getString(SettingsActivity.FILE_URI, SettingsActivity.FILE_URI_DEFAULT);
            if (uriString.isEmpty()) {
                filePicker.launch(new String[]{"text/*"});
            } else {
                uri = Uri.parse(uriString);
                loadFile();
            }
        }

        handler.postDelayed(safetySaveRunnable, SAFETY_SAVE_INTERVAL_MS);
    }

    /**
     * Adopts a file handed over by another app ("Open with..." from Dropbox
     * etc.) as the File from now on. The permission grant is persisted when
     * the sender allows it; otherwise it lasts for this session and a later
     * failing load falls back to the picker flow.
     */
    private boolean adoptIntentFile(Intent intent) {
        Uri data = intent.getData();
        if (data == null) {
            return false;
        }
        try {
            getContentResolver().takePersistableUriPermission(data,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            Log.w(TAG, "Permission for the shared file is not persistable", e);
        }
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(SettingsActivity.FILE_URI, data.toString())
                .apply();
        uri = data;
        return true;
    }

    private void openNotepadIfTaskRoot() {
        if (isTaskRoot()) {
            startActivity(new Intent(this, FullscreenActivity.class));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        // Save the current File before another one arrives via "Open with..."
        saveFile();
        if (adoptIntentFile(intent)) {
            loadFile();
            return;
        }

        // The File may have been switched in Settings while this (singleTask)
        // instance was alive
        String uriString = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(SettingsActivity.FILE_URI, SettingsActivity.FILE_URI_DEFAULT);
        if (!uriString.isEmpty() && (uri == null || !uriString.equals(uri.toString()))) {
            uri = Uri.parse(uriString);
            loadFile();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        editText.requestFocus();

        // Pick up remote edits when nothing is pending locally
        if (uri != null && lastSavedText != null && !readOnly
                && editText.getText().toString().equals(lastSavedText)) {
            final Uri fileUri = uri;
            final long expected = lastModifiedAtLoad;
            io.execute(() -> {
                long current = queryLastModified(fileUri);
                if (current != 0 && current != expected) {
                    handler.post(this::loadFile);
                }
            });
        }
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
        handler.removeCallbacks(mirrorRunnable);
        writeMirror();
        saveFile();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        tts.destroy();
        batteryStatus.destroy();
        yesNo.destroy();
        io.shutdown(); // Lets a queued save finish
        super.onDestroy();
    }

    private void loadFile() {
        editText.setVisibility(View.GONE);
        loading.setVisibility(View.VISIBLE);
        final Uri fileUri = uri;
        io.execute(() -> {
            try {
                String raw;
                try (InputStream in = getContentResolver().openInputStream(fileUri)) {
                    raw = readUtf8(Objects.requireNonNull(in));
                }
                long lastModified = queryLastModified(fileUri);
                boolean writable = isWritable(fileUri);
                LoadedText loadedText = load(raw);
                handler.post(() -> showLoaded(loadedText, lastModified, writable));
            } catch (Exception e) {
                Log.e(TAG, "Unable to load file", e);
                handler.post(() -> {
                    Toast.makeText(this, R.string.file_load_failed, Toast.LENGTH_LONG).show();
                    openNotepadIfTaskRoot();
                    finish();
                });
            }
        });
    }

    private void showLoaded(LoadedText loadedText, long lastModified, boolean writable) {
        crlf = loadedText.crlf;
        lastModifiedAtLoad = lastModified;
        readOnly = !writable;
        String text = loadedText.text;
        lastSavedText = text;

        // Crash recovery: unsaved mirror content for this File wins over the
        // (older) file content and gets saved by the next pause/timer
        SharedPreferences mirror = getSharedPreferences(MIRROR_PREFS, MODE_PRIVATE);
        if (!readOnly && mirror.getBoolean(MIRROR_DIRTY, false)
                && uri.toString().equals(mirror.getString(MIRROR_URI, ""))) {
            text = mirror.getString(MIRROR_TEXT, text);
        }

        abbreviations.pause();
        editText.setFilters(new InputFilter[]{});
        editText.setText(text);
        editText.setSelection(editText.getText().length());
        abbreviations.resume();

        if (readOnly) {
            disableTyping();
            popupSnackbar(R.string.file_read_only);
        }
        loading.setVisibility(View.GONE);
        editText.setVisibility(View.VISIBLE);
        editText.requestFocus();
    }

    private void writeMirror() {
        if (lastSavedText == null || readOnly || uri == null) {
            return;
        }
        String text = editText.getText().toString();
        if (text.equals(lastSavedText)) {
            return;
        }
        getSharedPreferences(MIRROR_PREFS, MODE_PRIVATE).edit()
                .putString(MIRROR_URI, uri.toString())
                .putString(MIRROR_TEXT, text)
                .putBoolean(MIRROR_DIRTY, true)
                .apply();
    }

    private void saveFile() {
        if (uri == null || readOnly || lastSavedText == null) {
            return;
        }
        final String text = editText.getText().toString();
        if (text.equals(lastSavedText)) {
            return;
        }
        final Uri fileUri = uri;
        final boolean fileCrlf = crlf;
        final long expectedLastModified = lastModifiedAtLoad;
        io.execute(() -> {
            try {
                // Experiment (editable-files.md): read the document right
                // before writing to nudge the provider into refreshing its
                // cached copy and revision - a stale cached rev is what makes
                // Dropbox mint "conflicted copy" files
                try (InputStream in = getContentResolver().openInputStream(fileUri)) {
                    readUtf8(Objects.requireNonNull(in));
                } catch (Exception e) {
                    Log.w(TAG, "Pre-write refresh read failed", e);
                }

                // Local edits win on conflict; the remote version stays
                // recoverable through the provider's own history (Dropbox)
                boolean changedElsewhere = queryLastModified(fileUri) != expectedLastModified;
                try (OutputStream out = getContentResolver().openOutputStream(fileUri, "wt")) {
                    Objects.requireNonNull(out).write(store(text, fileCrlf).getBytes(StandardCharsets.UTF_8));
                }
                long newLastModified = queryLastModified(fileUri);
                handler.post(() -> onSaved(text, newLastModified, changedElsewhere));
            } catch (Exception e) {
                Log.e(TAG, "Unable to save file", e);
                handler.post(this::showSaveFailure);
            }
        });
    }

    private void onSaved(String text, long lastModified, boolean changedElsewhere) {
        lastSavedText = text;
        lastModifiedAtLoad = lastModified;
        getSharedPreferences(MIRROR_PREFS, MODE_PRIVATE).edit()
                .putBoolean(MIRROR_DIRTY, false)
                .apply();
        if (saveFailedSnackbar != null) {
            saveFailedSnackbar.dismiss();
            saveFailedSnackbar = null;
        }
        if (changedElsewhere) {
            popupSnackbar(R.string.file_changed_elsewhere);
        }
    }

    private void showSaveFailure() {
        // Typing stays possible; the mirror keeps the text safe and every
        // next pause/timer retries the save
        if (saveFailedSnackbar == null) {
            saveFailedSnackbar = Snackbar.make(editText, R.string.file_save_failed, Snackbar.LENGTH_INDEFINITE);
            saveFailedSnackbar.setAnchorView(R.id.progressBar);
        }
        saveFailedSnackbar.show();
    }

    private void disableTyping() {
        InputFilter filter = (source, start, end, dest, dstart, dend) -> {
            for (int i = start; i < end; i++) {
                String character = Character.toString(source.charAt(i));
                if (character.equals(yesNo.getYesKey()) ||
                        character.equals(yesNo.getNoKey()) ||
                        character.equals(fontSize.getIncrementKey()) ||
                        character.equals(fontSize.getDecrementKey()) ||
                        character.equals(tts.getSpeakKey()) ||
                        character.equals(tts.getSpeakAllKey())) {
                    return null;
                }
            }
            return "";
        };
        editText.setFilters(new InputFilter[]{filter});
    }

    private void popupSnackbar(@StringRes int resourceId) {
        Snackbar snackbar = Snackbar.make(editText, resourceId, Snackbar.LENGTH_LONG);
        snackbar.setAnchorView(R.id.progressBar);
        snackbar.show();
    }

    private long queryLastModified(Uri fileUri) {
        try (Cursor cursor = getContentResolver().query(fileUri,
                new String[]{DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to query last modified", e);
        }
        return 0;
    }

    private boolean isWritable(Uri fileUri) {
        try (Cursor cursor = getContentResolver().query(fileUri,
                new String[]{DocumentsContract.Document.COLUMN_FLAGS}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return (cursor.getInt(0) & DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0;
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to query flags", e);
        }
        return true; // Assume writable; save failures have their own path
    }

    private static String readUtf8(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    /**
     * Decodes raw file content for the editor: strips a UTF-8 BOM and
     * normalizes line endings to \n (EditText works in \n), remembering
     * whether the file used \r\n so {@link #store} can restore it.
     */
    static LoadedText load(String raw) {
        boolean crlf = raw.contains("\r\n");
        String text = raw;
        if (text.startsWith(BOM)) {
            text = text.substring(BOM.length());
        }
        return new LoadedText(text.replace("\r\n", "\n"), crlf);
    }

    /**
     * Encodes editor content for the file: restores \r\n when the file used
     * it. A stripped BOM is never written back.
     */
    static String store(String text, boolean crlf) {
        return crlf ? text.replace("\n", "\r\n") : text;
    }

    static class LoadedText {
        final String text;
        final boolean crlf;

        LoadedText(String text, boolean crlf) {
            this.text = text;
            this.crlf = crlf;
        }
    }
}
