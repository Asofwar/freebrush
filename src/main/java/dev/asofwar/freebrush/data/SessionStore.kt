package dev.asofwar.freebrush.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * Brushing history in a JSON file in app storage.
 *
 * A few sessions a day stay well under a megabyte for years, so a plain file beats a database here.
 * Writes go to a temporary file first and replace the old one, so a crash never leaves half a file.
 */
class SessionStore(context: Context) {
    private val file = File(context.filesDir, "sessions.json")
    private val lock = Mutex()
    private val _sessions = MutableStateFlow<List<BrushSession>>(emptyList())

    /** All sessions, newest first. */
    val sessions: StateFlow<List<BrushSession>> = _sessions

    suspend fun load() = withContext(Dispatchers.IO) {
        lock.withLock { _sessions.value = readFile() }
    }

    /** Adds sessions that are not stored yet; returns how many were new. */
    suspend fun addAll(newSessions: List<BrushSession>): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            val byKey = readFile().associateByTo(LinkedHashMap()) { it.key }
            var added = 0
            for (s in newSessions) {
                val old = byKey[s.key]
                if (old == null) added++
                // A later read of the same session may carry fields the first one lacked (score, zones).
                byKey[s.key] = if (old == null) s else merge(old, s)
            }
            val all = byKey.values.sortedByDescending { it.time }
            writeFile(all)
            _sessions.value = all
            added
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock {
            file.delete()
            _sessions.value = emptyList()
        }
    }

    private fun merge(old: BrushSession, new: BrushSession) = old.copy(
        validDurationSec = new.validDurationSec ?: old.validDurationSec,
        score = new.score ?: old.score,
        schemeId = new.schemeId ?: old.schemeId,
        zones = new.zones.ifEmpty { old.zones },
        pressure = new.pressure.ifEmpty { old.pressure },
        overPressureCount = new.overPressureCount ?: old.overPressureCount,
        raw = if (new.raw.length > old.raw.length) new.raw else old.raw,
    )

    private fun readFile(): List<BrushSession> {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        return List(arr.length()) { BrushSession.fromJson(arr.getJSONObject(it)) }
            .sortedByDescending { it.time }
    }

    private fun writeFile(all: List<BrushSession>) {
        val arr = JSONArray()
        all.forEach { arr.put(it.toJson()) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
