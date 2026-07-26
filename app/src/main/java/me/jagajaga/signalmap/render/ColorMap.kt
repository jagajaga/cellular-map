package me.jagajaga.signalmap.render

object ColorMap {
    const val MIN_DBM = -120f
    const val MAX_DBM = -70f

    fun norm(dbm: Int): Float =
        ((dbm - MIN_DBM) / (MAX_DBM - MIN_DBM)).coerceIn(0f, 1f)

    /** 0 = red, 0.5 = yellow, 1 = green. */
    fun argb(t: Float, alpha: Int): Int {
        val tt = t.coerceIn(0f, 1f)
        val r: Int; val g: Int
        if (tt < 0.5f) { r = 0xFF; g = (tt * 2f * 255f).toInt() }
        else { r = ((1f - tt) * 2f * 255f).toInt(); g = 0xFF }
        return (alpha shl 24) or (r shl 16) or (g shl 8)
    }
}
