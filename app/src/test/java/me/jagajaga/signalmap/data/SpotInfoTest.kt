package me.jagajaga.signalmap.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotInfoTest {
    private fun sample(sim: Int, dbm: Int, net: String) = Sample(
        sessionId = 1, simSlot = sim, timestampMs = 0, lat = 0.0, lon = 0.0,
        accuracyM = 5f, dbm = dbm, networkType = net, mx = 0, my = 0, flagged = 0
    )

    @Test fun emptyGivesNoData() {
        assertEquals("No data here yet", SpotInfo.summarize(emptyList()))
    }

    @Test fun summarizesPerSimWithBestAvgAndDominantNetwork() {
        val out = SpotInfo.summarize(
            listOf(
                sample(0, -90, "LTE"), sample(0, -100, "LTE"), sample(0, -110, "NR"),
                sample(1, -80, "NR")
            )
        )
        assertEquals(
            "SIM 1: best -90 dBm, avg -100 dBm, LTE, 3 samples\n" +
                "SIM 2: best -80 dBm, avg -80 dBm, NR, 1 samples",
            out
        )
    }
}
