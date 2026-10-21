package dev.asofwar.freebrush.ble

/**
 * Settings and programmes of the Oclean X Pro Digital (hardware protocol `0003`), from the official app's
 * handler for that type. Every write goes to the command characteristic and is acknowledged with the command
 * id followed by ASCII "OK"; the brush reports the stored value back in the `0302` settings block.
 */
object BrushControls {

    /**
     * An on/off setting: written as `02 <cmd> <on|off>` and read back from byte [index] of the settings block.
     * [official] is false for switches the official app hides on this brush although its firmware handler has them.
     */
    enum class Toggle(
        val cmd: Int,
        val index: Int,
        val title: String,
        val description: String,
        val official: Boolean,
        val onValue: Int = 1,
        val offValue: Int = 0,
    ) {
        RAISE_WAKE(0x23, 2, "Пробуждение при подъёме", "Щётка включает экран, когда её берут в руку", true),
        AREA_REMIND(0x0D, 23, "Сигнал смены зоны", "Щётка подаёт знак, что пора переходить к следующей зоне", true),
        AUTO_MODE(0x25, 4, "Утро и вечер", "Щётка сама выбирает дневной или вечерний режим по времени", true),
        FILL_BRUSH(0x24, 3, "Автообновление", "Так называется в официальном приложении; что именно делает, неизвестно", true),
        HOLIDAY(0x28, 10, "Праздничные напоминания", "Поздравления на экране щётки по праздникам", true),
        OVER_CROSS(0x30, 33, "Сильное боковое движение", "Предупреждение, если щётку слишком сильно водить из стороны в сторону", true),
        OVER_PRESSURE(0x12, 22, "Сильный нажим", "Предупреждение при слишком сильном нажатии. Скрыто в официальном приложении", false),
        BRUSH_PAUSE(0x22, 1, "Пауза в чистке", "Щётка приостанавливается, если её убрать от зубов. Скрыто в официальном приложении", false),
        BRUSH_PATTERN(0x09, 12, "Режим пробуждения", "Что именно меняет, пока не выяснено. Скрыто в официальном приложении", false, onValue = 0x01, offValue = 0xEC),
        ;

        fun isOn(raw: ByteArray): Boolean = if (this == BRUSH_PATTERN) raw.u8(index) != offValue else raw.u8(index) != 0

        fun command(on: Boolean): ByteArray = byteArrayOf(0x02, cmd.toByte(), (if (on) onValue else offValue).toByte())
    }

    /** Languages of the brush display, with the ids the brush uses. */
    val LANGUAGES = listOf(
        7 to "Русский", 3 to "English", 17 to "Українська", 6 to "Deutsch", 4 to "Français", 8 to "Español",
        9 to "Italiano", 15 to "Português", 12 to "Polski", 16 to "Čeština", 11 to "Türkçe", 1 to "简体中文",
        2 to "繁體中文", 5 to "日本語", 14 to "한국어", 13 to "العربية",
    )

    const val LANGUAGE_INDEX = 31
    const val SCHEME_INDEX = 11
    const val HEAD_MAX_INDEX = 25

    fun language(id: Int): ByteArray = byteArrayOf(0x02, 0x16, id.toByte())

    /** Brush-head counters back to zero (days, sessions, wear). Cannot be undone. */
    val HEAD_RESET: ByteArray = byteArrayOf(0x02, 0x0F)

    /** Brush-head wear limit, big-endian; the brush counts the wear in the same unit (minutes, most likely). */
    fun headLimit(value: Int): ByteArray = byteArrayOf(0x02, 0x17, (value shr 8).toByte(), value.toByte())

    /**
     * Growth points behind the level the brush shows on its screen (bronze, silver, gold...). The brush counts new
     * points itself (`0308` returns them with a tag); the app is meant to add them to the running total and write the
     * total back as the base (`0226`), after which the brush starts counting new points from zero. Tested on an
     * X Pro Digital: base 100 turned "bronze 1" into "gold 2", and the new-points counter dropped to 0.
     */
    val GROWTH_READ: ByteArray = byteArrayOf(0x03, 0x08)

    fun growthBase(total: Int): ByteArray {
        val v = total.coerceIn(0, 0xFFFF)
        return byteArrayOf(0x02, 0x26, (v shr 8).toByte(), v.toByte())
    }

    /** New growth points from a `0308` reply (`03 08 value(2) tag(8) 'O' 'K'`), or null if it is not one. */
    fun parseGrowth(packet: ByteArray): Int? {
        if (packet.size < 6 || packet[0] != 0x03.toByte() || packet[1] != 0x08.toByte()) return null
        if (packet[packet.size - 2] != 'O'.code.toByte() || packet[packet.size - 1] != 'K'.code.toByte()) return null
        return ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
    }

    /** Growth points are handled by the type-0003 firmware only; other models use 0308 for something else. */
    fun hasGrowth(hardware: String?): Boolean = hardware?.startsWith("HH 0003") == true

    /** One step of a programme: motor [gear] for [seconds]. */
    data class Step(val gear: Int, val seconds: Int)

    /** Programme number: below 230 the brush's built-in modes, from 231 custom ones. */
    const val FIRST_CUSTOM = 231

    /**
     * Longest programme the brush takes. It accepts a programme in at most two packets of 20 bytes: 9 steps fit
     * (tested twice on an X Pro Digital), 10 make the second packet 22 bytes and the brush stops answering.
     * The official app allows 6.
     */
    const val MAX_STEPS = 9

    /** Largest packet the brush reliably accepts, whatever MTU was negotiated. */
    private const val MAX_PACKET = 20

    /**
     * The `0206` programme packet: `02 06 pNum N (enc, gear, seconds) x N 00 05`. enc is 32 for programmes with
     * step prompts ([prompt], the cloud's toastType 1, used by almost all of them), otherwise a code the official
     * app derives from the gear. When the packet does not fit into [mtu] - 3 bytes it is split like the official
     * app does: the first 16 bytes plus `2A 2B`, then `02 0B` and the rest. Each packet is acknowledged separately.
     */
    fun programme(pNum: Int, steps: List<Step>, mtu: Int = 23, prompt: Boolean = true): List<ByteArray> {
        require(steps.size in 1..MAX_STEPS) { "a programme has 1..$MAX_STEPS steps, got ${steps.size}" }
        val body = ArrayList<Byte>()
        body += 0x02; body += 0x06; body += pNum.toByte(); body += steps.size.toByte()
        for (s in steps) {
            require(s.seconds in 1..255) { "step of ${s.seconds} s does not fit one byte" }
            body += (if (prompt) 32 else voiceCode(s.gear)).toByte(); body += s.gear.toByte(); body += s.seconds.toByte()
        }
        body += 0x00; body += 0x05
        val payload = body.toByteArray()
        if (payload.size <= minOf(mtu - 3, MAX_PACKET)) return listOf(payload)
        val first = payload.copyOfRange(0, 16) + byteArrayOf(0x2A, 0x2B)
        val second = byteArrayOf(0x02, 0x0B) + payload.copyOfRange(16, payload.size)
        return listOf(first, second)
    }

    private fun voiceCode(gear: Int): Int = when (gear) {
        in 1..4 -> gear + 4
        in 5..12 -> gear + 12
        else -> 0
    }
}
