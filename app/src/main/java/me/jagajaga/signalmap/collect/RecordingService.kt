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
        val running = AtomicBoolean(false)
        @Volatile var currentSessionId: Long = 0
        @Volatile var sampleCount: Int = 0

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
    private var tms: List<Pair<Int, TelephonyManager>> = emptyList() // simSlot -> per-sub TM

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onFix(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!running.getAndSet(true)) startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        currentSessionId = System.currentTimeMillis()
        sampleCount = 0
        createChannel()
        startForeground(
            NOTIF_ID, buildNotification("Recording…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        )
        val subMgr = getSystemService(SubscriptionManager::class.java)
        val baseTm = getSystemService(TelephonyManager::class.java)
        tms = (subMgr.activeSubscriptionInfoList ?: emptyList()).map { info ->
            info.simSlotIndex to baseTm.createForSubscriptionId(info.subscriptionId)
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
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
            AppDb.get(this@RecordingService).dao().insertAll(rows)
            sampleCount += rows.size
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification("Recording… $sampleCount samples"))
        }
    }

    private fun stopRecording() {
        fused.removeLocationUpdates(callback)
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
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Signal Map")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        running.set(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopRecording()
        super.onTaskRemoved(rootIntent)
    }
}
