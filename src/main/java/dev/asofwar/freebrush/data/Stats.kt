package dev.asofwar.freebrush.data

import java.time.LocalDate

/** Sessions grouped by calendar day, newest day first. */
fun List<BrushSession>.byDay(): Map<LocalDate, List<BrushSession>> =
    groupBy { it.time.toLocalDate() }.toSortedMap(compareByDescending { it })

/** Consecutive days up to today (or yesterday, if today has no session yet) with at least one session. */
fun List<BrushSession>.streakDays(today: LocalDate = LocalDate.now()): Int {
    val days = mapTo(HashSet()) { it.time.toLocalDate() }
    var day = if (today in days) today else today.minusDays(1)
    var streak = 0
    while (day in days) {
        streak++
        day = day.minusDays(1)
    }
    return streak
}

fun List<BrushSession>.averageScore(): Int? =
    mapNotNull { it.score }.takeIf { it.isNotEmpty() }?.average()?.toInt()

fun List<BrushSession>.since(day: LocalDate): List<BrushSession> = filter { !it.time.toLocalDate().isBefore(day) }

fun List<BrushSession>.toCsv(): String = buildString {
    append("time,epoch,duration_s,planned_s,score,scheme")
    Zones.ALL.indices.forEach { append(",zone${it + 1}") }
    (1..5).forEach { append(",pressure$it") }
    append(",raw\n")
    for (s in this@toCsv) {
        append(s.time).append(',')
        append(s.epochSec).append(',')
        append(s.durationSec).append(',')
        append(s.plannedSec ?: "").append(',')
        append(s.score ?: "").append(',')
        append(s.schemeId ?: "")
        Zones.ALL.indices.forEach { append(',').append(s.zones.getOrNull(it) ?: "") }
        (0 until 5).forEach { append(',').append(s.pressure.getOrNull(it) ?: "") }
        append(',').append(s.raw).append('\n')
    }
}

fun formatDuration(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

/**
 * Seconds spent on each zone. The X Pro Digital reports seconds already (they add up to about the time brushed);
 * older models report weights summing to about 100 whatever the duration, which are scaled to the time brushed.
 */
fun BrushSession.zoneSeconds(): List<Int> {
    val total = zones.sum()
    if (total <= 0) return emptyList()
    if (total <= durationSec + 10) return zones
    return zones.map { (it.toDouble() / total * durationSec).toInt() }
}

/** Sessions before 15:00 count as morning, later ones as evening. */
fun BrushSession.isMorning(): Boolean = time.hour < 15
