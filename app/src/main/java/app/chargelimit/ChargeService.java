package app.chargelimit;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Watches the battery, applies {@link ChargePolicy} and broadcasts the result as a BLE advert
 * that the Shelly script listens for. Runs as a foreground service so it survives screen-off.
 */
public final class ChargeService extends Service {
    public static final String ACTION_STATUS = "app.chargelimit.STATUS";
    public static final String EXTRA_LEVEL = "level";
    public static final String EXTRA_CHARGING = "charging";
    public static final String EXTRA_WANT_ON = "wantOn";
    public static final String EXTRA_BROADCASTING = "broadcasting";
    public static final String EXTRA_ERROR = "error";

    private static final String TAG = "ChargeService";
    private static final String CHANNEL = "status";
    private static final int NOTIFICATION_ID = 1;

    /** Most recent status, so a newly opened screen has something to show. Null when stopped. */
    private static volatile Intent lastStatus;

    private Settings settings;
    private BluetoothLeAdvertiser advertiser;
    private AdvertisingSet advertisingSet;
    /** Callback of the current start attempt; older callbacks are ignored. */
    private AdvertisingSetCallback callback;
    private boolean destroyed;
    private boolean starting;
    private boolean receiversRegistered;

    private int level = -1;
    private boolean charging;
    private boolean wantOn;
    private int seq;
    private String error;

    public static void start(Context context) {
        context.startForegroundService(new Intent(context, ChargeService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, ChargeService.class));
    }

    public static Intent lastStatus() {
        return lastStatus;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        settings = new Settings(this);
        wantOn = settings.wantOn();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Status", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // startForeground must always be called after startForegroundService, even if we
        // are about to stop again.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
        } catch (RuntimeException e) {
            // Thrown when the Bluetooth permission is missing or the system refuses a
            // foreground start from the background.
            Log.w(TAG, "cannot enter foreground", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!settings.enabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!receiversRegistered) {
            registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            registerReceiver(bluetoothReceiver,
                    new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
            receiversRegistered = true;
        }
        // Thresholds may have changed; re-run the policy with the level we already have.
        if (level >= 0) {
            evaluate();
            updateAdvert();
        }
        startAdvertising();
        publish();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        if (receiversRegistered) {
            unregisterReceiver(batteryReceiver);
            unregisterReceiver(bluetoothReceiver);
        }
        stopAdvertising();
        lastStatus = null;
        sendBroadcast(new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_BROADCASTING, false));
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (raw < 0 || scale <= 0) return;
            int newLevel = raw * 100 / scale;
            boolean newCharging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            if (newLevel == level && newCharging == charging) return;
            level = newLevel;
            charging = newCharging;
            evaluate();
            updateAdvert();
            publish();
        }
    };

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                startAdvertising();
            } else if (state == BluetoothAdapter.STATE_TURNING_OFF
                    || state == BluetoothAdapter.STATE_OFF) {
                // The stack drops the advertising set when Bluetooth goes off.
                stopAdvertising();
                advertiser = null;
                error = "Bluetooth is off";
            }
            publish();
        }
    };

    private void evaluate() {
        boolean next = ChargePolicy.next(level, wantOn, settings.low(), settings.high());
        if (next != wantOn) {
            wantOn = next;
            settings.setWantOn(next);
        }
    }

    private AdvertiseData advertiseData() {
        // Before the first battery reading, report 0 % and "charger on": the safe direction.
        boolean known = level >= 0;
        return new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(AdvertPayload.COMPANY_ID,
                        AdvertPayload.build(known ? level : 0, !known || wantOn, charging, seq))
                .build();
    }

    private boolean hasAdvertisePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void startAdvertising() {
        if (advertisingSet != null || starting) return;
        if (!hasAdvertisePermission()) {
            error = "Bluetooth permission is needed to broadcast";
            return;
        }
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            error = "This phone has no Bluetooth";
            return;
        }
        if (!adapter.isEnabled()) {
            error = "Bluetooth is off";
            return;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            error = "This phone cannot broadcast over Bluetooth";
            return;
        }
        AdvertisingSetParameters parameters = new AdvertisingSetParameters.Builder()
                .setLegacyMode(true)
                .setConnectable(false)
                .setScannable(false)
                // INTERVAL_LOW is the short interval (about 100 ms), not a low rate.
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
                .build();
        try {
            starting = true;
            error = null;
            // A fresh callback per attempt: the stack refuses one it still has registered.
            callback = new SetCallback();
            advertiser.startAdvertisingSet(parameters, advertiseData(), null, null, null,
                    callback);
        } catch (RuntimeException e) {
            starting = false;
            error = "Could not start broadcasting: " + e.getMessage();
            Log.w(TAG, "startAdvertisingSet failed", e);
        }
    }

    // Only reached with a live advertising set, which needs the permission to exist; a
    // revocation mid-run surfaces as the SecurityException caught below.
    @SuppressLint("MissingPermission")
    private void updateAdvert() {
        seq++;
        if (advertisingSet == null) return;
        try {
            advertisingSet.setAdvertisingData(advertiseData());
        } catch (RuntimeException e) {
            Log.w(TAG, "setAdvertisingData failed", e);
        }
    }

    @SuppressLint("MissingPermission")
    private void stopAdvertising() {
        if (advertiser != null && callback != null) {
            try {
                advertiser.stopAdvertisingSet(callback);
            } catch (RuntimeException e) {
                Log.w(TAG, "stopAdvertisingSet failed", e);
            }
        }
        callback = null;
        advertisingSet = null;
        starting = false;
    }

    private final class SetCallback extends AdvertisingSetCallback {
        @Override
        public void onAdvertisingSetStarted(AdvertisingSet set, int txPower, int status) {
            if (destroyed || callback != this) return;
            starting = false;
            if (status == ADVERTISE_SUCCESS) {
                advertisingSet = set;
                error = null;
                // The battery may have changed while the set was starting.
                updateAdvert();
            } else {
                advertisingSet = null;
                error = "Broadcasting failed (status " + status + ")";
            }
            publish();
        }

        @Override
        public void onAdvertisingSetStopped(AdvertisingSet set) {
            if (destroyed || callback != this) return;
            advertisingSet = null;
            publish();
        }
    }

    /** Refreshes the notification and tells the screen, if it is open. */
    private void publish() {
        if (destroyed) return;
        Intent status = new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_LEVEL, level)
                .putExtra(EXTRA_CHARGING, charging)
                .putExtra(EXTRA_WANT_ON, wantOn)
                .putExtra(EXTRA_BROADCASTING, advertisingSet != null);
        if (error != null) status.putExtra(EXTRA_ERROR, error);
        lastStatus = status;
        sendBroadcast(status);
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        String title = level < 0 ? "Charge Limiter" : "Battery " + level + " %";
        String text = advertisingSet != null
                ? "Charger wanted " + (wantOn ? "ON" : "OFF")
                : (error != null ? error : "Starting");
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }
}
