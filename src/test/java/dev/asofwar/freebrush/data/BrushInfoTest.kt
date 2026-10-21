package dev.asofwar.freebrush.data

import dev.asofwar.freebrush.ui.distinctTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrushInfoTest {
    private val full = BrushInfo(
        id = "7028458556c7",
        address = "70:28:45:85:56:C7",
        label = "Андрей",
        name = "Oclean X Pro Digital",
        model = "OCLEANY3PB",
        firmware = "1.0.2.5",
        hardware = "HH 0003 0004",
        battery = 40,
        headDays = 83,
        headUses = 144,
        headWear = 234,
        headWearMax = 240,
        settingsHex = "0c010101010200000c",
        growthTotal = 4,
        lastSyncMillis = 1792594017166,
    )

    @Test
    fun jsonRoundTrip() {
        assertEquals(full, BrushInfo.fromJson(full.toJson()))
        val bare = BrushInfo(id = "a1", address = "AA:BB:CC:DD:EE:FF")
        assertEquals(bare, BrushInfo.fromJson(bare.toJson()))
    }

    @Test
    fun replacingKeepsThePersonNotTheDevice() {
        val next = full.replacedBy("11:22:33:44:55:66", "Oclean X Pro Digital")
        assertEquals(full.id, next.id)
        assertEquals("Андрей", next.label)
        assertEquals(4, next.growthTotal)
        assertEquals("11:22:33:44:55:66", next.address)
        assertNull(next.settingsHex)
        assertNull(next.headWear)
        assertNull(next.lastSyncMillis)
    }

    @Test
    fun titles() {
        assertEquals("Андрей", full.title)
        assertEquals("Oclean X Pro Digital", full.copy(label = null).title)
        assertEquals("Щётка", BrushInfo(id = "a", address = "x").title)
        val unnamed = full.copy(label = null)
        assertEquals(
            listOf("Андрей", "Oclean X Pro Digital 1", "Oclean X Pro Digital 2"),
            distinctTitles(listOf(full, unnamed, unnamed.copy(id = "b"))),
        )
    }
}
