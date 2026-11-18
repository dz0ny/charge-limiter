package app.chargelimit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AdvertPayloadTest {
    @Test
    fun layout() {
        val p = AdvertPayload.build(63, wantOn = true, charging = false, seq = 7)
        assertEquals(8, p.size)
        assertArrayEquals(AdvertPayload.TOKEN, p.copyOfRange(0, 4))
        assertEquals(1, p[4].toInt())
        assertEquals(63, p[5].toInt())
        assertEquals(0b01, p[6].toInt())
        assertEquals(7, p[7].toInt())
    }

    @Test
    fun flagsBothSet() {
        assertEquals(0b11, AdvertPayload.build(10, wantOn = true, charging = true, seq = 0)[6].toInt())
    }

    @Test
    fun flagsNoneSet() {
        assertEquals(0, AdvertPayload.build(90, wantOn = false, charging = false, seq = 0)[6].toInt())
    }

    @Test
    fun levelClamped() {
        assertEquals(0, AdvertPayload.build(-5, wantOn = true, charging = false, seq = 0)[5].toInt())
        assertEquals(100, AdvertPayload.build(250, wantOn = true, charging = false, seq = 0)[5].toInt())
    }

    @Test
    fun seqWraps() {
        assertEquals(0, AdvertPayload.build(50, wantOn = true, charging = false, seq = 256)[7].toInt())
    }

    @Test
    fun tokenMatchesTheShellyScript() {
        assertArrayEquals(byteArrayOf(0x6c, 0xe2.toByte(), 0x9d.toByte(), 0x77), AdvertPayload.TOKEN)
    }
}
