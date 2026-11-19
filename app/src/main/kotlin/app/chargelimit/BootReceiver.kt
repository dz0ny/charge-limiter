package app.chargelimit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the service again after a restart of the phone or an app update. This also works before the first unlock. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val boot = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (boot && Settings(context).enabled) ChargeService.start(context)
    }
}
