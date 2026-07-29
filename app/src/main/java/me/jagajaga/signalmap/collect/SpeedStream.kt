package me.jagajaga.signalmap.collect

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Continuous throughput probe: streams a huge download and drains it, tracking the
 * instantaneous rate over a rolling window. Each GPS fix reads [currentKbps], so
 * throughput is attributed to the position where it was actually measured instead
 * of smearing across a start/finish pair like a discrete speed test does.
 *
 * Saturates the data link while running — strictly opt-in, with [bytesTotal]
 * exposed so the UI can show how much data has been spent.
 */
object SpeedStream {
    private const val ENDPOINT = "https://speed.cloudflare.com/__down?bytes=5368709120" // 5 GB
    private const val WINDOW_MS = 1000L
    private const val STALL_MS = 4000L

    @Volatile var currentKbps: Int? = null
        private set
    @Volatile var bytesTotal: Long = 0
        private set
    @Volatile var streaming: Boolean = false
        private set

    /** Current movement speed in m/s, fed from GPS fixes; drives smoothing. */
    @Volatile var motionMps: Float = 0f

    private var job: Job? = null

    /** Rate for [bytes] transferred in [ms]. bytes*8/ms == kbit/s. Pure; unit-tested. */
    fun rateKbps(bytes: Long, ms: Long): Int = (bytes * 8.0 / max(ms, 1L)).roundToInt()

    /** Exponentially weighted smoothing so the readout is stable but still tracks moves. */
    fun smooth(prev: Int?, cur: Int, alpha: Double): Int =
        if (prev == null) cur else (prev * (1 - alpha) + cur * alpha).roundToInt()

    /**
     * Smoothing strength as a function of movement. Standing still, average hard for
     * accuracy; moving, respond fast so a reading belongs to the position it was taken
     * at instead of being smeared down the road behind it. Pure; unit-tested.
     */
    fun alphaFor(motionMps: Float): Double {
        // Below ~1.5 m/s the smear over the smoothing window is smaller than GPS error
        // itself, so keep full smoothing; ramp to no smoothing by 15 m/s (54 km/h).
        val over = ((motionMps - 1.5) / 13.5).coerceIn(0.0, 1.0)
        return 0.4 + 0.6 * over
    }

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        bytesTotal = 0
        currentKbps = null
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                streamOnce()
                if (isActive) delay(1000) // endpoint finished or failed; reconnect
            }
            streaming = false
            currentKbps = null
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        streaming = false
        currentKbps = null
    }

    private suspend fun streamOnce() {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }
            conn.inputStream.use { input ->
                streaming = true
                val buf = ByteArray(32 * 1024)
                var windowBytes = 0L
                var windowStart = SystemClock.elapsedRealtime()
                var lastData = windowStart
                while (currentCoroutineIsActive()) {
                    val n = input.read(buf)
                    if (n < 0) break
                    windowBytes += n
                    bytesTotal += n
                    val now = SystemClock.elapsedRealtime()
                    if (n > 0) lastData = now
                    val elapsed = now - windowStart
                    if (elapsed >= WINDOW_MS) {
                        currentKbps = smooth(
                            currentKbps, rateKbps(windowBytes, elapsed), alphaFor(motionMps)
                        )
                        windowBytes = 0
                        windowStart = now
                    } else if (now - lastData > STALL_MS) {
                        currentKbps = 0 // link stalled: that IS the measurement here
                    }
                }
            }
        } catch (_: Exception) {
            currentKbps = 0 // connection failed at this spot: no usable throughput
        } finally {
            streaming = false
            try { conn?.disconnect() } catch (_: Exception) { }
        }
    }

    private suspend fun currentCoroutineIsActive(): Boolean =
        kotlinx.coroutines.currentCoroutineContext()[Job]?.isActive != false
}
