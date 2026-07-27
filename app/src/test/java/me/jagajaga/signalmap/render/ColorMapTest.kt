package me.jagajaga.signalmap.render

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorMapTest {
    @Test fun normClamps() {
        assertEquals(0f, ColorMap.norm(-130), 1e-6f)
        assertEquals(1f, ColorMap.norm(-60), 1e-6f)
        assertEquals(0.5f, ColorMap.norm(-95), 1e-6f)
    }
    @Test fun endpointsAreRedAndGreen() {
        val red = ColorMap.argb(0f, 255)
        val green = ColorMap.argb(1f, 255)
        assertEquals(0xFF, (red shr 16) and 0xFF)   // full red channel
        assertEquals(0x00, red and 0xFF)             // no blue
        assertEquals(0xFF, (green shr 8) and 0xFF)  // full green channel
    }
    @Test fun genNormMapsGenerationsToGradient() {
        assertEquals(0f, ColorMap.genNorm(2), 1e-6f)    // 2G -> red
        assertEquals(0.5f, ColorMap.genNorm(3), 1e-6f)  // 3G -> yellow
        assertEquals(1f, ColorMap.genNorm(4), 1e-6f)    // 4G/5G -> green
        assertEquals(1f, ColorMap.genNorm(5), 1e-6f)    // clamped
        assertEquals(0f, ColorMap.genNorm(1), 1e-6f)    // clamped
    }

    @Test fun pingNormFastIsGreenSlowIsRed() {
        assertEquals(1f, ColorMap.pingNorm(0), 1e-6f)      // clamped
        assertEquals(1f, ColorMap.pingNorm(50), 1e-6f)     // 50ms or faster -> green
        assertEquals(0.5f, ColorMap.pingNorm(525), 1e-6f)  // midpoint
        assertEquals(0f, ColorMap.pingNorm(1000), 1e-6f)   // 1s -> red
        assertEquals(0f, ColorMap.pingNorm(2000), 1e-6f)   // clamped
    }

    @Test fun relNormStretchesOverVisibleRange() {
        assertEquals(0f, ColorMap.relNorm(2000, 2000, 18000), 1e-6f)   // slowest visible -> red
        assertEquals(1f, ColorMap.relNorm(18000, 2000, 18000), 1e-6f)  // fastest visible -> green
        assertEquals(0.5f, ColorMap.relNorm(10000, 2000, 18000), 1e-6f)
        assertEquals(1f, ColorMap.relNorm(5000, 5000, 5000), 1e-6f)    // single value -> green
    }

    @Test fun midIsYellow() {
        val y = ColorMap.argb(0.5f, 255)
        assertEquals(0xFF, (y shr 16) and 0xFF)
        assertEquals(0xFF, (y shr 8) and 0xFF)
    }
}
