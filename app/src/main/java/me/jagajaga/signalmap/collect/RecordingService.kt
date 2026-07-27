package me.jagajaga.signalmap.collect

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.jagajaga.signalmap.data.AppDb
import me.jagajaga.signalmap.data.Sample
import me.jagajaga.signalmap.render.Mercator
import me.jagajaga.signalmap.ui.MainActivity
import java.util.concurrent.atomic.AtomicBoolean

class RecordingService : Service() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val CHANNEL = "recording"
        const val NOTIF_ID = 1
        private const val PREFS = "recording"
        private const val KEY_ACTIVE = "active"
        private const val KEY_SESSION = "sessionId"
        val running = AtomicBoolean(false)
        @Volatile var currentSessionId: Long = 0
        @Volatile var sampleCount: Int = 0
        /** Toggled from the UI: when true, an adaptive speed test runs every >=30s. */
        @Volatile var speedTestEnabled: Boolean = false
        /** Live speed-test state for the UI status label. */
        @Volatile var speedTesting: Boolean = false
        @Volatile var lastSpeedKbps: Int? = null
        private const val SPEED_TEST_MIN_GAP_MS = 30_000L

        fun start(ctx: Context) {
            ctx.startForegroundService(
                Intent(ctx, RecordingService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var tms: List<Pair<Int, TelephonyManager>> = emptyList() // simSlot -> per-sub TM
    private var dataSlot: Int = -1 // slot of the SIM carrying data (probe results attach here)
    private var lastSpeedTestAt: Long = 0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onFix(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running.getAndSet(true)) startRecording(newSession = true)
            ACTION_STOP -> stopRecording()
            // null intent: START_STICKY restart after the system killed the process.
            // Resume the interrupted session so recording is never silently lost.
            null -> if (prefs.getBoolean(KEY_ACTIVE, false) && !running.getAndSet(true)) {
                startRecording(newSession = false)
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun startRecording(newSession: Boolean) {
        currentSessionId =
            if (newSession) System.currentTimeMillis()
            else prefs.getLong(KEY_SESSION, System.currentTimeMillis())
        sampleCount = 0
        prefs.edit().putBoolean(KEY_ACTIVE, true).putLong(KEY_SESSION, currentSessionId).apply()

        createChannel()
        startForeground(
            NOTIF_ID, buildNotification("Recording…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )

        // Keep the CPU alive while the screen is off so 1s sampling never pauses.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "signalmap:recording")
            .apply { setReferenceCounted(false); acquire() }

        val subMgr = getSystemService(SubscriptionManager::class.java)
        val baseTm = getSystemService(TelephonyManager::class.java)
        tms = (subMgr.activeSubscriptionInfoList ?: emptyList()).map { info ->
            info.simSlotIndex to baseTm.createForSubscriptionId(info.subscriptionId)
        }
        dataSlot = try {
            subMgr.getActiveSubscriptionInfo(SubscriptionManager.getDefaultDataSubscriptionId())
                ?.simSlotIndex ?: -1
        } catch (_: Exception) {
            -1
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(5000L)
            .build()
        fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
    }

    private fun onFix(loc: Location) {
        val flagged = if (loc.accuracy > 25f) 1 else 0
        val rows = tms.mapNotNull { (slot, tm) ->
            val (dbm, net) = SignalReader.read(tm) ?: return@mapNotNull null
            Sample(
                sessionId = currentSessionId, simSlot = slot,
                timestampMs = loc.time, lat = loc.latitude, lon = loc.longitude,
                accuracyM = loc.accuracy, dbm = dbm, networkType = net,
                mx = Mercator.lonToX(loc.longitude), my = Mercator.latToY(loc.latitude),
                flagged = flagged
            )
        }
        if (rows.isEmpty()) return
        scope.launch {
            // ~1 KB probe straight to YouTube on the data SIM: latency + reachability
            val probe = NetProbe.probe()
            // Movement-aware speed test: at most every 30s while enabled; the test's
            // time budget shrinks with speed so the result stays within ~10m of travel.
            val now = SystemClock.elapsedRealtime()
            val speed = if (
                speedTestEnabled && probe.pingMs != null &&
                now - lastSpeedTestAt >= SPEED_TEST_MIN_GAP_MS
            ) {
                lastSpeedTestAt = now
                speedTesting = true
                val kbps = SpeedTest.measure(SpeedTest.movementCapMs(loc.speed))
                speedTesting = false
                if (kbps != null) lastSpeedKbps = kbps
                kbps
            } else null
            val enriched = rows.map { r ->
                if (r.simSlot == dataSlot) {
                    r.copy(
                        pingMs = probe.pingMs,
                        youtubeOk = probe.youtubeOk?.let { if (it) 1 else 0 },
                        speedKbps = speed
                    )
                } else r
            }
            AppDb.get(this@RecordingService).dao().insertAll(enriched)
            sampleCount += rows.size
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification("Recording… $sampleCount samples"))
        }
    }

    private fun stopRecording() {
        prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
        fused.removeLocationUpdates(callback)
        wakeLock?.release()
        wakeLock = null
        running.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Signal Map")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopPi).build())
            .build()
    }

    // NOTE: no onTaskRemoved override — swiping the app away must NOT stop recording.

    override fun onDestroy() {
        // If the system is tearing us down mid-recording, leave KEY_ACTIVE=true so the
        // sticky restart resumes the session; only user Stop clears it.
        fused.removeLocationUpdates(callback)
        wakeLock?.release()
        running.set(false)
        scope.cancel()
        super.onDestroy()
    }
}
