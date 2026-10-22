package dev.asofwar.freebrush.ble

import dev.asofwar.freebrush.data.BrushSession
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

    private val none = AppPoints(0, until = 100, previousUntil = 100)

    private fun run(brush: FakeBrush, total: Int, app: AppPoints = none, marks: MutableList<Long>? = null): Pair<Int?, List<Int>> {
        val stored = mutableListOf<Int>()
        var result: Int? = -1
        runTest {
            result = syncGrowth(brush, total, app, onGrowth = { t, until ->
                stored += t
                marks?.add(until)
            }, onStep = {})
        }
        return result to stored
    }

    private fun session(epoch: Long, seconds: Int) = BrushSession(epochSec = epoch, durationSec = seconds, score = 70, zones = emptyList())

    @Test
    fun aPointForEveryBrushingOfTwoMinutesAfterTheMark() {
        val sessions = listOf(session(90, 150), session(110, 119), session(120, 120), session(130, 300), session(5_000, 200))
        // 90 is before the mark, 110 too short, 5000 later than now (a wrong brush clock).
        assertEquals(AppPoints(2, until = 130, previousUntil = 100), AppPoints.of(sessions, countedUntil = 100, nowSec = 1_000))
        assertEquals(AppPoints(0, until = 100, previousUntil = 100), AppPoints.of(listOf(session(110, 60)), 100, 1_000))
    }

    @Test
    fun brushingPointsAreWrittenWithTheBrushsOwn() {
        val brush = FakeBrush(3)
        val marks = mutableListOf<Long>()
        assertEquals(9 to listOf(9), run(brush, 4, AppPoints(2, until = 130, previousUntil = 100), marks))
        assertEquals(listOf(9), brush.written)
        assertEquals(listOf(130L), marks)
    }

    @Test
    fun brushingPointsAloneAreWritten() {
        // The X Pro Digital gave nothing for a 2.5-minute brushing with a score of 76 (2026-10-22).
        val brush = FakeBrush(0)
        assertEquals(5 to listOf(5), run(brush, 4, AppPoints(1, until = 130, previousUntil = 100)))
        assertEquals(listOf(5), brush.written)
    }

    @Test
    fun unconfirmedBrushingPointsAreCountedAgainNextTime() {
        // Nothing tells whether the brush took the base: the old total and mark go back, the next sync writes it again.
        val marks = mutableListOf<Long>()
        val result = run(FakeBrush(0, write = BaseWrite.UNCONFIRMED), 4, AppPoints(1, until = 130, previousUntil = 100), marks)
        assertEquals(4 to listOf(5, 4), result)
        assertEquals(listOf(130L, 100L), marks)
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
        val job = launch(start = CoroutineStart.UNDISPATCHED) { syncGrowth(brush, 4, none, { t, _ -> stored += t }, {}) }
        // The write has reached the brush; the user leaves the screen before the ack.
        job.cancel()
        brush.gate!!.complete(Unit)
        job.join()
        assertEquals(listOf(7), stored)
    }
}
