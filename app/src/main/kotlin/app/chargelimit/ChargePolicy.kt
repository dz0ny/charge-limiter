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

    /** The charger stops at this battery temperature. The unit is 0.1 °C, as Android reports it. */
    const val HOT_TENTHS = 400

    /** The charger can start again below this battery temperature. */
    const val COOL_TENTHS = 370

    /** The value for a phone that does not report its battery temperature. */
    const val TEMPERATURE_UNKNOWN = Int.MIN_VALUE

    /**
     * Returns true when the battery is too hot to charge.
     * Two thresholds prevent fast changes. An unknown temperature is not hot.
     */
    fun nextTooHot(temperatureTenths: Int, previousTooHot: Boolean): Boolean = when {
        temperatureTenths == TEMPERATURE_UNKNOWN -> false
        temperatureTenths >= HOT_TENTHS -> true
        temperatureTenths < COOL_TENTHS -> false
        else -> previousTooHot
    }

    /** Heat stops the charger at any battery level. */
    fun chargerOn(levelWantsOn: Boolean, tooHot: Boolean): Boolean = levelWantsOn && !tooHot

    fun validRange(low: Int, high: Int): Boolean = low in 0 until high && high <= 100
}
