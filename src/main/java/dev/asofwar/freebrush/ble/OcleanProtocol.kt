package dev.asofwar.freebrush.ble

import dev.asofwar.freebrush.data.BrushSession
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.math.abs

/**
 * Wire format of the Oclean X Pro family (X Pro Digital `OCLEANY3PB`, X Pro Elite `OCLEANY3P`).
 *
 * Pure functions only, so everything here is covered by unit tests with packets captured from real brushes.
 * All multi-byte integers are big-endian.
 */
object OcleanProtocol {
    val SERVICE: UUID = UUID.fromString("8082caa8-41a6-4021-91c6-56f9b954cc18")

    /** Command channel: every write except the session query. */
    val WRITE: UUID = UUID.fromString("9d84b9a3-000c-49d8-9183-855b673fbb85")

    /** Replies to commands: acks, status, settings. */
    val NOTIFY: UUID = UUID.fromString("5f78df94-798c-46f5-990a-855b673fbb86")

    /** Write-only channel for the session query; never subscribe to it. */
    val SESSION_QUERY: UUID = UUID.fromString("5f78df94-798c-46f5-990a-855b673fbb89")

    /** Session record stream. */
    val SESSION_STREAM: UUID = UUID.fromString("5f78df94-798c-46f5-990a-855b673fbb90")

    val DIS_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val DIS_MODEL: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val DIS_FIRMWARE: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val DIS_HARDWARE: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
    val DIS_SOFTWARE: UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    val CMD_STATUS = hex("0303")
    val CMD_SETTINGS = hex("030201")
    val CMD_SESSIONS = hex("0307")

    /** Tells the brush the delivered sessions are stored, after which it may delete them. */
    val CMD_ACK_SESSIONS = hex("0202")

    const val RECORD_SIZE = 42

    /**
     * Pressure buckets of brushes without a pressure log: X Pro Digital and Oclean X send this same value in every
     * record, so it carries no information.
     */
    val PLACEHOLDER_PRESSURE = listOf(5, 20, 75, 0, 0)
    private val STREAM_HEADER = hex("03072a4223") // "0307" + ASCII "*B#"

    /** UTC offsets in minutes; the brush stores a 1-based index into this table. */
    val TZ_OFFSETS = intArrayOf(
        -720, -660, -600, -540, -480, -420, -360, -300, -240, -210, -180, -120, -60, 0,
        60, 120, 180, 210, 240, 270, 300, 330, 345, 360, 390, 420, 480, 540, 570, 600, 660, 720, 780,
    )

    /** 1-based table index of the offset closest to [offsetMin]. */
    fun tzIndex(offsetMin: Int): Int = TZ_OFFSETS.indices.minBy { abs(TZ_OFFSETS[it] - offsetMin) } + 1

    fun tzOffset(index: Int): Int? = TZ_OFFSETS.getOrNull(index - 1)

    /** `0201` time sync: year-2000, month, day, hour, minute, second, weekday (Sunday = 0), time-zone index. */
    fun timeSyncCommand(now: ZonedDateTime): ByteArray = byteArrayOf(
        0x02, 0x01,
        (now.year - 2000).coerceAtLeast(0).toByte(),
        now.monthValue.toByte(),
        now.dayOfMonth.toByte(),
        now.hour.toByte(),
        now.minute.toByte(),
        now.second.toByte(),
        (now.dayOfWeek.value % 7).toByte(),
        tzIndex(now.offset.totalSeconds / 60).toByte(),
    )

    /**
     * Hardware revision: either plain text, or ASCII "HH" followed by binary protocol and OTA codes,
     * shown here as hex (for example "HH 0013 0002").
     */
    fun formatHardware(raw: ByteArray): String? {
        if (raw.size >= 6 && raw[0] == 'H'.code.toByte() && raw[1] == 'H'.code.toByte()) {
            return "HH " + raw.copyOfRange(2, 4).toHex() + " " + raw.copyOfRange(4, raw.size).toHex()
        }
        return raw.toString(Charsets.US_ASCII).trimEnd('\u0000', ' ').ifEmpty { null }
    }

