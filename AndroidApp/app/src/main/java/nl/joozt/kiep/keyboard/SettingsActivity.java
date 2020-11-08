package nl.joozt.kiep.keyboard;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

public class SettingsActivity extends AppCompatActivity {
    private static final String[] functionKeys = {"[", "]", "\\", ";", "'", "/", "-", "=", "`", "{", "}", "|", ":", "\"", "<", ">", "~"};
    public static final String ENABLE_YES_NO = "enable_yes_no";
    public static final boolean ENABLE_YES_NO_DEFAULT = true;
    public static final String ENABLE_HIGHLIGHT = "enable_highlight";
    public static final boolean ENABLE_HIGHLIGHT_DEFAULT = true;
    public static final String NO_KEY = "no_key";
    public static final String NO_KEY_DEFAULT = "[";
    public static final String YES_KEY = "yes_key";
    public static final String YES_KEY_DEFAULT = "]";

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
            addYesNoCategory(context, screen);
            setPreferenceScreen(screen);

            screen.findPreference(NO_KEY).setDependency(ENABLE_YES_NO);
            screen.findPreference(YES_KEY).setDependency(ENABLE_YES_NO);
            screen.findPreference(ENABLE_HIGHLIGHT).setDependency(ENABLE_YES_NO);
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
    }
}
