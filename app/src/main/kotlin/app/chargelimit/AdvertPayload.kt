package app.chargelimit

/** Builds the 8-byte payload for the BLE manufacturer data. Keep it the same as shelly/charge-limiter.js. */
object AdvertPayload {
    /** The Bluetooth SIG keeps this company ID for tests. */
    const val COMPANY_ID = 0xFFFF

    /** The Shelly script ignores adverts that do not have this token. */
    @JvmField
    val TOKEN = byteArrayOf(0x6c, 0xe2.toByte(), 0x9d.toByte(), 0x77)

    private const val VERSION = 1
    private const val FLAG_WANT_ON = 0b01
    private const val FLAG_CHARGING = 0b10

    /**
     * Returns token, version, level, flags and sequence counter.
     * The function limits [level] to 0-100 and sends only the low byte of [seq].
     */
    fun build(level: Int, wantOn: Boolean, charging: Boolean, seq: Int): ByteArray {
        val flags = (if (wantOn) FLAG_WANT_ON else 0) or (if (charging) FLAG_CHARGING else 0)
        return TOKEN + byteArrayOf(
            VERSION.toByte(),
            level.coerceIn(0, 100).toByte(),
            flags.toByte(),
            seq.toByte(),
        )
    }
}
