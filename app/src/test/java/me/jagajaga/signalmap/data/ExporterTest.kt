package me.jagajaga.signalmap.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExporterTest {
    private val s = Sample(
        id = 1, sessionId = 1000, simSlot = 0, timestampMs = 1721990000000,
        lat = 52.52, lon = 13.405, accuracyM = 4.5f, dbm = -95,
        networkType = "LTE", mx = 1, my = 2, flagged = 0
    )

    @Test fun csvHasHeaderAndRow() {
        val out = Exporter.csv(listOf(s))
        val lines = out.trim().lines()
        assertEquals(
            "sessionId,simSlot,timestampMs,lat,lon,accuracyM,dbm,networkType,flagged,pingMs,youtubeOk,speedKbps",
            lines[0]
        )
        assertEquals("1000,0,1721990000000,52.52,13.405,4.5,-95,LTE,0,,,", lines[1])
    }

    @Test fun csvIncludesProbeValues() {
        val out = Exporter.csv(listOf(s.copy(pingMs = 45, youtubeOk = 1, speedKbps = 8000)))
        assertEquals(
            "1000,0,1721990000000,52.52,13.405,4.5,-95,LTE,0,45,1,8000",
            out.trim().lines()[1]
        )
    }

    @Test fun geoJsonIsFeatureCollectionWithPoint() {
        val out = Exporter.geoJson(listOf(s))
        assertTrue(out.contains("\"FeatureCollection\""))
        assertTrue(out.contains("[13.405,52.52]"))
        assertTrue(out.contains("\"dbm\":-95"))
        assertTrue(out.contains("\"sim\":0"))
        assertTrue(out.contains("\"pingMs\":null"))
    }
}
