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
import java.io.FileOutputStream

/**
 * Brushing history of one brush ([brushId]) in a JSON file in app storage.
 *
 * A few sessions a day stay well under a megabyte for years, so a plain file beats a database here.
 * Writes go to a synced temporary file that then replaces the old one, which is kept as a backup: a crash or a power
 * cut leaves either file readable. A file that cannot be parsed is set aside as `.corrupt-<time>` instead of
 * crashing the app on every start.
 */
class SessionStore(private val file: File) {
    constructor(context: Context, brushId: String) : this(fileFor(context, brushId))

    private val backup = File(file.path + ".bak")
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
            backup.delete()
            _sessions.value = emptyList()
        }
    }

    companion object {
        fun fileFor(context: Context, brushId: String) = File(context.filesDir, "sessions-$brushId.json")
    }

    /**
     * A full record replaces the short head seen earlier for the same session. A stored full record is
     * never overwritten: a misaligned re-read of the same session must not replace good data.
     */
    private fun merge(old: BrushSession, new: BrushSession) = if (!old.isComplete && new.isComplete) new else old

    private fun readFile(): List<BrushSession> {
        for (f in listOf(file, backup)) {
            if (!f.exists()) continue
            try {
                val arr = JSONArray(f.readText())
                return List(arr.length()) { BrushSession.fromJson(arr.getJSONObject(it)) }.sortedByDescending { it.time }
            } catch (e: Exception) {
                // Keep the damaged file for a device report, and fall back to the backup.
                f.renameTo(File(f.path + ".corrupt-" + System.currentTimeMillis()))
            }
        }
        return emptyList()
    }

    private fun writeFile(all: List<BrushSession>) {
        val arr = JSONArray()
        all.forEach { arr.put(it.toJson()) }
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(arr.toString().toByteArray())
            out.fd.sync()
        }
        if (file.exists()) {
            backup.delete()
            file.renameTo(backup)
        }
        if (!tmp.renameTo(file)) throw java.io.IOException("Не удалось сохранить историю")
    }
}
