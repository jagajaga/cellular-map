package me.jagajaga.signalmap.collect

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny connectivity probes (~1 KB each):
 *  - internet: generate_204 endpoint built for connectivity checks; measures latency
 *  - youtube: YouTube's own generate_204 — succeeds only if YouTube is actually reachable,
 *    which detects regional blocking even when the internet otherwise works.
 */
object NetProbe {
    data class Result(val pingMs: Int?, val youtubeOk: Boolean?)

    suspend fun probe(): Result = withContext(Dispatchers.IO) {
        val ping = latencyOf("https://connectivitycheck.gstatic.com/generate_204")
        val youtube =
            if (ping == null) null
            else latencyOf("https://www.youtube.com/generate_204") != null
        Result(ping, youtube)
    }

    private fun latencyOf(url: String): Int? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.instanceFollowRedirects = false
        val t0 = SystemClock.elapsedRealtime()
        val code = conn.responseCode
        val dt = (SystemClock.elapsedRealtime() - t0).toInt()
        conn.disconnect()
        if (code in 200..399) dt else null
    } catch (_: Exception) {
        null
    }
}
