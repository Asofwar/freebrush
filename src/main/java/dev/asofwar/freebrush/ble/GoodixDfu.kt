package dev.asofwar.freebrush.ble

import java.util.UUID

/**
 * Goodix GR5xx DFU ("GR5xxxDfu2"), the firmware update of brushes whose hardware revision ends in OTA type `0004`
 * (`HH 0003 0004`: X Pro Digital on a Goodix GR551x). Read from the official app's SDK and written down in
 * oclean-re/firmware/README.md; every multi-byte number is little-endian.
 *
 * A frame is `47 44 | opcode u16 | length u16 | data | checksum u16`, the checksum being the sum of the opcode,
 * length and data bytes. The phone writes frames to [RX] in MTU-3 sized pieces; the brush answers on [TX]
 * notifications, which are glued together by the length in the header.
 */
object GoodixDfu {
    val SERVICE: UUID = UUID.fromString("a6ed0401-d344-460a-8075-b9e8ec90d71b")

    /** Brush to phone (notify). */
    val TX: UUID = UUID.fromString("a6ed0402-d344-460a-8075-b9e8ec90d71b")

    /** Phone to brush (write). */
    val RX: UUID = UUID.fromString("a6ed0403-d344-460a-8075-b9e8ec90d71b")

    /** Control point; only "app bootloader" chips (indicate on it) use it, X Pro Digital does not. */
    val CTRL: UUID = UUID.fromString("a6ed0404-d344-460a-8075-b9e8ec90d71b")

    const val MAGIC = 0x4744

    const val OP_CHIP_INFO = 0x01
    const val OP_PROGRAM_START = 0x23
    const val OP_PROGRAM_FLASH = 0x24
    const val OP_PROGRAM_END = 0x25
    const val OP_SYSTEM_INFO = 0x27

    /** The only chip FreeBrush has read and planned an update on: GR5515 in the X Pro Digital. */
    const val GR5515 = 2952

    /** End of the GR5515's 1 MB flash. */
    const val FLASH_END = 0x01100000

    /** Where the new image is written: the second bank. The bootloader checks it and copies it to its run address. */
    const val STAGING_ADDRESS = 0x01050000

    /** Bytes of image data in one program frame. */
    const val CHUNK = 1024

    /** Whether a brush with this hardware revision updates through this DFU (OTA type `0004` at the end). */
    fun supports(hardware: String?): Boolean = hardware?.trim()?.endsWith("0004") == true

    /** System configuration area: depends on the chip. */
    fun scaAddress(chipId: Int): Int = if (chipId == 2952 || chipId == 7848) 0x01000000 else 0x00200000

    fun frame(opcode: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(8 + data.size)
        put16(out, 0, MAGIC)
        put16(out, 2, opcode)
        put16(out, 4, data.size)
        data.copyInto(out, 6)
        var sum = 0
        for (i in 2 until 6 + data.size) sum += out[i].toInt() and 0xFF
        put16(out, 6 + data.size, sum)
        return out
    }

    /** A parsed answer: its opcode and data, after the header and checksum were checked. */
    class Reply(val opcode: Int, val data: ByteArray) {
        /** First data byte: 1 means success for every command. */
        val status: Int get() = data.firstOrNull()?.toInt()?.and(0xFF) ?: -1
    }

    /**
     * Glues TX notifications into replies. [feed] returns a reply once a whole frame arrived, or null while one is
     * still incomplete; a frame with a bad header or checksum throws.
     */
    class Assembler {
        private var buf = ByteArray(0)

        fun feed(chunk: ByteArray): Reply? {
            buf += chunk
            if (buf.size < 6) return null
            require(get16(buf, 0) == MAGIC) { "DFU: неверный заголовок ответа ${buf.take(6).joinToString("") { "%02x".format(it) }}" }
            val length = get16(buf, 4)
            if (buf.size < 8 + length) return null
            var sum = 0
            for (i in 2 until 6 + length) sum += buf[i].toInt() and 0xFF
            require(get16(buf, 6 + length) == sum and 0xFFFF) { "DFU: контрольная сумма ответа не сошлась" }
            val reply = Reply(get16(buf, 2), buf.copyOfRange(6, 6 + length))
            buf = buf.copyOfRange(8 + length, buf.size)
            return reply
        }
    }

