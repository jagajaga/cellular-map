package me.jagajaga.signalmap.collect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalReaderTest {
    @Test fun picksFirstValidPreferringOrder() {
        // list is already ordered NR > LTE > other by read(); picker takes first valid
        assertEquals(-88 to "LTE", SignalReader.pickDbm(listOf(2147483647 to "NR", -88 to "LTE")))
    }
    @Test fun rejectsSentinelAndPositive() {
        assertNull(SignalReader.pickDbm(listOf(2147483647 to "NR", 99 to "GSM")))
    }
    @Test fun emptyIsNull() {
        assertNull(SignalReader.pickDbm(emptyList()))
    }
}
