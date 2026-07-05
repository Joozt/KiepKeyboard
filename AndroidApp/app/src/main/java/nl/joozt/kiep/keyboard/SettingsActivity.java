package nl.joozt.kiep.keyboard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SettingsActivity extends AppCompatActivity {
    static final String[] functionKeys = {"[", "]", "\\", ";", "'", "/", "-", "=", "`", "{", "}", "|", ":", "\"", "<", ">", "~",
            "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12", "Scroll lock", "Break"};
    public static final String OPEN_PLAY_STORE_LISTING = "open_play_store_listing";
    public static final String ENABLE_SETTINGS_BUTTON = "enable_settings_button";
    public static final boolean ENABLE_SETTINGS_BUTTON_DEFAULT = true;
    public static final String ABBREVIATIONS = "abbreviations";
    public static final String ABBREVIATIONS_DEFAULT = "::kkb::KiepKeyboard";
    public static final String ENABLE_TTS = "enable_tts";
    public static final boolean ENABLE_TTS_DEFAULT = true;
    public static final String TTS_KEY = "tts_key";
    public static final String TTS_KEY_DEFAULT = "\\";
    public static final String TTS_SPEAK_ALL_KEY = "tts_speak_all_key";
    public static final String TTS_SPEAK_ALL_KEY_DEFAULT = "";
    public static final String TTS_RATE = "tts_rate";
    public static final int TTS_RATE_DEFAULT = 100;
    public static final String TTS_PITCH = "tts_pitch";
    public static final int TTS_PITCH_DEFAULT = 100;
    public static final String ENABLE_YES_NO = "enable_yes_no";
    public static final boolean ENABLE_YES_NO_DEFAULT = true;
    public static final String ENABLE_HIGHLIGHT = "enable_highlight";
    public static final boolean ENABLE_HIGHLIGHT_DEFAULT = true;
    public static final String NO_KEY = "no_key";
    public static final String NO_KEY_DEFAULT = "[";
    public static final String YES_KEY = "yes_key";
    public static final String YES_KEY_DEFAULT = "]";
    public static final String ENABLE_EXTERNAL_DISPLAY = "enable_external_display";
    public static final boolean ENABLE_EXTERNAL_DISPLAY_DEFAULT = false;
    public static final String EXTERNAL_DISPLAY_MODE = "external_display_mode";
    public static final String EXTERNAL_DISPLAY_MODE_DEFAULT = "off";
    public static final String EXTERNAL_DISPLAY_MODE_CHARACTER = "character";
    public static final String EXTERNAL_DISPLAY_MODE_WORD = "word";
    public static final String EXTERNAL_DISPLAY_MODE_SENTENCE = "sentence";
    public static final String SEND_KEY = "send_key";
    public static final String SEND_KEY_DEFAULT = "\\"; // Same as the TTS speak key: one press speaks and displays
    public static final String ATTENTION_KEY = "attention_key";
    public static final String ATTENTION_KEY_DEFAULT = "";
    public static final String AUTO_ATTENTION = "auto_attention";
    public static final boolean AUTO_ATTENTION_DEFAULT = false;
    public static final String ATTENTION_REPEAT = "attention_repeat";
    public static final int ATTENTION_REPEAT_DEFAULT = 1;
    public static final String EXTERNAL_DISPLAYS = "external_displays"; // StringSet of "address|name"
    public static final String FILE_URI = "file_uri";
    public static final String FILE_URI_DEFAULT = "";
    public static final String OPEN_FILE_KEY = "open_file_key";
    public static final String OPEN_FILE_KEY_DEFAULT = "F2";
    public static final String BATTERY_STATUS = "battery_status";
    public static final boolean BATTERY_STATUS_DEFAULT = true;
    public static final String LOWBAT_WARNING = "lowbat_warning";
    public static final boolean LOWBAT_WARNING_DEFAULT = true;
    public static final String LOWBAT_LEVEL = "lowbat_level";
    public static final int LOWBAT_LEVEL_DEFAULT = 10;
    public static final String FONT_SIZE = "font_size";
    public static final int FONT_SIZE_DEFAULT = 100;
    public static final String FONT_SIZE_DECREMENT_KEY = "decrement_key";
    public static final String FONT_SIZE_DECREMENT_KEY_DEFAULT = "F9";
    public static final String FONT_SIZE_INCREMENT_KEY = "increment_key";
    public static final String FONT_SIZE_INCREMENT_KEY_DEFAULT = "F10";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new SettingsFragment())
                .commit();
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private static final String CHOOSE_FILE = "choose_file";
        private static final String ADD_DISPLAY = "add_display";
        private static final String DISPLAYS = "displays";
        private static final long SCAN_DURATION_MS = 6000;

        private final ActivityResultLauncher<String[]> blePermissionRequest = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), grants -> {
                    if (!grants.containsValue(false)) {
                        scanForDisplays();
                    }
                });

        private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri == null) {
                        return;
                    }
                    requireContext().getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    getPreferenceManager().getSharedPreferences().edit()
                            .putString(FILE_URI, uri.toString())
                            .apply();
                    updateChooseFileSummary();
                });

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context context = getPreferenceManager().getContext();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
            addPlayStoreListing(context, screen);
            addSettingsButton(context, screen);
            addAbbreviations(context, screen);
            addFontSizeCategory(context, screen);
            addYesNoCategory(context, screen);
            addTtsCategory(context, screen);
            addExternalDisplayCategory(context, screen);
            addFileCategory(context, screen);
            addBatteryStatusCategory(context, screen);
            setPreferenceScreen(screen);
            updateChooseFileSummary();
            updateDisplaysSummary();

            screen.findPreference(NO_KEY).setDependency(ENABLE_YES_NO);
            screen.findPreference(YES_KEY).setDependency(ENABLE_YES_NO);
            screen.findPreference(ENABLE_HIGHLIGHT).setDependency(ENABLE_YES_NO);
            screen.findPreference(TTS_KEY).setDependency(ENABLE_TTS);
            screen.findPreference(TTS_SPEAK_ALL_KEY).setDependency(ENABLE_TTS);
            screen.findPreference(TTS_RATE).setDependency(ENABLE_TTS);
            screen.findPreference(TTS_PITCH).setDependency(ENABLE_TTS);
            screen.findPreference(EXTERNAL_DISPLAY_MODE).setDependency(ENABLE_EXTERNAL_DISPLAY);
            screen.findPreference(SEND_KEY).setDependency(ENABLE_EXTERNAL_DISPLAY);
            screen.findPreference(ATTENTION_KEY).setDependency(ENABLE_EXTERNAL_DISPLAY);
            screen.findPreference(AUTO_ATTENTION).setDependency(ENABLE_EXTERNAL_DISPLAY);
            screen.findPreference(ATTENTION_REPEAT).setDependency(ENABLE_EXTERNAL_DISPLAY);
            screen.findPreference(ADD_DISPLAY).setDependency(ENABLE_EXTERNAL_DISPLAY);
            screen.findPreference(DISPLAYS).setDependency(ENABLE_EXTERNAL_DISPLAY);

            preventDuplicateKeys();
        }

        /**
         * All command-key preferences pick from the same key list; two commands
         * on the same key would silently break one of them (last registration
         * wins). Reject a pick that is already in use and tell the user which
         * command owns it.
         */
        private void preventDuplicateKeys() {
            String[] keyPreferences = {FONT_SIZE_DECREMENT_KEY, FONT_SIZE_INCREMENT_KEY, NO_KEY, YES_KEY, TTS_KEY, TTS_SPEAK_ALL_KEY, OPEN_FILE_KEY, SEND_KEY, ATTENTION_KEY};
            for (String keyPreference : keyPreferences) {
                ListPreference preference = findPreference(keyPreference);
                preference.setOnPreferenceChangeListener((changed, newValue) -> {
                    CharSequence owner = ownerOfKey((String) newValue, changed.getKey(), keyPreferences);
                    if (owner != null) {
                        Toast.makeText(requireContext(), getString(R.string.key_already_used, newValue, owner), Toast.LENGTH_LONG).show();
                        return false;
                    }
                    return true;
                });
            }
        }

        private CharSequence ownerOfKey(String newValue, String changedPreference, String[] keyPreferences) {
            if (newValue.isEmpty()) {
                return null; // "None" never conflicts
            }
            for (String keyPreference : keyPreferences) {
                if (keyPreference.equals(changedPreference)) {
                    continue;
                }
                if (isAllowedCombination(changedPreference, keyPreference)) {
                    continue;
                }
                ListPreference other = findPreference(keyPreference);
                if (newValue.equals(other.getValue())) {
                    return other.getTitle();
                }
            }
            return null;
        }

        /**
         * The send key may deliberately equal the TTS speak key: one press
         * speaks and shows on the external displays (multi-listener dispatch).
         */
        private static boolean isAllowedCombination(String changedPreference, String otherPreference) {
            return (SEND_KEY.equals(changedPreference) && TTS_KEY.equals(otherPreference))
                    || (TTS_KEY.equals(changedPreference) && SEND_KEY.equals(otherPreference));
        }

        /**
         * The shared key list with a leading "None" entry (empty value) for
         * optional functions that can be left unassigned.
         */
        private String[] keysWithNone(boolean asValues) {
            String[] result = new String[functionKeys.length + 1];
            result[0] = asValues ? "" : getString(R.string.key_none);
            System.arraycopy(functionKeys, 0, result, 1, functionKeys.length);
            return result;
        }

        private void addPlayStoreListing(final Context context, PreferenceScreen screen) {
            Preference openPlayStoreListing = new Preference(context);
            openPlayStoreListing.setKey(OPEN_PLAY_STORE_LISTING);
            openPlayStoreListing.setTitle(R.string.setting_open_play_store_listing);
            openPlayStoreListing.setSummary(R.string.setting_open_play_store_listing_description);
            openPlayStoreListing.setOnPreferenceClickListener(preference -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + context.getPackageName())));
                } catch (android.content.ActivityNotFoundException ignored) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + context.getPackageName())));
                }
                return true;
            });
            screen.addPreference(openPlayStoreListing);
        }

        private void addSettingsButton(final Context context, PreferenceScreen screen) {
            SwitchPreferenceCompat enableSettingsButton = new SwitchPreferenceCompat(context);
            enableSettingsButton.setKey(ENABLE_SETTINGS_BUTTON);
            enableSettingsButton.setTitle(R.string.setting_enable_settings_button);
            enableSettingsButton.setSummary(R.string.setting_enable_settings_button_description);
            enableSettingsButton.setDefaultValue(ENABLE_SETTINGS_BUTTON_DEFAULT);
            screen.addPreference(enableSettingsButton);
        }

        private void addAbbreviations(Context context, PreferenceScreen screen) {
            EditTextPreference abbreviations = new EditTextPreference(context);
            abbreviations.setKey(ABBREVIATIONS);
            abbreviations.setTitle(R.string.setting_abbreviations);
            abbreviations.setSummary(R.string.setting_abbreviations_description);
            abbreviations.setDefaultValue(ABBREVIATIONS_DEFAULT);
            abbreviations.setOnBindEditTextListener(editText -> editText.setLines(5));
            screen.addPreference(abbreviations);
        }

        private void addFontSizeCategory(Context context, PreferenceScreen screen) {
            PreferenceCategory category = new PreferenceCategory(context);
            category.setKey("font_size");
            category.setTitle(R.string.setting_font_size);
            screen.addPreference(category);

            SeekBarPreference fontSize = new SeekBarPreference(context);
            fontSize.setKey(FONT_SIZE);
            fontSize.setTitle(R.string.setting_font_size);
            fontSize.setDefaultValue(FONT_SIZE_DEFAULT);
            fontSize.setSeekBarIncrement(10);
            fontSize.setMin(10);
            fontSize.setMax(400);
            fontSize.setShowSeekBarValue(true);
            category.addPreference(fontSize);

            ListPreference decrementKey = new ListPreference(context);
            decrementKey.setKey(FONT_SIZE_DECREMENT_KEY);
            decrementKey.setTitle(R.string.setting_font_size_decrement_key);
            decrementKey.setSummary(R.string.setting_font_size_decrement_key_description);
            decrementKey.setEntries(functionKeys);
            decrementKey.setEntryValues(functionKeys);
            decrementKey.setDefaultValue(FONT_SIZE_DECREMENT_KEY_DEFAULT);
            category.addPreference(decrementKey);

            ListPreference incrementKey = new ListPreference(context);
            incrementKey.setKey(FONT_SIZE_INCREMENT_KEY);
            incrementKey.setTitle(R.string.setting_font_size_increment_key);
            incrementKey.setSummary(R.string.setting_font_size_increment_key_description);
            incrementKey.setEntries(functionKeys);
            incrementKey.setEntryValues(functionKeys);
            incrementKey.setDefaultValue(FONT_SIZE_INCREMENT_KEY_DEFAULT);
            category.addPreference(incrementKey);
        }

        private void addYesNoCategory(Context context, PreferenceScreen screen) {
            PreferenceCategory category = new PreferenceCategory(context);
            category.setKey("yes_no");
            category.setTitle(R.string.setting_yes_no);
            screen.addPreference(category);

            SwitchPreferenceCompat enableYesNo = new SwitchPreferenceCompat(context);
            enableYesNo.setKey(ENABLE_YES_NO);
            enableYesNo.setTitle(R.string.setting_enable_yes_no);
            enableYesNo.setSummary(R.string.setting_enable_yes_no_description);
            enableYesNo.setDefaultValue(ENABLE_YES_NO_DEFAULT);
            category.addPreference(enableYesNo);

            ListPreference noKey = new ListPreference(context);
            noKey.setKey(NO_KEY);
            noKey.setTitle(R.string.setting_no_key);
            noKey.setSummary(R.string.setting_no_key_description);
            noKey.setEntries(functionKeys);
            noKey.setEntryValues(functionKeys);
            noKey.setDefaultValue(NO_KEY_DEFAULT);
            category.addPreference(noKey);

            ListPreference yesKey = new ListPreference(context);
            yesKey.setKey(YES_KEY);
            yesKey.setTitle(R.string.setting_yes_key);
            yesKey.setSummary(R.string.setting_yes_key_description);
            yesKey.setEntries(functionKeys);
            yesKey.setEntryValues(functionKeys);
            yesKey.setDefaultValue(YES_KEY_DEFAULT);
            category.addPreference(yesKey);

            SwitchPreferenceCompat enableHighlight = new SwitchPreferenceCompat(context);
            enableHighlight.setKey(ENABLE_HIGHLIGHT);
            enableHighlight.setTitle(R.string.setting_enable_highlight);
            enableHighlight.setSummary(R.string.setting_enable_highlight_description);
            enableHighlight.setDefaultValue(ENABLE_HIGHLIGHT_DEFAULT);
            category.addPreference(enableHighlight);
        }

        private void addTtsCategory(Context context, PreferenceScreen screen) {
            PreferenceCategory category = new PreferenceCategory(context);
            category.setKey("tts");
            category.setTitle(R.string.setting_tts);
            screen.addPreference(category);

            SwitchPreferenceCompat enableTts = new SwitchPreferenceCompat(context);
            enableTts.setKey(ENABLE_TTS);
            enableTts.setTitle(R.string.setting_enable_tts);
            enableTts.setSummary(R.string.setting_enable_tts_description);
            enableTts.setDefaultValue(ENABLE_TTS_DEFAULT);
            category.addPreference(enableTts);

            ListPreference speakKey = new ListPreference(context);
            speakKey.setKey(TTS_KEY);
            speakKey.setTitle(R.string.setting_tts_key);
            speakKey.setSummary(R.string.setting_tts_key_description);
            speakKey.setEntries(functionKeys);
            speakKey.setEntryValues(functionKeys);
            speakKey.setDefaultValue(TTS_KEY_DEFAULT);
            category.addPreference(speakKey);

            ListPreference speakAllKey = new ListPreference(context);
            speakAllKey.setKey(TTS_SPEAK_ALL_KEY);
            speakAllKey.setTitle(R.string.setting_tts_speak_all_key);
            speakAllKey.setSummary(R.string.setting_tts_speak_all_key_description);
            speakAllKey.setEntries(keysWithNone(false));
            speakAllKey.setEntryValues(keysWithNone(true));
            speakAllKey.setDefaultValue(TTS_SPEAK_ALL_KEY_DEFAULT);
            category.addPreference(speakAllKey);

            SeekBarPreference rate = new SeekBarPreference(context);
            rate.setKey(TTS_RATE);
            rate.setTitle(R.string.setting_tts_rate);
            rate.setDefaultValue(TTS_RATE_DEFAULT);
            rate.setSeekBarIncrement(5);
            rate.setMin(25);
            rate.setMax(120);
            rate.setShowSeekBarValue(true);
            category.addPreference(rate);

            SeekBarPreference pitch = new SeekBarPreference(context);
            pitch.setKey(TTS_PITCH);
            pitch.setTitle(R.string.setting_tts_pitch);
            pitch.setDefaultValue(TTS_PITCH_DEFAULT);
            pitch.setSeekBarIncrement(10);
            pitch.setMin(50);
            pitch.setMax(200);
            pitch.setShowSeekBarValue(true);
            category.addPreference(pitch);
        }

        private void addExternalDisplayCategory(Context context, PreferenceScreen screen) {
            PreferenceCategory category = new PreferenceCategory(context);
            category.setKey("external_display");
            category.setTitle(R.string.setting_external_display);
            screen.addPreference(category);

            SwitchPreferenceCompat enable = new SwitchPreferenceCompat(context);
            enable.setKey(ENABLE_EXTERNAL_DISPLAY);
            enable.setTitle(R.string.setting_enable_external_display);
            enable.setSummary(R.string.setting_enable_external_display_description);
            enable.setDefaultValue(ENABLE_EXTERNAL_DISPLAY_DEFAULT);
            category.addPreference(enable);

            ListPreference mode = new ListPreference(context);
            mode.setKey(EXTERNAL_DISPLAY_MODE);
            mode.setTitle(R.string.setting_external_display_mode);
            mode.setSummary("%s");
            mode.setEntries(new String[]{
                    getString(R.string.mode_hotkey_only),
                    getString(R.string.mode_character),
                    getString(R.string.mode_word),
                    getString(R.string.mode_sentence)});
            mode.setEntryValues(new String[]{
                    EXTERNAL_DISPLAY_MODE_DEFAULT,
                    EXTERNAL_DISPLAY_MODE_CHARACTER,
                    EXTERNAL_DISPLAY_MODE_WORD,
                    EXTERNAL_DISPLAY_MODE_SENTENCE});
            mode.setDefaultValue(EXTERNAL_DISPLAY_MODE_DEFAULT);
            category.addPreference(mode);

            ListPreference sendKey = new ListPreference(context);
            sendKey.setKey(SEND_KEY);
            sendKey.setTitle(R.string.setting_send_key);
            sendKey.setSummary(R.string.setting_send_key_description);
            sendKey.setEntries(functionKeys);
            sendKey.setEntryValues(functionKeys);
            sendKey.setDefaultValue(SEND_KEY_DEFAULT);
            category.addPreference(sendKey);

            ListPreference attentionKey = new ListPreference(context);
            attentionKey.setKey(ATTENTION_KEY);
            attentionKey.setTitle(R.string.setting_attention_key);
            attentionKey.setSummary(R.string.setting_attention_key_description);
            attentionKey.setEntries(keysWithNone(false));
            attentionKey.setEntryValues(keysWithNone(true));
            attentionKey.setDefaultValue(ATTENTION_KEY_DEFAULT);
            category.addPreference(attentionKey);

            SwitchPreferenceCompat autoAttention = new SwitchPreferenceCompat(context);
            autoAttention.setKey(AUTO_ATTENTION);
            autoAttention.setTitle(R.string.setting_auto_attention);
            autoAttention.setSummary(R.string.setting_auto_attention_description);
            autoAttention.setDefaultValue(AUTO_ATTENTION_DEFAULT);
            category.addPreference(autoAttention);

            SeekBarPreference attentionRepeat = new SeekBarPreference(context);
            attentionRepeat.setKey(ATTENTION_REPEAT);
            attentionRepeat.setTitle(R.string.setting_attention_repeat);
            attentionRepeat.setDefaultValue(ATTENTION_REPEAT_DEFAULT);
            attentionRepeat.setMin(1);
            attentionRepeat.setMax(5);
            attentionRepeat.setSeekBarIncrement(1);
            attentionRepeat.setShowSeekBarValue(true);
            category.addPreference(attentionRepeat);

            Preference addDisplay = new Preference(context);
            addDisplay.setKey(ADD_DISPLAY);
            addDisplay.setTitle(R.string.setting_add_display);
            addDisplay.setOnPreferenceClickListener(preference -> {
                addDisplayClicked();
                return true;
            });
            category.addPreference(addDisplay);

            Preference displays = new Preference(context);
            displays.setKey(DISPLAYS);
            displays.setTitle(R.string.setting_displays);
            displays.setOnPreferenceClickListener(preference -> {
                manageDisplaysClicked();
                return true;
            });
            category.addPreference(displays);
        }

        private void addDisplayClicked() {
            String[] permissions = blePermissions();
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                    blePermissionRequest.launch(permissions);
                    return;
                }
            }
            scanForDisplays();
        }

        private static String[] blePermissions() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
            }
            return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        }

        @SuppressLint("MissingPermission")
        private void scanForDisplays() {
            BluetoothManager bluetoothManager = (BluetoothManager) requireContext().getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bluetoothManager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                Toast.makeText(requireContext(), R.string.bluetooth_disabled, Toast.LENGTH_LONG).show();
                return;
            }

            Map<String, String> found = new LinkedHashMap<>();
            ScanCallback callback = new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, ScanResult result) {
                    String name = result.getDevice().getName();
                    found.put(result.getDevice().getAddress(),
                            name != null ? name : result.getDevice().getAddress());
                }
            };

            Toast.makeText(requireContext(), R.string.searching_displays, Toast.LENGTH_SHORT).show();
            List<ScanFilter> filters = Collections.singletonList(new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(ExternalDisplay.SERVICE_UUID))
                    .build());
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            adapter.getBluetoothLeScanner().startScan(filters, settings, callback);

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                adapter.getBluetoothLeScanner().stopScan(callback);
                if (!isAdded()) {
                    return;
                }
                if (found.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.no_displays_found, Toast.LENGTH_LONG).show();
                    return;
                }
                String[] addresses = found.keySet().toArray(new String[0]);
                String[] names = found.values().toArray(new String[0]);
                new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.setting_add_display)
                        .setItems(names, (dialog, which) -> addDisplay(addresses[which], names[which]))
                        .show();
            }, SCAN_DURATION_MS);
        }

        private void addDisplay(String address, String name) {
            Set<String> displays = new HashSet<>(getPreferenceManager().getSharedPreferences()
                    .getStringSet(EXTERNAL_DISPLAYS, Collections.emptySet()));
            displays.add(address + "|" + name);
            getPreferenceManager().getSharedPreferences().edit()
                    .putStringSet(EXTERNAL_DISPLAYS, displays)
                    .apply();
            updateDisplaysSummary();
        }

        private void manageDisplaysClicked() {
            Set<String> displays = getPreferenceManager().getSharedPreferences()
                    .getStringSet(EXTERNAL_DISPLAYS, Collections.emptySet());
            if (displays.isEmpty()) {
                Toast.makeText(requireContext(), R.string.no_displays_paired, Toast.LENGTH_LONG).show();
                return;
            }
            String[] entries = displays.toArray(new String[0]);
            String[] names = new String[entries.length];
            for (int i = 0; i < entries.length; i++) {
                names[i] = entries[i].substring(entries[i].indexOf('|') + 1);
            }
            new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.remove_display_title)
                    .setItems(names, (dialog, which) -> {
                        Set<String> updated = new HashSet<>(getPreferenceManager().getSharedPreferences()
                                .getStringSet(EXTERNAL_DISPLAYS, Collections.emptySet()));
                        updated.remove(entries[which]);
                        getPreferenceManager().getSharedPreferences().edit()
                                .putStringSet(EXTERNAL_DISPLAYS, updated)
                                .apply();
                        updateDisplaysSummary();
                    })
                    .show();
        }

        private void updateDisplaysSummary() {
            Preference displaysPreference = findPreference(DISPLAYS);
            if (displaysPreference == null) {
                return;
            }
            Set<String> displays = getPreferenceManager().getSharedPreferences()
                    .getStringSet(EXTERNAL_DISPLAYS, Collections.emptySet());
            if (displays.isEmpty()) {
                displaysPreference.setSummary(R.string.no_displays_paired);
                return;
            }
            List<String> names = new ArrayList<>();
            for (String display : displays) {
                names.add(display.substring(display.indexOf('|') + 1));
            }
            displaysPreference.setSummary(TextUtils.join(", ", names));
        }

        private void addFileCategory(Context context, PreferenceScreen screen) {
            PreferenceCategory category = new PreferenceCategory(context);
            category.setKey("file");
            category.setTitle(R.string.setting_file);
            screen.addPreference(category);

            Preference chooseFile = new Preference(context);
            chooseFile.setKey(CHOOSE_FILE);
            chooseFile.setTitle(R.string.setting_choose_file);
            chooseFile.setOnPreferenceClickListener(preference -> {
                filePicker.launch(new String[]{"text/*"});
                return true;
            });
            category.addPreference(chooseFile);

            ListPreference openFileKey = new ListPreference(context);
            openFileKey.setKey(OPEN_FILE_KEY);
            openFileKey.setTitle(R.string.setting_open_file_key);
            openFileKey.setSummary(R.string.setting_open_file_key_description);
            openFileKey.setEntries(functionKeys);
            openFileKey.setEntryValues(functionKeys);
            openFileKey.setDefaultValue(OPEN_FILE_KEY_DEFAULT);
            category.addPreference(openFileKey);
        }

        private void updateChooseFileSummary() {
            Preference chooseFile = findPreference(CHOOSE_FILE);
            if (chooseFile != null) {
                chooseFile.setSummary(fileDisplayName());
            }
        }

        private CharSequence fileDisplayName() {
            String uriString = getPreferenceManager().getSharedPreferences().getString(FILE_URI, FILE_URI_DEFAULT);
            if (!uriString.isEmpty()) {
                try (Cursor cursor = requireContext().getContentResolver().query(Uri.parse(uriString),
                        new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        return cursor.getString(0);
                    }
                } catch (Exception ignored) {
                    // Fall through to the "no file" summary
                }
            }
            return getString(R.string.no_file_chosen);
        }

        private void addBatteryStatusCategory(Context context, PreferenceScreen screen) {
            PreferenceCategory category = new PreferenceCategory(context);
            category.setKey("battery_status");
            category.setTitle(R.string.setting_battery_status);
            screen.addPreference(category);

            SwitchPreferenceCompat batteryStatus = new SwitchPreferenceCompat(context);
            batteryStatus.setKey(BATTERY_STATUS);
            batteryStatus.setTitle(R.string.setting_show_battery_status);
            batteryStatus.setSummary(R.string.setting_show_battery_status_description);
            batteryStatus.setDefaultValue(BATTERY_STATUS_DEFAULT);
            category.addPreference(batteryStatus);

            SwitchPreferenceCompat lowBatWarning = new SwitchPreferenceCompat(context);
            lowBatWarning.setKey(LOWBAT_WARNING);
            lowBatWarning.setTitle(R.string.setting_battery_status_lowbat);
            lowBatWarning.setSummary(R.string.setting_battery_status_lowbat_description);
            lowBatWarning.setDefaultValue(LOWBAT_WARNING_DEFAULT);
            category.addPreference(lowBatWarning);

            SeekBarPreference lowBatLevel = new SeekBarPreference(context);
            lowBatLevel.setKey(LOWBAT_LEVEL);
            lowBatLevel.setTitle(R.string.setting_battery_status_lowbat_level);
            lowBatLevel.setDefaultValue(LOWBAT_LEVEL_DEFAULT);
            lowBatLevel.setSeekBarIncrement(5);
            lowBatLevel.setMin(5);
            lowBatLevel.setMax(95);
            lowBatLevel.setShowSeekBarValue(true);
            category.addPreference(lowBatLevel);
        }
    }
}
