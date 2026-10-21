package dev.asofwar.freebrush.jaw

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** What the camera needs from a jaw mesh to fit it into the view and to pick triangles under a tap. */
interface JawGeometry {
    val vertexCount: Int
    val indices: IntArray
    val triZone: IntArray
    val pickableTriangles: Int
    val triangleCount: Int get() = indices.size / 3
    fun x(i: Int): Float
    fun y(i: Int): Float
    fun z(i: Int): Float
}

/**
 * The jaw as baked at build time and loaded on the phone: GPU-ready vertex and index bytes plus float positions
 * for picking. Building the mesh takes ~0.4 s of CPU on a phone; loading the baked file takes a few milliseconds.
 */
class BakedJaw(
    private val positions: FloatArray,
    override val indices: IntArray,
    override val triZone: IntArray,
    override val pickableTriangles: Int,
    /** [JawCodec.VERTEX_BYTES] bytes per vertex, layout in [JawCodec]. */
    val gpuVertices: ByteArray,
    /** Unsigned 16-bit little-endian triangle indices. */
    val gpuIndices: ByteArray,
) : JawGeometry {
    override val vertexCount: Int get() = positions.size / 3
    override fun x(i: Int) = positions[i * 3]
    override fun y(i: Int) = positions[i * 3 + 1]
    override fun z(i: Int) = positions[i * 3 + 2]
}

/**
 * Compact binary format of the baked jaw, little-endian:
 * magic "JAW2", vertex count, index count, pickable triangle count (int32 each), then per vertex 16 bytes —
 * position int16 x3 in [POSITION_UNIT] mm, normal int8 x3 (normalised), zone int8, colour uint8 x3 and
 * ambient occlusion uint8 (normalised), outer weight uint8 (normalised), one padding byte — then uint16 indices.
 */
object JawCodec {
    const val VERTEX_BYTES = 16
    const val POSITION_UNIT = 0.01f
    private const val MAGIC = 0x3257414A // "JAW2" read little-endian

    fun encode(mesh: JawMesh): ByteArray {
        require(mesh.vertexCount <= 0xFFFF) { "too many vertices for 16-bit indices: ${mesh.vertexCount}" }
        val buf = ByteBuffer.allocate(16 + mesh.vertexCount * VERTEX_BYTES + mesh.indices.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC).putInt(mesh.vertexCount).putInt(mesh.indices.size).putInt(mesh.pickableTriangles)
        val v = mesh.vertices
        for (i in 0 until mesh.vertexCount) {
            val o = i * JawMesh.STRIDE
            for (c in 0 until 3) buf.putShort((v[o + c] / POSITION_UNIT).roundToInt().toShort())
            for (c in 3 until 6) buf.put((v[o + c] * 127f).roundToInt().coerceIn(-127, 127).toByte())
            buf.put(Math.round(v[o + 10]).toByte())
            for (c in 6 until 10) buf.put(unit(v[o + c]))
            buf.put(unit(v[o + 11]))
            buf.put(0)
        }
        for (index in mesh.indices) buf.putShort(index.toShort())
        return buf.array()
    }

    fun decode(bytes: ByteArray): BakedJaw {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(buf.int == MAGIC) { "not a baked jaw" }
        val vertexCount = buf.int
        val indexCount = buf.int
        val pickable = buf.int
        val vertexStart = buf.position()
        val positions = FloatArray(vertexCount * 3)
        val zone = IntArray(vertexCount)
        val outer = FloatArray(vertexCount)
        for (i in 0 until vertexCount) {
            val o = vertexStart + i * VERTEX_BYTES
            positions[i * 3] = buf.getShort(o) * POSITION_UNIT
            positions[i * 3 + 1] = buf.getShort(o + 2) * POSITION_UNIT
            positions[i * 3 + 2] = buf.getShort(o + 4) * POSITION_UNIT
            zone[i] = buf.get(o + 9).toInt()
            outer[i] = (buf.get(o + 14).toInt() and 0xFF) / 255f
        }
        val indexStart = vertexStart + vertexCount * VERTEX_BYTES
        val indices = IntArray(indexCount) { buf.getShort(indexStart + it * 2).toInt() and 0xFFFF }
        val triZone = IntArray(indexCount / 3) { t ->
            zoneOf(zone[indices[t * 3]], (outer[indices[t * 3]] + outer[indices[t * 3 + 1]] + outer[indices[t * 3 + 2]]) / 3)
        }
        return BakedJaw(
            positions, indices, triZone, pickable,
            bytes.copyOfRange(vertexStart, indexStart),
            bytes.copyOfRange(indexStart, indexStart + indexCount * 2),
        )
    }

    /** Zone of a triangle from its first vertex's zone tag and the mean outer weight (see [JawMesh]). */
    fun zoneOf(base: Int, meanOuter: Float): Int = when {
        base < 0 -> JawMesh.GUM
        base >= JawMesh.HALO -> base - JawMesh.HALO
        base >= JawMesh.RING -> base - JawMesh.RING
        meanOuter >= 0.5f -> base
        else -> base + 1
    }

    private fun unit(x: Float): Byte = (x.coerceIn(0f, 1f) * 255f).roundToInt().toByte()
}

/** Build-time entry point: bakes the jaw into the file given as the only argument. */
fun main(args: Array<String>) {
    val out = File(args.single())
    out.parentFile?.mkdirs()
    val mesh = JawModel.build()
    out.writeBytes(JawCodec.encode(mesh))
    println("Baked jaw: ${mesh.vertexCount} vertices, ${mesh.triangleCount} triangles, ${out.length() / 1024} KiB -> $out")
}
