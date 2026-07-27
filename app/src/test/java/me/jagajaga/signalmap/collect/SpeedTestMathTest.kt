package me.jagajaga.signalmap.collect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestMathTest {
    @Test fun stableWithinTwentyPercent() {
        assertTrue(SpeedTest.isStable(1000.0, 1100.0))
        assertTrue(SpeedTest.isStable(1000.0, 850.0))
        assertFalse(SpeedTest.isStable(1000.0, 1500.0))
        assertFalse(SpeedTest.isStable(1000.0, 500.0))
    }

    @Test fun movementCapShrinksWithSpeed() {
        assertEquals(4000L, SpeedTest.movementCapMs(0f))    // standing: max window
        assertEquals(4000L, SpeedTest.movementCapMs(1.4f))  // walking: still max
        assertEquals(2000L, SpeedTest.movementCapMs(5f))    // running/cycling: 10m / 5mps
        assertEquals(1000L, SpeedTest.movementCapMs(20f))   // driving: clamped floor
    }
}
