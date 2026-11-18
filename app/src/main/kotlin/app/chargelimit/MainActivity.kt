package app.chargelimit

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var battery: TextView
    private lateinit var wanted: TextView
    private lateinit var broadcasting: TextView
    private lateinit var low: EditText
    private lateinit var high: EditText
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        settings = Settings(this)
        battery = findViewById(R.id.battery)
        wanted = findViewById(R.id.wanted)
        broadcasting = findViewById(R.id.broadcasting)
        low = findViewById(R.id.low)
        high = findViewById(R.id.high)
        toggle = findViewById(R.id.toggle)

        showRange()
        val saveOnBlur = View.OnFocusChangeListener { _, focused -> if (!focused) saveRange() }
        low.onFocusChangeListener = saveOnBlur
        high.onFocusChangeListener = saveOnBlur
        toggle.setOnClickListener { if (settings.enabled) stopService() else startService() }
    }

    // Android 13 added the not-exported flag.
    // On older versions, a different app can send a false status to this screen.
    // The false status does not change the service.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ChargeService.ACTION_STATUS)
        filter.addAction(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        showStatus(ChargeService.lastStatus)
        showToggle()
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        saveRange()
        super.onStop()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) showBattery(intent) else showStatus(intent)
        }
    }

    private fun showBattery(intent: Intent) {
        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (raw < 0 || scale <= 0) return
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, ChargePolicy.TEMPERATURE_UNKNOWN)
        val degrees = if (tenths == ChargePolicy.TEMPERATURE_UNKNOWN) "" else "  ${tenths / 10} °C"
        battery.text = "${raw * 100 / scale} %  ${if (charging) "charging" else "not charging"}$degrees"
    }

    private fun showStatus(status: Intent?) {
        if (status == null || !settings.enabled) {
            wanted.text = "–"
            broadcasting.text = "no"
            return
        }
        wanted.text = when {
            status.getBooleanExtra(ChargeService.EXTRA_TOO_HOT, false) -> "OFF – battery is hot"
            status.getBooleanExtra(ChargeService.EXTRA_WANT_ON, true) -> "ON"
            else -> "OFF"
        }
        val error = status.getStringExtra(ChargeService.EXTRA_ERROR)
        val on = status.getBooleanExtra(ChargeService.EXTRA_BROADCASTING, false)
        broadcasting.text = when {
            on -> "yes"
            error == null -> "no"
            else -> "no – $error"
        }
    }

    private fun showToggle() {
        toggle.text = if (settings.enabled) "Stop service" else "Start service"
    }

    private fun showRange() {
        low.setText(settings.low.toString())
        high.setText(settings.high.toString())
    }

    /** Saves the two fields. On bad input, shows the stored values and the reason. */
    private fun saveRange() {
        val newLow = low.text.toString().trim().toIntOrNull() ?: -1
        val newHigh = high.text.toString().trim().toIntOrNull() ?: -1
        if (newLow == settings.low && newHigh == settings.high) return
        if (!settings.setRange(newLow, newHigh)) {
            showRange()
            Toast.makeText(this, "Charge below must be less than Stop at (0–100)", Toast.LENGTH_LONG).show()
            return
        }
        if (!settings.enabled) return
        // A second start makes the service apply the new thresholds.
        try {
            ChargeService.start(this)
        } catch (e: RuntimeException) {
            // Android refuses this when the screen is in the background and the service is stopped.
            // The next start of the service uses the saved thresholds.
        }
    }

    private fun startService() {
        saveRange()
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            missing += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isEmpty()) launch() else requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        if (requestCode != REQUEST_PERMISSIONS) return
        // The app can work without notifications. It cannot broadcast without Bluetooth.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(Manifest.permission.BLUETOOTH_ADVERTISE)) {
            broadcasting.text = "no – Bluetooth permission is needed to broadcast"
            return
        }
        launch()
    }

    // The app is not for the Play Store. Without this exemption, Android can stop the service.
    @SuppressLint("BatteryLife")
    private fun launch() {
        settings.enabled = true
        ChargeService.start(this)
        showToggle()
        val power = getSystemService(PowerManager::class.java)
        if (!power.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun stopService() {
        settings.enabled = false
        ChargeService.stop(this)
        showToggle()
        showStatus(null)
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
    }
}
