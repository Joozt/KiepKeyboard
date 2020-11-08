package nl.joozt.kiep.keyboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.BatteryManager;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;

import androidx.preference.PreferenceManager;

public class BatteryStatus {
    private static final String TAG = BatteryStatus.class.getSimpleName();

    public BatteryStatus(Context context, final ProgressBar progressBar, final View background) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        final boolean progressbarEnabled = preferences.getBoolean(SettingsActivity.BATTERY_STATUS, SettingsActivity.BATTERY_STATUS_DEFAULT);
        final boolean lowBatWarningEnabled = preferences.getBoolean(SettingsActivity.LOWBAT_WARNING, SettingsActivity.LOWBAT_WARNING_DEFAULT);
        final int lowBatLevel = preferences.getInt(SettingsActivity.LOWBAT_LEVEL, SettingsActivity.LOWBAT_LEVEL_DEFAULT);

        if (!progressbarEnabled) {
            progressBar.setVisibility(View.INVISIBLE);
        }

        BroadcastReceiver batteryInfoReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
                Log.d(TAG, "Battery level = " + level + "%");
                progressBar.setProgress(level);

                if (level <= lowBatLevel) {
                    progressBar.setProgressTintList(ColorStateList.valueOf(Color.parseColor("#FFC107")));

                    if (lowBatWarningEnabled) {
                        background.setBackgroundColor((Color.parseColor("#493700")));
                    }
                } else {
                    progressBar.setProgressTintList(ColorStateList.valueOf(Color.parseColor("#33B5E5")));
                    background.setBackgroundColor((Color.BLACK));
                }
            }
        };

        context.registerReceiver(batteryInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }
}
