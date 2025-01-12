package nl.joozt.kiep.keyboard;

import android.app.Activity;
import android.content.IntentSender;
import android.util.Log;

import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.InstallState;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;

public class UpdateCheck implements OnSuccessListener<AppUpdateInfo>, InstallStateUpdatedListener {
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
        } else if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
            Log.d(TAG, "Update downloaded, completing now");
            popupSnackbarForCompleteUpdate();
        } else {
            Log.d(TAG, "No update available");
        }
    }

    private void startUpdate(AppUpdateInfo appUpdateInfo) {
        Log.d(TAG, "Starting flexible update");
        updateManager.registerListener(this);
        try {
            updateManager.startUpdateFlowForResult(appUpdateInfo, AppUpdateType.FLEXIBLE, activity, 1);
        } catch (IntentSender.SendIntentException ignored) {
            Log.e(TAG, "Start update failed");
        }
    }

    @Override
    public void onStateUpdate(InstallState state) {
        if (state.installStatus() == InstallStatus.DOWNLOADED) {
            Log.d(TAG, "Update downloaded, completing now");
            updateManager.unregisterListener(this);
            popupSnackbarForCompleteUpdate();
        }
    }

    private void popupSnackbarForCompleteUpdate() {
        Snackbar snackbar = Snackbar.make(activity.findViewById(R.id.content), R.string.update_ready, Snackbar.LENGTH_INDEFINITE);
        snackbar.setAnchorView(R.id.progressBar);
        snackbar.setAction(R.string.update_restart, view -> updateManager.completeUpdate());
        snackbar.show();
    }
}
