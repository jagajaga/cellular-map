package me.jagajaga.signalmap.collect

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts recording when a user-selected Bluetooth device connects
 * (and optionally stops when it disconnects). Works from the background
 * thanks to the battery-optimization exemption the app requests.
 */
class BtTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val device =
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        if (device.address !in BtPrefs.devices(context)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED ->
                if (!RecordingService.running.get()) RecordingService.start(context)
            BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                if (BtPrefs.autoStop(context) && RecordingService.running.get()) {
                    RecordingService.stop(context)
                }
        }
    }
}
