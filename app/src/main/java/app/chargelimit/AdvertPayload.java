package app.chargelimit;

/** The 8-byte payload carried in BLE manufacturer data. Must match shelly/charge-limiter.js. */
public final class AdvertPayload {
    /** Bluetooth SIG company ID reserved for testing. */
    public static final int COMPANY_ID = 0xFFFF;
    /** Shared token; the Shelly script ignores adverts that do not carry it. */
    static final byte[] TOKEN = {(byte) 0x6c, (byte) 0xe2, (byte) 0x9d, (byte) 0x77};
    static final int VERSION = 1;

    static final int FLAG_WANT_ON = 0b01;
    static final int FLAG_CHARGING = 0b10;

    private AdvertPayload() {}

    /**
     * @param level battery percent; clamped to 0-100
     * @param wantOn whether the charger relay should be on
     * @param charging whether the phone reports that it is charging
     * @param seq change counter; only the low byte is sent
     */
    public static byte[] build(int level, boolean wantOn, boolean charging, int seq) {
        byte[] p = new byte[8];
        System.arraycopy(TOKEN, 0, p, 0, 4);
        p[4] = VERSION;
        p[5] = (byte) Math.max(0, Math.min(100, level));
        p[6] = (byte) ((wantOn ? FLAG_WANT_ON : 0) | (charging ? FLAG_CHARGING : 0));
        p[7] = (byte) seq;
        return p;
    }
}
