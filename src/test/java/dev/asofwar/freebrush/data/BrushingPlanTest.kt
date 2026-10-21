package dev.asofwar.freebrush.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushingPlanTest {
    private fun session(epoch: Long, zones: List<Int>) =
        BrushSession(epochSec = epoch, durationSec = zones.sum(), score = 70, zones = zones)

    @Test
    fun withoutHistoryTheTimeIsSplitEvenly() {
        val plan = BrushingPlan.plan(120, emptyList())
        assertEquals(BrushingPlan.ORDER, plan.map { it.zone })
        assertEquals(List(8) { 15 }, plan.map { it.seconds })
        assertTrue(plan.all { it.extra == 0 })
    }

    @Test
    fun secondsAlwaysAddUpToTheProgramme() {
        for (total in listOf(80, 120, 125, 180, 240, 7)) {
            val history = listOf(session(1, listOf(30, 2, 20, 0, 25, 10, 15, 18)))
            assertEquals(maxOf(total, 8), BrushingPlan.plan(total, history).sumOf { it.seconds })
        }
    }

    @Test
    fun zonesLeftShortGetMoreTime() {
        // Top-left inside (zone 1) and bottom-left inside (zone 3) were nearly skipped.
        val history = List(5) { session(it.toLong(), listOf(20, 2, 20, 1, 20, 20, 20, 17)) }
        val plan = BrushingPlan.plan(120, history).associateBy { it.zone }
        assertTrue(plan.getValue(1).seconds > 15 && plan.getValue(1).extra > 0)
        assertTrue(plan.getValue(3).seconds > 15)
        assertTrue(plan.getValue(0).seconds < 15)
        // Never more than one and a half times the even share.
        assertTrue(plan.values.all { it.seconds <= 23 })
    }

    @Test
    fun theProgrammeLengthComesFromTheBrushMode() {
        val settings = ByteArray(34)
        assertEquals(240, BrushingPlan.programmeSeconds(settings, emptyList(), morning = true, auto = true, active = null))
        assertEquals(180, BrushingPlan.programmeSeconds(settings, emptyList(), morning = false, auto = true, active = null))
        assertEquals(80, BrushingPlan.programmeSeconds(settings, emptyList(), morning = true, auto = false, active = 88))
        assertEquals(BrushingPlan.FALLBACK_SECONDS, BrushingPlan.programmeSeconds(null, emptyList(), true, false, 88))
        assertEquals(BrushingPlan.FALLBACK_SECONDS, BrushingPlan.programmeSeconds(settings, emptyList(), true, false, 199))
    }
}
