package app.chargelimit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Gets the result of an update from the system installer. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The system wants the user to confirm. Show its question.
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            // After a success, Android replaces the app. BootReceiver then starts the service.
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> context.sendBroadcast(
                Intent(ACTION_FAILED)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_MESSAGE, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)),
            )
        }
    }

    companion object {
        const val ACTION_FAILED = "app.chargelimit.UPDATE_FAILED"
        const val EXTRA_MESSAGE = "message"
    }
}
