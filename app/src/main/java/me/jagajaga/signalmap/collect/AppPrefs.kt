package me.jagajaga.signalmap.collect

import android.content.Context

/** Persisted user settings: Bluetooth auto-record and map filtering. */
object AppPrefs {
    private const val FILE = "bt"
    private const val KEY_DEVICES = "devices"
    private const val KEY_AUTOSTOP = "autoStop"
    private const val KEY_SLOW_ONLY = "slowOnly"

    /** Movement ceiling for the "slow samples only" filter: 5 m/s == 18 km/h. */
    const val SLOW_MPS = 5f

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun devices(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_DEVICES, emptySet()) ?: emptySet()

    fun setDevices(ctx: Context, macs: Set<String>) {
        prefs(ctx).edit().putStringSet(KEY_DEVICES, macs.toSet()).apply()
    }

    fun autoStop(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTOSTOP, false)

    fun setAutoStop(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTOSTOP, value).apply()
    }

    /** When on, the map only renders samples taken while barely moving. */
    fun slowOnly(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SLOW_ONLY, false)

    fun setSlowOnly(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SLOW_ONLY, value).apply()
    }
}
