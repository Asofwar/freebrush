package dev.asofwar.freebrush.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import dev.asofwar.freebrush.ble.OcleanProtocol.BATTERY_LEVEL
import dev.asofwar.freebrush.ble.OcleanProtocol.BATTERY_SERVICE
import dev.asofwar.freebrush.ble.OcleanProtocol.DIS_FIRMWARE
import dev.asofwar.freebrush.ble.OcleanProtocol.DIS_HARDWARE
import dev.asofwar.freebrush.ble.OcleanProtocol.DIS_MODEL
import dev.asofwar.freebrush.ble.OcleanProtocol.DIS_SERVICE
import dev.asofwar.freebrush.ble.OcleanProtocol.DIS_SOFTWARE
import dev.asofwar.freebrush.ble.OcleanProtocol.NOTIFY
import dev.asofwar.freebrush.ble.OcleanProtocol.SERVICE
import dev.asofwar.freebrush.ble.OcleanProtocol.SESSION_QUERY
import dev.asofwar.freebrush.ble.OcleanProtocol.SESSION_STREAM
import dev.asofwar.freebrush.ble.OcleanProtocol.WRITE
import dev.asofwar.freebrush.data.BrushSession
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class SyncResult(
    val model: String? = null,
    val firmware: String? = null,
    val hardware: String? = null,
    val battery: Int? = null,
    val settings: OcleanProtocol.Settings? = null,
    val sessions: List<BrushSession> = emptyList(),
)

/**
 * One sync with the brush: connect, read identity and battery, set its clock, read settings and
 * stored sessions, disconnect.
 *
 * Sessions are not acknowledged (`0202`), so the brush keeps them and sends them again next time;
 * the local store de-duplicates them. Whether the ack deletes records is not confirmed on a real
 * X Pro Digital yet, and not acking can never lose data.
 */
class OcleanClient(private val context: Context) {

    suspend fun sync(address: String, onStep: (String) -> Unit): SyncResult = withBrush(address, onStep) { s ->
        exchange(s, onStep)
    }

    /** A connected brush, for writing settings: one command in flight at a time, as the brush expects. */
    inner class Link internal constructor(private val s: GattSession, private val onStep: (String) -> Unit) {
        /** Writes [bytes] and waits for the brush's "OK"; true when it confirmed. */
        suspend fun send(bytes: ByteArray, label: String): Boolean {
            onStep(label)
            val ok = request(s, WRITE, bytes, onStep) { OcleanProtocol.isAck(it.value, bytes) } != null
            if (!ok) onStep("Щётка не подтвердила: $label")
            delay(100)
            return ok
        }

        /** Reads the settings block back from the brush. */
        suspend fun settings(): OcleanProtocol.Settings? = readSettings(s, onStep)
    }

    /** Connects, runs [block] with a [Link] (logging every notification), disconnects. */
    suspend fun <T> connect(address: String, onStep: (String) -> Unit, block: suspend Link.() -> T): T =
        withBrush(address, onStep) { s ->
            coroutineScope {
                val logger = launchLogger(s, onStep)
                try {
                    Link(s, onStep).block()
                } finally {
                    logger.cancel()
                }
            }
        }

    class ApplyResult(val confirmed: Int, val total: Int, val settings: OcleanProtocol.Settings?)

    /**
     * Writes [commands] (bytes and a label for the log) one by one, each waiting for the brush's "OK", then reads
     * the settings back so the app shows what the brush actually stored rather than what was asked for.
     */
    suspend fun apply(address: String, commands: List<Pair<ByteArray, String>>, onStep: (String) -> Unit): ApplyResult =
        connect(address, onStep) {
            val confirmed = commands.count { (bytes, label) -> send(bytes, label) }
            ApplyResult(confirmed, commands.size, settings())
        }

