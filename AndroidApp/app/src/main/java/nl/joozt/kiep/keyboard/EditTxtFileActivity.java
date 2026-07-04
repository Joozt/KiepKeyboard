package nl.joozt.kiep.keyboard;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Editable sibling of {@link FullscreenActivity}'s scratch pad: opens a single text file picked
 * through the Storage Access Framework, edits it in the fullscreen buffer, and autosaves back
 * through the same {@code content://} URI. Modelled on the old read-only viewer.
 */
public class EditTxtFileActivity extends AppCompatActivity {
    private static final long AUTOSAVE_DELAY_MS = 2500;
    private static final char UTF8_BOM = '\uFEFF';

    private EditText editText;
    private ProgressBar loading;
    private FontSize fontSize;
    private TTS tts;
    private YesNo yesNo;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Uri fileUri;
    private boolean writable;
    private boolean isLoading;
    private boolean dirty;
    private boolean conflictPending;

    // Best-effort external-edit detection. Refreshed after every successful own write.
    private boolean baselineValid;
    private long baselineModified;
    private long baselineSize;

    private Snackbar conflictSnackbar;

    private ActivityResultLauncher<Intent> pickerLauncher;
    private final Runnable autosaveRunnable = this::autosave;

    public static void registerKeyPressListener(Context context, GlobalKeyPressListener keyPressListener) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean enabled = preferences.getBoolean(SettingsActivity.EDIT_TXT_ENABLE, SettingsActivity.EDIT_TXT_ENABLE_DEFAULT);
        if (!enabled) {
            return;
        }
        String editKey = preferences.getString(SettingsActivity.EDIT_TXT_KEY, SettingsActivity.EDIT_TXT_KEY_DEFAULT);
        keyPressListener.addListener(editKey, () -> {
            context.startActivity(new Intent(context, EditTxtFileActivity.class));
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
        new BatteryStatus(this, progressBar, background);

        loading = findViewById(R.id.loading);
        editText = findViewById(R.id.editText);
        editText.setShowSoftInputOnFocus(false);
        editText.setText("");

        GlobalKeyPressListener keyPressListener = new GlobalKeyPressListener(editText);
        fontSize = new FontSize(this, keyPressListener, editText);
        yesNo = new YesNo(this, keyPressListener, editText);
        tts = new TTS(this, keyPressListener, editText);
        new Abbreviations(this, keyPressListener, editText);

        // Added after the helpers so onTextChanged sees the inserted command char before
        // GlobalKeyPressListener strips it in afterTextChanged.
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (conflictPending && count > 0) {
                    resolveConflictByKey(s.subSequence(start, start + count).toString());
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (isLoading) {
                    return;
                }
                dirty = true;
                scheduleAutosave();
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }
        });

        pickerLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                Uri uri = result.getData().getData();
                takePersistablePermission(uri);
                persistConfiguredFile(uri);
                openFile(uri);
            } else if (fileUri == null) {
                // User cancelled with nothing configured: nothing to edit.
                finish();
            }
        });

        String storedUri = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(SettingsActivity.EDIT_TXT_URI, "");
        if (storedUri.isEmpty()) {
            launchPicker();
        } else {
            openFile(Uri.parse(storedUri));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        editText.requestFocus();
        checkForExternalChange();
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
        // Flush so an OS kill after a recent edit doesn't lose work.
        mainHandler.removeCallbacks(autosaveRunnable);
        if (dirty && writable && !conflictPending && fileUri != null) {
            final Uri uri = fileUri;
            final String snapshot = editText.getText().toString();
            io.execute(() -> {
                try {
                    writeContent(uri, snapshot);
                } catch (IOException ignored) {
                    // Best-effort on the way out; conflicts/failures are handled in the foreground.
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(autosaveRunnable);
        io.shutdown();
        tts.destroy();
        super.onDestroy();
    }

    private void launchPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        pickerLauncher.launch(intent);
    }

    private void takePersistablePermission(Uri uri) {
        int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (SecurityException e) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // No persistable permission; the editor will surface read/write problems.
            }
        }
    }

    private void persistConfiguredFile(Uri uri) {
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.putString(SettingsActivity.EDIT_TXT_URI, uri.toString());
        editor.putString(SettingsActivity.EDIT_TXT_DISPLAY_NAME, queryDisplayName(uri));
        editor.apply();
    }

    private boolean hasWritePermission(Uri uri) {
        for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(uri)) {
                return permission.isWritePermission();
            }
        }
        return false;
    }

    private void openFile(Uri uri) {
        fileUri = uri;
        writable = hasWritePermission(uri);
        conflictPending = false;

        isLoading = true;
        editText.setVisibility(View.GONE);
        loading.setVisibility(View.VISIBLE);

        final Uri target = uri;
        io.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(target)) {
                if (in == null) {
                    throw new IOException("openInputStream returned null");
                }
                final String text = stripBom(readAll(in));
                final long[] meta = queryMeta(target);
                mainHandler.post(() -> applyLoadedText(text, meta));
            } catch (IOException | SecurityException e) {
                mainHandler.post(() -> {
                    isLoading = false;
                    loading.setVisibility(View.GONE);
                    editText.setVisibility(View.VISIBLE);
                    popupSnackbar(R.string.edit_txt_load_failed, Snackbar.LENGTH_INDEFINITE);
                });
            }
        });
    }

    private void applyLoadedText(String text, long[] meta) {
        editText.setFilters(new InputFilter[]{});
        editText.setText(text);
        editText.setSelection(editText.getText().length());
        editText.setVisibility(View.VISIBLE);
        loading.setVisibility(View.GONE);
        editText.requestFocus();

        updateBaseline(meta);
        dirty = false;
        isLoading = false;

        if (!writable) {
            applyReadOnlyFilter();
            popupSnackbar(R.string.edit_txt_read_only, Snackbar.LENGTH_INDEFINITE);
        }

        String name = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(SettingsActivity.EDIT_TXT_DISPLAY_NAME, "");
        if (!name.isEmpty()) {
            Snackbar.make(editText, getString(R.string.edit_txt_opened, name), Snackbar.LENGTH_SHORT)
                    .setAnchorView(R.id.progressBar)
                    .show();
        }
    }

    private void scheduleAutosave() {
        mainHandler.removeCallbacks(autosaveRunnable);
        mainHandler.postDelayed(autosaveRunnable, AUTOSAVE_DELAY_MS);
    }

    private void autosave() {
        if (!dirty || !writable || isLoading || conflictPending || fileUri == null) {
            return;
        }
        final Uri uri = fileUri;
        final String snapshot = editText.getText().toString();
        io.execute(() -> {
            long[] meta = queryMeta(uri);
            if (isExternalChange(meta)) {
                mainHandler.post(this::onConflictWhileDirty);
                return;
            }
            try {
                writeContent(uri, snapshot);
                long[] after = queryMeta(uri);
                mainHandler.post(() -> onWriteSucceeded(snapshot, after));
            } catch (IOException e) {
                mainHandler.post(this::onWriteFailed);
            }
        });
    }

    private void onWriteSucceeded(String snapshot, long[] meta) {
        updateBaseline(meta);
        if (editText.getText().toString().equals(snapshot)) {
            dirty = false;
        } else {
            // The user typed during the write; keep dirty and save again.
            scheduleAutosave();
        }
    }

    private void onWriteFailed() {
        writable = false;
        applyReadOnlyFilter();
        popupSnackbar(R.string.edit_txt_save_failed, Snackbar.LENGTH_INDEFINITE);
    }

    private void checkForExternalChange() {
        if (fileUri == null || isLoading || conflictPending) {
            return;
        }
        final Uri uri = fileUri;
        io.execute(() -> {
            long[] meta = queryMeta(uri);
            if (!isExternalChange(meta)) {
                return;
            }
            mainHandler.post(() -> {
                if (dirty) {
                    onConflictWhileDirty();
                } else {
                    openFile(uri); // silent reload of the remote copy
                }
            });
        });
    }

    private void onConflictWhileDirty() {
        conflictPending = true;
        conflictSnackbar = popupSnackbar(R.string.edit_txt_conflict, Snackbar.LENGTH_INDEFINITE);
    }

    private void resolveConflictByKey(String inserted) {
        String yesKey = yesNo.getYesKey();
        String noKey = yesNo.getNoKey();
        if (!yesKey.isEmpty() && inserted.contains(yesKey)) {
            dismissConflictSnackbar();
            conflictPending = false;
            openFile(fileUri); // reload, discarding local edits
        } else if (!noKey.isEmpty() && inserted.contains(noKey)) {
            dismissConflictSnackbar();
            conflictPending = false;
            dirty = true;
            scheduleAutosave(); // keep mine: overwrite remote on the next save tick
        }
    }

    private void dismissConflictSnackbar() {
        if (conflictSnackbar != null) {
            conflictSnackbar.dismiss();
            conflictSnackbar = null;
        }
    }

    private boolean isExternalChange(long[] meta) {
        return baselineValid && meta != null && (meta[0] != baselineModified || meta[1] != baselineSize);
    }

    private void updateBaseline(long[] meta) {
        if (meta == null) {
            baselineValid = false;
            return;
        }
        baselineModified = meta[0];
        baselineSize = meta[1];
        baselineValid = true;
    }

    /**
     * @return {@code {lastModified, size}}, or {@code null} when the provider does not expose them.
     */
    private long[] queryMeta(Uri uri) {
        String[] projection = {
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE
        };
        try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                int sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
                if (modifiedIndex >= 0 && sizeIndex >= 0
                        && !cursor.isNull(modifiedIndex) && !cursor.isNull(sizeIndex)) {
                    return new long[]{cursor.getLong(modifiedIndex), cursor.getLong(sizeIndex)};
                }
            }
        } catch (Exception ignored) {
            // Provider gap: conflict detection is skipped.
        }
        return null;
    }

    private String queryDisplayName(Uri uri) {
        String[] projection = {android.provider.OpenableColumns.DISPLAY_NAME};
        try (Cursor cursor = getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to empty name.
        }
        return "";
    }

    private void writeContent(Uri uri, String content) throws IOException {
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) {
                throw new IOException("openOutputStream returned null");
            }
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String stripBom(String text) {
        if (!text.isEmpty() && text.charAt(0) == UTF8_BOM) {
            return text.substring(1);
        }
        return text;
    }

    private void applyReadOnlyFilter() {
        InputFilter filter = (source, start, end, dest, dstart, dend) -> {
            for (int i = start; i < end; i++) {
                String character = Character.toString(source.charAt(i));
                if (character.equals(yesNo.getYesKey())
                        || character.equals(yesNo.getNoKey())
                        || character.equals(fontSize.getIncrementKey())
                        || character.equals(fontSize.getDecrementKey())
                        || character.equals(tts.getCharSpeak())
                        || character.equals("\n")) {
                    return null;
                }
            }
            return "";
        };
        editText.setFilters(new InputFilter[]{filter});
    }

    private Snackbar popupSnackbar(@StringRes int resourceId, int duration) {
        Snackbar snackbar = Snackbar.make(editText, resourceId, duration);
        snackbar.setAnchorView(R.id.progressBar);
        snackbar.show();
        return snackbar;
    }
}
