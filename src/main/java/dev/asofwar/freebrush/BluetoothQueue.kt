package dev.asofwar.freebrush

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Bluetooth jobs run one at a time, in the order they were asked for: a brush accepts a single connection. Each job
 * knows the brush it is for, so forgetting or replacing a brush stops that brush's jobs, running or waiting, and
 * leaves the others alone. Confined to the thread of [scope] (the main thread in the app).
 */
class BluetoothQueue(private val scope: CoroutineScope) {
    enum class Kind { SYNC, WRITE }

    private class Entry(val brushId: String?, val kind: Kind) {
        lateinit var job: Job
    }

    private val entries = mutableListOf<Entry>()

    /** True while any job runs or waits. */
    val isBusy: Boolean get() = entries.isNotEmpty()

    /** Whether a job of [kind] for [brushId] runs or waits; null is a job for several brushes. */
    fun has(brushId: String?, kind: Kind): Boolean = entries.any { it.brushId == brushId && it.kind == kind }

    /** Queues [block] behind every job queued before it, even ones cancelled meanwhile. */
    fun enqueue(brushId: String?, kind: Kind, block: suspend () -> Unit): Job {
        val before = entries.map { it.job }
        val entry = Entry(brushId, kind)
        entry.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                before.joinAll()
                block()
            } finally {
                entries.remove(entry)
            }
        }
        entries += entry
        entry.job.start()
        return entry.job
    }

    /**
     * Cancels the jobs of [brushId], and the running job too when [alsoRunning] (a job for several brushes that is on
     * this one now). They leave the queue at once, so a new job for the brush is not mistaken for a duplicate.
     */
    fun cancel(brushId: String, alsoRunning: Boolean = false) {
        val head = entries.firstOrNull()
        val doomed = entries.filter { it.brushId == brushId || (alsoRunning && it === head) }
        entries.removeAll(doomed)
        doomed.forEach { it.job.cancel() }
    }
}