    /** Fields of an image info block (40 bytes) as the chip and the firmware file store them. */
    data class ImageInfo(
        val pattern: Int,
        val version: Int,
        val binSize: Int,
        val checksum: Int,
        val loadAddress: Int,
        val runAddress: Int,
        val xqspiCmd: Int,
        val flags: Int,
        val comment: String,
    ) {
        fun toBytes(loadAt: Int = loadAddress): ByteArray {
            val out = ByteArray(40)
            put16(out, 0, pattern)
            put16(out, 2, version)
            put32(out, 4, binSize)
            put32(out, 8, checksum)
            put32(out, 12, loadAt)
            put32(out, 16, runAddress)
            put32(out, 20, xqspiCmd)
            put32(out, 24, flags)
            comment.toByteArray(Charsets.US_ASCII).take(12).forEachIndexed { i, b -> out[28 + i] = b }
            return out
        }

        companion object {
            fun parse(b: ByteArray, at: Int): ImageInfo = ImageInfo(
                pattern = get16(b, at),
                version = get16(b, at + 2),
                binSize = get32(b, at + 4),
                checksum = get32(b, at + 8),
                loadAddress = get32(b, at + 12),
                runAddress = get32(b, at + 16),
                xqspiCmd = get32(b, at + 20),
                flags = get32(b, at + 24),
                comment = b.copyOfRange(at + 28, at + 40).takeWhile { it != 0.toByte() && it != 0xFF.toByte() }
                    .toByteArray().toString(Charsets.US_ASCII),
            )
        }
    }

    /** A firmware file: the whole file is sent; [info] describes it, [sum] goes into the program-end frame. */
    class Firmware(val bytes: ByteArray, val info: ImageInfo, val sum: Int, val encrypted: Boolean, val signed: Boolean)

    /**
     * Reads a firmware file as the SDK does: the image info is in the last 48 bytes of a plain image, or 904 bytes
     * before the end of a signed one. Checks what can be checked without the chip: the info's size and checksum
     * against the data.
     */
    fun parseFirmware(bytes: ByteArray): Firmware {
        require(bytes.size > 904) { "Файл прошивки слишком мал" }
        val plain = get16(bytes, bytes.size - 48) == MAGIC
        val at = if (plain) bytes.size - 48 else bytes.size - 904
        require(get16(bytes, at) == MAGIC) { "В файле нет описания образа" }
        val signed = !plain
        val encrypted = signed && get32(bytes, bytes.size - 784) != 0x4E47_4953 // "SIGN": signed but not encrypted
        val info = ImageInfo.parse(bytes, at)
        if (plain) {
            require(info.binSize == bytes.size - 48) { "Размер образа ${info.binSize} не совпадает с файлом ${bytes.size - 48}" }
            var dataSum = 0L
            for (i in 0 until info.binSize) dataSum += bytes[i].toInt() and 0xFF
            require((dataSum and 0xFFFFFFFFL).toInt() == info.checksum) { "Контрольная сумма образа не сошлась" }
        }
        var sum = 0
        for (b in bytes) sum += b.toInt() and 0xFF
        return Firmware(bytes, info, sum, encrypted, signed)
    }

    fun get16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    fun get32(b: ByteArray, at: Int): Int = get16(b, at) or (get16(b, at + 2) shl 16)

    fun put16(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte()
        b[at + 1] = (v shr 8).toByte()
    }

    fun put32(b: ByteArray, at: Int, v: Int) {
        put16(b, at, v)
        put16(b, at + 2, v shr 16)
    }

    /** Data of a system-info read: mode 0 (read), [address], [length]. */
    fun systemRead(address: Int, length: Int): ByteArray = ByteArray(7).also {
        it[0] = 0
        put32(it, 1, address)
        put16(it, 5, length)
    }
}
