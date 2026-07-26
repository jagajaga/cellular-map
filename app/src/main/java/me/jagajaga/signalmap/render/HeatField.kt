package me.jagajaga.signalmap.render

import kotlin.math.max
import kotlin.math.min

/**
 * Value/weight accumulation field for IDW-style smooth rendering.
 * splat() adds a radial kernel; colorize() divides value by weight per pixel
 * and maps through ColorMap, transparent where weight ~ 0.
 */
class HeatField(val w: Int, val h: Int) {
    private val valSum = FloatArray(w * h)
    private val wSum = FloatArray(w * h)

    fun splat(cx: Float, cy: Float, value: Float, radius: Float) {
        val r2 = radius * radius
        val x0 = max(0, (cx - radius).toInt()); val x1 = min(w - 1, (cx + radius).toInt())
        val y0 = max(0, (cy - radius).toInt()); val y1 = min(h - 1, (cy + radius).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x - cx; val dy = y - cy
            val d2 = dx * dx + dy * dy
            if (d2 >= r2) continue
            val q = 1f - d2 / r2
            val k = q * q
            val i = y * w + x
            valSum[i] += value * k
            wSum[i] += k
        }
    }

    fun colorize(maxAlpha: Int = 255): IntArray {
        val out = IntArray(w * h)
        for (i in out.indices) {
            val wt = wSum[i]
            if (wt < 1e-4f) continue
            val v = valSum[i] / wt
            val alpha = (maxAlpha * min(1f, wt / 0.25f)).toInt()
            out[i] = ColorMap.argb(v, alpha)
        }
        return out
    }
}
