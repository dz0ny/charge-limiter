package app.chargelimit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the service after a reboot, including before the first unlock. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action);
        if (boot && new Settings(context).enabled()) {
            ChargeService.start(context);
        }
    }
}
