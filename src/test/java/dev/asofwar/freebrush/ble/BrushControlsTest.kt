package dev.asofwar.freebrush.ble

import dev.asofwar.freebrush.ble.BrushControls.Step
import dev.asofwar.freebrush.ble.BrushControls.Toggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushControlsTest {
    /** Settings block read from the user's X Pro Digital on 2026-10-21. */
    private val sample = hex("0c010101010200000c010101000200ea1a0a150f162100011100f000530090070001")

    @Test
    fun togglesReadTheSample() {
        assertTrue(Toggle.RAISE_WAKE.isOn(sample))
        assertTrue(Toggle.FILL_BRUSH.isOn(sample))
        assertTrue(Toggle.AUTO_MODE.isOn(sample))
        assertTrue(Toggle.HOLIDAY.isOn(sample))
        assertTrue(Toggle.AREA_REMIND.isOn(sample))
        assertTrue(Toggle.OVER_CROSS.isOn(sample))
        assertTrue(Toggle.BRUSH_PAUSE.isOn(sample))
        assertFalse(Toggle.OVER_PRESSURE.isOn(sample))
        // 00 is not EC, so the official rule reads it as on.
        assertTrue(Toggle.BRUSH_PATTERN.isOn(sample))
    }

    @Test
    fun toggleCommands() {
        assertEquals("021201", Toggle.OVER_PRESSURE.command(true).toHex())
        assertEquals("021200", Toggle.OVER_PRESSURE.command(false).toHex())
        assertEquals("020d01", Toggle.AREA_REMIND.command(true).toHex())
        assertEquals("022300", Toggle.RAISE_WAKE.command(false).toHex())
        assertEquals("0209ec", Toggle.BRUSH_PATTERN.command(false).toHex())
        assertEquals("023001", Toggle.OVER_CROSS.command(true).toHex())
    }

    @Test
    fun otherCommands() {
        assertEquals("021607", BrushControls.language(7).toHex())
        assertEquals("020f", BrushControls.HEAD_RESET.toHex())
        assertEquals("021700f0", BrushControls.headLimit(240).toHex())
    }

    @Test
    fun shortProgrammeIsOnePacket() {
        val packets = BrushControls.programme(231, List(3) { Step(2, 30) }, prompt = false)
        assertEquals(listOf("0206e703060" + "21e06021e06021e0005"), packets.map { it.toHex() })
    }

    @Test
    fun builtInExclusiveModeWithPrompts() {
        assertEquals(listOf("02060101201 2f00005".replace(" ", "")), BrushControls.programme(1, listOf(Step(18, 240))).map { it.toHex() })
    }

    @Test
    fun longProgrammeIsSplitLikeTheOfficialApp() {
        val gears = listOf(16, 16, 24, 16, 16, 37)
        val packets = BrushControls.programme(72, gears.map { Step(it, 30) }, mtu = 23, prompt = false)
        assertEquals(listOf("0206480600101e00101e00181e00101e2a2b", "020b00101e00251e0005"), packets.map { it.toHex() })
        val single = BrushControls.programme(72, gears.map { Step(it, 30) }, mtu = 123, prompt = false)
        assertEquals(listOf("0206480600101e00101e00181e00101e00101e00251e0005"), single.map { it.toHex() })
    }
}
