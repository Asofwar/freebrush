package dev.asofwar.freebrush

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.asofwar.freebrush.ble.BrushControls
import dev.asofwar.freebrush.ble.FoundDevice
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

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

    /** The one Bluetooth job at a time: a sync or a write. The brush accepts a single connection. */
    private var syncJob: Job? = null

    /** Brush the Bluetooth job is talking to right now, and brushes whose sync waits in the queue. */
    private var busyBrush: String? = null
    private val queued = mutableSetOf<String>()

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
        // Only a job on this very brush stops; another person's sync finishes and this one queues behind it.
        if (busyBrush == info.id) syncJob?.cancel()
        prefs.update(info.id) { it.replacedBy(device.address, device.name) }
        _sync.value = SyncState.Idle
        syncNow()
    }

    fun select(id: String) {
        if (id == brush.value?.id) return
        prefs.select(id)
        // A sync of another brush keeps running and stays visible; otherwise the old result belongs to the old brush.
        if (syncJob?.isActive != true) {
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
        if (busyBrush == info.id) syncJob?.cancel()
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
        if (info.id in queued || (busyBrush == info.id && syncJob?.isActive == true)) return
        queued += info.id
        val previous = syncJob
        syncJob = viewModelScope.launch {
            try {
                previous?.join()
            } finally {
                queued -= info.id
            }
            syncAll(listOf(info))
        }
    }

    /**
     * The sync when the app opens. With several brushes it first listens for a few seconds which of them are awake
     * and syncs those, the one on screen first; with one brush, or when none is heard, it tries the one on screen.
     */
    fun syncOnOpen() {
        val all = brushes.value
        val shown = brush.value ?: return
        if (all.size < 2) return syncNow()
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            _log.value = emptyList()
            _sync.value = SyncState.Running("Ищу проснувшиеся щётки…")
            val awake = awakeAddresses(all.map { it.address }.toSet())
            val targets = all.filter { it.address in awake }.sortedByDescending { it.id == shown.id }
            syncAll(targets.ifEmpty { listOf(shown) })
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

    /** Syncs [targets] one after another; one brush failing does not stop the others. */
    private suspend fun syncAll(targets: List<BrushInfo>) {
        val named = brushes.value.size > 1
        val lines = mutableListOf<String>()
        _log.value = emptyList()
        _sync.value = SyncState.Running("Подключение…")
        var added = 0
        var synced = 0
        var failure: String? = null
        for (target in targets) {
            val info = prefs.get(target.id) ?: continue
            val prefix = if (named) "${info.title}: " else ""
            busyBrush = info.id
            try {
                added += syncOne(info, prefix, lines)
                synced++
            } catch (e: CancellationException) {
                busyBrush = null
                throw e
            } catch (e: Exception) {
                lines += prefix + "Ошибка: ${e.message}"
                _log.value = lines.toList()
                failure = prefix + (e.message ?: e.javaClass.simpleName)
            }
        }
        busyBrush = null
        _sync.value = if (synced == 0 && failure != null) SyncState.Failed(failure) else SyncState.Done(added)
    }

    private suspend fun syncOne(info: BrushInfo, prefix: String, lines: MutableList<String>): Int {
        val result = client.sync(
            info.address,
            info.growthTotal,
            onGrowth = { total -> prefs.update(info.id) { it.copy(growthTotal = total) } },
        ) { step ->
            lines += prefix + step
            _log.value = lines.toList()
            // Raw packet lines go to the log only.
            if (!step.startsWith("←") && !step.startsWith("→")) _sync.value = SyncState.Running(prefix + step)
        }
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

    /** Stores what brush [id] reported about its settings and brush head. */
    private fun saveSettings(id: String, settings: OcleanProtocol.Settings?) {
        settings ?: return
        prefs.update(id) {
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
        val previous = syncJob
        syncJob = viewModelScope.launch {
            previous?.join()
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
        saveSettings(info.id, result.settings)
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
            saveSettings(info.id, settings)
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
            saveSettings(info.id, end)
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
    fun addFakeBrush(label: String) {
        val current = brush.value
        prefs.add(BrushInfo(id = BrushInfo.newId(), address = "00:00:00:00:00:%02X".format(brushes.value.size), label = label))
        current?.let { prefs.select(it.id) }
    }

    /** Debug: sets the stored growth total, e.g. to the points the brush really had before an experiment. */
    fun setGrowthTotal(total: Int) {
        val info = brush.value ?: return
        prefs.update(info.id) { it.copy(growthTotal = total) }
    }

    /** Debug: writes raw hex commands one by one and reads the settings back; replies go to the log. */
    fun sendRaw(commands: List<String>) = onBrush("Отправка команд…") { info, onStep ->
        val result = client.apply(info.address, commands.map { hex(it) to "→ $it" }, onStep)
        saveSettings(info.id, result.settings)
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
    }
}
