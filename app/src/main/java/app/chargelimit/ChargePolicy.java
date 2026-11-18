package app.chargelimit;

/** Decides whether the charger should be on, with hysteresis between two thresholds. */
public final class ChargePolicy {
    public static final int DEFAULT_LOW = 20;
    public static final int DEFAULT_HIGH = 80;

    private ChargePolicy() {}

    /** On at or below {@code low}, off at or above {@code high}, otherwise unchanged. */
    public static boolean next(int level, boolean previousWantOn, int low, int high) {
        if (level <= low) return true;
        if (level >= high) return false;
        return previousWantOn;
    }

    public static boolean validRange(int low, int high) {
        return 0 <= low && low < high && high <= 100;
    }
}
