package dev.asofwar.freebrush.ble

/**
 * What a connected brush shows of a brushing. It sends nothing while it runs: listening to an X Pro Digital through
 * brushings (2026-10-21) showed no packet that changes with the motor; the battery-level notifications that seemed to
 * pause come and go with the brush's screen instead. One thing is reliable: a byte of the status reply (`0303`) steps
 * up when the brush has stored a new session record (0x11 to 0x12, 0x13 to 0x14 in two brushings).
 */
sealed interface BrushWatch {
    data object Connecting : BrushWatch

    /** Connected: the end of the next brushing will be seen. */
    data object Waiting : BrushWatch

    /** The brush stored the session: its record can be read now. */
    data object Saved : BrushWatch

    data class Failed(val message: String) : BrushWatch
}

/** Turns status replies into [BrushWatch] states; pure, so the rule is tested without a brush. */
class BrushWatchDetector {
    private var counter: Int? = null
    var state: BrushWatch = BrushWatch.Waiting
        private set

    /** A status reply; returns true when the stored-records byte stepped, i.e. a new session was saved. */
    fun status(packet: ByteArray): Boolean {
        val c = OcleanProtocol.parseStatusRecords(packet) ?: return false
        val stepped = counter != null && c != counter
        counter = c
        if (stepped) state = BrushWatch.Saved
        return stepped
    }
}
