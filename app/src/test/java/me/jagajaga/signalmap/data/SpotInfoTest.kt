package me.jagajaga.signalmap.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SpotInfoTest {
    private fun sample(
        sim: Int, dbm: Int, net: String, ping: Int? = null, yt: Int? = null
    ) = Sample(
        sessionId = 1, simSlot = sim, timestampMs = 0, lat = 0.0, lon = 0.0,
        accuracyM = 5f, dbm = dbm, networkType = net, mx = 0, my = 0, flagged = 0,
        pingMs = ping, youtubeOk = yt
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

    @Test fun includesPingAndYoutubeWhenPresent() {
        val out = SpotInfo.summarize(
            listOf(
                sample(0, -90, "LTE", ping = 40, yt = 1),
                sample(0, -90, "LTE", ping = 60, yt = 1),
                sample(1, -95, "GSM", ping = 500, yt = 0)
            )
        )
        assertEquals(
            "SIM 1: best -90 dBm, avg -90 dBm, LTE, 2 samples, ping 50 ms, YT ✓\n" +
                "SIM 2: best -95 dBm, avg -95 dBm, GSM, 1 samples, ping 500 ms, YT ✗",
            out
        )
    }
}
