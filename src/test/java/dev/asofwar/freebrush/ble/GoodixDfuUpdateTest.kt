package dev.asofwar.freebrush.ble

import dev.asofwar.freebrush.ble.GoodixDfu.ImageInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The update against a simulated GR5515 that answers like the user's brush did on 2026-10-21 (chip 2952, SCA at
 * 0x01000000, boot info and list entry of `Y3L_AS_MBV05` at 0x01010000). Frames go through the real encoder,
 * checksum and reassembly, cut into 20-byte notifications.
 */
class GoodixDfuUpdateTest {
    private val running = image(236336, comment = "Y3L_AS_MBV05")

    /** A plain firmware file as Oclean ships it: data, then a 48-byte trailer with the image info. */
    private fun firmwareFile(size: Int, comment: String = "Y3L_AS_MBV05", runAddress: Int = 0x01010000, seed: Int = 7): ByteArray {
        val data = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }
        var sum = 0L
        for (b in data) sum += b.toInt() and 0xFF
        val info = ImageInfo(GoodixDfu.MAGIC, 1, size, sum.toInt(), 0x01010000, runAddress, 0x0b, 0x344, comment)
        return data + info.toBytes() + ByteArray(8)
    }

    private fun image(size: Int, checksum: Int = 0x015da560, comment: String) =
        ImageInfo(GoodixDfu.MAGIC, 1, size, checksum, 0x01010000, 0x01010000, 0x0b, 0x344, comment)

    /** Flash and the DFU commands of the chip; [failAt] makes that program frame answer status 0. */
    private class Chip(
        boot: ImageInfo,
        list: List<ImageInfo>,
        val failAt: Int = -1,
        val answerEnd: Boolean = true,
        /** The link drops while the end frame is written: the chip got it, the phone sees an error. */
        val dropOnEnd: Boolean = false,
        val chipId: Int = 2952,
    ) : DfuTransport {
        val flash = ByteArray(0x100000) { 0xFF.toByte() }
        private val replies = ArrayDeque<ByteArray>()
        private val assembler = GoodixDfu.Assembler()
        var start: ImageInfo? = null
        var programFrames = 0
        var listWrites = 0
        var ended = false

        init {
            boot.toBytes().copyInto(flash, 0, 4, 28) // the 24-byte boot info: size .. flags
            list.forEachIndexed { i, img -> img.toBytes().copyInto(flash, 64 + i * 40) }
        }

        val boot: ImageInfo get() = ImageInfo(GoodixDfu.MAGIC, 1, get32(0), get32(4), get32(8), get32(12), get32(16), get32(20), "boot")

        private fun get32(at: Int) = GoodixDfu.get32(flash, at)
        private fun off(address: Int) = address - 0x01000000

        override suspend fun send(opcode: Int, data: ByteArray) {
            // Decode as the chip would: header, length and checksum of the frame the phone built.
            val frame = GoodixDfu.frame(opcode, data)
            val got = GoodixDfu.Assembler().feed(frame)!!
            val reply = handle(got.opcode, got.data)
            if (dropOnEnd && opcode == GoodixDfu.OP_PROGRAM_END) throw GattException("Соединение потеряно")
            reply?.let { replies.addLast(GoodixDfu.frame(got.opcode, it)) }
        }

        override suspend fun receive(opcode: Int, timeoutMs: Long): GoodixDfu.Reply? {
            val frame = replies.removeFirstOrNull() ?: return null
            var r: GoodixDfu.Reply? = null
            for (at in frame.indices step 20) r = assembler.feed(frame.copyOfRange(at, minOf(at + 20, frame.size)))
            return r!!.also { assertEquals(opcode, it.opcode) }
        }

        private fun handle(opcode: Int, d: ByteArray): ByteArray? = when (opcode) {
            GoodixDfu.OP_CHIP_INFO -> ByteArray(18).also {
                it[0] = 1
                GoodixDfu.put32(it, 5, chipId)
            }
            GoodixDfu.OP_SYSTEM_INFO -> {
                val address = GoodixDfu.get32(d, 1)
                val length = GoodixDfu.get16(d, 5)
                if (d[0].toInt() == 0) {
                    val out = ByteArray(8 + length)
                    out[0] = 1
                    GoodixDfu.put32(out, 2, address)
                    GoodixDfu.put16(out, 6, length)
                    flash.copyInto(out, 8, off(address), off(address) + length)
                    out
                } else {
                    assertEquals(0x01000040, address)
                    d.copyInto(flash, off(address), 7, 7 + length)
                    listWrites++
                    byteArrayOf(1)
                }
            }
            GoodixDfu.OP_PROGRAM_START -> {
                assertEquals(0, d[0].toInt())
                start = ImageInfo.parse(d, 1)
                byteArrayOf(1)
            }
            GoodixDfu.OP_PROGRAM_FLASH -> {
                assertEquals(1, d[0].toInt())
                val address = GoodixDfu.get32(d, 1)
                val length = GoodixDfu.get16(d, 5)
                assertTrue(length in 1..GoodixDfu.CHUNK)
                assertTrue("writes only into the second bank", address >= GoodixDfu.STAGING_ADDRESS)
                d.copyInto(flash, off(address), 7, 7 + length)
                if (programFrames++ == failAt) byteArrayOf(0) else byteArrayOf(1)
            }
            GoodixDfu.OP_PROGRAM_END -> {
                val s = start!!
                val total = s.binSize + 48
                var sum = 0
                for (i in 0 until total) sum += flash[off(GoodixDfu.STAGING_ADDRESS) + i].toInt() and 0xFF
                val ok = sum == GoodixDfu.get32(d, 1)
                if (ok) {
                    // Take the new image: copy it to its run address and point the boot info at it.
                    flash.copyInto(flash, off(s.runAddress), off(GoodixDfu.STAGING_ADDRESS), off(GoodixDfu.STAGING_ADDRESS) + s.binSize)
                    s.copy(loadAddress = s.runAddress).toBytes().copyInto(flash, 0, 4, 28)
                    ended = true
                }
                if (answerEnd) byteArrayOf(if (ok) 1 else 0) else null
            }
            else -> throw AssertionError("unexpected opcode $opcode")
        }
    }

    @Test
    fun readsTheStateTheBrushReported() = runTest {
        val chip = Chip(running, listOf(running))
        val state = GoodixDfuUpdate.readState(chip) {}
        assertEquals(2952, state.chipId)
        assertEquals(0x01000000, state.sca)
        assertFalse(state.encrypted)
        assertEquals(236336, state.boot.binSize)
        assertEquals(0x015da560, state.boot.checksum)
        assertEquals("Y3L_AS_MBV05", state.running?.comment)
    }

    @Test
    fun aMatchingFileIsPlannedLikeTheOfficialApp() = runTest {
        val fw = GoodixDfu.parseFirmware(firmwareFile(236336))
        val state = GoodixDfuUpdate.readState(Chip(running, listOf(running))) {}
        val plan = GoodixDfuUpdate.plan(state, fw, trusted = false)
        // The new image covers the old entry: the list is written back empty.
        assertEquals(emptyList<ImageInfo>(), plan.list)
        assertEquals(GoodixDfu.STAGING_ADDRESS, plan.start.loadAddress)
        assertEquals(fw.info.copy(loadAddress = GoodixDfu.STAGING_ADDRESS), plan.start)
    }

    @Test
    fun filesThatDoNotFitAreRefusedBeforeAnyWrite() = runTest {
        val state = GoodixDfuUpdate.readState(Chip(running, listOf(running))) {}
        fun refused(bytes: ByteArray, trusted: Boolean = true, s: GoodixDfuUpdate.ChipState = state) {
            val fw = GoodixDfu.parseFirmware(bytes)
            try {
                GoodixDfuUpdate.plan(s, fw, trusted)
                fail("planned")
            } catch (_: IllegalArgumentException) {
            }
        }
        refused(firmwareFile(236336, comment = "K3_OTHER"))
        refused(firmwareFile(236336, runAddress = 0x01020000))
        // Too big for the room before the second bank: where it runs would reach into where it is written.
        refused(firmwareFile(0x50000))
        // A chip that does not name its program takes only the server's file for it.
        val unnamed = GoodixDfuUpdate.readState(Chip(running, emptyList())) {}
        refused(firmwareFile(236336), trusted = false, s = unnamed)
    }

    @Test
    fun anUnnamedChipKeepsItsRunningImageListed() = runTest {
        val other = running.copy(loadAddress = 0x010C0000, binSize = 4096, checksum = 5, comment = "DATA")
        val state = GoodixDfuUpdate.readState(Chip(running, listOf(other))) {}
        val plan = GoodixDfuUpdate.plan(state, GoodixDfu.parseFirmware(firmwareFile(236336)), trusted = true)
        val list = plan.list!!
        assertEquals(listOf("fromBoot", "DATA"), list.map { it.comment })
        assertEquals(running.checksum, list[0].checksum)
    }

    @Test
    fun installsAndTheChipBootsTheNewImage() = runTest {
        val bytes = firmwareFile(236336, seed = 3)
        val fw = GoodixDfu.parseFirmware(bytes)
        val chip = Chip(running, listOf(running))
        val state = GoodixDfuUpdate.readState(chip) {}
        var last = 0 to 0
        val answered = GoodixDfuUpdate.flash(chip, GoodixDfuUpdate.plan(state, fw, false), { a, b -> last = a to b }) {}
        assertTrue(answered)
        assertTrue(chip.ended)
        assertEquals(1, chip.listWrites)
        assertEquals((bytes.size + 1023) / 1024, chip.programFrames)
        assertEquals(bytes.size to bytes.size, last)
        assertArrayEquals(bytes, chip.flash.copyOfRange(0x50000, 0x50000 + bytes.size))
        assertTrue(GoodixDfuUpdate.installed(GoodixDfuUpdate.readState(chip) {}, fw))
        assertArrayEquals(bytes.copyOfRange(0, fw.info.binSize), chip.flash.copyOfRange(0x10000, 0x10000 + fw.info.binSize))
    }

    @Test
    fun aLinkDroppedOnTheEndFrameIsLeftToTheCheck() = runTest {
        val fw = GoodixDfu.parseFirmware(firmwareFile(100_000))
        val chip = Chip(running, listOf(running), dropOnEnd = true)
        val plan = GoodixDfuUpdate.plan(GoodixDfuUpdate.readState(chip) {}, fw, false)
        assertFalse(GoodixDfuUpdate.flash(chip, plan, { _, _ -> }) {})
        assertTrue(GoodixDfuUpdate.installed(GoodixDfuUpdate.readState(chip) {}, fw))
    }

    @Test
    fun aBrokenInstallCanBeRetriedWithTheRememberedName() = runTest {
        val bytes = firmwareFile(236336, seed = 5)
        val fw = GoodixDfu.parseFirmware(bytes)
        val chip = Chip(running, listOf(running), failAt = 10)
        val before = GoodixDfuUpdate.readState(chip) {}
        val name = before.running!!.comment
        try {
            GoodixDfuUpdate.flash(chip, GoodixDfuUpdate.plan(before, fw, false), { _, _ -> }) {}
            fail("flashed")
        } catch (_: GattException) {
        }
        // The list was rewritten without the running image: the chip no longer names it.
        val after = GoodixDfuUpdate.readState(chip) {}
        assertEquals(null, after.running)
        assertEquals(running.checksum, after.boot.checksum)
        try {
            GoodixDfuUpdate.plan(after, fw, trusted = false)
            fail("planned without a name")
        } catch (_: IllegalArgumentException) {
        }
        try {
            GoodixDfuUpdate.plan(after, GoodixDfu.parseFirmware(firmwareFile(236336, comment = "K3_OTHER")), false, knownImage = name)
            fail("planned another brush's file")
        } catch (_: IllegalArgumentException) {
        }
        val retry = Chip(running, emptyList())
        val plan = GoodixDfuUpdate.plan(GoodixDfuUpdate.readState(retry) {}, fw, trusted = false, knownImage = name)
        assertTrue(GoodixDfuUpdate.flash(retry, plan, { _, _ -> }) {})
        assertTrue(GoodixDfuUpdate.installed(GoodixDfuUpdate.readState(retry) {}, fw))
    }

    @Test
    fun onlyTheGr5515IsUpdated() = runTest {
        val state = GoodixDfuUpdate.readState(Chip(running, listOf(running), chipId = 7848)) {}
        try {
            GoodixDfuUpdate.plan(state, GoodixDfu.parseFirmware(firmwareFile(236336)), trusted = true)
            fail("planned on another chip")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("7848"))
        }
    }

    @Test
    fun aRestartBeforeTheEndAnswerIsAccepted() = runTest {
        val fw = GoodixDfu.parseFirmware(firmwareFile(100_000))
        val chip = Chip(running, listOf(running), answerEnd = false)
        val plan = GoodixDfuUpdate.plan(GoodixDfuUpdate.readState(chip) {}, fw, false)
        assertFalse(GoodixDfuUpdate.flash(chip, plan, { _, _ -> }) {})
        assertTrue(chip.ended)
    }

    @Test
    fun aRefusedFrameStopsTheWriteAndLeavesTheBootInfo() = runTest {
        val fw = GoodixDfu.parseFirmware(firmwareFile(236336, seed = 9))
        val chip = Chip(running, listOf(running), failAt = 5)
        val plan = GoodixDfuUpdate.plan(GoodixDfuUpdate.readState(chip) {}, fw, false)
        try {
            GoodixDfuUpdate.flash(chip, plan, { _, _ -> }) {}
            fail("flashed")
        } catch (e: GattException) {
            assertNotNull(e.message)
        }
        assertEquals(6, chip.programFrames)
        assertFalse(chip.ended)
        assertEquals(running.checksum, chip.boot.checksum)
        assertEquals(running.loadAddress, chip.boot.loadAddress)
    }

    @Test
    fun theListWriteIsFilledWithFF() {
        val data = GoodixDfuUpdate.listWrite(0x01000000, listOf(running))
        assertEquals(407, data.size)
        assertEquals(1, data[0].toInt())
        assertEquals(0x01000040, GoodixDfu.get32(data, 1))
        assertEquals(400, GoodixDfu.get16(data, 5))
        assertArrayEquals(running.toBytes(), data.copyOfRange(7, 47))
        assertTrue(data.copyOfRange(47, 407).all { it == 0xFF.toByte() })
    }

    @Test
    fun rangesOverlapAsUnsigned() {
        assertTrue(GoodixDfuUpdate.overlaps(0x01010000, 0x40000, 0x01040000, 16))
        assertFalse(GoodixDfuUpdate.overlaps(0x01010000, 0x39b30, 0x01050000, 0x39b60))
        assertFalse(GoodixDfuUpdate.overlaps(0x01050000, 0x39b60, 0x01000000, GoodixDfuUpdate.SCA_SIZE))
        assertTrue(GoodixDfuUpdate.overlaps(0xFFFFF000.toInt(), 0x2000, 0xFFFFF800.toInt(), 16))
    }

    @Test
    fun onlyOtaType0004Updates() {
        assertTrue(GoodixDfu.supports("HH 0003 0004"))
        assertFalse(GoodixDfu.supports("HH 0002 0001"))
        assertFalse(GoodixDfu.supports(null))
    }
}
