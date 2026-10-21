package dev.asofwar.freebrush

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.asofwar.freebrush.ble.BrushControls
import dev.asofwar.freebrush.ble.FoundDevice
import dev.asofwar.freebrush.ble.OcleanProtocol
import dev.asofwar.freebrush.ble.OcleanClient
import dev.asofwar.freebrush.ble.hex
import dev.asofwar.freebrush.ble.toHex
import dev.asofwar.freebrush.data.BrushInfo
import dev.asofwar.freebrush.data.BrushPrefs
import dev.asofwar.freebrush.data.CustomProgrammes
import dev.asofwar.freebrush.data.Programme
import dev.asofwar.freebrush.data.SessionStore
import dev.asofwar.freebrush.data.toCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val store = SessionStore(app)
    private val prefs = BrushPrefs(app)
    private val client = OcleanClient(app)
    private val custom = CustomProgrammes(app)

    /** The user's own programmes. */
    val customProgrammes = custom.items

    val sessions = store.sessions
    val brush: StateFlow<BrushInfo?> = prefs.brush

    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val sync: StateFlow<SyncState> = _sync

    /** Lines of the last sync, shown on the brush screen for troubleshooting. */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val _action = MutableStateFlow<ActionState>(ActionState.Idle)
    val action: StateFlow<ActionState> = _action

    /** The one Bluetooth job at a time: a sync or a write. The brush accepts a single connection. */
    private var syncJob: Job? = null

    init {
        viewModelScope.launch { store.load() }
    }

    fun pair(device: FoundDevice) {
        prefs.save(BrushInfo(address = device.address, name = device.name))
        syncNow()
    }

    fun forget() {
        syncJob?.cancel()
        prefs.save(null)
        _sync.value = SyncState.Idle
    }

    fun syncNow() {
        val info = brush.value ?: return
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            val lines = mutableListOf<String>()
            _log.value = lines.toList()
            _sync.value = SyncState.Running("Подключение…")
            try {
                val result = client.sync(info.address, info.growthTotal) { step ->
                    lines += step
                    _log.value = lines.toList()
                    // Raw packet lines go to the log only.
                    if (!step.startsWith("←") && !step.startsWith("→")) _sync.value = SyncState.Running(step)
                }
                val added = store.addAll(result.sessions)
                prefs.save(
                    info.copy(
                        model = result.model ?: info.model,
                        firmware = result.firmware ?: info.firmware,
                        hardware = result.hardware ?: info.hardware,
                        battery = result.battery ?: info.battery,
                        headDays = result.settings?.headUsedDays ?: info.headDays,
                        headUses = result.settings?.headUsedTimes ?: info.headUses,
                        headWear = result.settings?.headUsedTime ?: info.headWear,
                        headWearMax = result.settings?.headMaxTime ?: info.headWearMax,
                        settingsHex = result.settings?.raw?.toHex() ?: info.settingsHex,
                        growthTotal = result.growthTotal ?: info.growthTotal,
                        lastSyncMillis = System.currentTimeMillis(),
                    ),
                )
                _sync.value = SyncState.Done(added)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lines += "Ошибка: ${e.message}"
                _log.value = lines.toList()
                _sync.value = SyncState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Stores what the brush reported about its settings and brush head. */
    private fun saveSettings(settings: OcleanProtocol.Settings?) {
        val info = brush.value ?: return
        settings ?: return
        prefs.save(
            info.copy(
                headDays = settings.headUsedDays,
                headUses = settings.headUsedTimes,
                headWear = settings.headUsedTime,
                headWearMax = settings.headMaxTime,
                settingsHex = settings.raw.toHex(),
            ),
        )
    }

    /** Runs [block] on the brush as the single Bluetooth job, reporting progress in [action] and the log. */
    private fun onBrush(start: String, block: suspend (address: String, onStep: (String) -> Unit) -> String) {
        val info = brush.value ?: return
        // Queue behind a running sync instead of refusing: the brush takes one connection at a time.
        val previous = syncJob
        syncJob = viewModelScope.launch {
            previous?.join()
            val lines = mutableListOf<String>()
            _log.value = lines.toList()
            _action.value = ActionState.Running(start)
            try {
                val done = block(info.address) { step ->
                    lines += step
                    _log.value = lines.toList()
                    if (!step.startsWith("←") && !step.startsWith("→")) _action.value = ActionState.Running(step)
                }
                _action.value = ActionState.Done(done)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lines += "Ошибка: ${e.message}"
                _log.value = lines.toList()
                _action.value = ActionState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Writes settings commands and stores what the brush read back. */
    fun applySettings(commands: List<Pair<ByteArray, String>>, done: String) = onBrush("Записываю на щётку…") { address, onStep ->
        val result = client.apply(address, commands, onStep)
        saveSettings(result.settings)
        if (result.confirmed == result.total) done else "Щётка подтвердила ${result.confirmed} из ${result.total}"
    }

    /** Sends a brushing programme to the brush (one or two packets) and reads back the active programme. */
    fun sendProgramme(pNum: Int, steps: List<BrushControls.Step>, name: String, prompt: Boolean = true) = onBrush("Отправляю «$name»…") { address, onStep ->
        client.connect(address, onStep) {
            val packets = BrushControls.programme(pNum, steps, prompt = prompt)
            var ok = true
            for ((i, packet) in packets.withIndex()) {
                if (!send(packet, "Программа, пакет ${i + 1} из ${packets.size}")) { ok = false; break }
            }
            val settings = settings()
            saveSettings(settings)
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
    fun runProbe() = onBrush("Проверка настроек…") { address, onStep ->
        client.connect(address, onStep) {
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
            saveSettings(end)
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

    /** Debug: sets the stored growth total, e.g. to the points the brush really had before an experiment. */
    fun setGrowthTotal(total: Int) {
        val info = brush.value ?: return
        prefs.save(info.copy(growthTotal = total))
    }

    /** Debug: writes raw hex commands one by one and reads the settings back; replies go to the log. */
    fun sendRaw(commands: List<String>) = onBrush("Отправка команд…") { address, onStep ->
        val result = client.apply(address, commands.map { hex(it) to "→ $it" }, onStep)
        saveSettings(result.settings)
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
        viewModelScope.launch { store.clear() }
    }
}
