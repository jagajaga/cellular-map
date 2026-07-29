package me.jagajaga.signalmap.data

import kotlin.math.roundToInt

object SpotInfo {
    /** Human-readable per-SIM stats for the samples around a tapped spot. Pure; unit-tested. */
    fun summarize(samples: List<Sample>): String {
        if (samples.isEmpty()) return "No data here yet"
        return samples.groupBy { it.simSlot }.toSortedMap().entries.joinToString("\n") { (slot, list) ->
            val best = list.maxOf { it.dbm }
            val avg = list.map { it.dbm.toDouble() }.average().roundToInt()
            val net = list.groupingBy { it.networkType }.eachCount().maxByOrNull { it.value }!!.key
            val extra = buildString {
                val pings = list.mapNotNull { it.pingMs }
                if (pings.isNotEmpty()) append(", ping ${pings.average().roundToInt()} ms")
                val yts = list.mapNotNull { it.youtubeOk }
                if (yts.isNotEmpty()) append(if (yts.average() >= 0.5) ", YT ✓" else ", YT ✗")
                // Report the best throughput seen here, together with how fast the phone
                // was moving when it was measured — the two are not independent.
                val best = list.filter { it.speedKbps != null }.maxByOrNull { it.speedKbps!! }
                if (best != null) {
                    append(", ${String.format(java.util.Locale.US, "%.1f", best.speedKbps!! / 1000.0)} Mbps")
                    val kmh = ((best.speedMps ?: 0f) * 3.6f).roundToInt()
                    if (kmh >= 2) append(" at $kmh km/h")
                }
            }
            "SIM ${slot + 1}: best $best dBm, avg $avg dBm, $net, ${list.size} samples$extra"
        }
    }
}
