package nl.joozt.kiep.keyboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.BatteryManager;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;

public class BatteryStatus {
    private static final String TAG = BatteryStatus.class.getSimpleName();

    public BatteryStatus(Context context, final ProgressBar progressBar, final View background) {
        BroadcastReceiver batteryInfoReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
                Log.d(TAG, "Battery level = " + level + "%");
                progressBar.setProgress(level);

                if (level <= 10) {
                    progressBar.setProgressTintList(ColorStateList.valueOf(Color.parseColor("#FFC107")));
                    background.setBackgroundColor((Color.parseColor("#493700")));
                } else {
                    progressBar.setProgressTintList(ColorStateList.valueOf(Color.parseColor("#33B5E5")));
                    background.setBackgroundColor((Color.BLACK));
                }
            }
        };

        context.registerReceiver(batteryInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }
}
