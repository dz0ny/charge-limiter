package app.chargelimit;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persistent settings. Kept in device-protected storage so the service can read them after a
 * reboot, before the phone is unlocked for the first time.
 */
public final class Settings {
    private final SharedPreferences prefs;

    public Settings(Context context) {
        prefs = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public int low() {
        return prefs.getInt("low", ChargePolicy.DEFAULT_LOW);
    }

    public int high() {
        return prefs.getInt("high", ChargePolicy.DEFAULT_HIGH);
    }

    /** @return false, leaving the stored values unchanged, when the range is not valid */
    public boolean setRange(int low, int high) {
        if (!ChargePolicy.validRange(low, high)) return false;
        prefs.edit().putInt("low", low).putInt("high", high).apply();
        return true;
    }

    /** Whether the user wants the service running; decides whether it starts on boot. */
    public boolean enabled() {
        return prefs.getBoolean("enabled", false);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean("enabled", enabled).apply();
    }

    /** Last decision of the policy; the charger starts out wanted on. */
    public boolean wantOn() {
        return prefs.getBoolean("wantOn", true);
    }

    public void setWantOn(boolean wantOn) {
        prefs.edit().putBoolean("wantOn", wantOn).apply();
    }
}
