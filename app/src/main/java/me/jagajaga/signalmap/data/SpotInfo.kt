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
                val speeds = list.mapNotNull { it.speedKbps }
                if (speeds.isNotEmpty()) {
                    append(", ${String.format(java.util.Locale.US, "%.1f", speeds.max() / 1000.0)} Mbps")
                }
            }
            "SIM ${slot + 1}: best $best dBm, avg $avg dBm, $net, ${list.size} samples$extra"
        }
    }
}
