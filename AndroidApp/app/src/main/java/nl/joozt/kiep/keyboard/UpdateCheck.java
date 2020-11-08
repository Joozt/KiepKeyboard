package nl.joozt.kiep.keyboard;

import android.app.Activity;
import android.content.IntentSender;
import android.util.Log;

import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.UpdateAvailability;
import com.google.android.play.core.tasks.OnSuccessListener;
import com.google.android.play.core.tasks.Task;

public class UpdateCheck implements OnSuccessListener<AppUpdateInfo> {
    private static final String TAG = UpdateCheck.class.getSimpleName();
    private final Activity activity;
    private final AppUpdateManager updateManager;

    public UpdateCheck(Activity activity) {
        this.activity = activity;
        updateManager = AppUpdateManagerFactory.create(activity);
    }

    public void checkForUpdate() {
        Log.d(TAG, "Check for update");
        Task<AppUpdateInfo> appUpdateInfoTask = updateManager.getAppUpdateInfo();
        appUpdateInfoTask.addOnSuccessListener(this);
        appUpdateInfoTask.addOnFailureListener(c -> Log.e(TAG, "Update task failed"));
    }

    @Override
    public void onSuccess(AppUpdateInfo appUpdateInfo) {
        if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
            startUpdate(appUpdateInfo);
        } else {
            Log.d(TAG, "No update available");
        }
    }

    private void startUpdate(AppUpdateInfo appUpdateInfo) {
        if (!appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
            Log.e(TAG, "Immediate update not allowed");
            return;
        }

        try {
            updateManager.startUpdateFlowForResult(appUpdateInfo, AppUpdateType.IMMEDIATE, activity, 1);
        } catch (IntentSender.SendIntentException ignored) {
            Log.e(TAG, "Start update failed");
        }
    }
}
