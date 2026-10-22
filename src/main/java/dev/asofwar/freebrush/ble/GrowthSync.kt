package dev.asofwar.freebrush.ble

import dev.asofwar.freebrush.data.BrushSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** How writing the growth base went. */
enum class BaseWrite {
    /** The write did not reach the brush: nothing changed there. */
    FAILED,

    /** The brush confirmed the new base. */
    ACKED,

    /** The write reached the brush but no ack came: it may or may not have taken it. */
    UNCONFIRMED,
}

/** A brushing at least this long earns one growth point from FreeBrush, on top of what the brush gives itself. */
const val APP_POINT_SECONDS = 120

/**
 * FreeBrush's own growth points for one sync: [points] for the brushings after the mark [previousUntil], and the mark
 * [until] that goes with them once they are on the brush. Both are epoch seconds of session starts.
 *
 * The brush gives points by its own rule, which leaves most brushings out: an X Pro Digital gave nothing for 2.5 and
 * 3.5 minutes with scores of 76 and 82 (2026-10-21/22), while a brush whose sessions mostly score 85 or more had
 * collected 244 points.
 */
data class AppPoints(val points: Int, val until: Long, val previousUntil: Long) {
    companion object {
        /**
         * One point for each of [sessions] that started after [countedUntil] and lasted [APP_POINT_SECONDS] or more.
         * Records stamped later than [nowSec] (a brush clock gone wrong) are left out, so the mark cannot run ahead.
         */
        fun of(sessions: List<BrushSession>, countedUntil: Long, nowSec: Long): AppPoints {
            val counted = sessions.filter { it.epochSec in (countedUntil + 1)..nowSec && it.durationSec >= APP_POINT_SECONDS }
            return AppPoints(counted.size, counted.maxOfOrNull { it.epochSec } ?: countedUntil, countedUntil)
        }
    }
}

/** What [syncGrowth] needs from the brush; the app talks GATT, tests use a fake. */
interface GrowthLink {
    /** New growth points the brush has counted since its base was last written, or null without an answer. */
    suspend fun readCounter(): Int?

    /** Writes [total] as the base; [onDelivered] runs once the write has reached the brush, before the ack wait. */
    suspend fun writeBase(total: Int, onDelivered: () -> Unit): BaseWrite
}

/**
 * Adds the brush's new growth points and FreeBrush's own [app] points to [total] and writes the sum back as the
 * brush's base, which also clears its counter. The new total and mark go to [onGrowth] the moment the write is
 * delivered, before the ack, and that part cannot be cancelled: from then on the brush counts from zero, so a total
 * lost with a dropped ack or a cancelled sync would make the level on the brush fall back next time. Without an ack
 * the counter is read again; if it still holds the brush's points, the brush ignored the write and the old total and
 * mark are put back. Returns the total the app keeps now, or null when nothing was written.
 */
suspend fun syncGrowth(
    link: GrowthLink,
    total: Int,
    app: AppPoints,
    onGrowth: (total: Int, countedUntil: Long) -> Unit,
    onStep: (String) -> Unit,
): Int? {
    val added = link.readCounter() ?: return null
    if (added == 0 && app.points == 0) {
        onStep("Очки роста: новых нет, всего $total")
        return total
    }
    val newTotal = (total + added + app.points).coerceAtMost(0xFFFF)
    val gained = listOfNotNull(
        "+$added от щётки".takeIf { added > 0 },
        "+${app.points} за чистки от 2 минут".takeIf { app.points > 0 },
    ).joinToString(", ")
    return withContext(NonCancellable) {
        when (link.writeBase(newTotal) { onGrowth(newTotal, app.until) }) {
            BaseWrite.FAILED -> {
                onStep("Очки роста не записаны")
                null
            }
            BaseWrite.ACKED -> {
                onStep("Очки роста: $gained, всего $newTotal")
                newTotal
            }
            BaseWrite.UNCONFIRMED -> {
                // A counter that still holds the brush's points shows the write was ignored. With none from the brush
                // nothing tells, so the points count as not taken: the next sync writes the same base again, which
                // changes nothing on the brush if this write did land.
                val ignored = if (added > 0) link.readCounter().let { it != null && it >= added } else true
                if (ignored) {
                    onGrowth(total, app.previousUntil)
                    onStep("Щётка не подтвердила очки роста, всего по-прежнему $total")
                    total
                } else {
                    onStep("Очки роста: $gained, всего $newTotal (без подтверждения)")
                    newTotal
                }
            }
        }
    }
}
