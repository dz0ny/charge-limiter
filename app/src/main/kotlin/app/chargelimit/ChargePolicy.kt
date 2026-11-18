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

    fun validRange(low: Int, high: Int): Boolean = low in 0 until high && high <= 100
}
