package dev.asofwar.freebrush.ui.jaw

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Triangle mesh of both jaws.
 *
 * Coordinates: x to the patient's left, y up, z forward (out of the mouth). [triZone] holds the brushing zone
 * of every triangle in [dev.asofwar.freebrush.data.Zones.ALL] order, or [GUM] for gum tissue.
 */
class JawMesh(
    val positions: FloatArray,
    val triangles: IntArray,
    val normals: FloatArray,
    val triZone: IntArray,
    /** Object (one tooth, or one slice of gum) each triangle belongs to; objects are depth-sorted as a whole. */
    val triObject: IntArray,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = triangles.size / 3

    companion object {
        const val GUM = -1
    }
}

/** Builds a stylised mouth: 7 teeth per quadrant along a U-shaped arch, embedded in a gum ridge. */
object JawModel {

    private enum class Kind { INCISOR, CANINE, PREMOLAR, MOLAR }

    private class Tooth(val kind: Kind, val width: Float, val depth: Float, val height: Float)

    /** Front to back: central incisor, lateral incisor, canine, two premolars, two molars. */
    private val UPPER = listOf(
        Tooth(Kind.INCISOR, 0.86f, 0.34f, 1.05f),
        Tooth(Kind.INCISOR, 0.66f, 0.32f, 0.98f),
        Tooth(Kind.CANINE, 0.74f, 0.58f, 1.10f),
        Tooth(Kind.PREMOLAR, 0.66f, 0.76f, 0.96f),
        Tooth(Kind.PREMOLAR, 0.64f, 0.76f, 0.94f),
        Tooth(Kind.MOLAR, 0.94f, 0.94f, 0.92f),
        Tooth(Kind.MOLAR, 0.86f, 0.88f, 0.90f),
    )
    private val LOWER = listOf(
        Tooth(Kind.INCISOR, 0.56f, 0.30f, 1.0f),
        Tooth(Kind.INCISOR, 0.60f, 0.31f, 1.0f),
        Tooth(Kind.CANINE, 0.68f, 0.54f, 1.08f),
        Tooth(Kind.PREMOLAR, 0.66f, 0.72f, 0.96f),
        Tooth(Kind.PREMOLAR, 0.68f, 0.74f, 0.94f),
        Tooth(Kind.MOLAR, 1.00f, 0.90f, 0.92f),
        Tooth(Kind.MOLAR, 0.94f, 0.86f, 0.90f),
    )

    private const val GAP = 0.05f
    private const val TIP_Y = 0.32f // half of the opening between upper and lower incisor tips

    /** Gum line height above the tips; every tooth starts here, so shorter teeth stop short of the bite. */
    private const val CROWN = 1.05f
    private const val U_SEG = 12
    private const val V_SEG = 6

    fun build(): JawMesh {
        val b = MeshBuilder()
        for (upper in listOf(true, false)) {
            val arch = if (upper) Arch(2.25f, 2.55f) else Arch(2.05f, 2.35f)
            val teeth = if (upper) UPPER else LOWER
            for (side in listOf(1, -1)) {
                var s = GAP / 2
                for (t in teeth) {
                    val center = s + t.width / 2
                    addTooth(b, arch, upper, side, center, t)
                    s += t.width + GAP
                }
            }
            val length = teeth.sumOf { (it.width + GAP).toDouble() }.toFloat()
            addGum(b, arch, upper, length + 0.15f)
        }
        return b.build()
    }

    private fun zoneOf(upper: Boolean, left: Boolean, outer: Boolean): Int =
        (if (left) 0 else 4) + (if (upper) 0 else 2) + (if (outer) 0 else 1)