    /**
     * Ack of a command: the command id, optionally the value that was written (the X Pro Digital echoes it,
     * e.g. `0209ec4f4b`), and ASCII "OK" at the end.
     */
    fun isAck(packet: ByteArray, command: ByteArray): Boolean =
        packet.size in 4..8 && packet[0] == command[0] && packet[1] == command[1] &&
            packet[packet.size - 2] == 'O'.code.toByte() && packet[packet.size - 1] == 'K'.code.toByte()

    fun hasType(packet: ByteArray, type: ByteArray): Boolean =
        packet.size >= 2 && packet[0] == type[0] && packet[1] == type[1]

    /** Battery percent from a `0303` status reply, or null if the packet is not one. */
    fun parseStatusBattery(packet: ByteArray): Int? {
        if (!hasType(packet, CMD_STATUS) || packet.size < 6) return null
        return packet.u8(5).takeIf { it in 0..100 }
    }

    /**
     * The byte of the status reply that steps up when the brush stores a session record (`03 03 02 0e7b 27 11 00`:
     * 0x11 before a brushing, 0x12 once it was saved; seen on an X Pro Digital, 2026-10-21).
     */
    fun parseStatusRecords(packet: ByteArray): Int? {
        if (!hasType(packet, CMD_STATUS) || packet.size < 7) return null
        return packet.u8(6)
    }

    class Settings(
        /** The 34 data bytes as received, kept so newly understood fields can be read from stored settings. */
        val raw: ByteArray,
        val schemeId: Int,
        val overPressureAlert: Boolean,
        val areaRemind: Boolean,
        /** Days since the brush head was last reset. */
        val headUsedDays: Int,
        /** Sessions since the brush head was last reset. */
        val headUsedTimes: Int,
        /** Brush-head wear counter and its limit; the unit is not known yet (minutes are likely). */
        val headUsedTime: Int,
        val headMaxTime: Int,
        val language: Int,
    )

    /**
     * Collects the `0302` settings reply. Every notification starts with the `03 02` type; the first one then
     * carries `'#' L`, where L-2 is the data length. A continuation that arrives without its header (seen right
     * after a programme write, when the brush dropped the first fragment) is ignored instead of being misread as
     * the start of the data; a new header always starts over.
     */
    class SettingsAssembler {
        private var buffer: ByteArray? = null
        private var expected = 0

        /** Returns the settings once the reply is complete; null while more bytes are needed or the packet is unrelated. */
        fun feed(packet: ByteArray): Settings? {
            if (!hasType(packet, TYPE_SETTINGS)) return null
            val body = packet.copyOfRange(2, packet.size)
            val current = buffer
            val header = body.size >= 2 && body[0] == '#'.code.toByte() && body.u8(1) - 2 in SETTINGS_SIZE..64
            if (header) {
                expected = body.u8(1) - 2
                buffer = body.copyOfRange(2, body.size)
            } else if (current != null) {
                buffer = current + body
            } else {
                return null
            }
            val data = buffer!!
            if (data.size < expected || data.size < SETTINGS_SIZE) return null
            buffer = null
            return decodeSettings(data)
        }
    }

    private const val SETTINGS_SIZE = 34
    private val TYPE_SETTINGS = hex("0302")

    fun decodeSettings(d: ByteArray): Settings = Settings(
        raw = d.copyOf(SETTINGS_SIZE),
        schemeId = d.u8(11),
        overPressureAlert = d.u8(22) != 0,
        areaRemind = d.u8(23) != 0,
        headUsedTime = d.u16(14),
        headMaxTime = d.u16(25),
        headUsedDays = d.u16(27),
        headUsedTimes = d.u16(29),
        language = d.u8(31),
    )

