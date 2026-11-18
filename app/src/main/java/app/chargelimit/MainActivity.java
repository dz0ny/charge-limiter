package app.chargelimit;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 1;

    private Settings settings;
    private TextView battery;
    private TextView wanted;
    private TextView broadcasting;
    private EditText low;
    private EditText high;
    private Button toggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        settings = new Settings(this);
        battery = findViewById(R.id.battery);
        wanted = findViewById(R.id.wanted);
        broadcasting = findViewById(R.id.broadcasting);
        low = findViewById(R.id.low);
        high = findViewById(R.id.high);
        toggle = findViewById(R.id.toggle);

        showRange();
        low.setOnFocusChangeListener((v, focused) -> { if (!focused) saveRange(); });
        high.setOnFocusChangeListener((v, focused) -> { if (!focused) saveRange(); });
        toggle.setOnClickListener(v -> {
            if (settings.enabled()) stopService(); else startService();
        });
    }

    // The not-exported flag only exists from Android 13. On older versions another app could
    // fake the status shown on this screen, which changes nothing the service does.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ChargeService.ACTION_STATUS);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        showStatus(ChargeService.lastStatus());
        showToggle();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(receiver);
        saveRange();
        super.onStop();
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                showBattery(intent);
            } else {
                showStatus(intent);
            }
        }
    };

    private void showBattery(Intent intent) {
        int raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (raw < 0 || scale <= 0) return;
        boolean charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        battery.setText((raw * 100 / scale) + " %  " + (charging ? "charging" : "not charging"));
    }

    private void showStatus(Intent status) {
        if (status == null || !settings.enabled()) {
            wanted.setText("–");
            broadcasting.setText("no");
            return;
        }
        wanted.setText(status.getBooleanExtra(ChargeService.EXTRA_WANT_ON, true) ? "ON" : "OFF");
        String error = status.getStringExtra(ChargeService.EXTRA_ERROR);
        boolean on = status.getBooleanExtra(ChargeService.EXTRA_BROADCASTING, false);
        broadcasting.setText(on ? "yes" : (error == null ? "no" : "no – " + error));
    }

    private void showToggle() {
        toggle.setText(settings.enabled() ? "Stop service" : "Start service");
    }

    private void showRange() {
        low.setText(String.valueOf(settings.low()));
        high.setText(String.valueOf(settings.high()));
    }

    /** Saves the two fields; on bad input restores the stored values and says why. */
    private void saveRange() {
        int newLow;
        int newHigh;
        try {
            newLow = Integer.parseInt(low.getText().toString().trim());
            newHigh = Integer.parseInt(high.getText().toString().trim());
        } catch (NumberFormatException e) {
            newLow = -1;
            newHigh = -1;
        }
        if (newLow == settings.low() && newHigh == settings.high()) return;
        if (!settings.setRange(newLow, newHigh)) {
            showRange();
            Toast.makeText(this, "Charge below must be less than Stop at (0–100)",
                    Toast.LENGTH_LONG).show();
            return;
        }
        // Let a running service re-run the policy with the new thresholds.
        if (settings.enabled()) ChargeService.start(this);
    }

    private void startService() {
        saveRange();
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                        != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }
        launch();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQUEST_PERMISSIONS) return;
        // Notifications are optional; broadcasting is not possible without Bluetooth.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                        != PackageManager.PERMISSION_GRANTED) {
            broadcasting.setText("no – Bluetooth permission is needed to broadcast");
            return;
        }
        launch();
    }

    private void launch() {
        settings.setEnabled(true);
        ChargeService.start(this);
        showToggle();
        PowerManager power = getSystemService(PowerManager.class);
        if (!power.isIgnoringBatteryOptimizations(getPackageName())) {
            // Without this exemption the system may stop the service in the background.
            startActivity(new Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void stopService() {
        settings.setEnabled(false);
        ChargeService.stop(this);
        showToggle();
        showStatus(null);
    }
}
