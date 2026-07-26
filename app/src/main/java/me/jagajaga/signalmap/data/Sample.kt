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
    val flagged: Int
)

data class CellAgg(val cx: Int, val cy: Int, val maxDbm: Int, val avgDbm: Double, val n: Int)

data class SessionRow(val sessionId: Long, val n: Int, val startMs: Long, val endMs: Long)
