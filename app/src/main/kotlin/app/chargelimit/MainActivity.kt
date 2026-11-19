package app.chargelimit

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var battery: TextView
    private lateinit var batteryDetail: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var wantedDot: View
    private lateinit var broadcastDot: View
    private lateinit var wanted: TextView
    private lateinit var broadcasting: TextView
    private lateinit var low: EditText
    private lateinit var high: EditText
    private lateinit var hot: EditText
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        settings = Settings(this)
        battery = findViewById(R.id.battery)
        batteryDetail = findViewById(R.id.batteryDetail)
        levelBar = findViewById(R.id.levelBar)
        wantedDot = findViewById(R.id.wantedDot)
        broadcastDot = findViewById(R.id.broadcastDot)
        wanted = findViewById(R.id.wanted)
        broadcasting = findViewById(R.id.broadcasting)
        low = findViewById(R.id.low)
        high = findViewById(R.id.high)
        hot = findViewById(R.id.hot)
        toggle = findViewById(R.id.toggle)

        showRange()
        val saveOnBlur = View.OnFocusChangeListener { _, focused -> if (!focused) saveRange() }
        low.onFocusChangeListener = saveOnBlur
        high.onFocusChangeListener = saveOnBlur
        hot.onFocusChangeListener = saveOnBlur
        toggle.setOnClickListener { if (settings.enabled) confirmStop() else startService() }
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
        // An app update stops the service. Start it again when the user wants it to run.
        if (settings.enabled && ChargeService.lastStatus == null) ChargeService.start(this)
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
        val level = raw * 100 / scale
        battery.text = getString(R.string.level_percent, level)
        levelBar.progress = level
        val state = getString(if (charging) R.string.charging else R.string.not_charging)
        batteryDetail.text = if (tenths == ChargePolicy.TEMPERATURE_UNKNOWN) {
            state
        } else {
            getString(R.string.battery_detail_temp, state, tenths / 10)
        }
    }

    private fun showStatus(status: Intent?) {
        if (status == null || !settings.enabled) {
            wanted.setText(R.string.service_stopped)
            broadcasting.setText(R.string.broadcast_off)
            tint(wantedDot, R.color.status_off)
            tint(broadcastDot, R.color.status_off)
            return
        }
        val tooHot = status.getBooleanExtra(ChargeService.EXTRA_TOO_HOT, false)
        val wantOn = status.getBooleanExtra(ChargeService.EXTRA_WANT_ON, true)
        wanted.text = when {
            tooHot -> getString(R.string.charger_paused_hot)
            wantOn -> getString(R.string.charger_on, settings.high)
            else -> getString(R.string.charger_off)
        }
        tint(
            wantedDot,
            when {
                tooHot -> R.color.status_warn
                wantOn -> R.color.status_on
                else -> R.color.status_off
            },
        )
        val error = status.getStringExtra(ChargeService.EXTRA_ERROR)
        val on = status.getBooleanExtra(ChargeService.EXTRA_BROADCASTING, false)
        broadcasting.text = when {
            on -> getString(R.string.broadcast_active)
            error == null -> getString(R.string.broadcast_off)
            else -> getString(R.string.broadcast_off_reason, error)
        }
        tint(broadcastDot, if (on) R.color.status_on else R.color.status_warn)
    }

    private fun tint(dot: View, color: Int) {
        dot.backgroundTintList = ColorStateList.valueOf(getColor(color))
    }

    private fun showToggle() {
        val running = settings.enabled
        toggle.setText(if (running) R.string.stop else R.string.start)
        toggle.setBackgroundResource(if (running) R.drawable.button_tonal else R.drawable.button_primary)
        toggle.setTextColor(getColor(if (running) R.color.on_surface else R.color.on_accent))
    }

    private fun showRange() {
        low.setText(settings.low.toString())
        high.setText(settings.high.toString())
        hot.setText(settings.hotC.toString())
    }

    /** Saves the three fields. On bad input, shows the stored values and the reason. */
    private fun saveRange() {
        val newLow = low.text.toString().trim().toIntOrNull() ?: -1
        val newHigh = high.text.toString().trim().toIntOrNull() ?: -1
        val newHot = hot.text.toString().trim().toIntOrNull() ?: -1
        val rangeChanged = newLow != settings.low || newHigh != settings.high
        val hotChanged = newHot != settings.hotC
        if (!rangeChanged && !hotChanged) return
        if (rangeChanged && !settings.setRange(newLow, newHigh)) {
            showRange()
            Toast.makeText(this, R.string.range_invalid, Toast.LENGTH_LONG).show()
            return
        }
        if (hotChanged && !settings.setHot(newHot)) {
            showRange()
            Toast.makeText(this, R.string.hot_invalid, Toast.LENGTH_LONG).show()
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
            broadcasting.text =
                getString(R.string.broadcast_off_reason, getString(R.string.error_permission))
            tint(broadcastDot, R.color.status_warn)
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

    /** Asks before the stop. An accidental tap must not remove the charge limit. */
    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setTitle(R.string.stop_title)
            .setMessage(R.string.stop_message)
            .setNegativeButton(R.string.keep_running, null)
            .setPositiveButton(R.string.stop) { _, _ -> stopService() }
            .show()
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
