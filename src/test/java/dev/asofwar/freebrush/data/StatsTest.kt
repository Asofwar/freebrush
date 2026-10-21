package dev.asofwar.freebrush.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StatsTest {
    private fun session(duration: Int, zones: List<Int>) = BrushSession(epochSec = 0, durationSec = duration, zones = zones)

    @Test
    fun zonesInSecondsAreKept() {
        // X Pro Digital: 113 s brushed, zones add up to 99.
        val zones = listOf(4, 3, 30, 6, 22, 3, 28, 3)
        assertEquals(zones, session(113, zones).zoneSeconds())
    }

    @Test
    fun zoneWeightsAreScaledToTimeBrushed() {
        // X Pro Elite: 11 s brushed, weights add up to 97.
        val s = session(11, listOf(15, 13, 38, 16, 1, 0, 13, 1)).zoneSeconds()
        assertEquals(listOf(1, 1, 4, 1, 0, 0, 1, 0), s)
    }

    @Test
    fun streakCountsBackFromYesterdayWhenTodayIsEmpty() {
        val today = LocalDate.of(2026, 10, 21)
        fun on(day: LocalDate) = BrushSession(
            epochSec = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toEpochSecond(),
            durationSec = 120,
        )
        val list = listOf(on(today.minusDays(1)), on(today.minusDays(2)), on(today.minusDays(4)))
        assertEquals(2, list.streakDays(today))
    }
}
