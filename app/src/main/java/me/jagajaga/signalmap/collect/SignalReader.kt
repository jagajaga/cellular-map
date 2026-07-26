package me.jagajaga.signalmap.collect

import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthWcdma
import android.telephony.TelephonyManager

object SignalReader {
    /** First entry with a plausible negative dBm (list pre-ordered by preference). Pure; unit-tested. */
    fun pickDbm(strengths: List<Pair<Int, String>>): Pair<Int, String>? =
        strengths.firstOrNull { (dbm, _) -> dbm in -160..-20 }

    /** Reads the system-cached signal for this (per-subscription) TelephonyManager. */
    fun read(tm: TelephonyManager): Pair<Int, String>? {
        val ss = tm.signalStrength ?: return null
        val ordered = ss.cellSignalStrengths.sortedBy { cs ->
            when (cs) {
                is CellSignalStrengthNr -> 0
                is CellSignalStrengthLte -> 1
                is CellSignalStrengthWcdma -> 2
                is CellSignalStrengthGsm -> 3
                else -> 4
            }
        }.map { cs ->
            when (cs) {
                is CellSignalStrengthNr -> cs.ssRsrp to "NR"
                is CellSignalStrengthLte -> cs.rsrp to "LTE"
                is CellSignalStrengthWcdma -> cs.dbm to "WCDMA"
                is CellSignalStrengthGsm -> cs.dbm to "GSM"
                else -> cs.dbm to "OTHER"
            }
        }
        return pickDbm(ordered)
    }
}
