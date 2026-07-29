package me.jagajaga.signalmap.collect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts recording when a user-selected Bluetooth device connects (and optionally
 * stops on disconnect). Every step is failure-tolerant: background service starts
 * are restricted on Android 12+, so a refusal must surface as a notification
 * rather than crashing the app.
 */
class BtTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            @Suppress("DEPRECATION")
            val device =
                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val address = try { device.address } catch (_: SecurityException) { return }
            if (address !in AppPrefs.devices(context)) return

            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (RecordingService.running.get()) return
                    val failure = RecordingService.start(context)
                    if (failure != null) notify(context, "Auto-record didn't start: $failure")
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (AppPrefs.autoStop(context) && RecordingService.running.get()) {
                        RecordingService.stop(context)
                    }
                }
            }
        } catch (e: Exception) {
            notify(context, "Auto-record error: ${e.javaClass.simpleName}")
        }
    }

    private fun notify(context: Context, text: String) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    RecordingService.CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW
                )
            )
            nm.notify(
                2,
                Notification.Builder(context, RecordingService.CHANNEL)
                    .setContentTitle("Signal Map")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setAutoCancel(true)
                    .build()
            )
        } catch (_: Exception) {
            // notifications unavailable; nothing more we can do
        }
    }
}
