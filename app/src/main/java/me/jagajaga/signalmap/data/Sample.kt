package me.jagajaga.signalmap.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "samples",
    indices = [Index("simSlot", "mx", "my"), Index("sessionId")]
)
data class Sample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val simSlot: Int,
    val timestampMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val dbm: Int,
    val networkType: String,
    val mx: Int,
    val my: Int,
    val flagged: Int,
    /** Internet reachability latency in ms (null = not probed or offline). Data SIM only. */
    val pingMs: Int? = null,
    /** 1 = YouTube reachable, 0 = blocked while internet worked, null = not probed. Data SIM only. */
    val youtubeOk: Int? = null,
    /** Downlink estimate in kbit/s from the adaptive speed test; null = not measured. Data SIM only. */
    val speedKbps: Int? = null
)

data class CellAgg(
    val cx: Int,
    val cy: Int,
    val maxDbm: Int,
    val avgDbm: Double,
    val n: Int,
    /** Worst network generation seen in the cell: 2 = 2G, 3 = 3G, 4 = 4G/5G. */
    val minGen: Int,
    /** Best internet latency seen in the cell; null if never probed here. */
    val minPing: Int?,
    /** Share of probes where YouTube worked (0..1); null if never probed here. */
    val ytRatio: Double?,
    /** Best measured downlink in the cell (kbit/s); null if never measured here. */
    val maxSpeed: Int?
)

data class SessionRow(val sessionId: Long, val n: Int, val startMs: Long, val endMs: Long)
