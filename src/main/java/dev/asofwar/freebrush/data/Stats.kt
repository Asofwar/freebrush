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
    append("time,duration_s,valid_duration_s,score,scheme,over_pressure")
    Zones.ALL.indices.forEach { append(",zone${it + 1}") }
    append(",raw\n")
    for (s in this@toCsv) {
        append(s.time).append(',')
        append(s.durationSec).append(',')
        append(s.validDurationSec ?: "").append(',')
        append(s.score ?: "").append(',')
        append(s.schemeId ?: "").append(',')
        append(s.overPressureCount ?: "")
        Zones.ALL.indices.forEach { append(',').append(s.zones.getOrNull(it) ?: "") }
        append(',').append(s.raw).append('\n')
    }
}

fun formatDuration(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
