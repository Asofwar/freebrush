package dev.asofwar.freebrush.ble

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

/** What [syncGrowth] needs from the brush; the app talks GATT, tests use a fake. */
interface GrowthLink {
    /** New growth points the brush has counted since its base was last written, or null without an answer. */
    suspend fun readCounter(): Int?

    /** Writes [total] as the base; [onDelivered] runs once the write has reached the brush, before the ack wait. */
    suspend fun writeBase(total: Int, onDelivered: () -> Unit): BaseWrite
}

/**
 * Adds the brush's new growth points to [total] and writes the sum back as its base, which also clears the brush's
 * counter. The new total goes to [onGrowth] the moment the write is delivered, before the ack, and that part cannot
 * be cancelled: from then on the brush counts from zero, so a total lost with a dropped ack or a cancelled sync would
 * make the level on the brush fall back next time. Without an ack the counter is read again; if it still holds the
 * points, the brush ignored the write and the old total is put back. Returns the total the app keeps now, or null
 * when nothing was written.
 */
suspend fun syncGrowth(link: GrowthLink, total: Int, onGrowth: (Int) -> Unit, onStep: (String) -> Unit): Int? {
    val added = link.readCounter() ?: return null
    if (added == 0) {
        onStep("Очки роста: новых нет, всего $total")
        return total
    }
    val newTotal = (total + added).coerceAtMost(0xFFFF)
    return withContext(NonCancellable) {
        when (link.writeBase(newTotal) { onGrowth(newTotal) }) {
            BaseWrite.FAILED -> {
                onStep("Очки роста не записаны")
                null
            }
            BaseWrite.ACKED -> {
                onStep("Очки роста: +$added, всего $newTotal")
                newTotal
            }
            BaseWrite.UNCONFIRMED -> {
                val counter = link.readCounter()
                if (counter != null && counter >= added) {
                    onGrowth(total)
                    onStep("Щётка не приняла очки роста, всего по-прежнему $total")
                    total
                } else {
                    onStep("Очки роста: +$added, всего $newTotal (без подтверждения)")
                    newTotal
                }
            }
        }
    }
}
