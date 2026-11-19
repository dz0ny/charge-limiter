package app.chargelimit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val base = "https://github.com/dz0ny/charge-limiter/releases/download"

    @Test
    fun newerPatchMinorAndMajor() {
        assertTrue(UpdatePolicy.isNewer("v0.1.1", "0.1.0"))
        assertTrue(UpdatePolicy.isNewer("v0.2.0", "0.1.9"))
        assertTrue(UpdatePolicy.isNewer("v1.0.0", "0.9.9"))
    }

    @Test
    fun comparesNumbersNotText() {
        assertTrue(UpdatePolicy.isNewer("v0.10.0", "0.9.0"))
        assertFalse(UpdatePolicy.isNewer("v0.9.0", "0.10.0"))
    }

    @Test
    fun sameOrOlderIsNotNewer() {
        assertFalse(UpdatePolicy.isNewer("v0.1.0", "0.1.0"))
        assertFalse(UpdatePolicy.isNewer("v0.1.0", "0.2.0"))
    }

    @Test
    fun tagWithoutThePrefixWorks() {
        assertTrue(UpdatePolicy.isNewer("0.2.0", "0.1.0"))
    }

    @Test
    fun shorterVersionCountsMissingPartsAsZero() {
        assertTrue(UpdatePolicy.isNewer("v0.2", "0.1.5"))
        assertFalse(UpdatePolicy.isNewer("v0.2", "0.2.0"))
    }

    @Test
    fun badVersionsAreNeverNewer() {
        assertFalse(UpdatePolicy.isNewer("latest", "0.1.0"))
        assertFalse(UpdatePolicy.isNewer("v0.2.0", "0.1-dev"))
        assertFalse(UpdatePolicy.isNewer("", "0.1.0"))
        assertFalse(UpdatePolicy.isNewer("v1.-2.0", "0.1.0"))
    }

    @Test
    fun onlyReleaseBuildsCanUpdateThemselves() {
        assertTrue(UpdatePolicy.canSelfUpdate("0.1.0"))
        assertFalse(UpdatePolicy.canSelfUpdate("0.1-dev"))
        assertFalse(UpdatePolicy.canSelfUpdate("0.0.7-dev"))
    }

    @Test
    fun checksOnceADay() {
        val day = 24L * 60 * 60 * 1000
        assertTrue(UpdatePolicy.dueForCheck(nowMs = day, lastMs = 0))
        assertFalse(UpdatePolicy.dueForCheck(nowMs = day - 1, lastMs = 0))
        assertTrue(UpdatePolicy.dueForCheck(nowMs = 5, lastMs = 10))
    }

    @Test
    fun picksTheApkFromThisRepository() {
        val assets = listOf(
            "charge-limiter.js" to "$base/v0.2.0/charge-limiter.js",
            "charge-limiter-0.2.0.apk" to "$base/v0.2.0/charge-limiter-0.2.0.apk",
        )
        assertEquals("$base/v0.2.0/charge-limiter-0.2.0.apk", UpdatePolicy.apkUrl(assets))
    }

    @Test
    fun refusesAnApkFromAnotherPlace() {
        assertNull(UpdatePolicy.apkUrl(listOf("x.apk" to "https://example.com/x.apk")))
        assertNull(UpdatePolicy.apkUrl(listOf("x.apk" to "http://github.com/dz0ny/charge-limiter/releases/download/v1/x.apk")))
        assertNull(UpdatePolicy.apkUrl(listOf("x.apk" to "https://github.com/someone/else/releases/download/v1/x.apk")))
        assertNull(UpdatePolicy.apkUrl(emptyList()))
    }
}
