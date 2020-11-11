package nl.joozt.kiep.keyboard;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

public class SettingsActivity extends AppCompatActivity {
    private static final String[] functionKeys = {"[", "]", "\\", ";", "'", "/", "-", "=", "`", "{", "}", "|", ":", "\"", "<", ">", "~"};
    public static final String OPEN_PLAY_STORE_LISTING = "open_play_store_listing";
    public static final String ENABLE_YES_NO = "enable_yes_no";
    public static final boolean ENABLE_YES_NO_DEFAULT = true;
    public static final String ENABLE_HIGHLIGHT = "enable_highlight";
    public static final boolean ENABLE_HIGHLIGHT_DEFAULT = true;
    public static final String NO_KEY = "no_key";
    public static final String NO_KEY_DEFAULT = "[";
    public static final String YES_KEY = "yes_key";
    public static final String YES_KEY_DEFAULT = "]";
    public static final String BATTERY_STATUS = "battery_status";
    public static final boolean BATTERY_STATUS_DEFAULT = true;
    public static final String LOWBAT_WARNING = "lowbat_warning";
    public static final boolean LOWBAT_WARNING_DEFAULT = true;
    public static final String LOWBAT_LEVEL = "lowbat_level";
    public static final int LOWBAT_LEVEL_DEFAULT = 10;
    public static final String DROPBOX_OPEN_OAUTH = "dropbox_open_oauth";
    public static final String DROPBOX_OAUTH_KEY = "dropbox_oauth";
    public static final String DROPBOX_OAUTH_KEY_DEFAULT = "";

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
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context context = getPreferenceManager().getContext();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
            addPlayStoreListing(context, screen);
            addYesNoCategory(context, screen);
            addBatteryStatusCategory(context, screen);
            addDropboxSyncCategory(context,screen);
            setPreferenceScreen(screen);

            screen.findPreference(NO_KEY).setDependency(ENABLE_YES_NO);
            screen.findPreference(YES_KEY).setDependency(ENABLE_YES_NO);
            screen.findPreference(ENABLE_HIGHLIGHT).setDependency(ENABLE_YES_NO);
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
            enableHighlight.setTitle(R.string.setting_enable_hightlight);
            enableHighlight.setSummary(R.string.setting_enable_hightlight_description);
            enableHighlight.setDefaultValue(ENABLE_HIGHLIGHT_DEFAULT);
            category.addPreference(enableHighlight);
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
            lowBatLevel.setTitle(R.string.settings_battery_status_lowbat_level);
            lowBatLevel.setDefaultValue(LOWBAT_LEVEL_DEFAULT);
            lowBatLevel.setSeekBarIncrement(5);
            lowBatLevel.setMin(5);
            lowBatLevel.setMax(95);
            lowBatLevel.setShowSeekBarValue(true);
            category.addPreference(lowBatLevel);
        }
        private void addDropboxSyncCategory(Context context, PreferenceScreen screen) {
            PreferenceCategory category = new PreferenceCategory(context);
            category.setKey("dropbox_sync");
            category.setTitle("Dropbox synchronization");
            screen.addPreference(category);

            Preference openDropboxOauth = new Preference(context);
            openDropboxOauth.setKey(DROPBOX_OPEN_OAUTH);
            openDropboxOauth.setTitle("Perform Dropbox Authentication");
            openDropboxOauth.setSummary("Generate oauth2 authentication key with Dropbox");
            openDropboxOauth.setOnPreferenceClickListener(preference ->
            {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.dropbox.com/oauth2/authorize?client_id=x3tgfp5v2r3zix2&response_type=code")));
                return true;
            });
            screen.addPreference(openDropboxOauth);

            EditTextPreference editTextPreference =  new EditTextPreference(context);
            editTextPreference.setKey(DROPBOX_OAUTH_KEY);
            editTextPreference.setTitle("Store generated OAUTH key Dropbox");
            editTextPreference.setSummary("Save the OAUTH key generated after pushing the button above");
            editTextPreference.setDefaultValue(DROPBOX_OAUTH_KEY_DEFAULT);
            category.addPreference((editTextPreference));

        }
    }
}
