package me.jagajaga.signalmap.collect

import android.content.Context

/** Persisted Bluetooth auto-record settings. */
object BtPrefs {
    private const val FILE = "bt"
    private const val KEY_DEVICES = "devices"
    private const val KEY_AUTOSTOP = "autoStop"

    fun devices(ctx: Context): Set<String> =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getStringSet(KEY_DEVICES, emptySet()) ?: emptySet()

    fun setDevices(ctx: Context, macs: Set<String>) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_DEVICES, macs.toSet()).apply()
    }

    fun autoStop(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_AUTOSTOP, false)

    fun setAutoStop(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTOSTOP, value).apply()
    }
}
