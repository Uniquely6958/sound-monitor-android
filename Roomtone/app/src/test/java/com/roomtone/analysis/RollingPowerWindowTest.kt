package com.roomtone.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RollingPowerWindowTest {
    @Test
    fun alertWaitsUntilTheEntireWindowIsAvailable() {
        val window = RollingPowerWindow(sampleRateHz = 10, windowSeconds = 2)

        window.add(power = 1.0, samples = 19)
        assertFalse(window.isReady)
        assertFalse(window.isAtOrAbove(-10.0))
        window.add(power = 1.0, samples = 1)
        assertTrue(window.isReady)
        assertTrue(window.isAtOrAbove(-10.0))
    }

    @Test
    fun shortLoudSpikeIsDilutedByTheFullWindow() {
        val window = RollingPowerWindow(sampleRateHz = 100, windowSeconds = 1)
        window.add(power = 0.0, samples = 99)
        window.add(power = 1.0, samples = 1)

        assertFalse(window.isAtOrAbove(-15.0))
        assertEquals(-20.0, powerToDbfs(window.averagePower), 0.001)
    }

    @Test
    fun alertStopsWhenTheRollingAverageFallsBelowThreshold() {
        val window = RollingPowerWindow(sampleRateHz = 10, windowSeconds = 1)
        window.add(power = 1.0, samples = 10)
        assertTrue(window.isAtOrAbove(-10.0))

        window.add(power = 0.0001, samples = 10)
        assertFalse(window.isAtOrAbove(-10.0))
    }

    @Test
    fun resetClearsReadinessAndPreviousAlertPower() {
        val window = RollingPowerWindow(sampleRateHz = 10, windowSeconds = 1)
        window.add(power = 1.0, samples = 10)
        assertTrue(window.isAtOrAbove(-10.0))

        window.reset()

        assertFalse(window.isReady)
        assertEquals(0.0, window.averagePower, 0.0)
        assertFalse(window.isAtOrAbove(-10.0))
        window.add(power = 0.0001, samples = 10)
        assertFalse(window.isAtOrAbove(-10.0))
    }

    @Test
    fun partialOldestSegmentIsTrimmedBySampleDuration() {
        val window = RollingPowerWindow(sampleRateHz = 4, windowSeconds = 1)
        window.add(power = 1.0, samples = 4)
        window.add(power = 0.0, samples = 2)

        assertEquals(0.5, window.averagePower, 0.000001)
    }
}