    /**
     * Collects the `0307` session stream from the session characteristic.
     *
     * The first notification is `03 07 '*' 'B' '#' count(2)` followed by record bytes; the rest are raw record bytes.
     * Exactly count*42 bytes are used: the last notification can carry the start of the next stored record.
     * With count 0 the header carries the first 13 bytes of the newest record instead of nothing.
     */
    class SessionAssembler {
        private var count = -1
        private var buffer = ByteArray(0)

        var inline: ByteArray? = null
            private set

        val isStarted: Boolean get() = count >= 0
        val isComplete: Boolean get() = count == 0 || (count > 0 && buffer.size >= count * RECORD_SIZE)

        /** Number of records announced by the header, or -1 before the header arrived. */
        val announced: Int get() = count

        fun feed(packet: ByteArray) {
            if (packet.startsWith(STREAM_HEADER) && packet.size >= 7) {
                count = packet.u16(5)
                buffer = packet.copyOfRange(7, packet.size)
                inline = if (count == 0) buffer.copyOf() else null
            } else if (count > 0) {
                buffer += packet
            }
        }

        /** The complete 42-byte records received so far. */
        fun records(): List<ByteArray> {
            if (count <= 0) return emptyList()
            val n = minOf(count, buffer.size / RECORD_SIZE)
            return List(n) { buffer.copyOfRange(it * RECORD_SIZE, (it + 1) * RECORD_SIZE) }
        }
    }

    /**
     * Decodes one 42-byte record. Returns null for records that cannot be placed in time:
     * clock never set on the brush (year byte 0) or impossible date fields.
     *
     * [fallbackTzIndex] is used when the record carries no valid time-zone index.
     */
    fun decodeRecord(r: ByteArray, fallbackTzIndex: Int): BrushSession? {
        if (r.size < RECORD_SIZE) return null
        val tzIndex = r.u8(17).takeIf { it in 1..TZ_OFFSETS.size } ?: fallbackTzIndex
        val epoch = epochOf(r, tzIndex) ?: return null
        val planned = r.u16(7)
        val valid = r.u16(9)
        val rawScore = r.u8(33)
        return BrushSession(
            epochSec = epoch,
            tzOffsetMin = tzOffset(tzIndex),
            durationSec = valid,
            plannedSec = planned,
            // 0xFF means "no score"; the official app also scores sessions of 5 s or less as 1.
            score = if (rawScore == 0xFF) null else if (valid <= 5) 1 else minOf(rawScore, 100),
            schemeId = r.u8(6),
            zones = List(8) { r.u8(23 + it) },
            pressure = List(5) { r.u8(11 + it) }.takeIf { it != PLACEHOLDER_PRESSURE }.orEmpty(),
            raw = r.copyOf(RECORD_SIZE).toHex(),
        )
    }

    /**
     * Decodes the 13-byte record head sent with a "no new records" header: time, scheme, durations.
     * It has no time-zone byte, so [tzIndex] (the zone the brush was last synced to) is assumed.
     */
    fun decodeInline(head: ByteArray, tzIndex: Int): BrushSession? {
        if (head.size < 11) return null
        val epoch = epochOf(head, tzIndex) ?: return null
        return BrushSession(
            epochSec = epoch,
            tzOffsetMin = tzOffset(tzIndex),
            durationSec = head.u16(9),
            plannedSec = head.u16(7),
            schemeId = head.u8(6),
            raw = head.toHex(),
        )
    }

    private fun epochOf(r: ByteArray, tzIndex: Int): Long? {
        val year = r.u8(0)
        val month = r.u8(1)
        val day = r.u8(2)
        val hour = r.u8(3)
        val minute = r.u8(4)
        val second = r.u8(5)
        if (year == 0 || month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 59) return null
        val offset = tzOffset(tzIndex) ?: return null
        val local = runCatching { LocalDateTime.of(2000 + year, month, day, hour, minute, second) }.getOrNull() ?: return null
        return local.toEpochSecond(ZoneOffset.ofTotalSeconds(offset * 60))
    }
}

fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
internal fun ByteArray.u16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
internal fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
