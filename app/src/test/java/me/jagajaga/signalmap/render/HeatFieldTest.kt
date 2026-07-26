package me.jagajaga.signalmap.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatFieldTest {
    @Test fun emptyFieldIsTransparent() {
        val px = HeatField(4, 4).colorize()
        assertTrue(px.all { it == 0 })
    }
    @Test fun splatCenterHasValueColor() {
        val f = HeatField(9, 9)
        f.splat(4f, 4f, 1f, 3f)
        val px = f.colorize()
        val c = px[4 * 9 + 4]
        assertTrue((c ushr 24) > 0)                    // visible
        assertEquals(0xFF, (c shr 8) and 0xFF)         // green (t=1)
    }
    @Test fun interpolationBetweenTwoSplatsIsBetweenValues() {
        val f = HeatField(21, 5)
        f.splat(2f, 2f, 0f, 6f)
        f.splat(18f, 2f, 1f, 6f)
        val c = f.colorize()[2 * 21 + 5]
        val redCh = (c shr 16) and 0xFF
        assertTrue((c ushr 24) > 0 && redCh > 0)       // visible, leaning red side
    }
    @Test fun outsideRadiusIsTransparent() {
        val f = HeatField(20, 20)
        f.splat(2f, 2f, 1f, 3f)
        assertEquals(0, f.colorize()[19 * 20 + 19])
    }
}