    /** Connects, discovers the brush service and subscribes to its notifications, runs [block], disconnects. */
    private suspend fun <T> withBrush(address: String, onStep: (String) -> Unit, block: suspend (GattSession) -> T): T {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw GattException("Нет Bluetooth")
        if (!adapter.isEnabled) throw GattException("Bluetooth выключен")
        val device = adapter.getRemoteDevice(address)

        val session = connectWithRetry(device, onStep)
        try {
            onStep("Чтение сервисов…")
            session.discoverServices()
            runCatching { session.requestMtu(123) }.onSuccess { onStep("MTU $it") }
            if (!session.hasCharacteristic(SERVICE, WRITE) || !session.hasCharacteristic(SERVICE, SESSION_STREAM)) {
                throw GattException("Это не похоже на щётку Oclean: нет нужного сервиса")
            }
            for (uuid in listOf(NOTIFY, SESSION_STREAM)) subscribe(session, uuid, onStep)
            delay(200)
            return block(session)
        } finally {
            session.close()
        }
    }

    /** Reads the settings block, asking a second time if the reply came incomplete. */
    private suspend fun readSettings(s: GattSession, onStep: (String) -> Unit): OcleanProtocol.Settings? {
        repeat(2) { attempt ->
            onStep(if (attempt == 0) "Чтение настроек…" else "Чтение настроек, ещё раз…")
            val asm = OcleanProtocol.SettingsAssembler()
            var settings: OcleanProtocol.Settings? = null
            request(s, WRITE, OcleanProtocol.CMD_SETTINGS, onStep) { n ->
                n.characteristic == NOTIFY && asm.feed(n.value)?.also { settings = it } != null
            }
            if (settings != null) return settings
        }
        onStep("Настройки не пришли")
        return null
    }

    private suspend fun connectWithRetry(
        device: android.bluetooth.BluetoothDevice,
        onStep: (String) -> Unit,
    ): GattSession {
        var last: Exception? = null
        repeat(3) { attempt ->
            val s = GattSession(context, device)
            try {
                onStep(if (attempt == 0) "Подключение…" else "Подключение, попытка ${attempt + 1}…")
                s.connect()
                return s
            } catch (e: Exception) {
                s.close()
                last = e
                delay(1_000)
            }
        }
        throw GattException(
            "Щётка не отвечает. Возьми её в руку, чтобы проснулась, и закрой приложение Oclean" +
                (last?.message?.let { " ($it)" } ?: ""),
        )
    }

    private suspend fun exchange(s: GattSession, onStep: (String) -> Unit): SyncResult = coroutineScope {
        // Log every notification: the log is what users attach to device reports.
        val logger = launchLogger(s, onStep)

        val model = s.readText(DIS_SERVICE, DIS_MODEL)
        val firmware = s.readText(DIS_SERVICE, DIS_FIRMWARE)
        val hardware = runCatching { OcleanProtocol.formatHardware(s.read(DIS_SERVICE, DIS_HARDWARE)) }.getOrNull()
        val software = s.readText(DIS_SERVICE, DIS_SOFTWARE)
        onStep("Модель $model, прошивка ${software ?: firmware}, железо $hardware")
        var battery = runCatching { s.read(BATTERY_SERVICE, BATTERY_LEVEL)[0].toInt() and 0xFF }.getOrNull()

        val now = ZonedDateTime.now()
        val tzIndex = OcleanProtocol.tzIndex(now.offset.totalSeconds / 60)

        onStep("Установка времени…")
        val timeCmd = OcleanProtocol.timeSyncCommand(now)
        request(s, WRITE, timeCmd, onStep) { OcleanProtocol.isAck(it.value, timeCmd) }
            ?: onStep("Щётка не подтвердила время")

        val settings = readSettings(s, onStep)

        val status = request(s, WRITE, OcleanProtocol.CMD_STATUS, onStep) { OcleanProtocol.parseStatusBattery(it.value) != null }
            // Some firmware answers queries only on the session channel.
            ?: request(s, SESSION_QUERY, OcleanProtocol.CMD_STATUS, onStep) { OcleanProtocol.parseStatusBattery(it.value) != null }
        status?.let { OcleanProtocol.parseStatusBattery(it.value) }?.let { battery = it }

        onStep("Чтение чисток…")
        val sessions = readSessions(s, tzIndex, onStep)
        onStep("Готово: заряд ${battery ?: "?"} %, чисток в щётке ${sessions.size}")

        logger.cancel()
        SyncResult(
            model = model,
            firmware = software ?: firmware,
            hardware = hardware,
            battery = battery,
            settings = settings,
            sessions = sessions,
        )
    }

