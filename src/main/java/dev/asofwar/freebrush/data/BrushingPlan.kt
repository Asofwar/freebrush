package dev.asofwar.freebrush.data

import kotlin.math.floor

/** One stop of a guided brushing: zone [zone] (index in [Zones.ALL]) for [seconds], [extra] of them added for practice. */
data class ZoneStep(val zone: Int, val seconds: Int, val extra: Int)

/**
 * The plan the phone guides a brushing by. The brush sends nothing while it runs, so the phone keeps the time itself:
 * the length of the programme the brush will run, split over the eight zones, with more time for the zones the
 * recent sessions left short.
 */
object BrushingPlan {
    /**
     * One continuous path round the mouth: top outer left to right, top inner back to the left, then the same below.
     * Indices into [Zones.ALL].
     */
    val ORDER = listOf(0, 4, 5, 1, 2, 6, 7, 3)

    /** Sessions the weak zones are judged by. */
    private const val RECENT = 7

    /** How much a zone short of its share can gain, and the most it may get relative to an even split. */
    private const val CATCH_UP = 0.6
    private const val MAX_WEIGHT = 1.5

    const val FALLBACK_SECONDS = 120

    /** Seconds of the programme the brush runs next: built-in day/evening modes in auto mode, otherwise the active one. */
    fun programmeSeconds(settings: ByteArray?, custom: List<Programme>, morning: Boolean, auto: Boolean, active: Int?): Int {
        if (settings == null) return FALLBACK_SECONDS
        val num = if (auto) (if (morning) 1 else 2) else active
        return num?.let { Programmes.find(it, custom)?.seconds }?.takeIf { it > 0 } ?: FALLBACK_SECONDS
    }

    /** Average share of the brushing each zone got in the recent sessions with zone data; null without any. */
    fun recentShares(history: List<BrushSession>): List<Double>? {
        val recent = history.asSequence()
            .map { it.zoneSeconds() }
            .filter { it.size >= 8 && it.sum() > 0 }
            .take(RECENT)
            .toList()
        if (recent.isEmpty()) return null
        return List(8) { z -> recent.sumOf { it[z].toDouble() / it.sum() } / recent.size }
    }

    /**
     * Splits [totalSec] over the zones in [ORDER]. A zone whose recent share fell below an even 1/8 gets up to
     * [MAX_WEIGHT] times the even time, paid for by the others; the seconds always add up to [totalSec].
     */
    fun plan(totalSec: Int, history: List<BrushSession>): List<ZoneStep> {
        val total = totalSec.coerceAtLeast(8)
        val even = 1.0 / 8
        val shares = recentShares(history)
        val weights = List(8) { z ->
            val share = shares?.get(z) ?: even
            (1 + CATCH_UP * ((even - share) / even).coerceAtLeast(0.0)).coerceAtMost(MAX_WEIGHT)
        }
        val raw = weights.map { it / weights.sum() * total }
        // Whole seconds that still add up: floors first, then the leftover seconds to the largest remainders.
        val seconds = raw.map { floor(it).toInt() }.toMutableList()
        raw.indices.sortedByDescending { raw[it] - seconds[it] }.take(total - seconds.sum()).forEach { seconds[it]++ }
        val base = total / 8
        return ORDER.map { z -> ZoneStep(z, seconds[z], (seconds[z] - base).coerceAtLeast(0)) }
    }
}
