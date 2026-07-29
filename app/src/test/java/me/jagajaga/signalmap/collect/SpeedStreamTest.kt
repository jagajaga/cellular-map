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
        assertEquals(5000, SpeedStream.smooth(null, 5000))
    }

    @Test fun smoothMovesTowardNewValue() {
        assertEquals(6000, SpeedStream.smooth(10000, 0))   // 10000*0.6 + 0*0.4
        assertEquals(4000, SpeedStream.smooth(0, 10000))   // 0*0.6 + 10000*0.4
    }
}
