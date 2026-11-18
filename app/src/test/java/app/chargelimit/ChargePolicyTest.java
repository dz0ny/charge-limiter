package app.chargelimit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChargePolicyTest {
    @Test public void turnsOnAtLow() {
        assertTrue(ChargePolicy.next(20, false, 20, 80));
    }

    @Test public void turnsOffAtHigh() {
        assertFalse(ChargePolicy.next(80, true, 20, 80));
    }

    @Test public void holdsBetween() {
        assertTrue(ChargePolicy.next(50, true, 20, 80));
        assertFalse(ChargePolicy.next(50, false, 20, 80));
    }

    @Test public void jitterAtHighGivesOneChange() {
        boolean want = true;
        int changes = 0;
        for (int level : new int[] {79, 80, 79, 80, 79}) {
            boolean next = ChargePolicy.next(level, want, 20, 80);
            if (next != want) changes++;
            want = next;
        }
        assertEquals(1, changes);
    }

    @Test public void rangeValidation() {
        assertTrue(ChargePolicy.validRange(20, 80));
        assertFalse(ChargePolicy.validRange(80, 80));
        assertFalse(ChargePolicy.validRange(90, 80));
        assertFalse(ChargePolicy.validRange(-1, 80));
        assertFalse(ChargePolicy.validRange(20, 101));
    }

    @Test public void defaults() {
        assertEquals(20, ChargePolicy.DEFAULT_LOW);
        assertEquals(80, ChargePolicy.DEFAULT_HIGH);
    }
}
