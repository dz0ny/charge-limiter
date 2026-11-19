package app.chargelimit

import android.content.Context
import android.content.SharedPreferences

/**
 * Keeps the settings in device-protected storage.
 * The service can then read them after a restart, before the first unlock.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.createDeviceProtectedStorageContext()
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    val low: Int get() = prefs.getInt("low", ChargePolicy.DEFAULT_LOW)

    val high: Int get() = prefs.getInt("high", ChargePolicy.DEFAULT_HIGH)

    /** Returns false and keeps the stored values when the range is not valid. */
    fun setRange(low: Int, high: Int): Boolean {
        if (!ChargePolicy.validRange(low, high)) return false
        prefs.edit().putInt("low", low).putInt("high", high).apply()
        return true
    }

    /** The battery temperature in °C at which the charger stops. */
    val hotC: Int get() = prefs.getInt("hotC", ChargePolicy.DEFAULT_HOT_C)

    /** Returns false and keeps the stored value when the temperature is not valid. */
    fun setHot(hotC: Int): Boolean {
        if (!ChargePolicy.validHot(hotC)) return false
        prefs.edit().putInt("hotC", hotC).apply()
        return true
    }

    /** True when the user wants the service to run. The boot receiver reads this value. */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    /** The last decision of the policy. The first value is on. */
    var wantOn: Boolean
        get() = prefs.getBoolean("wantOn", true)
        set(value) = prefs.edit().putBoolean("wantOn", value).apply()
}
