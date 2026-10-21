package dev.asofwar.freebrush.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushWatchTest {
    // Status replies of the user's X Pro Digital before and after a brushing.
    private val before = hex("0303020e7b271100")
    private val after = hex("0303020e73271200")

    @Test
    fun statusRecordsByte() {
        assertEquals(0x11, OcleanProtocol.parseStatusRecords(before))
        assertEquals(0x12, OcleanProtocol.parseStatusRecords(after))
        assertEquals(null, OcleanProtocol.parseStatusRecords(hex("02264f4b")))
    }

    @Test
    fun theRecordsByteSteppingMeansSaved() {
        val d = BrushWatchDetector()
        assertFalse(d.status(before))
        assertFalse(d.status(before))
        assertEquals(BrushWatch.Waiting, d.state)
        assertTrue(d.status(after))
        assertEquals(BrushWatch.Saved, d.state)
    }

    @Test
    fun theFirstReplyIsOnlyABaseline() {
        val d = BrushWatchDetector()
        assertFalse(d.status(after))
        assertEquals(BrushWatch.Waiting, d.state)
    }
}
