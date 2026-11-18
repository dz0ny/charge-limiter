package app.chargelimit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import org.junit.Test;

public class AdvertPayloadTest {
    @Test public void layout() {
        byte[] p = AdvertPayload.build(63, true, false, 7);
        assertEquals(8, p.length);
        assertArrayEquals(AdvertPayload.TOKEN, Arrays.copyOfRange(p, 0, 4));
        assertEquals(1, p[4]);
        assertEquals(63, p[5]);
        assertEquals(0b01, p[6]);
        assertEquals(7, p[7]);
    }

    @Test public void flagsBothSet() {
        assertEquals(0b11, AdvertPayload.build(10, true, true, 0)[6]);
    }

    @Test public void flagsNoneSet() {
        assertEquals(0, AdvertPayload.build(90, false, false, 0)[6]);
    }

    @Test public void levelClamped() {
        assertEquals(0, AdvertPayload.build(-5, true, false, 0)[5]);
        assertEquals(100, AdvertPayload.build(250, true, false, 0)[5]);
    }

    @Test public void seqWraps() {
        assertEquals(0, AdvertPayload.build(50, true, false, 256)[7]);
    }
}
