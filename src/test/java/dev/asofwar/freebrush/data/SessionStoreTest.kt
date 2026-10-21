package dev.asofwar.freebrush.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionStoreTest {
    @get:Rule
    val dir = TemporaryFolder()

    private fun session(epoch: Long, score: Int? = 70) =
        BrushSession(epochSec = epoch, durationSec = 120, score = score, zones = List(8) { 15 })

    private fun file() = File(dir.root, "sessions-a.json")

    @Test
    fun savesAndLoadsAgain() = runTest {
        val store = SessionStore(file())
        assertEquals(2, store.addAll(listOf(session(100), session(200))))
        assertEquals(0, store.addAll(listOf(session(100))))

        val again = SessionStore(file())
        again.load()
        assertEquals(listOf(200L, 100L), again.sessions.value.map { it.epochSec })
    }

    @Test
    fun aDamagedFileFallsBackToTheBackupAndIsKeptAside() = runTest {
        val store = SessionStore(file())
        store.addAll(listOf(session(100)))
        store.addAll(listOf(session(200)))
        // A power cut left the file empty; the backup holds the write before.
        file().writeText("")

        val again = SessionStore(file())
        again.load()
        assertEquals(listOf(100L), again.sessions.value.map { it.epochSec })
        assertTrue(dir.root.listFiles()!!.any { it.name.startsWith("sessions-a.json.corrupt-") })
    }

    @Test
    fun nothingReadableStartsEmptyInsteadOfCrashing() = runTest {
        file().writeText("{not json")
        val store = SessionStore(file())
        store.load()
        assertTrue(store.sessions.value.isEmpty())
        // And it can be written again.
        store.addAll(listOf(session(300)))
        val again = SessionStore(file())
        again.load()
        assertEquals(listOf(300L), again.sessions.value.map { it.epochSec })
    }

    @Test
    fun aCompleteRecordReplacesTheShortHeadOnly() = runTest {
        val store = SessionStore(file())
        store.addAll(listOf(session(100, score = null)))
        store.addAll(listOf(session(100, score = 80)))
        store.addAll(listOf(session(100, score = 10)))
        assertEquals(80, store.sessions.value.single().score)
    }
}
