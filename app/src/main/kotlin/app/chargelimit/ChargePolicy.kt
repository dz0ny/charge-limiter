package app.chargelimit

/** Decides if the charger must be on. Two thresholds prevent fast changes. */
object ChargePolicy {
    /**
     * The charger starts again below this level.
     * The value is near the limit, so a phone that you connect at 45 % charges.
     */
    const val DEFAULT_LOW = 75

    /** The charger stops at this level. */
    const val DEFAULT_HIGH = 80

    /** Returns on at or below [low], off at or above [high], and no change between them. */
    fun next(level: Int, previousWantOn: Boolean, low: Int, high: Int): Boolean = when {
        level <= low -> true
        level >= high -> false
        else -> previousWantOn
    }

    /** The charger stops at this battery temperature in °C. A phone on a fast charger is frequently above 40 °C. */
    const val DEFAULT_HOT_C = 45

    /** The charger can start again when the battery is this much cooler than the limit. The unit is 0.1 °C. */
    private const val COOL_MARGIN_TENTHS = 30

    /** The value for a phone that does not report its battery temperature. */
    const val TEMPERATURE_UNKNOWN = Int.MIN_VALUE

    /**
     * Returns true when the battery is too hot to charge.
     * Android reports [temperatureTenths] in units of 0.1 °C. The limit [hotC] is in °C.
     * Two thresholds prevent fast changes. An unknown temperature is not hot.
     */
    fun nextTooHot(temperatureTenths: Int, previousTooHot: Boolean, hotC: Int): Boolean = when {
        temperatureTenths == TEMPERATURE_UNKNOWN -> false
        temperatureTenths >= hotC * 10 -> true
        temperatureTenths < hotC * 10 - COOL_MARGIN_TENTHS -> false
        else -> previousTooHot
    }

    fun validHot(hotC: Int): Boolean = hotC in 30..60

    /** Heat stops the charger at any battery level. */
    fun chargerOn(levelWantsOn: Boolean, tooHot: Boolean): Boolean = levelWantsOn && !tooHot

    fun validRange(low: Int, high: Int): Boolean = low in 0 until high && high <= 100
}
