package me.jagajaga.signalmap.render

import org.junit.Assert.assertEquals
import org.junit.Test

class MercatorTest {
    @Test fun roundTripBerlin() {
        val lat = 52.52; val lon = 13.405
        assertEquals(lat, Mercator.yToLat(Mercator.latToY(lat)), 1e-5)
        assertEquals(lon, Mercator.xToLon(Mercator.lonToX(lon)), 1e-5)
    }
    @Test fun originIsCenter() {
        assertEquals(1 shl 29, Mercator.lonToX(0.0))
        assertEquals(1 shl 29, Mercator.latToY(0.0))
    }
    @Test fun monotonic() {
        assert(Mercator.lonToX(10.0) < Mercator.lonToX(10.001))
        assert(Mercator.latToY(50.0) > Mercator.latToY(50.001)) // y grows southward
    }
    @Test fun shiftClamps() {
        assertEquals(6, Mercator.shiftForZoom(22.0))
        assertEquals(6, Mercator.shiftForZoom(21.0))
        assertEquals(7, Mercator.shiftForZoom(20.0))
        assertEquals(13, Mercator.shiftForZoom(14.0))
    }
}
