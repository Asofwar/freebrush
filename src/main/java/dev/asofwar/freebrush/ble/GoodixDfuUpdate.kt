package dev.asofwar.freebrush.ble

import dev.asofwar.freebrush.ble.GoodixDfu.ImageInfo

/** The two ends of a DFU exchange: the GATT link to a brush in the app, a simulated chip in tests. */
interface DfuTransport {
    suspend fun send(opcode: Int, data: ByteArray)

    /** The answer to [opcode], or null when none came within [timeoutMs]. */
    suspend fun receive(opcode: Int, timeoutMs: Long): GoodixDfu.Reply?
}

/**
 * Firmware update of a Goodix GR5xx brush, in the order the official app uses:
 * 1. read the chip id (it tells where the system configuration area, SCA, is), the boot info of the running image and
 *    the image list kept in the SCA ([readState]);
 * 2. check the file against the chip and the addresses ([plan]); nothing is written before this passes;
 * 3. drop list entries the new image will cover, announce the image (program start) and write it in 1 KB frames to
 *    the second bank at [GoodixDfu.STAGING_ADDRESS];
 * 4. send the sum of the file (program end): the brush checks it, takes the new image and restarts.
 * The running image and its boot info are not written before step 4, so a broken transfer leaves the old firmware
 * in place.
 */
object GoodixDfuUpdate {
    const val SCA_SIZE = 8192

    /** Battery the brush needs before a write starts, in percent. */
    const val MIN_BATTERY = 30
    private const val LIST_OFFSET = 64
    private const val LIST_SIZE = 400
    private const val INFO_SIZE = 40
    private const val MAX_IMAGES = LIST_SIZE / INFO_SIZE

    /** What the chip and its bootloader report. */
    class ChipState(
        val chipId: Int,
        val sca: Int,
        val encrypted: Boolean,
        val boot: ImageInfo,
        val images: List<ImageInfo>,
    ) {
        /** The list entry of the running image, if the list has one; its comment names the program (Y3L_AS_MBV05). */
        val running: ImageInfo? get() = images.firstOrNull { it.checksum == boot.checksum && it.loadAddress == boot.loadAddress }
    }

    /** Everything the update will write, worked out before the first write. [list] is null when it stays as it is. */
    class Plan(val firmware: GoodixDfu.Firmware, val sca: Int, val start: ImageInfo, val list: List<ImageInfo>?)

    private suspend fun DfuTransport.ok(opcode: Int, data: ByteArray = ByteArray(0), timeoutMs: Long = 10_000): ByteArray {
        send(opcode, data)
        val reply = receive(opcode, timeoutMs) ?: throw GattException("DFU: щётка не ответила на команду 0x%02x".format(opcode))
        if (reply.status != 1) throw GattException("DFU: команда 0x%02x вернула %d".format(opcode, reply.status))
        return reply.data
    }

    /** Chip id, boot info and image list; nothing is written. */
    suspend fun readState(t: DfuTransport, onStep: (String) -> Unit): ChipState {
        val chip = t.ok(GoodixDfu.OP_CHIP_INFO)
        // status, then 1 + 1 + 2 bytes, then the chip id.
        val chipId = GoodixDfu.get32(chip, 5)
        val sca = GoodixDfu.scaAddress(chipId)
        onStep("DFU: чип $chipId, SCA 0x%08x".format(sca))

        // A system read answers: status, flags, address (4), length (2), then the data.
        val bootReply = t.ok(GoodixDfu.OP_SYSTEM_INFO, GoodixDfu.systemRead(sca, 24))
        if (GoodixDfu.get32(bootReply, 2) != sca || GoodixDfu.get16(bootReply, 6) != 24) throw GattException("DFU: загрузочная информация пришла не та")
        val encrypted = (bootReply[1].toInt() and 0xF0) != 0
        val boot = bootInfo(bootReply, 8)
        onStep("DFU: загрузка ${boot.binSize} байт, сумма 0x%08x, адрес 0x%08x, шифрование $encrypted".format(boot.checksum, boot.loadAddress))

        val listReply = t.ok(GoodixDfu.OP_SYSTEM_INFO, GoodixDfu.systemRead(sca + LIST_OFFSET, LIST_SIZE))
        if (GoodixDfu.get32(listReply, 2) != sca + LIST_OFFSET || GoodixDfu.get16(listReply, 6) != LIST_SIZE) {
            throw GattException("DFU: список образов пришёл не тот")
        }
        val images = (0 until MAX_IMAGES).map { ImageInfo.parse(listReply, 8 + it * INFO_SIZE) }.filter { it.pattern == GoodixDfu.MAGIC }
        images.forEach {
            onStep("DFU: образ «${it.comment}» v${it.version}, ${it.binSize} байт, сумма 0x%08x, адрес 0x%08x".format(it.checksum, it.loadAddress))
        }
        return ChipState(chipId, sca, encrypted, boot, images)
    }

