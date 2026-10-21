package dev.asofwar.freebrush.jaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class JawCodecTest {
    private val mesh = JawModel.build()
    private val bytes = JawCodec.encode(mesh)
    private val baked = JawCodec.decode(bytes)

    @Test
    fun fitsSixteenBitIndices() {
        assertTrue("${mesh.vertexCount} vertices", mesh.vertexCount <= 0xFFFF)
    }

    @Test
    fun bakedFileIsCompact() {
        assertTrue("${bytes.size / 1024} KiB", bytes.size < 1_400_000)
    }

    @Test
    fun roundTripKeepsGeometry() {
        assertEquals(mesh.vertexCount, baked.vertexCount)
        assertEquals(mesh.indices.toList(), baked.indices.toList())
        assertEquals(mesh.pickableTriangles, baked.pickableTriangles)
        for (i in 0 until mesh.vertexCount) {
            assertEquals(mesh.x(i), baked.x(i), 0.006f)
            assertEquals(mesh.y(i), baked.y(i), 0.006f)
            assertEquals(mesh.z(i), baked.z(i), 0.006f)
        }
    }

    @Test
    fun roundTripKeepsZones() {
        val differ = mesh.triZone.indices.count { mesh.triZone[it] != baked.triZone[it] }
        // Outer weights are stored in 8 bits, so a triangle sitting exactly on an inner/outer border may flip.
        assertTrue("$differ of ${mesh.triangleCount} differ", differ <= mesh.triangleCount / 500)
    }

    @Test
    fun pickingWorksOnBakedJaw() {
        val camera = JawCamera(baked)
        val (px, py, _) = camera.project(2.5f, 8.5f, 16.5f, 1000f, 700f, 0f, 0.3f, 1f)
        assertEquals(0, camera.pick(px, py, 1000f, 700f, 0f, 0.3f, 1f))
        assertTrue(abs(camera.minY - JawCamera(mesh).minY) < 0.01f)
    }
}
