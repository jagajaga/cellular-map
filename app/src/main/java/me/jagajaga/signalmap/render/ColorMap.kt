package me.jagajaga.signalmap.render

import kotlin.math.log10

object ColorMap {
    const val MIN_DBM = -120f
    const val MAX_DBM = -70f

    fun norm(dbm: Int): Float =
        ((dbm - MIN_DBM) / (MAX_DBM - MIN_DBM)).coerceIn(0f, 1f)

    /** Downlink kbit/s to gradient position, log scale: <=100 kbps red, ~2.2 Mbps yellow, >=50 Mbps green. */
    fun speedNorm(kbps: Int): Float {
        if (kbps <= 100) return 0f
        val t = (log10(kbps.toDouble()) - 2.0) / (log10(50000.0) - 2.0)
        return t.toFloat().coerceIn(0f, 1f)
    }

    /** YouTube latency to gradient position: <=50 ms green, ~525 ms yellow, >=1000 ms red. */
    fun pingNorm(ms: Int): Float = (1f - (ms - 50f) / 950f).coerceIn(0f, 1f)

    /** Network generation (2=2G, 3=3G, 4=4G/5G) to gradient position: 2G red, 3G yellow, 4G+ green. */
    fun genNorm(gen: Int): Float = ((gen - 2) / 2f).coerceIn(0f, 1f)

    /** 0 = red, 0.5 = yellow, 1 = green. */
    fun argb(t: Float, alpha: Int): Int {
        val tt = t.coerceIn(0f, 1f)
        val r: Int; val g: Int
        if (tt < 0.5f) { r = 0xFF; g = (tt * 2f * 255f).toInt() }
        else { r = ((1f - tt) * 2f * 255f).toInt(); g = 0xFF }
        return (alpha shl 24) or (r shl 16) or (g shl 8)
    }
}