    /** The 24-byte boot info: an image info without pattern, version and comment. */
    private fun bootInfo(b: ByteArray, at: Int) = ImageInfo(
        pattern = GoodixDfu.MAGIC,
        version = 1,
        binSize = GoodixDfu.get32(b, at),
        checksum = GoodixDfu.get32(b, at + 4),
        loadAddress = GoodixDfu.get32(b, at + 8),
        runAddress = GoodixDfu.get32(b, at + 12),
        xqspiCmd = GoodixDfu.get32(b, at + 16),
        flags = GoodixDfu.get32(b, at + 20),
        comment = "fromBoot",
    )

    /** Whether flash ranges [a, a + aSize) and [b, b + bSize) share a byte; addresses are unsigned. */
    fun overlaps(a: Int, aSize: Int, b: Int, bSize: Int): Boolean {
        val a0 = a.toLong() and 0xFFFFFFFFL
        val b0 = b.toLong() and 0xFFFFFFFFL
        return a0 + (aSize.toLong() and 0xFFFFFFFFL) > b0 && a0 < b0 + (bSize.toLong() and 0xFFFFFFFFL)
    }

    /**
     * Checks [fw] against the chip and works out what to write; throws with the reason when the file does not fit.
     * The image name in the file must be the running one's: from the chip's list, or, when the list lost it (an
     * install rewrites it, so a broken one leaves it without), [knownImage] remembered from before. [trusted] is true
     * for a file the Oclean server picked for this very brush, which may go on a chip that names nothing.
     */
    fun plan(state: ChipState, fw: GoodixDfu.Firmware, trusted: Boolean, knownImage: String? = null): Plan {
        require(state.chipId == GoodixDfu.GR5515) { "Чип ${state.chipId}: FreeBrush обновляет только GR5515 (${GoodixDfu.GR5515})" }
        require(!fw.signed) { "Подписанные образы FreeBrush не ставит" }
        require(fw.encrypted == state.encrypted) { "Шифрование образа не совпадает со щёткой" }
        val name = state.running?.comment ?: knownImage
        if (name != null) {
            require(fw.info.comment == name) { "Файл для другой щётки: в нём «${fw.info.comment}», в щётке «$name»" }
        } else {
            require(trusted) { "Щётка не называет свою программу: поставить можно только файл с сервера Oclean для этой щётки" }
        }
        val running = state.running
        require(fw.info.runAddress == state.boot.runAddress) {
            "Адрес запуска в файле 0x%08x, у щётки 0x%08x".format(fw.info.runAddress, state.boot.runAddress)
        }

        val size = fw.bytes.size
        val staging = GoodixDfu.STAGING_ADDRESS
        val target = fw.info.loadAddress
        require((staging.toLong() and 0xFFFFFFFFL) + size <= GoodixDfu.FLASH_END) { "Образ не помещается во flash щётки" }
        require(!overlaps(staging, size, state.sca, SCA_SIZE)) { "Образ лёг бы на системную область щётки" }
        require(!overlaps(state.boot.loadAddress, state.boot.binSize, staging, size)) { "Образ лёг бы на работающую программу" }
        if (target != staging) {
            require(!overlaps(staging, size, target, size)) { "Образ лёг бы на место, куда его потом скопируют" }
            require(!overlaps(target, size, state.sca, SCA_SIZE)) { "Адрес образа задевает системную область" }
        }

        // The list keeps the running image (added from the boot info when missing) and drops the entries the new one
        // covers. The official app adds that boot entry without the overlap check, so this does too.
        var changed = false
        val list = mutableListOf<ImageInfo>()
        if (running == null) {
            list += state.boot
            changed = true
        }
        for (image in state.images) {
            if (overlaps(image.loadAddress, image.binSize, target, size)) changed = true else list += image
        }
        require(list.size <= MAX_IMAGES) { "Список образов щётки переполнен" }
        return Plan(fw, state.sca, fw.info.copy(loadAddress = staging), if (changed) list else null)
    }

