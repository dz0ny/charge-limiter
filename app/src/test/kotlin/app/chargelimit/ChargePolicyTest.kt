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
    fun hotAtTheLimit() {
        assertFalse(ChargePolicy.nextTooHot(449, previousTooHot = false, hotC = 45))
        assertTrue(ChargePolicy.nextTooHot(450, previousTooHot = false, hotC = 45))
    }

    @Test
    fun staysHotUntilThreeDegreesBelowTheLimit() {
        assertTrue(ChargePolicy.nextTooHot(420, previousTooHot = true, hotC = 45))
        assertFalse(ChargePolicy.nextTooHot(419, previousTooHot = true, hotC = 45))
    }

    @Test
    fun theLimitIsAdjustable() {
        assertTrue(ChargePolicy.nextTooHot(400, previousTooHot = false, hotC = 40))
        assertFalse(ChargePolicy.nextTooHot(400, previousTooHot = false, hotC = 45))
    }

    @Test
    fun temperatureJitterAtTheLimitGivesOneChange() {
        var hot = false
        var changes = 0
        for (tenths in intArrayOf(448, 450, 446, 451, 445)) {
            val next = ChargePolicy.nextTooHot(tenths, hot, hotC = 45)
            if (next != hot) changes++
            hot = next
        }
        assertEquals(1, changes)
    }

    @Test
    fun unknownTemperatureIsNotHot() {
        assertFalse(ChargePolicy.nextTooHot(ChargePolicy.TEMPERATURE_UNKNOWN, previousTooHot = true, hotC = 45))
    }

    @Test
    fun heatLimitDefaultAndRange() {
        assertEquals(45, ChargePolicy.DEFAULT_HOT_C)
        assertTrue(ChargePolicy.validHot(30))
        assertTrue(ChargePolicy.validHot(60))
        assertFalse(ChargePolicy.validHot(29))
        assertFalse(ChargePolicy.validHot(61))
    }

    @Test
    fun heatStopsTheCharger() {
        assertFalse(ChargePolicy.chargerOn(levelWantsOn = true, tooHot = true))
        assertTrue(ChargePolicy.chargerOn(levelWantsOn = true, tooHot = false))
        assertFalse(ChargePolicy.chargerOn(levelWantsOn = false, tooHot = false))
    }

    @Test
    fun phonePluggedInAtHalfChargesWithTheDefaults() {
        assertTrue(ChargePolicy.next(45, previousWantOn = false, ChargePolicy.DEFAULT_LOW, ChargePolicy.DEFAULT_HIGH))
    }
}
