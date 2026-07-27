package me.jagajaga.signalmap.collect

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Single tiny probe (~1 KB) straight to YouTube's generate_204 endpoint:
 * latency = YouTube ping; success = YouTube reachable. Failure means either
 * no internet or YouTube blocked — both count as "YouTube doesn't work here".
 */
object NetProbe {
    data class Result(val pingMs: Int?, val youtubeOk: Boolean?)

    suspend fun probe(): Result = withContext(Dispatchers.IO) {
        val ping = latencyOf("https://www.youtube.com/generate_204")
        Result(ping, ping != null)
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
