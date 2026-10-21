package dev.asofwar.freebrush.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoodixDfuTest {
    @Test
    fun chipInfoFrame() {
        // 47 44 | 01 00 | 00 00 | checksum 01 00
        assertEquals("4447010000000100", GoodixDfu.frame(GoodixDfu.OP_CHIP_INFO).toHex())
    }

    @Test
    fun systemReadFrame() {
        val f = GoodixDfu.frame(GoodixDfu.OP_SYSTEM_INFO, GoodixDfu.systemRead(0x01000000, 24))
        assertEquals("44472700070000000000011800", f.toHex().substring(0, 26))
        assertEquals(15, f.size)
    }

    @Test
    fun repliesAreGluedAcrossNotifications() {
        val reply = GoodixDfu.frame(0x27, byteArrayOf(1, 2, 3, 4, 5))
        val a = GoodixDfu.Assembler()
        assertNull(a.feed(reply.copyOfRange(0, 4)))
        val r = a.feed(reply.copyOfRange(4, reply.size))!!
        assertEquals(0x27, r.opcode)
        assertEquals(1, r.status)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), r.data)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBadChecksumIsRefused() {
        val reply = GoodixDfu.frame(0x01, byteArrayOf(1))
        reply[reply.size - 1] = 0x7F
        GoodixDfu.Assembler().feed(reply)
    }

    @Test
    fun imageTrailerOfTheBrushFirmware() {
        // Last 48 bytes of OCLEANY3PB 1.0.2.5 (oclean-re/firmware).
        val tail = hex("44470100309b030060a55d0100000101000001010b0000004403000059334c5f41535f4d425630350000000000000000")
        val info = GoodixDfu.ImageInfo.parse(tail, 0)
        assertEquals(0x39b30, info.binSize)
        assertEquals(0x015da560, info.checksum)
        assertEquals(0x01010000, info.loadAddress)
        assertEquals("Y3L_AS_MBV05", info.comment)
        // Rewritten for the second bank, the rest unchanged.
        val staged = info.toBytes(loadAt = GoodixDfu.STAGING_ADDRESS)
        assertEquals(GoodixDfu.STAGING_ADDRESS, GoodixDfu.get32(staged, 12))
        assertArrayEquals(tail.copyOfRange(0, 12), staged.copyOfRange(0, 12))
        assertArrayEquals(tail.copyOfRange(16, 40), staged.copyOfRange(16, 40))
    }
}
