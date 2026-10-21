package dev.asofwar.freebrush.ui.jaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JawModelTest {
    private val mesh = JawModel.build()
    private val camera = JawCamera(mesh)
    private val w = 1000f
    private val h = 700f

    /** Picks at the screen position of a model point, as a tap there would. */
    private fun pickAt(x: Float, y: Float, z: Float, yaw: Float, pitch: Float = 0.3f): Int? {
        val (px, py, _) = camera.project(x, y, z, w, h, yaw, pitch, 1f)
        return camera.pick(px, py, w, h, yaw, pitch, 1f)
    }

    @Test
    fun everyZoneHasSurface() {
        val counts = IntArray(8)
        mesh.triZone.forEach { if (it >= 0) counts[it]++ }
        counts.forEachIndexed { zone, c -> assertTrue("zone $zone has $c triangles", c > 500) }
        assertTrue(mesh.triZone.count { it == JawMesh.GUM } > 1000)
    }

    @Test
    fun normalsAreUnitLength() {
        for (i in 0 until mesh.vertexCount step 97) {
            val o = i * JawMesh.STRIDE
            val n = mesh.vertices
            val l = n[o + 3] * n[o + 3] + n[o + 4] * n[o + 4] + n[o + 5] * n[o + 5]
            assertEquals(1f, l, 1e-3f)
        }
    }

    @Test
    fun frontOfUpperLeftIncisorIsUpperLeftOuter() {
        assertEquals(0, pickAt(2.5f, 8.5f, 16.5f, yaw = 0f))
    }

    @Test
    fun frontOfLowerRightIncisorIsLowerRightOuter() {
        assertEquals(6, pickAt(-2f, -8f, 14.5f, yaw = 0f))
    }

    @Test
    fun backOfUpperIncisorsIsInner() {
        val zone = pickAt(2.5f, 8.5f, 10f, yaw = Math.PI.toFloat(), pitch = 0.1f)
        assertTrue("got $zone", zone == 1 || zone == JawMesh.GUM)
    }

    @Test
    fun emptyCornerHitsNothing() {
        assertNull(camera.pick(2f, 2f, w, h, 0f, 0.3f, 1f))
    }
}
