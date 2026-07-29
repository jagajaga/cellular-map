package me.jagajaga.signalmap.collect

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedStreamTest {
    @Test fun rateConvertsBytesPerMsToKbps() {
        assertEquals(8000, SpeedStream.rateKbps(1_000_000, 1000)) // 1 MB/s == 8 Mbps
        assertEquals(800, SpeedStream.rateKbps(100_000, 1000))
        assertEquals(16000, SpeedStream.rateKbps(1_000_000, 500))
    }

    @Test fun rateNeverDividesByZero() {
        assertEquals(8000, SpeedStream.rateKbps(1000, 0))
    }

    @Test fun smoothSeedsWithFirstSample() {
        assertEquals(5000, SpeedStream.smooth(null, 5000, 0.4))
    }

    @Test fun smoothMovesTowardNewValue() {
        assertEquals(6000, SpeedStream.smooth(10000, 0, 0.4))   // 10000*0.6 + 0*0.4
        assertEquals(4000, SpeedStream.smooth(0, 10000, 0.4))   // 0*0.6 + 10000*0.4
    }

    @Test fun smoothWithAlphaOneTakesRawValue() {
        assertEquals(10000, SpeedStream.smooth(0, 10000, 1.0))
    }

    @Test fun alphaScalesWithMotion() {
        // Stationary: heavy smoothing for accuracy.
        assertEquals(0.4, SpeedStream.alphaFor(0f), 1e-9)
        assertEquals(0.4, SpeedStream.alphaFor(1.4f), 1e-9)     // walking
        // Moving: less smoothing so the reading belongs to the position.
        assertEquals(0.7, SpeedStream.alphaFor(8.25f), 1e-6)    // ~30 km/h -> half way
        assertEquals(1.0, SpeedStream.alphaFor(15f), 1e-6)      // 54 km/h -> raw window
        assertEquals(1.0, SpeedStream.alphaFor(40f), 1e-6)      // clamped
    }
}
