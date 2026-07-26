package me.jagajaga.signalmap.render

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/** Web-mercator integer coords at "pixel zoom 30" (256px tiles * 2^22). ~3.7cm/unit at equator. */
object Mercator {
    private const val WORLD = 1L shl 30
    private const val MAX = (1 shl 30) - 1

    fun lonToX(lon: Double): Int =
        ((lon + 180.0) / 360.0 * WORLD).toLong().coerceIn(0, MAX.toLong()).toInt()

    fun latToY(lat: Double): Int {
        val s = sin(lat * PI / 180.0).coerceIn(-0.9999, 0.9999)
        val y = 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
        return (y * WORLD).toLong().coerceIn(0, MAX.toLong()).toInt()
    }

    fun xToLon(x: Int): Double = x.toDouble() / WORLD * 360.0 - 180.0

    fun yToLat(y: Int): Double {
        val n = PI - 2.0 * PI * (y.toDouble() / WORLD)
        return 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
    }

    /** Grid cell = 2^shift storage units; ~24-32 screen px at [zoom], floored at shift 6 (>=2m). */
    fun shiftForZoom(zoom: Double): Int = max(27 - zoom.toInt(), 6)
}