    private fun addTooth(b: MeshBuilder, arch: Arch, upper: Boolean, side: Int, s: Float, t: Tooth) {
        val f = arch.frame(s, side)
        val dirY = if (upper) -1f else 1f
        val baseY = if (upper) TIP_Y + CROWN else -TIP_Y - CROWN
        b.newObject()
        val first = b.vertexCount
        for (j in 0..V_SEG) {
            val v = j / V_SEG.toFloat()
            val (sw, sd) = taper(t.kind, v)
            // Narrower where the crown leaves the gum.
            val neck = 0.84f + 0.16f * minOf(1f, v * 4f)
            for (i in 0 until U_SEG) {
                val u = 2 * PI * i / U_SEG
                val lx = t.width / 2 * spow(cos(u), 0.55) * sw * neck
                val lz = t.depth / 2 * spow(sin(u), 0.55) * sd * neck
                val y = baseY + dirY * t.height * v
                b.vertex(f.px + f.tx * lx + f.rx * lz, y, f.pz + f.tz * lx + f.rz * lz)
            }
        }
        // Cap: a point on the biting end; raised for canines, slightly hollow for molars.
        val capH = when (t.kind) {
            Kind.CANINE -> 1.07f
            Kind.MOLAR -> 0.97f
            else -> 1.0f
        }
        val cap = b.vertex(f.px, baseY + dirY * t.height * capH, f.pz)
        val center = floatArrayOf(f.px, baseY + dirY * t.height * 0.5f, f.pz)
        val outward = floatArrayOf(f.rx, 0f, f.rz)
        val left = side > 0
        fun tri(a: Int, c: Int, d: Int) {
            val n = b.triangle(a, c, d, center)
            val outer = n[0] * outward[0] + n[2] * outward[2] >= 0f
            b.zone(zoneOf(upper, left, outer))
        }
        for (j in 0 until V_SEG) {
            for (i in 0 until U_SEG) {
                val a = first + j * U_SEG + i
                val c = first + j * U_SEG + (i + 1) % U_SEG
                tri(a, c, a + U_SEG)
                tri(c, c + U_SEG, a + U_SEG)
            }
        }
        val top = first + V_SEG * U_SEG
        for (i in 0 until U_SEG) tri(top + i, top + (i + 1) % U_SEG, cap)
    }

    /** Width and depth scale of a cross-section at height [v] (0 = gum line, 1 = biting end). */
    private fun taper(kind: Kind, v: Float): Pair<Float, Float> = when (kind) {
        Kind.INCISOR -> (1f + 0.08f * v) to (1f - 0.72f * v.pow(1.2f))
        Kind.CANINE -> (1f - 0.55f * v.pow(1.7f)) to (1f - 0.45f * v.pow(1.7f))
        Kind.PREMOLAR, Kind.MOLAR -> {
            val r = if (v < 0.72f) 1f else 1f - 0.25f * ((v - 0.72f) / 0.28f).pow(2)
            r to r
        }
    }

    private fun addGum(b: MeshBuilder, arch: Arch, upper: Boolean, length: Float) {
        val along = 56
        val around = 10
        val radius = 0.5f
        val radiusY = 0.34f
        // Tube centre just beyond the gum line, so the gum's edge meets the tooth necks.
        val y0 = if (upper) TIP_Y + CROWN + 0.30f else -TIP_Y - CROWN - 0.30f
        val first = b.vertexCount
        val centers = ArrayList<FloatArray>()
        for (k in 0..along) {
            val s = -length + 2 * length * k / along
            val f = arch.frame(abs(s), if (s >= 0) 1 else -1)
            centers += floatArrayOf(f.px, y0, f.pz)
            for (i in 0 until around) {
                val a = 2 * PI * i / around
                val r = radius * cos(a).toFloat()
                val y = radiusY * sin(a).toFloat()
                b.vertex(f.px + f.rx * r, y0 + y, f.pz + f.rz * r)
            }
        }
        for (k in 0 until along) {
            val mid = centers[k]
            b.newObject()
            for (i in 0 until around) {
                val a = first + k * around + i
                val c = first + k * around + (i + 1) % around
                b.triangle(a, c, a + around, mid)
                b.zone(JawMesh.GUM)
                b.triangle(c, c + around, a + around, mid)
                b.zone(JawMesh.GUM)
            }
        }
        // Close both ends of the ridge behind the last molars.
        for ((ring, inward) in listOf(0 to 1, along to along - 1)) {
            b.newObject()
            val c = centers[ring]
            val tip = b.vertex(c[0], c[1], c[2])
            val inside = centers[inward]
            for (i in 0 until around) {
                b.triangle(first + ring * around + i, first + ring * around + (i + 1) % around, tip, inside)
                b.zone(JawMesh.GUM)
            }
        }
    }

    private fun spow(x: Double, e: Double): Float = (sign(x) * abs(x).pow(e)).toFloat()

