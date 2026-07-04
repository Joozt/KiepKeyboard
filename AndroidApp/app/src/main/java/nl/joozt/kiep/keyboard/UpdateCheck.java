package nl.joozt.kiep.keyboard;

import android.app.Activity;
import android.util.Log;

import androidx.activity.result.ActivityResultCaller;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.appupdate.AppUpdateOptions;
import com.google.android.play.core.install.InstallState;
import com.google.android.play.core.install.InstallStateUpdatedListener;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.InstallStatus;
import com.google.android.play.core.install.model.UpdateAvailability;

public class UpdateCheck implements OnSuccessListener<AppUpdateInfo>, InstallStateUpdatedListener {
    private static final String TAG = UpdateCheck.class.getSimpleName();
    private final Activity activity;
    private final AppUpdateManager updateManager;
    private ActivityResultLauncher<IntentSenderRequest> updateLauncher;

    public UpdateCheck(Activity activity) {
        this.activity = activity;
        if (activity instanceof ActivityResultCaller) {
            updateLauncher = ((ActivityResultCaller) activity).registerForActivityResult(
                    new ActivityResultContracts.StartIntentSenderForResult(),
                    result -> {
                        if (result.getResultCode() != Activity.RESULT_OK) {
                            Log.e(TAG, "Update flow failed! Result code: " + result.getResultCode());
                        }
                    });
        }
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
        if (updateLauncher != null) {
            updateManager.startUpdateFlowForResult(appUpdateInfo, updateLauncher, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build());
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

    public void destroy() {
        // No-op when not registered; prevents a downloading update from
        // keeping a destroyed activity reachable through the listener.
        updateManager.unregisterListener(this);
    }

    private void popupSnackbarForCompleteUpdate() {
        Snackbar snackbar = Snackbar.make(activity.findViewById(R.id.content), R.string.update_ready, Snackbar.LENGTH_INDEFINITE);
        snackbar.setAnchorView(R.id.progressBar);
        snackbar.setAction(R.string.update_restart, view -> updateManager.completeUpdate());
        snackbar.show();
    }
}
