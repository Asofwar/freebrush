package dev.asofwar.freebrush

import android.app.Application
import androidx.core.content.ContextCompat
import android.os.SystemClock
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.asofwar.freebrush.ble.BrushControls
import dev.asofwar.freebrush.ble.BrushWatch
import dev.asofwar.freebrush.ble.FoundDevice
import dev.asofwar.freebrush.ble.GoodixDfu
import dev.asofwar.freebrush.ble.GoodixDfuUpdate
import dev.asofwar.freebrush.ble.scanDevices
import dev.asofwar.freebrush.ble.OcleanProtocol
import dev.asofwar.freebrush.ble.OcleanClient
import dev.asofwar.freebrush.ble.hex
import dev.asofwar.freebrush.ble.toHex
import dev.asofwar.freebrush.data.BrushInfo
import dev.asofwar.freebrush.data.BrushPrefs
import dev.asofwar.freebrush.data.BrushSession
import dev.asofwar.freebrush.data.CustomProgrammes
import dev.asofwar.freebrush.data.Programme
import dev.asofwar.freebrush.data.SessionStore
import dev.asofwar.freebrush.data.toCsv
import dev.asofwar.freebrush.update.OcleanOta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** The firmware update of a brush, step by step. */
sealed interface FirmwareState {
    data object Idle : FirmwareState
    data class Working(val text: String) : FirmwareState

    /** The server has nothing newer than [version]. */
    data class UpToDate(val version: String) : FirmwareState

    /** A checked file for brush [brushId] at [address]; [trusted] when the Oclean server picked it for that brush. */
    data class Ready(
        val brushId: String,
        val address: String,
        val firmware: GoodixDfu.Firmware,
        val label: String,
        val trusted: Boolean,
    ) : FirmwareState
    data class Installing(val text: String, val done: Int, val total: Int) : FirmwareState
    data class Installed(val text: String) : FirmwareState

    /** [retry] keeps the file, so the install can be started again. */
    data class Failed(val text: String, val retry: Ready? = null) : FirmwareState
}

