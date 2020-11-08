package nl.joozt.kiep.keyboard;

import android.app.Activity;
import android.content.IntentSender;

import com.google.android.play.core.appupdate.AppUpdateInfo;
import com.google.android.play.core.appupdate.AppUpdateManager;
import com.google.android.play.core.appupdate.AppUpdateManagerFactory;
import com.google.android.play.core.install.model.AppUpdateType;
import com.google.android.play.core.install.model.UpdateAvailability;
import com.google.android.play.core.tasks.OnSuccessListener;
import com.google.android.play.core.tasks.Task;

public class UpdateCheck implements OnSuccessListener<AppUpdateInfo> {
    private final Activity activity;
    private final AppUpdateManager updateManager;

    public UpdateCheck(Activity activity) {
        this.activity = activity;
        updateManager = AppUpdateManagerFactory.create(activity);
    }

    public void checkForUpdate() {
        Task<AppUpdateInfo> appUpdateInfoTask = updateManager.getAppUpdateInfo();
        appUpdateInfoTask.addOnSuccessListener(this);
    }

    @Override
    public void onSuccess(AppUpdateInfo appUpdateInfo) {
        if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
            startUpdate(appUpdateInfo);
        }
    }

    private void startUpdate(AppUpdateInfo appUpdateInfo) {
        try {
            updateManager.startUpdateFlowForResult(appUpdateInfo, AppUpdateType.IMMEDIATE, activity, 1);
        } catch (IntentSender.SendIntentException ignored) {
        }
    }
}
