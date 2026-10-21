package dev.asofwar.freebrush

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.asofwar.freebrush.ble.FoundDevice
import dev.asofwar.freebrush.ble.OcleanClient
import dev.asofwar.freebrush.data.BrushInfo
import dev.asofwar.freebrush.data.BrushPrefs
import dev.asofwar.freebrush.data.SessionStore
import dev.asofwar.freebrush.data.toCsv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    val sessions = store.sessions
    val brush: StateFlow<BrushInfo?> = prefs.brush

    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val sync: StateFlow<SyncState> = _sync

    /** Lines of the last sync, shown on the brush screen for troubleshooting. */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

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
                val result = client.sync(info.address) { step ->
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
                        headDays = result.headDays ?: info.headDays,
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