/** A write to the brush (settings, programme, probe) and how it went. */
sealed interface ActionState {
    data object Idle : ActionState
    data class Running(val text: String) : ActionState
    data class Done(val text: String) : ActionState
    data class Failed(val text: String) : ActionState
}

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val step: String) : SyncState
    data class Done(val added: Int) : SyncState
    data class Failed(val message: String) : SyncState
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = BrushPrefs(app)
    private val client = OcleanClient(app)
    private val custom = CustomProgrammes(app)
    private val stores = mutableMapOf<String, SessionStore>()

    /** The user's own programmes, shared by all brushes. */
    val customProgrammes = custom.items

    /** Every brush the app knows, one per person. */
    val brushes: StateFlow<List<BrushInfo>> = prefs.brushes

    /** The brush the screens show. */
    val brush: StateFlow<BrushInfo?> = prefs.current

    /** History of the brush on screen, newest first. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val sessions: StateFlow<List<BrushSession>> = prefs.current
        .map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else store(id).sessions }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val sync: StateFlow<SyncState> = _sync

    /** Lines of the last sync, shown on the brush screen for troubleshooting. */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val _action = MutableStateFlow<ActionState>(ActionState.Idle)
    val action: StateFlow<ActionState> = _action

    /** Syncs and writes, one at a time: the brush accepts a single connection. */
    private val queue = BluetoothQueue(viewModelScope)

    /** Brush the running Bluetooth job talks to now; a sync of several brushes moves through them. */
    private var busyBrush: String? = null

    /** When the app last synced by itself (elapsed realtime), so turning the phone does not start another. */
    private var lastAutoSync = 0L

    private fun store(id: String): SessionStore = stores.getOrPut(id) {
        SessionStore(getApplication(), id).also { s -> viewModelScope.launch { s.load() } }
    }

    /** Adds the brush picked in the scan, named [label], shows it and syncs it. */
    fun pair(device: FoundDevice, label: String?) {
        prefs.add(BrushInfo(id = BrushInfo.newId(), address = device.address, label = label, name = device.name))
        syncNow()
    }

    /** Moves the brush on screen to the same person's new brush: history, name and level stay. */
    fun replace(device: FoundDevice) {
        val info = brush.value ?: return
        // Only jobs on this very brush stop; another person's sync finishes and this one queues behind it.
        queue.cancel(info.id, alsoRunning = busyBrush == info.id)
        prefs.update(info.id) { it.replacedBy(device.address, device.name) }
        _sync.value = SyncState.Idle
        syncNow()
    }

    fun select(id: String) {
        if (id == brush.value?.id) return
        prefs.select(id)
        // A sync of another brush keeps running and stays visible; otherwise the old result belongs to the old brush.
        if (!queue.isBusy) {
            _sync.value = SyncState.Idle
            _log.value = emptyList()
        }
    }

    fun rename(label: String?) {
        val info = brush.value ?: return
        prefs.update(info.id) { it.copy(label = label?.trim()?.ifEmpty { null }) }
    }

    /** Forgets the brush on screen together with its history on the phone. */
    fun remove() {
        val info = brush.value ?: return
        queue.cancel(info.id, alsoRunning = busyBrush == info.id)
        prefs.remove(info.id)
        _sync.value = SyncState.Idle
        _log.value = emptyList()
        val store = store(info.id)
        stores.remove(info.id)
        viewModelScope.launch { store.clear() }
    }

    /** Syncs the brush on screen, after whatever Bluetooth job is running; once, however often it is asked. */
    fun syncNow() {
        val info = brush.value ?: return
        if (queue.has(info.id, BluetoothQueue.Kind.SYNC)) return
        queue.enqueue(info.id, BluetoothQueue.Kind.SYNC) { syncAll(listOf(info.id)) }
    }

    /**
     * The sync of the app coming to the foreground: on start and on return from the background, but not again within
     * a couple of minutes, so turning the phone or changing the theme does not reconnect to the brush.
     */
    fun onAppStart() {
        val now = SystemClock.elapsedRealtime()
        if (lastAutoSync != 0L && now - lastAutoSync < AUTO_SYNC_GAP_MS) return
        if (brush.value == null || !bluetoothAllowed()) return
        lastAutoSync = now
        syncOnOpen()
    }

    private fun bluetoothAllowed(): Boolean = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .all { ContextCompat.checkSelfPermission(getApplication(), it) == PackageManager.PERMISSION_GRANTED }

    /**
     * With several brushes, first listens for a few seconds which of them are awake and syncs those, the one on
     * screen first; with one brush, or when none is heard, it tries the one on screen.
     */
    private fun syncOnOpen() {
        val all = brushes.value
        val shown = brush.value ?: return
        if (all.size < 2) return syncNow()
        if (queue.has(null, BluetoothQueue.Kind.SYNC)) return
        queue.enqueue(null, BluetoothQueue.Kind.SYNC) {
            _log.value = emptyList()
            _sync.value = SyncState.Running("Ищу проснувшиеся щётки…")
            val awake = awakeAddresses(all.map { it.address }.toSet())
            val targets = all.filter { it.address in awake }.sortedByDescending { it.id == shown.id }
            syncAll(targets.ifEmpty { listOf(shown) }.map { it.id })
        }
    }

    /** Addresses of the known brushes that advertise within a few seconds (a brush does so only while awake). */
    private suspend fun awakeAddresses(known: Set<String>): Set<String> {
        val seen = mutableSetOf<String>()
        try {
            withTimeoutOrNull(SCAN_MS) {
                scanDevices(getApplication()).first { d ->
                    if (d.address in known) seen += d.address
                    seen.size == known.size
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Bluetooth off or the scan refused: the sync of the brush on screen reports the real problem.
        }
        return seen
    }

    /** Syncs the brushes [ids] one after another; one brush failing does not stop the others. */
    private suspend fun syncAll(ids: List<String>) {
        val named = brushes.value.size > 1
        val lines = mutableListOf<String>()
        _log.value = emptyList()
        _sync.value = SyncState.Running("Подключение…")
        var added = 0
        var synced = 0
        var failure: String? = null
        try {
            for (id in ids) {
                // Re-read: the brush may have been removed or replaced while this job waited.
                val info = prefs.get(id) ?: continue
                val prefix = if (named) "${info.title}: " else ""
                busyBrush = info.id
                try {
                    added += syncOne(info, prefix, lines)
                    synced++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lines += prefix + "Ошибка: ${e.message}"
                    _log.value = lines.toList()
                    failure = prefix + (e.message ?: e.javaClass.simpleName)
                }
            }
            _sync.value = if (synced == 0 && failure != null) SyncState.Failed(failure) else SyncState.Done(added)
        } catch (e: CancellationException) {
            // Stopped because its brush was removed or replaced: nothing runs any more.
            if (_sync.value is SyncState.Running) _sync.value = SyncState.Idle
            throw e
        } finally {
            busyBrush = null
        }
    }

    private suspend fun syncOne(info: BrushInfo, prefix: String, lines: MutableList<String>): Int {
        // FreeBrush's own growth points start with the day they came in; the mark is stored at once, so a first sync
        // on a later day still counts that day's brushings.
        val countedUntil = info.growthCountedUntil ?: java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault())
            .toEpochSecond().also { mark -> prefs.update(info.id, durable = true) { it.copy(growthCountedUntil = it.growthCountedUntil ?: mark) } }
        val result = client.sync(
            info.address,
            info.growthTotal,
            countedUntil,
            onGrowth = { total, until -> prefs.update(info.id, durable = true) { it.copy(growthTotal = total, growthCountedUntil = until) } },
        ) { step ->
            lines += prefix + step
            _log.value = lines.toList()
            // Raw packet lines go to the log only.
            if (!step.startsWith("←") && !step.startsWith("→")) _sync.value = SyncState.Running(prefix + step)
        }
        // The brush was removed or moved to another device meanwhile: what came from the old one is not its data.
        if (prefs.get(info.id)?.address != info.address) return 0
        val added = store(info.id).addAll(result.sessions)
        prefs.update(info.id) {
            it.copy(
                model = result.model ?: it.model,
                firmware = result.firmware ?: it.firmware,
                hardware = result.hardware ?: it.hardware,
                battery = result.battery ?: it.battery,
                headDays = result.settings?.headUsedDays ?: it.headDays,
                headUses = result.settings?.headUsedTimes ?: it.headUses,
                headWear = result.settings?.headUsedTime ?: it.headWear,
                headWearMax = result.settings?.headMaxTime ?: it.headWearMax,
                settingsHex = result.settings?.raw?.toHex() ?: it.settingsHex,
                lastSyncMillis = System.currentTimeMillis(),
            )
        }
        return added
    }

    /** Stores what brush [info] reported about its settings and brush head, if the record still points at it. */
    private fun saveSettings(info: BrushInfo, settings: OcleanProtocol.Settings?) {
        settings ?: return
        if (prefs.get(info.id)?.address != info.address) return
        prefs.update(info.id) {
            it.copy(
                headDays = settings.headUsedDays,
                headUses = settings.headUsedTimes,
                headWear = settings.headUsedTime,
                headWearMax = settings.headMaxTime,
                settingsHex = settings.raw.toHex(),
            )
        }
    }

    /**
     * Runs [block] on the brush on screen as the single Bluetooth job, reporting progress in [action] and the log.
     * The block gets that brush, so a switch to another brush meanwhile does not redirect what it stores.
     */
    private fun onBrush(start: String, block: suspend (info: BrushInfo, onStep: (String) -> Unit) -> String) {
        val info = brush.value ?: return
        // Queue behind a running sync instead of refusing: the brush takes one connection at a time.
        _action.value = ActionState.Running(if (queue.isBusy) "$start Жду щётку…" else start)
        queue.enqueue(info.id, BluetoothQueue.Kind.WRITE) {
            val lines = mutableListOf<String>()
            _log.value = lines.toList()
            _action.value = ActionState.Running(start)
            busyBrush = info.id
            try {
                val done = block(info) { step ->
                    lines += step
                    _log.value = lines.toList()
                    if (!step.startsWith("←") && !step.startsWith("→")) _action.value = ActionState.Running(step)
                }
                _action.value = ActionState.Done(done)
            } catch (e: CancellationException) {
                // Stopped by removing or replacing this brush: nothing is running any more.
                _action.value = ActionState.Idle
                throw e
            } catch (e: Exception) {
                lines += "Ошибка: ${e.message}"
                _log.value = lines.toList()
                _action.value = ActionState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                busyBrush = null
            }
        }
    }

    /** Writes settings commands and stores what the brush read back. */
    fun applySettings(commands: List<Pair<ByteArray, String>>, done: String) = onBrush("Записываю на щётку…") { info, onStep ->
        val result = client.apply(info.address, commands, onStep)
        saveSettings(info, result.settings)
        if (result.confirmed == result.total) done else "Щётка подтвердила ${result.confirmed} из ${result.total}"
    }

    /** Sends a brushing programme to the brush (one or two packets) and reads back the active programme. */
    fun sendProgramme(pNum: Int, steps: List<BrushControls.Step>, name: String, prompt: Boolean = true) = onBrush("Отправляю «$name»…") { info, onStep ->
        client.connect(info.address, onStep) {
            val packets = BrushControls.programme(pNum, steps, prompt = prompt)
            var ok = true
            for ((i, packet) in packets.withIndex()) {
                if (!send(packet, "Программа, пакет ${i + 1} из ${packets.size}")) { ok = false; break }
            }
            val settings = settings()
            saveSettings(info, settings)
            val active = settings?.raw?.let { it[BrushControls.SCHEME_INDEX].toInt() and 0xFF }
            android.util.Log.i("FreeBrushProbe", "programme $pNum ${steps.joinToString { "${it.gear}x${it.seconds}" }} ok=$ok active=$active settings=${settings?.raw?.toHex()}")
            when {
                !ok -> throw IllegalStateException("щётка не приняла программу")
                active == pNum -> "«$name» включена на щётке"
                else -> "Щётка приняла программу, но активна №$active"
            }
        }
    }

    /**
     * Checks which settings this brush really supports, including ones the official app hides: every switch is
     * flipped, read back and restored; the language and the brush-head limit are changed and put back.
     * Results go to the log (and logcat tag FreeBrushProbe).
     */
    fun runProbe() = onBrush("Проверка настроек…") { info, onStep ->
        client.connect(info.address, onStep) {
            fun report(line: String) {
                onStep("ПРОБА $line")
                android.util.Log.i("FreeBrushProbe", line)
            }
            val start = settings() ?: throw IllegalStateException("настройки не прочитаны")
            report("исходные настройки ${start.raw.toHex()}")
            var works = 0
            for (t in BrushControls.Toggle.entries) {
                val before = settings()?.raw ?: continue
                val was = t.isOn(before)
                val ack = send(t.command(!was), "${t.title}: ${if (was) "выкл" else "вкл"}")
                val after = settings()?.raw
                val changed = after != null && t.isOn(after) == !was
                send(t.command(was), "${t.title}: возврат")
                val restored = settings()?.raw?.let { t.isOn(it) == was } == true
                if (changed) works++
                report("${t.name} 02${"%02x".format(t.cmd)} было=${if (was) 1 else 0} ack=$ack меняется=$changed возвращено=$restored")
            }
            val lang = settings()?.raw?.let { it[BrushControls.LANGUAGE_INDEX].toInt() and 0xFF }
            if (lang != null) {
                val other = if (lang == 3) 7 else 3
                val ack = send(BrushControls.language(other), "Язык: $other")
                val changed = settings()?.raw?.let { (it[BrushControls.LANGUAGE_INDEX].toInt() and 0xFF) == other } == true
                send(BrushControls.language(lang), "Язык: возврат")
                val restored = settings()?.raw?.let { (it[BrushControls.LANGUAGE_INDEX].toInt() and 0xFF) == lang } == true
                if (changed) works++
                report("LANGUAGE 0216 было=$lang ack=$ack меняется=$changed возвращено=$restored")
            }
            val limit = settings()?.headMaxTime
            if (limit != null) {
                val ack = send(BrushControls.headLimit(limit + 1), "Ресурс насадки: ${limit + 1}")
                val changed = settings()?.headMaxTime == limit + 1
                send(BrushControls.headLimit(limit), "Ресурс насадки: возврат")
                val restored = settings()?.headMaxTime == limit
                if (changed) works++
                report("HEAD_LIMIT 0217 было=$limit ack=$ack меняется=$changed возвращено=$restored")
            }
            val end = settings()
            saveSettings(info, end)
            report("итоговые настройки ${end?.raw?.toHex()}")
            "Проверка закончена: работает $works настроек, подробности в журнале"
        }
    }

    fun setToggle(t: BrushControls.Toggle, on: Boolean) =
        applySettings(listOf(t.command(on) to "${t.title}: ${if (on) "вкл" else "выкл"}"), "${t.title}: ${if (on) "включено" else "выключено"}")

    fun setLanguage(id: Int) {
        val name = BrushControls.LANGUAGES.firstOrNull { it.first == id }?.second ?: "№$id"
        applySettings(listOf(BrushControls.language(id) to "Язык: $name"), "Язык щётки: $name")
    }

    fun resetHead() = applySettings(listOf(BrushControls.HEAD_RESET to "Сброс счётчиков насадки"), "Счётчики насадки обнулены")

    fun setHeadLimit(value: Int) = applySettings(listOf(BrushControls.headLimit(value) to "Ресурс насадки: $value"), "Ресурс насадки: $value")

    fun sendProgramme(p: Programme) = sendProgramme(p.num, p.steps, p.name, p.prompt)

    fun saveProgramme(p: Programme) = custom.save(p)

    fun deleteProgramme(p: Programme) = custom.delete(p.num)

    fun newProgrammeNum(): Int? = custom.nextNum()

    /** Debug: a brush record with an address no device has, so the switcher can be tried with one brush. */
    fun addFakeBrush(label: String, address: String? = null) {
        val current = brush.value
        prefs.add(
            BrushInfo(
                id = BrushInfo.newId(),
                address = address ?: "00:00:00:00:00:%02X".format(brushes.value.size),
                label = label,
                // Like the X Pro Digital, so the screens that depend on the model show.
                model = "OCLEANY3PB",
                firmware = "1.0.2.5",
                hardware = "HH 0003 0004",
            ),
        )
        current?.let { prefs.select(it.id) }
    }

    /** Debug: a session that just ended on the brush on screen, to try the guided mode's comparison without a brush. */
    fun addFakeSession() {
        val info = brush.value ?: return
        val now = System.currentTimeMillis() / 1000
        val zones = listOf(14, 9, 16, 12, 15, 18, 11, 7)
        viewModelScope.launch {
            store(info.id).addAll(listOf(BrushSession(epochSec = now - 60, durationSec = zones.sum(), plannedSec = 120, score = 74, zones = zones)))
        }
    }

    /** Debug: sets the stored growth total, e.g. to the points the brush really had before an experiment. */
    fun setGrowthTotal(total: Int) {
        val info = brush.value ?: return
        prefs.update(info.id, durable = true) { it.copy(growthTotal = total) }
    }

    /** Debug: writes raw hex commands one by one and reads the settings back; replies go to the log. */
    private val _watch = MutableStateFlow<BrushWatch?>(null)

    /** The guided brushing screen's view of the brush; null while nothing watches. */
    val watch: StateFlow<BrushWatch?> = _watch
    private var watchJob: kotlinx.coroutines.Job? = null

    /**
     * Connects to the brush on screen and follows its brushing for the guided screen: the guide starts with the motor
     * and the session is read as soon as the brush has stored it.
     */
    fun startWatch() {
        val info = brush.value ?: return
        // A watch that saw its record is finishing (it still reads the record): the next one queues behind it.
        if (watchJob?.isActive == true && _watch.value != BrushWatch.Saved) return
        _watch.value = BrushWatch.Connecting
        watchJob = queue.enqueue(info.id, BluetoothQueue.Kind.WRITE) {
            busyBrush = info.id
            try {
                client.watch(info.address, onState = { _watch.value = it }) { step ->
                    android.util.Log.i("FreeBrushWatch", step)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _watch.value = BrushWatch.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                busyBrush = null
            }
            // Stored: read it right away, the brush is still awake.
            if (_watch.value == BrushWatch.Saved) syncNow()
        }
    }

    fun stopWatch() {
        watchJob?.cancel()
        watchJob = null
        _watch.value = null
    }

    private val ota by lazy { OcleanOta("FreeBrush/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.RELEASE})") }
    private val _firmware = MutableStateFlow<FirmwareState>(FirmwareState.Idle)

    /** The firmware update of the brush on screen. */
    val firmware: StateFlow<FirmwareState> = _firmware
    private var firmwareJob: kotlinx.coroutines.Job? = null

    init {
        // A prepared file belongs to the brush on screen: switching to another brush or device drops it.
        viewModelScope.launch {
            prefs.current.map { it?.id to it?.address }.distinctUntilChanged().drop(1).collect {
                if (_firmware.value !is FirmwareState.Installing) {
                    firmwareJob?.cancel()
                    _firmware.value = FirmwareState.Idle
                }
            }
        }
    }

    private fun firmwareIdle() = _firmware.value !is FirmwareState.Installing && _firmware.value !is FirmwareState.Working

    /**
     * Asks Oclean's server for the firmware of the brush on screen (it answers by the brush's MAC). [again] fetches
     * the newest file even when the brush already runs it, to install it once more.
     */
    fun firmwareFromServer(again: Boolean = false) {
        val info = brush.value ?: return
        if (!firmwareIdle()) return
        _firmware.value = FirmwareState.Working("Спрашиваю сервер Oclean…")
        firmwareJob = viewModelScope.launch {
            _firmware.value = try {
                withContext(Dispatchers.IO) {
                    val current = OcleanOta.serverVersion(info.firmware)
                    val offer = ota.offer(info.address, info.model.orEmpty(), if (again) "1.0.0.0" else current)
                    val url = offer.url
                    val newer = OcleanOta.compareVersions(offer.version, current) > 0
                    if (url == null && OcleanOta.compareVersions(offer.version, current) < 0) {
                        // An unknown MAC gets 1.0.0.0 and no file.
                        FirmwareState.Failed("Сервер Oclean не знает эту щётку: он выдаёт прошивку только щёткам из аккаунта Oclean. Можно поставить файл OTA.bin.")
                    } else if (url == null || (!again && !newer)) {
                        FirmwareState.UpToDate(offer.version.takeIf { newer || again }?.ifBlank { null } ?: current)
                    } else {
                        _firmware.value = FirmwareState.Working("Загружаю ${offer.version}…")
                        val fw = GoodixDfu.parseFirmware(ota.download(url))
                        val label = when (OcleanOta.compareVersions(offer.version, current).coerceIn(-1, 1)) {
                            1 -> "${offer.version} с сервера Oclean"
                            0 -> "${offer.version} с сервера Oclean, та же, что стоит"
                            else -> "${offer.version} с сервера Oclean: старее, чем стоит ($current)"
                        }
                        FirmwareState.Ready(info.id, info.address, fw, label, trusted = true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FirmwareState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Loads an OTA.bin the user picked; checks it as far as the file alone allows. */
    fun firmwareFromFile(uri: Uri) {
        val info = brush.value ?: return
        if (!firmwareIdle()) return
        _firmware.value = FirmwareState.Working("Читаю файл…")
        firmwareJob = viewModelScope.launch {
            _firmware.value = try {
                val (name, bytes) = withContext(Dispatchers.IO) { readFirmwareFile(uri) }
                FirmwareState.Ready(info.id, info.address, GoodixDfu.parseFirmware(bytes), name, trusted = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FirmwareState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun readFirmwareFile(uri: Uri): Pair<String, ByteArray> {
        val resolver = getApplication<Application>().contentResolver
        val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "файл"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                out.write(chunk, 0, read)
                require(out.size() <= 2 * 1024 * 1024) { "Файл больше 2 МБ: это не прошивка щётки" }
            }
            out.toByteArray()
        } ?: throw IllegalArgumentException("Файл не открылся")
        return name to bytes
    }

    /**
     * Writes the ready firmware to its brush, then waits for the restart and checks by the boot info that the brush
     * runs it. The user confirmed the risks on screen before this is called.
     */
    fun installFirmware() {
        val ready = _firmware.value as? FirmwareState.Ready ?: return
        val info = brushes.value.firstOrNull { it.id == ready.brushId && it.address == ready.address }
        if (info == null) {
            _firmware.value = FirmwareState.Failed("Щётка сменилась: выбери прошивку заново")
            return
        }
        val total = ready.firmware.bytes.size
        _firmware.value = FirmwareState.Installing(if (queue.isBusy) "Жду щётку…" else "Подключаюсь…", 0, total)
        firmwareJob = queue.enqueue(info.id, BluetoothQueue.Kind.WRITE) {
            busyBrush = info.id
            val lines = mutableListOf<String>()
            _log.value = lines.toList()
            var stage = "Подключаюсь…"
            var done = 0
            val onStep: (String) -> Unit = { step ->
                lines += step
                _log.value = lines.toList()
                android.util.Log.i("FreeBrushDfu", step)
                if (!step.startsWith("←") && !step.startsWith("→")) {
                    stage = step
                    _firmware.value = FirmwareState.Installing(stage, done, total)
                }
            }
            var verified: Boolean? = null
            try {
                val known = brushes.value.firstOrNull { it.id == info.id }?.firmwareImage
                val result = client.installFirmware(info.address, ready.firmware, ready.trusted, known, onChip = { chip ->
                    // Before anything is written: the list that names the image is about to change.
                    chip.running?.comment?.let { name -> prefs.update(info.id, durable = true) { it.copy(firmwareImage = name) } }
                }, onProgress = { written, all ->
                    done = written
                    _firmware.value = FirmwareState.Installing("Записываю образ…", written, all)
                }, onStep = onStep)
                onStep(if (result.answered) "Щётка приняла образ, перезапускается" else "Щётка перезапустилась, не ответив")
                // The restart takes a few seconds; then the boot info tells which image it runs. The dialog cannot be
                // closed, so the check gets one budget for all attempts.
                verified = withTimeoutOrNull(90_000) {
                    var answer: Boolean? = null
                    var attempt = 0
                    while (answer == null) {
                        delay(if (++attempt == 1) 8_000 else 5_000)
                        onStep("Проверяю щётку после перезапуска ($attempt)…")
                        answer = try {
                            client.verifyFirmware(info.address, ready.firmware, onStep)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                    answer
                }
                val same = GoodixDfuUpdate.installed(result.before, ready.firmware)
                _firmware.value = when (verified) {
                    true -> FirmwareState.Installed(
                        if (same) "Прошивка записана заново, щётка работает с ней" else "Прошивка установлена, щётка работает с новым образом",
                    )
                    false -> FirmwareState.Failed("Щётка перезапустилась со старым образом: новый не принят. Можно попробовать снова.", ready)
                    null -> FirmwareState.Installed("Образ записан, но щётка пока не отвечает. Возьми её в руку и синхронизируй через минуту.")
                }
            } catch (e: CancellationException) {
                _firmware.value = FirmwareState.Failed("Остановлено", ready)
                throw e
            } catch (e: Exception) {
                lines += "Ошибка: ${e.message}"
                _log.value = lines.toList()
                _firmware.value = FirmwareState.Failed(e.message ?: e.javaClass.simpleName, ready)
            } finally {
                busyBrush = null
            }
            // The version the brush reports, for the screen.
            if (verified == true) syncNow()
        }
    }

    /** Closes a result or message; a failed install goes back to its file, ready to try again. */
    fun closeFirmware() {
        when (val s = _firmware.value) {
            is FirmwareState.Installing -> return
            is FirmwareState.Failed -> _firmware.value = s.retry ?: FirmwareState.Idle
            else -> {
                firmwareJob?.cancel()
                _firmware.value = FirmwareState.Idle
            }
        }
    }

    /** Debug: reads the brush's DFU state (chip, boot info, images); writes nothing. */
    fun dfuInfo() = onBrush("Читаю загрузчик…") { info, onStep ->
        val state = client.dfuInfo(info.address) { line ->
            onStep(line)
            android.util.Log.i("FreeBrushProbe", line)
        }
        "Загрузчик: чип ${state.chipId}, образов ${state.images.size}"
    }

    /**
     * Debug: checks the firmware file [name] (in the app's external files directory, where adb can push it) against
     * the brush and logs the plan; writes nothing.
     */
    fun dfuPlan(name: String) = onBrush("Проверяю прошивку…") { info, onStep ->
        val log: (String) -> Unit = { line ->
            onStep(line)
            android.util.Log.i("FreeBrushProbe", line)
        }
        try {
            val file = File(getApplication<Application>().getExternalFilesDir(null), name)
            val plan = client.planFirmware(info.address, GoodixDfu.parseFirmware(file.readBytes()), trusted = false, info.firmwareImage, onStep = log)
            val list = plan.list?.joinToString { it.comment }?.let { "список станет [$it]" } ?: "список без изменений"
            "План: $list, запись с 0x%08x, ${plan.firmware.bytes.size} байт".format(plan.start.loadAddress).also(log)
        } catch (e: Exception) {
            log("План отклонён: ${e.message}")
            throw e
        }
    }

    /** Debug: holds the connection for [seconds] during a brushing and logs every packet (logcat tag FreeBrushProbe). */
    fun listen(seconds: Int) = onBrush("Слушаю щётку…") { info, onStep ->
        client.listen(info.address, seconds) { line ->
            onStep(line)
            android.util.Log.i("FreeBrushProbe", line)
        }
    }

    fun sendRaw(commands: List<String>) = onBrush("Отправка команд…") { info, onStep ->
        val result = client.apply(info.address, commands.map { hex(it) to "→ $it" }, onStep)
        saveSettings(info, result.settings)
        android.util.Log.i("FreeBrushProbe", "raw ${commands.joinToString()} confirmed=${result.confirmed}/${result.total} settings=${result.settings?.raw?.toHex()}")
        "Подтверждено ${result.confirmed} из ${result.total}"
    }

    /** Writes the history to a CSV file and returns a shareable content URI. */
    suspend fun exportCsv(): Uri = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val dir = File(app.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, "freebrush-history.csv")
        file.writeText(sessions.value.toCsv())
        FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    fun clearHistory() {
        val id = brush.value?.id ?: return
        viewModelScope.launch { store(id).clear() }
    }

    private companion object {
        const val SCAN_MS = 4_000L
        const val AUTO_SYNC_GAP_MS = 2 * 60_000L
    }
}
