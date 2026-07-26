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
            "SIM ${slot + 1}: best $best dBm, avg $avg dBm, $net, ${list.size} samples"
        }
    }
}
