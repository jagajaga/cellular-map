package me.jagajaga.signalmap.data

object Exporter {
    fun csv(samples: List<Sample>): String = buildString {
        appendLine("sessionId,simSlot,timestampMs,lat,lon,accuracyM,dbm,networkType,flagged")
        for (s in samples) {
            appendLine("${s.sessionId},${s.simSlot},${s.timestampMs},${s.lat},${s.lon},${s.accuracyM},${s.dbm},${s.networkType},${s.flagged}")
        }
    }

    fun geoJson(samples: List<Sample>): String = buildString {
        append("""{"type":"FeatureCollection","features":[""")
        samples.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append(
                """{"type":"Feature","geometry":{"type":"Point","coordinates":[${s.lon},${s.lat}]},""" +
                    """"properties":{"sim":${s.simSlot},"dbm":${s.dbm},"networkType":"${s.networkType}",""" +
                    """"accuracyM":${s.accuracyM},"timestampMs":${s.timestampMs}}}"""
            )
        }
        append("]}")
    }
}
