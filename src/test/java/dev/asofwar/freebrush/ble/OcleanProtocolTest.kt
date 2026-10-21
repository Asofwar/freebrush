package dev.asofwar.freebrush.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Packets below were captured from real Oclean brushes (X, X Pro, X Pro Elite) by the
 * ha-oclean-integration project. Where a record was captured whole, it is split here into the
 * 20-byte notifications a brush sends at the default MTU.
 */
class OcleanProtocolTest {

    private fun assemble(vararg packets: String): OcleanProtocol.SessionAssembler =
        OcleanProtocol.SessionAssembler().apply { packets.forEach { feed(hex(it)) } }

    private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, offsetH: Int) =
        LocalDateTime.of(y, mo, d, h, mi, s).toEpochSecond(ZoneOffset.ofHours(offsetH))

    @Test
    fun timeSync() {
        val march = ZonedDateTime.of(2026, 3, 18, 13, 33, 9, 0, ZoneOffset.ofHours(1))
        assertEquals("02011a03120d2109030f", OcleanProtocol.timeSyncCommand(march).toHex())
        val october = ZonedDateTime.of(2026, 10, 21, 8, 5, 30, 0, ZoneOffset.ofHours(3))
        assertEquals("02011a0a1508051e0311", OcleanProtocol.timeSyncCommand(october).toHex())
    }

    @Test
    fun tzIndexTable() {
        assertEquals(14, OcleanProtocol.tzIndex(0))
        assertEquals(17, OcleanProtocol.tzIndex(180))
        assertEquals(22, OcleanProtocol.tzIndex(330))
        assertEquals(180, OcleanProtocol.tzOffset(17))
        assertNull(OcleanProtocol.tzOffset(0))
    }

    @Test
    fun statusBattery() {
        assertEquals(29, OcleanProtocol.parseStatusBattery(hex("0303020e4b1d0000")))
        assertEquals(51, OcleanProtocol.parseStatusBattery(hex("0303020f00330000")))
        assertEquals(45, OcleanProtocol.parseStatusBattery(hex("0303020ea72d0100")))
        assertNull(OcleanProtocol.parseStatusBattery(hex("02024f4b")))
    }

    @Test
    fun ack() {
        assertTrue(OcleanProtocol.isAck(hex("02024f4b"), OcleanProtocol.CMD_ACK_SESSIONS))
        assertFalse(OcleanProtocol.isAck(hex("02014f4b"), OcleanProtocol.CMD_ACK_SESSIONS))
    }

    @Test
    fun xProEliteRecordWithLowScore() {
        val a = assemble(
            "03072a422300011a03120d2109000078000b6400",
            "000000000f001d38010e0f0d261001000d010000",
            "0100ffffffffffffff",
        )
        assertTrue(a.isComplete)
        val s = OcleanProtocol.decodeRecord(a.records().single(), 14)!!
        assertEquals(1773837189L, s.epochSec)
        assertEquals(epoch(2026, 3, 18, 13, 33, 9, 1), s.epochSec)
        assertEquals(60, s.tzOffsetMin)
        assertEquals(11, s.durationSec)
        assertEquals(120, s.plannedSec)
        assertEquals(0, s.schemeId)
        assertEquals(1, s.score)
        assertEquals(listOf(100, 0, 0, 0, 0), s.pressure)
        assertEquals(listOf(15, 13, 38, 16, 1, 0, 13, 1), s.zones)
    }

    @Test
    fun xProEliteRecordWithHighScore() {
        val a = assemble(
            "03072a422300011a03161722234c009600960001",
            "041e00000f00141f171a050e0f0d080f0c0e0000",
            "6200ffffffffffffff",
        )
        val s = OcleanProtocol.decodeRecord(a.records().single(), 14)!!
        assertEquals(1774218875L, s.epochSec)
        assertEquals(76, s.schemeId)
        assertEquals(150, s.durationSec)
        assertEquals(98, s.score)
        assertEquals(listOf(0, 1, 4, 30, 0), s.pressure)
        assertEquals(listOf(5, 14, 15, 13, 8, 15, 12, 14), s.zones)
    }

    @Test
    fun xProRecordInSummerTime() {
        val a = assemble(
            "03072a422300011a060700322b00007800780702",
            "5a0000001000171b151d090e0d0d0c090e0d0000",
            "643cffffffffffff10",
        )
        val s = OcleanProtocol.decodeRecord(a.records().single(), 14)!!
        assertEquals(1780786243L, s.epochSec)
        assertEquals(120, s.tzOffsetMin)
        assertEquals(100, s.score)
        assertEquals(listOf(9, 14, 13, 13, 12, 9, 14, 13), s.zones)
    }

    @Test
    fun surplusBytesOfNextRecordAreIgnored() {
        val a = assemble(
            "03072a422300011a02181535134c009600960b03",
            "540000000f000823172208001111070f0f110000",
            "5a00ffffffffffffff1a02180018134c00960096",
        )
        assertTrue(a.isComplete)
        val records = a.records()
        assertEquals(1, records.size)
        val s = OcleanProtocol.decodeRecord(records.single(), 14)!!
        assertEquals(1771966399L, s.epochSec)
        assertEquals(90, s.score)
        assertEquals(listOf(11, 3, 84, 0, 0), s.pressure)
        assertEquals(listOf(8, 0, 17, 17, 7, 15, 15, 17), s.zones)
    }

    @Test
    fun recordInFarEastZone() {
        val a = assemble(
            "03072a422300011a030b14021700007800780514",
            "4b0000001f00000000001c14190c0a0a0a000000",
            "5b030301ffffffffff1a0306071e0f0000780078",
        )
        val s = OcleanProtocol.decodeRecord(a.records().single(), 14)!!
        assertEquals(epoch(2026, 3, 11, 20, 2, 23, 11), s.epochSec)
        assertEquals(91, s.score)
        assertEquals(listOf(28, 20, 25, 12, 10, 10, 10, 0), s.zones)
    }

    @Test
    fun recordFromUnsetClockIsDropped() {
        val a = assemble(
            "03072a4223000100081b00212800007800780000",
            "021f00000f000f211123010d120f010f0f120000",
            "5100ffffffffffffff00080d0038320000780078",
        )
        assertEquals(1, a.records().size)
        assertNull(OcleanProtocol.decodeRecord(a.records().single(), 15))
    }

    @Test
    fun incompleteStreamYieldsNoRecord() {
        val a = assemble("03072a422300011a03161722234c009600960001", "041e00000f00141f171a050e0f0d080f0c0e0000")
        assertFalse(a.isComplete)
        assertTrue(a.records().isEmpty())
    }

    @Test
    fun noNewRecordsHeaderCarriesNewestSessionHead() {
        val a = assemble("03072a422300001a02150f2a134d009600962704")
        assertTrue(a.isComplete)
        assertTrue(a.records().isEmpty())
        val s = OcleanProtocol.decodeInline(a.inline!!, 15)!!
        assertEquals(epoch(2026, 2, 21, 15, 42, 19, 1), s.epochSec)
        assertEquals(77, s.schemeId)
        assertEquals(150, s.durationSec)
        assertNull(s.score)
    }

    @Test
    fun settingsFramedAcrossNotifications() {
        val data = ByteArray(34)
        data[11] = 76
        data[22] = 1
        data[25] = 0; data[26] = 90
        data[27] = 0; data[28] = 12
        data[29] = 0; data[30] = 25
        data[31] = 7
        val payload = hex("0302") + byteArrayOf('#'.code.toByte(), (data.size + 2).toByte()) + data
        val asm = OcleanProtocol.SettingsAssembler()
        assertNull(asm.feed(payload.copyOfRange(0, 20)))
        assertNull(asm.feed(payload.copyOfRange(20, 30)))
        val s = asm.feed(payload.copyOfRange(30, payload.size))
        assertNotNull(s)
        s!!
        assertEquals(76, s.schemeId)
        assertTrue(s.overPressureAlert)
        assertFalse(s.areaRemind)
        assertEquals(90, s.headMaxTime)
        assertEquals(12, s.headUsedDays)
        assertEquals(25, s.headUsedTimes)
        assertEquals(7, s.language)
    }

    @Test
    fun hexRoundTrip() {
        assertArrayEquals(byteArrayOf(0x03, 0x07, 0x2a), hex("03072a"))
    }
}