    /** A point on the arch with its tangent (t) and outward direction (r) in the horizontal plane. */
    private class Frame(val px: Float, val pz: Float, val tx: Float, val tz: Float, val rx: Float, val rz: Float)

    /**
     * Dental arch: a half-ellipse at the front (semi-axes [a] sideways, [b] forward) continued by straight lines
     * going back, sampled by arc length [s] from the midline. [side] +1 is the patient's left (positive x).
     */
    private class Arch(val a: Float, val b: Float) {
        private val samples = 400
        private val xs = FloatArray(samples + 1)
        private val zs = FloatArray(samples + 1)
        private val len = FloatArray(samples + 1)

        init {
            for (i in 0..samples) {
                val phi = PI / 2 * i / samples
                xs[i] = (a * sin(phi)).toFloat()
                zs[i] = (b * cos(phi)).toFloat() - b * 0.45f
                if (i > 0) {
                    val dx = xs[i] - xs[i - 1]
                    val dz = zs[i] - zs[i - 1]
                    len[i] = len[i - 1] + sqrt(dx * dx + dz * dz)
                }
            }
        }

        fun frame(s: Float, side: Int): Frame {
            val quarter = len[samples]
            val x: Float
            val z: Float
            var tx: Float
            var tz: Float
            if (s >= quarter) {
                x = a
                z = zs[samples] - (s - quarter)
                tx = 0f
                tz = -1f
            } else {
                var i = 1
                while (i < samples && len[i] < s) i++
                val k = (s - len[i - 1]) / (len[i] - len[i - 1])
                x = xs[i - 1] + (xs[i] - xs[i - 1]) * k
                z = zs[i - 1] + (zs[i] - zs[i - 1]) * k
                tx = xs[i] - xs[i - 1]
                tz = zs[i] - zs[i - 1]
                val n = sqrt(tx * tx + tz * tz)
                tx /= n
                tz /= n
            }
            // Outward = tangent turned towards +x (away from the arch centre).
            val rx = -tz
            val rz = tx
            return if (side > 0) Frame(x, z, tx, tz, rx, rz) else Frame(-x, z, -tx, tz, -rx, rz)
        }
    }
}

/** Collects vertices and triangles; every triangle's winding is made to face away from a given inside point. */
internal class MeshBuilder {
    private val pos = ArrayList<Float>()
    private val tris = ArrayList<Int>()
    private val normals = ArrayList<Float>()
    private val zones = ArrayList<Int>()
    private val objects = ArrayList<Int>()
    private var current = -1

    val vertexCount: Int get() = pos.size / 3

    fun vertex(x: Float, y: Float, z: Float): Int {
        pos += x; pos += y; pos += z
        return vertexCount - 1
    }

    /** Adds a triangle facing away from [inside]; returns its unit normal. */
    fun triangle(a: Int, b: Int, c: Int, inside: FloatArray): FloatArray {
        val ax = pos[a * 3]; val ay = pos[a * 3 + 1]; val az = pos[a * 3 + 2]
        val ux = pos[b * 3] - ax; val uy = pos[b * 3 + 1] - ay; val uz = pos[b * 3 + 2] - az
        val vx = pos[c * 3] - ax; val vy = pos[c * 3 + 1] - ay; val vz = pos[c * 3 + 2] - az
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 1e-9f } ?: 1f
        nx /= l; ny /= l; nz /= l
        val cx = (ax + pos[b * 3] + pos[c * 3]) / 3 - inside[0]
        val cy = (ay + pos[b * 3 + 1] + pos[c * 3 + 1]) / 3 - inside[1]
        val cz = (az + pos[b * 3 + 2] + pos[c * 3 + 2]) / 3 - inside[2]
        if (nx * cx + ny * cy + nz * cz < 0) {
            nx = -nx; ny = -ny; nz = -nz
            tris += a; tris += c; tris += b
        } else {
            tris += a; tris += b; tris += c
        }
        normals += nx; normals += ny; normals += nz
        return floatArrayOf(nx, ny, nz)
    }

    fun newObject() {
        current++
    }

    fun zone(z: Int) {
        zones += z
        objects += current
    }

    fun build() = JawMesh(pos.toFloatArray(), tris.toIntArray(), normals.toFloatArray(), zones.toIntArray(), objects.toIntArray())
}