    private fun kotlinx.coroutines.CoroutineScope.launchLogger(s: GattSession, onStep: (String) -> Unit) =
        async(start = CoroutineStart.UNDISPATCHED) {
            s.notifications.collect { onStep("← ${it.characteristic.short()} ${it.value.toHex()}") }
        }

    private suspend fun subscribe(s: GattSession, uuid: UUID, onStep: (String) -> Unit) {
        val ok = runCatching { s.enableNotifications(SERVICE, uuid) }.getOrDefault(false)
        if (ok) return
        // Stale CCCD left enabled from an earlier connection: clear it and try again.
        runCatching { s.disableNotifications(SERVICE, uuid) }
        delay(300)
        if (!runCatching { s.enableNotifications(SERVICE, uuid) }.getOrDefault(false)) {
            onStep("Не удалось подписаться на ${uuid.short()}")
        }
    }

    /** Writes [command] and waits up to 5 s for the first notification that [matches]; null on timeout. */
    private suspend fun request(
        s: GattSession,
        characteristic: UUID,
        command: ByteArray,
        onStep: (String) -> Unit,
        matches: (Notification) -> Boolean,
    ): Notification? = coroutineScope {
        onStep("→ ${characteristic.short()} ${command.toHex()}")
        val reply = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(5_000) { s.notifications.first(matches) }
        }
        try {
            s.write(SERVICE, characteristic, command)
        } catch (e: GattException) {
            onStep("Запись не удалась: ${e.message}")
            reply.cancel()
            return@coroutineScope null
        }
        reply.await()
    }

    /** Sends the session query and collects the stream until it is complete or goes quiet for 5 s. */
    @OptIn(FlowPreview::class)
    private suspend fun readSessions(s: GattSession, tzIndex: Int, onStep: (String) -> Unit): List<BrushSession> =
        coroutineScope {
            val asm = OcleanProtocol.SessionAssembler()
            val collector = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(30_000) {
                    runCatching {
                        s.notifications
                            .filter { it.characteristic == SESSION_STREAM }
                            .timeout(5.seconds)
                            .takeWhile { n -> asm.feed(n.value); !asm.isComplete }
                            .collect()
                    }
                }
            }
            onStep("→ ${SESSION_QUERY.short()} ${OcleanProtocol.CMD_SESSIONS.toHex()}")
            s.write(SERVICE, SESSION_QUERY, OcleanProtocol.CMD_SESSIONS)
            collector.await()
            if (!asm.isStarted) {
                onStep("Щётка не прислала список чисток")
                return@coroutineScope emptyList()
            }
            onStep("Записей в щётке: ${asm.announced}, получено целиком: ${asm.records().size}")

            val decoded = asm.records().mapNotNull { r ->
                OcleanProtocol.decodeRecord(r, tzIndex).also {
                    if (it == null) onStep("Пропущена запись без даты: ${r.toHex()}")
                }
            }
            val inline = asm.inline?.let { OcleanProtocol.decodeInline(it, tzIndex) }
            decoded + listOfNotNull(inline)
        }

    private suspend fun GattSession.readText(service: UUID, uuid: UUID): String? =
        runCatching { read(service, uuid).toString(Charsets.US_ASCII).trimEnd('\u0000', ' ') }
            .getOrNull()?.ifEmpty { null }
}
