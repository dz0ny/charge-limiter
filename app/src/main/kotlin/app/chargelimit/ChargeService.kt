package app.chargelimit

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Monitors the battery, applies [ChargePolicy] and sends the result as a BLE advert.
 * The Shelly script listens for this advert.
 * The service runs in the foreground, so it continues when the screen is off.
 */
class ChargeService : Service() {
    private lateinit var settings: Settings
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertisingSet: AdvertisingSet? = null

    /** The callback of the current start attempt. The service ignores older callbacks. */
    private var callback: AdvertisingSetCallback? = null
    private var starting = false
    private var destroyed = false
    private var receiversRegistered = false

    private var level = -1
    private var charging = false
    private var wantOn = true
    private var seq = 0
    private var error: String? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        wantOn = settings.wantOn
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Status", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires startForeground after startForegroundService, also when the service stops immediately.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: RuntimeException) {
            // Android refuses the start without the Bluetooth permission, or from the background.
            Log.w(TAG, "cannot enter foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!settings.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!receiversRegistered) {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            receiversRegistered = true
        }
        // The user can change the thresholds. Apply the policy again to the known level.
        if (level >= 0) {
            evaluate()
            updateAdvert()
        }
        startAdvertising()
        publish()
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        if (receiversRegistered) {
            unregisterReceiver(batteryReceiver)
            unregisterReceiver(bluetoothReceiver)
        }
        stopAdvertising()
        lastStatus = null
        sendBroadcast(
            Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_BROADCASTING, false),
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (raw < 0 || scale <= 0) return
            val newLevel = raw * 100 / scale
            val newCharging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (newLevel == level && newCharging == charging) return
            level = newLevel
            charging = newCharging
            evaluate()
            updateAdvert()
            publish()
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> startAdvertising()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    // The Bluetooth stack removes the advertising set when Bluetooth goes off.
                    stopAdvertising()
                    advertiser = null
                    error = "Bluetooth is off"
                }
            }
            publish()
        }
    }

    private fun evaluate() {
        val next = ChargePolicy.next(level, wantOn, settings.low, settings.high)
        if (next != wantOn) {
            wantOn = next
            settings.wantOn = next
        }
    }

    private fun advertiseData(): AdvertiseData {
        // Before the first battery value, send 0 % and "charger on". This is the safe direction.
        val known = level >= 0
        val payload = AdvertPayload.build(if (known) level else 0, !known || wantOn, charging, seq)
        return AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(AdvertPayload.COMPANY_ID, payload)
            .build()
    }

    private fun hasAdvertisePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    // The function checks the permission with hasAdvertisePermission before each Bluetooth call.
    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        if (advertisingSet != null || starting) return
        if (!hasAdvertisePermission()) {
            error = "Bluetooth permission is needed to broadcast"
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            error = "This phone has no Bluetooth"
            return
        }
        if (!adapter.isEnabled) {
            error = "Bluetooth is off"
            return
        }
        val leAdvertiser = adapter.bluetoothLeAdvertiser
        if (leAdvertiser == null) {
            error = "This phone cannot broadcast over Bluetooth"
            return
        }
        advertiser = leAdvertiser
        val parameters = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(false)
            .setScannable(false)
            // INTERVAL_LOW is the short interval of approximately 100 ms.
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
            .build()
        try {
            starting = true
            error = null
            // Use a new callback for each attempt. The stack refuses a callback that it knows.
            val setCallback = SetCallback()
            callback = setCallback
            leAdvertiser.startAdvertisingSet(parameters, advertiseData(), null, null, null, setCallback)
        } catch (e: RuntimeException) {
            starting = false
            callback = null
            error = "Could not start broadcasting: ${e.message}"
            Log.w(TAG, "startAdvertisingSet failed", e)
        }
    }

    // An advertising set exists only with the permission. The catch handles a later removal.
    @SuppressLint("MissingPermission")
    private fun updateAdvert() {
        seq++
        try {
            advertisingSet?.setAdvertisingData(advertiseData())
        } catch (e: RuntimeException) {
            Log.w(TAG, "setAdvertisingData failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        val current = callback
        if (current != null) {
            try {
                advertiser?.stopAdvertisingSet(current)
            } catch (e: RuntimeException) {
                Log.w(TAG, "stopAdvertisingSet failed", e)
            }
        }
        callback = null
        advertisingSet = null
        starting = false
    }

    private inner class SetCallback : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            if (destroyed || callback !== this) return
            starting = false
            if (status == ADVERTISE_SUCCESS && set != null) {
                advertisingSet = set
                error = null
                // The battery level can change while the set starts.
                updateAdvert()
            } else {
                advertisingSet = null
                error = "Broadcasting failed (status $status)"
            }
            publish()
        }

        override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
            if (destroyed || callback !== this) return
            advertisingSet = null
            publish()
        }
    }

    /** Updates the notification and tells the screen, if it is open. */
    private fun publish() {
        if (destroyed) return
        val status = Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra(EXTRA_LEVEL, level)
            .putExtra(EXTRA_CHARGING, charging)
            .putExtra(EXTRA_WANT_ON, wantOn)
            .putExtra(EXTRA_BROADCASTING, advertisingSet != null)
        error?.let { status.putExtra(EXTRA_ERROR, it) }
        lastStatus = status
        sendBroadcast(status)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val title = if (level < 0) "Charge Limiter" else "Battery $level %"
        val text = when {
            advertisingSet != null -> "Charger wanted ${if (wantOn) "ON" else "OFF"}"
            else -> error ?: "Starting"
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val ACTION_STATUS = "app.chargelimit.STATUS"
        const val EXTRA_LEVEL = "level"
        const val EXTRA_CHARGING = "charging"
        const val EXTRA_WANT_ON = "wantOn"
        const val EXTRA_BROADCASTING = "broadcasting"
        const val EXTRA_ERROR = "error"

        private const val TAG = "ChargeService"
        private const val CHANNEL = "status"
        private const val NOTIFICATION_ID = 1

        /** The most recent status, for a screen that opens later. Null when the service is stopped. */
        @Volatile
        var lastStatus: Intent? = null
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ChargeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChargeService::class.java))
        }
    }
}
