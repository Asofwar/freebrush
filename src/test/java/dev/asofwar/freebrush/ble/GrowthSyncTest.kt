package dev.asofwar.freebrush.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrowthSyncTest {
    /** A brush whose counter replies come from [counters] in turn and whose base write ends as [write]. */
    private class FakeBrush(vararg counters: Int?, val write: BaseWrite = BaseWrite.ACKED) : GrowthLink {
        private val replies = ArrayDeque(counters.toList())
        val written = mutableListOf<Int>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun readCounter(): Int? = replies.removeFirstOrNull()

        override suspend fun writeBase(total: Int, onDelivered: () -> Unit): BaseWrite {
            written += total
            if (write != BaseWrite.FAILED) onDelivered()
            gate?.await()
            return write
        }
    }

    private fun run(brush: FakeBrush, total: Int): Pair<Int?, List<Int>> {
        val stored = mutableListOf<Int>()
        var result: Int? = -1
        runTest { result = syncGrowth(brush, total, onGrowth = { stored += it }, onStep = {}) }
        return result to stored
    }

    @Test
    fun ackedWriteStoresTheNewTotal() {
        val brush = FakeBrush(3)
        assertEquals(7 to listOf(7), run(brush, 4))
        assertEquals(listOf(7), brush.written)
    }

    @Test
    fun noNewPointsWritesNothing() {
        val brush = FakeBrush(0)
        assertEquals(4 to emptyList<Int>(), run(brush, 4))
        assertTrue(brush.written.isEmpty())
    }

    @Test
    fun unansweredCounterWritesNothing() {
        val brush = FakeBrush(null)
        val (result, stored) = run(brush, 4)
        assertNull(result)
        assertTrue(stored.isEmpty() && brush.written.isEmpty())
    }

    @Test
    fun failedWriteKeepsTheOldTotal() {
        val (result, stored) = run(FakeBrush(3, write = BaseWrite.FAILED), 4)
        assertNull(result)
        assertTrue(stored.isEmpty())
    }

    @Test
    fun lostAckWithClearedCounterKeepsTheNewTotal() {
        // The brush took the base (counter back at 0) but its "OK" was lost.
        assertEquals(7 to listOf(7), run(FakeBrush(3, 0, write = BaseWrite.UNCONFIRMED), 4))
    }

    @Test
    fun lostAckWithUnreadableCounterKeepsTheNewTotal() {
        assertEquals(7 to listOf(7), run(FakeBrush(3, null, write = BaseWrite.UNCONFIRMED), 4))
    }

    @Test
    fun ignoredWritePutsTheOldTotalBack() {
        // Counter still holds the points: the brush did not take the base.
        assertEquals(4 to listOf(7, 4), run(FakeBrush(3, 3, write = BaseWrite.UNCONFIRMED), 4))
    }

    @Test
    fun totalStopsAtSixteenBits() {
        assertEquals(0xFFFF to listOf(0xFFFF), run(FakeBrush(10), 0xFFFF - 2))
    }

    @Test
    fun cancellingTheSyncDuringTheWriteStillStoresTheTotal() = runTest {
        val brush = FakeBrush(3).apply { gate = CompletableDeferred() }
        val stored = mutableListOf<Int>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { syncGrowth(brush, 4, { stored += it }, {}) }
        // The write has reached the brush; the user leaves the screen before the ack.
        job.cancel()
        brush.gate!!.complete(Unit)
        job.join()
        assertEquals(listOf(7), stored)
    }
}
