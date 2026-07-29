package me.jagajaga.signalmap.collect

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
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

        /** Toggled from the UI: when true, a continuous download measures live throughput. */
        @Volatile var speedTestEnabled: Boolean = false

        /** Permissions without which the location foreground service cannot legally start. */
        fun hasPermissions(ctx: Context): Boolean =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * Starts recording. Returns null on success or a short reason string on failure —
         * background starts are restricted on Android 12+, so callers (e.g. the Bluetooth
         * trigger) must handle refusal instead of letting it crash the process.
         */
        fun start(ctx: Context): String? {
            if (!hasPermissions(ctx)) return "missing location/phone permission"
            return try {
                ctx.startForegroundService(
                    Intent(ctx, RecordingService::class.java).setAction(ACTION_START)
                )
                null
            } catch (e: Exception) {
                e.javaClass.simpleName
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.startService(
                    Intent(ctx, RecordingService::class.java).setAction(ACTION_STOP)
                )
            } catch (_: Exception) {
                // service already gone
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private var wakeLock: PowerManager.WakeLock? = null
    private var tms: List<Pair<Int, TelephonyManager>> = emptyList() // simSlot -> per-sub TM
    private var dataSlot: Int = -1 // slot of the SIM carrying data (probe results attach here)
    private var foregroundTypes: Int = 0

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onFix(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every path here may have been launched via startForegroundService(), which
        // REQUIRES startForeground() within 5s — do it first, unconditionally, and bail
        // out cleanly if the system refuses (background-start limits, missing perms).
        try {
            createChannel()
            promoteForeground(
                if (running.get()) "Recording… $sampleCount samples" else "Starting…"
            )
        } catch (_: Exception) {
            running.set(false)
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START -> if (!running.getAndSet(true)) startRecording(newSession = true)
            ACTION_STOP -> stopRecording()
            // null intent: START_STICKY restart after the system killed the process.
            // Resume the interrupted session so recording is never silently lost.
            null -> if (prefs.getBoolean(KEY_ACTIVE, false) && !running.getAndSet(true)) {
                startRecording(newSession = false)
            } else if (!running.get()) {
                stopRecording()
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

        // Keep the CPU alive while the screen is off so sampling never pauses.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "signalmap:recording")
            .apply { setReferenceCounted(false); acquire() }

        try {
            val subMgr = getSystemService(SubscriptionManager::class.java)
            val baseTm = getSystemService(TelephonyManager::class.java)
            tms = (subMgr.activeSubscriptionInfoList ?: emptyList()).map { info ->
                info.simSlotIndex to baseTm.createForSubscriptionId(info.subscriptionId)
            }
            dataSlot = subMgr
                .getActiveSubscriptionInfo(SubscriptionManager.getDefaultDataSubscriptionId())
                ?.simSlotIndex ?: -1
        } catch (_: Exception) {
            tms = emptyList()
            dataSlot = -1
        }

        if (speedTestEnabled) SpeedStream.start(scope)

        try {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
                .setMinUpdateIntervalMillis(5000L)
                .build()
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            stopRecording()
        }
    }

    private fun onFix(loc: Location) {
        val flagged = if (loc.accuracy > 25f) 1 else 0
        val motion = if (loc.hasSpeed()) loc.speed else null
        // Movement drives how much the throughput reading is smoothed: heavy averaging
        // when still, responsive when moving so values stay tied to their position.
        SpeedStream.motionMps = motion ?: 0f
        val rows = tms.mapNotNull { (slot, tm) ->
            val (dbm, net) = SignalReader.read(tm) ?: return@mapNotNull null
            Sample(
                sessionId = currentSessionId, simSlot = slot,
                timestampMs = loc.time, lat = loc.latitude, lon = loc.longitude,
                accuracyM = loc.accuracy, dbm = dbm, networkType = net,
                mx = Mercator.lonToX(loc.longitude), my = Mercator.latToY(loc.latitude),
                flagged = flagged, speedMps = motion
            )
        }
        if (rows.isEmpty()) return
        // Keep the streaming speed reading in sync with the toggle mid-session, including
        // the foreground type — dataSync must be declared while the download runs.
        val wantTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
            if (speedTestEnabled) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        if (wantTypes != foregroundTypes) {
            try { promoteForeground("Recording… $sampleCount samples") } catch (_: Exception) { }
        }
        if (speedTestEnabled) SpeedStream.start(scope) else SpeedStream.stop()
        scope.launch {
            // ~1 KB probe straight to YouTube on the data SIM: latency + reachability
            val probe = NetProbe.probe()
            // Instantaneous throughput measured *at this position* by the continuous stream.
            val speed = if (speedTestEnabled) SpeedStream.currentKbps else null
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
            try {
                // Live speed in the notification: lets you confirm the background stream
                // is really running without opening the app.
                val suffix = if (speedTestEnabled) {
                    String.format(
                        java.util.Locale.US, " · %.1f Mbps · %.0f MB",
                        (SpeedStream.currentKbps ?: 0) / 1000.0,
                        SpeedStream.bytesTotal / 1_000_000.0
                    )
                } else ""
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotification("Recording… $sampleCount samples$suffix"))
            } catch (_: Exception) {
                // notifications denied; recording continues
            }
        }
    }

    private fun stopRecording() {
        prefs.edit().putBoolean(KEY_ACTIVE, false).apply()
        try { fused.removeLocationUpdates(callback) } catch (_: Exception) { }
        SpeedStream.stop()
        wakeLock?.release()
        wakeLock = null
        running.set(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Foreground types must cover what the service actually does: `location` for the
     * GPS stream and `dataSync` for the continuous speed download. Without dataSync,
     * Android 14+ throttles/blocks that transfer once the app leaves the foreground.
     */
    private fun promoteForeground(text: String) {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (speedTestEnabled) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(NOTIF_ID, buildNotification(text), types)
        foregroundTypes = types
    }

    /**
     * Android 15 caps `dataSync` foreground services at ~6h per day. When the system
     * calls time on it, drop speed streaming and keep recording signal as location-only
     * rather than letting the service be killed.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        SpeedStream.stop()
        speedTestEnabled = false
        try {
            promoteForeground("Recording… $sampleCount samples (speed test timed out)")
        } catch (_: Exception) {
            stopRecording()
        }
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
        try { fused.removeLocationUpdates(callback) } catch (_: Exception) { }
        SpeedStream.stop()
        wakeLock?.release()
        running.set(false)
        scope.cancel()
        super.onDestroy()
    }
}
