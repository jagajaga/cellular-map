package me.jagajaga.signalmap.collect

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Adaptive chunked download speed test against Cloudflare's speed endpoint.
 * Doubles chunk size until the measured rate stabilizes (±20%), the time budget
 * runs out, or the data budget (3 MB) is spent — so slow networks cost almost
 * nothing and fast networks converge in ~2 s.
 */
object SpeedTest {
    private const val ENDPOINT = "https://speed.cloudflare.com/__down?bytes="
    private const val WARMUP_BYTES = 64 * 1024
    private const val FIRST_CHUNK = 256 * 1024
    private const val DATA_BUDGET = 3 * 1024 * 1024L

    /** Rates within 20% of each other count as converged. Pure; unit-tested. */
    fun isStable(prev: Double, cur: Double): Boolean =
        abs(cur - prev) / prev <= 0.2

    /**
     * Time budget so the whole test happens within ~10 m of travel:
     * standing/walking -> 4 s max, driving -> clamped to 1 s. Pure; unit-tested.
     */
    fun movementCapMs(speedMps: Float): Long =
        ((10.0 / max(speedMps.toDouble(), 0.5)) * 1000).toLong().coerceIn(1000L, 4000L)

    /** Returns downlink estimate in kbit/s, or null if the network failed entirely. */
    suspend fun measure(maxMillis: Long): Int? = withContext(Dispatchers.IO) {
        val t0 = SystemClock.elapsedRealtime()
        download(WARMUP_BYTES) ?: return@withContext null // fills TCP slow-start; discarded
        var rate: Double? = null
        var chunk = FIRST_CHUNK
        var spent = WARMUP_BYTES.toLong()
        while (SystemClock.elapsedRealtime() - t0 < maxMillis && spent < DATA_BUDGET) {
            val ms = download(chunk) ?: break
            val cur = chunk * 8.0 / max(ms, 1L) // bytes*8/ms == kbit/s
            spent += chunk
            val prev = rate
            rate = cur
            if (prev != null && isStable(prev, cur)) break
            chunk *= 2
        }
        rate?.roundToInt()
    }

    private fun download(bytes: Int): Long? = try {
        val conn = URL(ENDPOINT + bytes).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 5000
        val t0 = SystemClock.elapsedRealtime()
        conn.inputStream.use { input ->
            val buf = ByteArray(16 * 1024)
            while (input.read(buf) != -1) { /* drain */ }
        }
        val dt = SystemClock.elapsedRealtime() - t0
        conn.disconnect()
        dt
    } catch (_: Exception) {
        null
    }
}