    /** Data of the list write: mode 1, the list's address and size, the entries, 0xFF after them. */
    fun listWrite(sca: Int, list: List<ImageInfo>): ByteArray {
        val data = ByteArray(7 + LIST_SIZE) { 0xFF.toByte() }
        data[0] = 1
        GoodixDfu.put32(data, 1, sca + LIST_OFFSET)
        GoodixDfu.put16(data, 5, LIST_SIZE)
        list.forEachIndexed { i, image -> image.toBytes().copyInto(data, 7 + i * INFO_SIZE) }
        return data
    }

    /**
     * Writes the planned image. [onProgress] gets bytes written and the total. Returns whether the brush answered the
     * end frame: it may restart before it does, as the official app also allows.
     */
    suspend fun flash(t: DfuTransport, plan: Plan, onProgress: (Int, Int) -> Unit, onStep: (String) -> Unit): Boolean {
        plan.list?.let {
            onStep("DFU: обновляю список образов (${it.size})")
            t.ok(GoodixDfu.OP_SYSTEM_INFO, listWrite(plan.sca, it))
        }
        onStep("DFU: начинаю запись")
        // Mode 0: a plain image into internal flash. The brush erases the bank before it answers.
        t.ok(GoodixDfu.OP_PROGRAM_START, byteArrayOf(0) + plan.start.toBytes(), timeoutMs = 60_000)

        val bytes = plan.firmware.bytes
        var at = 0
        while (at < bytes.size) {
            val n = minOf(GoodixDfu.CHUNK, bytes.size - at)
            val data = ByteArray(7 + n)
            data[0] = 1
            GoodixDfu.put32(data, 1, GoodixDfu.STAGING_ADDRESS + at)
            GoodixDfu.put16(data, 5, n)
            bytes.copyInto(data, 7, at, at + n)
            t.ok(GoodixDfu.OP_PROGRAM_FLASH, data)
            at += n
            onProgress(at, bytes.size)
        }

        onStep("DFU: записано, проверка суммы")
        val end = ByteArray(5)
        end[0] = 1
        GoodixDfu.put32(end, 1, plan.firmware.sum)
        try {
            t.send(GoodixDfu.OP_PROGRAM_END, end)
        } catch (e: GattException) {
            // The brush may restart on the frame before it acknowledges the write; the boot info will tell.
            onStep("DFU: связь оборвалась на последнем кадре (${e.message})")
            return false
        }
        val reply = t.receive(GoodixDfu.OP_PROGRAM_END, 10_000) ?: return false
        if (reply.status != 1) throw GattException("Щётка не приняла образ (ответ ${reply.status})")
        return true
    }

    /** Whether the brush now boots [fw]: its boot info describes that image. */
    fun installed(state: ChipState, fw: GoodixDfu.Firmware): Boolean =
        state.boot.checksum == fw.info.checksum && state.boot.binSize == fw.info.binSize
}
