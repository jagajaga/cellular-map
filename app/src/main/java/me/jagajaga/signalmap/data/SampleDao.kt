package me.jagajaga.signalmap.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SampleDao {
    @Insert suspend fun insertAll(samples: List<Sample>)

    @Query(
        """SELECT (mx >> :shift) AS cx, (my >> :shift) AS cy,
                  MAX(dbm) AS maxDbm, AVG(dbm) AS avgDbm, COUNT(*) AS n
           FROM samples
           WHERE simSlot = :sim AND flagged = 0
             AND mx BETWEEN :x0 AND :x1 AND my BETWEEN :y0 AND :y1
           GROUP BY cx, cy"""
    )
    suspend fun aggregate(sim: Int, shift: Int, x0: Int, x1: Int, y0: Int, y1: Int): List<CellAgg>

    @Query(
        """SELECT sessionId, COUNT(*) AS n, MIN(timestampMs) AS startMs, MAX(timestampMs) AS endMs
           FROM samples GROUP BY sessionId ORDER BY sessionId DESC"""
    )
    suspend fun sessions(): List<SessionRow>

    @Query("SELECT * FROM samples WHERE sessionId = :id ORDER BY timestampMs")
    suspend fun samplesForSession(id: Long): List<Sample>

    @Query("DELETE FROM samples WHERE sessionId = :id")
    suspend fun deleteSession(id: Long)

    @Query("SELECT COUNT(*) FROM samples WHERE sessionId = :id")
    suspend fun countForSession(id: Long): Int
}
