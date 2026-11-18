package app.chargelimit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChargePolicyTest {
    @Test
    fun turnsOnAtLow() {
        assertTrue(ChargePolicy.next(20, previousWantOn = false, low = 20, high = 80))
    }

    @Test
    fun turnsOffAtHigh() {
        assertFalse(ChargePolicy.next(80, previousWantOn = true, low = 20, high = 80))
    }

    @Test
    fun holdsBetween() {
        assertTrue(ChargePolicy.next(50, previousWantOn = true, low = 20, high = 80))
        assertFalse(ChargePolicy.next(50, previousWantOn = false, low = 20, high = 80))
    }

    @Test
    fun jitterAtHighGivesOneChange() {
        var want = true
        var changes = 0
        for (level in intArrayOf(79, 80, 79, 80, 79)) {
            val next = ChargePolicy.next(level, want, low = 20, high = 80)
            if (next != want) changes++
            want = next
        }
        assertEquals(1, changes)
    }

    @Test
    fun rangeValidation() {
        assertTrue(ChargePolicy.validRange(20, 80))
        assertFalse(ChargePolicy.validRange(80, 80))
        assertFalse(ChargePolicy.validRange(90, 80))
        assertFalse(ChargePolicy.validRange(-1, 80))
        assertFalse(ChargePolicy.validRange(20, 101))
    }

    @Test
    fun defaultsResumeChargingJustBelowTheLimit() {
        assertEquals(75, ChargePolicy.DEFAULT_LOW)
        assertEquals(80, ChargePolicy.DEFAULT_HIGH)
    }

    @Test
    fun phonePluggedInAtHalfChargesWithTheDefaults() {
        assertTrue(ChargePolicy.next(45, previousWantOn = false, ChargePolicy.DEFAULT_LOW, ChargePolicy.DEFAULT_HIGH))
    }
}
