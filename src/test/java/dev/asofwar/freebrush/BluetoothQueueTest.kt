package dev.asofwar.freebrush

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothQueueTest {
    @Test
    fun jobsRunOneAtATimeInOrder() = runTest {
        val queue = BluetoothQueue(backgroundScope)
        val log = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        queue.enqueue("a", BluetoothQueue.Kind.SYNC) { log += "a start"; gate.await(); log += "a end" }
        queue.enqueue("b", BluetoothQueue.Kind.WRITE) { log += "b" }
        testScheduler.runCurrent()
        assertEquals(listOf("a start"), log)
        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf("a start", "a end", "b"), log)
        assertFalse(queue.isBusy)
    }

    @Test
    fun cancellingABrushSkipsItsJobsButKeepsTheOrder() = runTest {
        val queue = BluetoothQueue(backgroundScope)
        val log = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        queue.enqueue("a", BluetoothQueue.Kind.SYNC) { gate.await(); log += "a" }
        queue.enqueue("b", BluetoothQueue.Kind.SYNC) { log += "b" }
        queue.enqueue("c", BluetoothQueue.Kind.SYNC) { log += "c" }
        testScheduler.runCurrent()

        queue.cancel("b")
        // Gone at once, so a new sync of b is not mistaken for a duplicate.
        assertFalse(queue.has("b", BluetoothQueue.Kind.SYNC))
        testScheduler.runCurrent()
        // c still waits for a, which is running: removing b did not let it jump the queue.
        assertTrue(log.isEmpty())

        gate.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf("a", "c"), log)
    }

    @Test
    fun cancellingTheRunningJobOfSeveralBrushes() = runTest {
        val queue = BluetoothQueue(backgroundScope)
        val log = mutableListOf<String>()
        queue.enqueue(null, BluetoothQueue.Kind.SYNC) { CompletableDeferred<Unit>().await(); log += "all" }
        queue.enqueue("b", BluetoothQueue.Kind.WRITE) { log += "b" }
        testScheduler.runCurrent()

        // The sync of all brushes is on brush "a" now; "a" is removed.
        queue.cancel("a", alsoRunning = true)
        testScheduler.runCurrent()
        assertEquals(listOf("b"), log)
    }

    @Test
    fun hasSeesQueuedJobsByBrushAndKind() = runTest {
        val queue = BluetoothQueue(backgroundScope)
        queue.enqueue("a", BluetoothQueue.Kind.SYNC) { CompletableDeferred<Unit>().await() }
        assertTrue(queue.has("a", BluetoothQueue.Kind.SYNC))
        assertFalse(queue.has("a", BluetoothQueue.Kind.WRITE))
        assertFalse(queue.has(null, BluetoothQueue.Kind.SYNC))
    }
}
