package dev.asofwar.freebrush.ui.jaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JawRendererTest {
    private val mesh = JawModel.build()
    private val colors = IntArray(8) { 0xFF808080.toInt() }

    private fun render(yaw: Float, pitch: Float = 0.2f) = JawRenderer(mesh).apply {
        render(1000f, 800f, yaw, pitch, 1f, colors, 0xFFE0A0A0.toInt(), -1, 0f)
    }

    @Test
    fun everyZoneHasSurface() {
        val counts = IntArray(8)
        mesh.triZone.forEach { if (it >= 0) counts[it]++ }
        counts.forEachIndexed { zone, c -> assertTrue("zone $zone is empty", c > 50) }
        assertTrue(mesh.triZone.any { it == JawMesh.GUM })
    }

    @Test
    fun backFacesAreCulled() {
        val r = render(0f)
        assertTrue(r.triangleCount in 1 until mesh.triangleCount)
    }

    @Test
    fun frontUpperTeethFromTheFrontAreOuterUpperZones() {
        val r = render(0f)
        // Just above the middle of the picture are the upper front teeth, seen from outside.
        val zone = r.hitTest(500f + 15f, 400f - 40f)
        assertTrue("got $zone", zone == 0 || zone == 4)
    }

    @Test
    fun frontLowerTeethFromTheFrontAreOuterLowerZones() {
        val r = render(0f)
        val zone = r.hitTest(500f + 15f, 400f + 120f)
        assertTrue("got $zone", zone == 2 || zone == 6)
    }

    @Test
    fun patientsLeftIsOnTheViewersRight() {
        val r = render(0f)
        // Canine area right of centre on screen belongs to the patient's left (zones 0-3).
        val zone = r.hitTest(500f + 170f, 400f - 40f)
        assertTrue("got $zone", zone in 0..3)
    }

    @Test
    fun turnedAroundShowsInnerSurfaces() {
        val r = render(Math.PI.toFloat())
        val zone = r.hitTest(500f + 15f, 400f - 110f)
        assertTrue("got $zone", zone == 1 || zone == 5 || zone == JawMesh.GUM)
    }

    @Test
    fun emptySpaceHitsNothing() {
        val r = render(0f)
        assertEquals(JawRenderer.NOTHING, r.hitTest(2f, 2f))
    }
}
