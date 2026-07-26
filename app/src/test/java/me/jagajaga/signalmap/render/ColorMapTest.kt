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
    @Test fun midIsYellow() {
        val y = ColorMap.argb(0.5f, 255)
        assertEquals(0xFF, (y shr 16) and 0xFF)
        assertEquals(0xFF, (y shr 8) and 0xFF)
    }
}
