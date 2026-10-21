package dev.asofwar.freebrush.ui.jaw

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Mesh of both jaws for the GPU: interleaved vertices of [STRIDE] floats
 * (position xyz, smooth normal xyz, base colour rgb, ambient occlusion, zone base, outer weight) and triangle indices.
 *
 * Coordinates are millimetres: x to the patient's left, y up, z forward. For tooth vertices the zone base is
 * the outer-surface zone of that tooth in [dev.asofwar.freebrush.data.Zones.ALL] order (the inner zone is base+1)
 * and the outer weight blends between them; gum vertices have zone base -1.
 */
class JawMesh(val vertices: FloatArray, val indices: IntArray, val triZone: IntArray) {
    val vertexCount: Int get() = vertices.size / STRIDE
    val triangleCount: Int get() = indices.size / 3

    fun x(i: Int) = vertices[i * STRIDE]
    fun y(i: Int) = vertices[i * STRIDE + 1]
    fun z(i: Int) = vertices[i * STRIDE + 2]

    companion object {
        const val STRIDE = 12
        const val GUM = -1
    }
}

/**
 * Builds a dental-model style mouth procedurally: anatomically shaped crowns (shovel incisors, pointed canines,
 * two-cusp premolars, four-cusp molars with fissures) along U-shaped arches, set in scalloped gums with
 * papillae between the teeth and bulges over the roots. Dimensions roughly follow average adult teeth.
 */
object JawModel {

    private enum class Kind { INCISOR, CANINE, PREMOLAR, MOLAR }

    /** Crown size in mm: mesio-distal width, bucco-lingual depth, height; [tilt] is the labial inclination in degrees. */
    private class Tooth(val kind: Kind, val width: Float, val depth: Float, val height: Float, val tilt: Float)

    private val UPPER = listOf(
        Tooth(Kind.INCISOR, 8.6f, 7.0f, 10.6f, 15f),
        Tooth(Kind.INCISOR, 6.6f, 6.2f, 9.2f, 13f),
        Tooth(Kind.CANINE, 7.6f, 8.0f, 10.2f, 7f),
        Tooth(Kind.PREMOLAR, 7.0f, 9.0f, 8.4f, 3f),
        Tooth(Kind.PREMOLAR, 6.6f, 9.0f, 7.9f, 2f),
        Tooth(Kind.MOLAR, 10.2f, 11.0f, 7.4f, 0f),
        Tooth(Kind.MOLAR, 9.2f, 10.8f, 7.0f, -1f),
    )
    private val LOWER = listOf(
        Tooth(Kind.INCISOR, 5.2f, 5.8f, 9.0f, 9f),
        Tooth(Kind.INCISOR, 5.8f, 6.2f, 9.4f, 8f),
        Tooth(Kind.CANINE, 7.0f, 7.6f, 10.8f, 5f),
        Tooth(Kind.PREMOLAR, 7.0f, 7.8f, 8.4f, 1f),
        Tooth(Kind.PREMOLAR, 7.2f, 8.2f, 8.0f, -1f),
        Tooth(Kind.MOLAR, 11.0f, 10.4f, 7.6f, -3f),
        Tooth(Kind.MOLAR, 10.4f, 10.0f, 7.2f, -4f),
    )

    private const val CONTACT_GAP = 0.15f
    private const val HALF_OPENING = 3.6f // distance of each occlusal plane from the middle
    private const val U = 40
    private const val V = 20

    private val ENAMEL = floatArrayOf(0.96f, 0.94f, 0.89f)
    private val CERVICAL = floatArrayOf(0.93f, 0.86f, 0.70f)
    private val TRANSLUCENT = floatArrayOf(0.80f, 0.86f, 0.92f)
    private val GUM_CORAL = floatArrayOf(0.93f, 0.58f, 0.60f)
    private val GUM_DEEP = floatArrayOf(0.80f, 0.37f, 0.43f)

    fun build(): JawMesh {
        val b = MeshBuilder()
        for (upper in listOf(true, false)) {
            val arch = if (upper) Arch(21.5f, 23.5f) else Arch(19.6f, 21.0f)
            val teeth = if (upper) UPPER else LOWER
            val placed = ArrayList<Placed>()
            for (side in listOf(1, -1)) {
                var s = CONTACT_GAP / 2
                for (t in teeth) {
                    placed += Placed(t, side, s, s + t.width)
                    addTooth(b, arch, upper, side, s + t.width / 2, t)
                    s += t.width + CONTACT_GAP
                }
            }
            addGum(b, arch, upper, placed)
        }
        return b.build()
    }

    private class Placed(val tooth: Tooth, val side: Int, val s0: Float, val s1: Float)

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun mix(a: FloatArray, c: FloatArray, t: Float) =
        floatArrayOf(a[0] + (c[0] - a[0]) * t, a[1] + (c[1] - a[1]) * t, a[2] + (c[2] - a[2]) * t)

    private fun spow(x: Double, e: Double): Float = (sign(x) * abs(x).pow(e)).toFloat()

    // ---- Teeth -------------------------------------------------------------------------------------------------

    /** Half-width and half-depth scale of the crown cross-section at height v (0 = cervical, 1 = occlusal end). */
    private fun profile(kind: Kind, v: Float): Pair<Float, Float> = when (kind) {
        Kind.INCISOR -> {
            // Widens towards the incisal edge; thins to a blade bucco-lingually.
            val w = 0.70f + 0.30f * smooth(0f, 0.6f, v) - 0.06f * smooth(0.9f, 1f, v)
            val d = 1f - 0.80f * v.pow(1.25f)
            w to d
        }
        Kind.CANINE -> {
            val bulge = 0.76f + 0.24f * smooth(0f, 0.55f, v)
            val point = if (v < 0.55f) 1f else 1f - 0.78f * ((v - 0.55f) / 0.45f).pow(1.4f)
            val depth = (0.8f + 0.2f * smooth(0f, 0.5f, v)) * (if (v < 0.6f) 1f else 1f - 0.6f * ((v - 0.6f) / 0.4f).pow(1.6f))
            (bulge * point) to depth
        }
        Kind.PREMOLAR, Kind.MOLAR -> {
            // Narrow neck, widest at the height of contour (~2/3), occlusal table slightly smaller.
            val rise = 0.78f + 0.22f * sin(PI / 2 * min(v / 0.68f, 1f)).toFloat()
            val table = 1f - 0.16f * smooth(0.7f, 1f, v)
            (rise * table) to (rise * table)
        }
    }

    /** Height of the occlusal surface above the crown top for posterior teeth, at normalised (nx, nz) in -1..1. */
    private fun cusps(kind: Kind, nx: Float, nz: Float): Float {
        fun bump(cx: Float, cz: Float, s: Float) = exp(-((nx - cx) * (nx - cx) + (nz - cz) * (nz - cz)) / (s * s))
        return when (kind) {
            Kind.MOLAR -> {
                val c = bump(0.42f, 0.45f, 0.42f) + bump(-0.42f, 0.45f, 0.42f) +
                    0.9f * bump(0.42f, -0.42f, 0.42f) + 0.85f * bump(-0.42f, -0.42f, 0.42f)
                val fossa = 0.55f * bump(0f, 0f, 0.28f)
                val groove = 0.35f * exp(-nz * nz / 0.012f) + 0.25f * exp(-nx * nx / 0.012f) * (if (nz > 0) 1f else 0.6f)
                c - fossa - groove
            }
            Kind.PREMOLAR -> {
                val c = 1.15f * bump(0f, 0.5f, 0.5f) + 0.75f * bump(0f, -0.5f, 0.45f)
                c - 0.4f * exp(-nz * nz / 0.015f)
            }
            else -> 0f
        }
    }

    private fun addTooth(b: MeshBuilder, arch: Arch, upper: Boolean, side: Int, s: Float, t: Tooth) {
        val f = arch.frame(s, side)
        val dirY = if (upper) -1f else 1f
        // Crowns stand on the occlusal plane; the cervical end sits [height] towards the gum, plus a hidden root stub.
        val tipY = if (upper) HALF_OPENING else -HALF_OPENING
        val cervY = tipY - dirY * t.height
        val shear = tan(Math.toRadians(t.tilt.toDouble())).toFloat()
        val zoneBase = (if (side > 0) 0 else 4) + (if (upper) 0 else 2)
        val posterior = t.kind == Kind.PREMOLAR || t.kind == Kind.MOLAR
        // Front crowns are rounded at the corners; widen them a little so neighbours meet at the contact points
        // (the overlap is hidden by the depth buffer).
        val w2 = t.width / 2 * (if (posterior) 1f else 1.05f)
        val d2 = t.depth / 2
        val ex = if (posterior) 0.62 else 0.7

        fun place(lx: Float, ly: Float, lz: Float, c: FloatArray, ao: Float): Int {
            // ly: 0 at cervical, t.height at the occlusal end; tilt shears the crown labially towards the tip.
            val z = lz + ly * shear
            val y = cervY + dirY * ly
            val outer = smooth(-0.18f * t.depth, 0.18f * t.depth, lz)
            return b.vertex(f.px + f.tx * lx + f.rx * z, y, f.pz + f.tz * lx + f.rz * z, c[0], c[1], c[2], ao, zoneBase.toFloat(), outer)
        }

        val first = b.vertexCount
        val rows = V + 2 // two extra rows below the cervical line: a root stub hidden in the gum
        val topRow = IntArray(U)
        for (j in 0..rows) {
            val v = ((j - 2) / V.toFloat()).coerceAtLeast(-0.15f)
            val (pw, pd) = profile(t.kind, v.coerceAtLeast(0f))
            val stub = if (v < 0f) 0.9f else 1f
            for (i in 0 until U) {
                val u = 2 * PI * i / U
                val lx = w2 * spow(cos(u), ex) * pw * stub
                var lz = d2 * spow(sin(u), ex) * pd * stub
                if (t.kind == Kind.INCISOR && lz < 0) lz *= 1f - 0.25f * smooth(0.2f, 0.8f, v) // lingual fossa
                val inter = 1f - 0.22f * abs(cos(u)).pow(6.0).toFloat()
                val ao = (0.55f + 0.45f * smooth(-0.05f, 0.28f, v)) * inter
                if (j == rows) topRow[i] = b.vertexCount
                place(lx, t.height * v, lz, colorFor(t.kind, v), ao)
            }
        }
        val center = floatArrayOf(f.px, cervY + dirY * t.height * 0.45f, f.pz)
        for (j in 0 until rows) {
            for (i in 0 until U) {
                val a = first + j * U + i
                val c = first + j * U + (i + 1) % U
                b.triangle(a, c, a + U, center)
                b.triangle(c, c + U, a + U, center)
            }
        }

        // Occlusal / incisal cap: rings shrinking to the centre.
        val k = if (posterior) 8 else 4
        var prev = topRow
        val (tw, td) = profile(t.kind, 1f)
        val capColor = colorFor(t.kind, 1f)
        for (ring in 1..k) {
            val q = ring / k.toFloat()
            val scale = 1f - q
            val cur = IntArray(U)
            for (i in 0 until U) {
                val u = 2 * PI * i / U
                val nx = spow(cos(u), ex) * scale
                val nz = spow(sin(u), ex) * scale
                val lift = when (t.kind) {
                    Kind.INCISOR -> 0.45f * sin(PI / 2 * q).toFloat()
                    Kind.CANINE -> 1.1f * q.pow(0.75f)
                    else -> {
                        val shoulder = 0.7f * sin(PI / 2 * min(q * 2.2f, 1f)).toFloat()
                        shoulder + smooth(0.1f, 0.45f, q) * 1.7f * (cusps(t.kind, nx, nz) - 0.35f)
                    }
                }
                val fissure = if (posterior) 0.78f + 0.22f * smooth(-0.2f, 0.6f, cusps(t.kind, nx, nz)) else 1f
                cur[i] = place(w2 * tw * nx, t.height + lift, d2 * td * nz, capColor, fissure)
            }
            for (i in 0 until U) {
                b.triangle(prev[i], prev[(i + 1) % U], cur[i], center)
                b.triangle(prev[(i + 1) % U], cur[(i + 1) % U], cur[i], center)
            }
            prev = cur
        }
        val tipLift = when (t.kind) {
            Kind.INCISOR -> 0.45f
            Kind.CANINE -> 1.1f
            else -> 0.7f + 1.7f * (cusps(t.kind, 0f, 0f) - 0.35f)
        }
        val tip = place(0f, t.height + tipLift, 0f, capColor, if (posterior) 0.75f else 1f)
        for (i in 0 until U) b.triangle(prev[i], prev[(i + 1) % U], tip, center)
    }

    /** Enamel colour: warmer at the neck, slightly translucent blue-grey at incisal edges and canine tips. */
    private fun colorFor(kind: Kind, v: Float): FloatArray {
        var c = mix(ENAMEL, CERVICAL, (1f - smooth(0f, 0.35f, v)) * 0.7f)
        if (kind == Kind.INCISOR || kind == Kind.CANINE) c = mix(c, TRANSLUCENT, smooth(0.78f, 1f, v) * 0.55f)
        return c
    }

    // ---- Gums --------------------------------------------------------------------------------------------------

    private fun addGum(b: MeshBuilder, arch: Arch, upper: Boolean, placed: List<Placed>) {
        val sideTeeth = placed.filter { it.side > 0 }
        val end = sideTeeth.last().s1 + 2.5f
        val step = 0.25f
        val slices = (2 * end / step).toInt()
        val dirQ = if (upper) 1f else -1f // q grows away from the teeth: up for the upper gum
        val plane = if (upper) HALF_OPENING else -HALF_OPENING
        val baseQ = if (upper) 15.5f else 14.5f
        val profileSize = 13
        val first = b.vertexCount
        val centers = ArrayList<FloatArray>()

        for (k in 0..slices) {
            val s = -end + 2 * end * k / slices
            val side = if (s >= 0) 1 else -1
            val sa = abs(s)
            val f = arch.frame(sa, side)
            // Tooth at this point of the arch and the position across it (0..1), for scallops and root bulges.
            val tooth = sideTeeth.firstOrNull { sa <= it.s1 + CONTACT_GAP / 2 } ?: sideTeeth.last()
            val across = ((sa - tooth.s0) / (tooth.s1 - tooth.s0)).coerceIn(0f, 1f)
            val atCenter = sin(PI * across).toFloat() // 1 mid-tooth, 0 at contacts
            // Half depth of the cervical cross-section, blended towards the neighbour near contacts.
            val i = sideTeeth.indexOf(tooth)
            val next = sideTeeth.getOrNull(if (across > 0.5f) i + 1 else i - 1) ?: tooth
            val blend = (1f - atCenter) * 0.5f
            val d = (tooth.tooth.depth * (1 - blend) + next.tooth.depth * blend) / 2 * 0.78f
            val h = tooth.tooth.height * (1 - blend) + next.tooth.height * blend
            val papilla = if (tooth.tooth.kind == Kind.INCISOR || tooth.tooth.kind == Kind.CANINE) 3.6f else 2.4f
            val beyond = sa > sideTeeth.last().s1
            // Distance from the occlusal plane to the gum margin: deep mid-tooth, close to the bite at papillae.
            val m = if (beyond) h + 1.5f else h - 0.7f - papilla * (1f - atCenter).pow(1.6f)
            val bulge = if (beyond) 0f else when (tooth.tooth.kind) {
                Kind.INCISOR, Kind.CANINE -> 0.9f
                else -> 0.5f
            } * atCenter * atCenter

            // Cross-section loop (r outward from the arch line, q away from the occlusal plane), labial to lingual.
            val r = floatArrayOf(
                d + 0.2f, d + 1.0f, d + 1.9f + bulge, d + 2.5f + bulge * 0.6f, d + 2.2f, d + 1.2f,
                -d - 1.0f, -d - 1.9f, -d - 2.3f, -d - 1.3f, -d - 0.2f, -d * 0.45f, d * 0.45f,
            )
            // Heights split the gum between its margin and the base, so the loop never folds over itself.
            val g = baseQ - m
            val q = floatArrayOf(
                m, m + 0.15f * g, m + 0.45f * g, m + 0.78f * g, baseQ - 0.6f, baseQ,
                baseQ, baseQ - 0.6f, m + 0.62f * g, m + 0.3f * g, m + 0.4f, m - 0.3f, m - 0.3f,
            )
            for (p in 0 until profileSize) {
                val nearMargin = p == 0 || p >= 10
                val depthT = smooth(m, baseQ, q[p])
                val col = mix(GUM_CORAL, GUM_DEEP, depthT * 0.9f)
                val ao = if (nearMargin) 0.72f else 0.9f + 0.1f * depthT
                b.vertex(
                    f.px + f.rx * r[p], plane + dirQ * q[p], f.pz + f.rz * r[p],
                    col[0], col[1], col[2], ao, JawMesh.GUM.toFloat(), 0f,
                )
            }
            centers += floatArrayOf(f.px, plane + dirQ * (m + baseQ) / 2, f.pz)
        }
        for (k in 0 until slices) {
            val mid = centers[k]
            for (p in 0 until profileSize) {
                val a = first + k * profileSize + p
                val c = first + k * profileSize + (p + 1) % profileSize
                b.triangle(a, c, a + profileSize, mid)
                b.triangle(c, c + profileSize, a + profileSize, mid)
            }
        }
        // Close the ridge behind the last molars.
        for ((slice, inward) in listOf(0 to 1, slices to slices - 1)) {
            val c = centers[slice]
            val col = mix(GUM_CORAL, GUM_DEEP, 0.5f)
            val center = b.vertex(c[0], c[1], c[2], col[0], col[1], col[2], 0.9f, JawMesh.GUM.toFloat(), 0f)
            for (p in 0 until profileSize) {
                b.triangle(first + slice * profileSize + p, first + slice * profileSize + (p + 1) % profileSize, center, centers[inward])
            }
        }
    }

    // ---- Arch --------------------------------------------------------------------------------------------------

    private class Frame(val px: Float, val pz: Float, val tx: Float, val tz: Float, val rx: Float, val rz: Float)

    /**
     * Dental arch: a half-ellipse at the front (semi-axes [a] sideways, [b] forward), continued by slightly diverging
     * straight lines towards the back. Sampled by arc length s from the midline; side +1 is the patient's left (+x).
     */
    private class Arch(private val a: Float, private val b: Float) {
        private val n = 600
        private val xs = FloatArray(n + 1)
        private val zs = FloatArray(n + 1)
        private val len = FloatArray(n + 1)
        private val endPhi = PI / 2 - Math.toRadians(7.0)

        init {
            for (i in 0..n) {
                val phi = endPhi * i / n
                xs[i] = (a * sin(phi)).toFloat()
                zs[i] = (b * cos(phi)).toFloat() - b * 0.42f
                if (i > 0) {
                    val dx = xs[i] - xs[i - 1]
                    val dz = zs[i] - zs[i - 1]
                    len[i] = len[i - 1] + sqrt(dx * dx + dz * dz)
                }
            }
        }

        fun frame(s: Float, side: Int): Frame {
            val x: Float
            val z: Float
            var tx: Float
            var tz: Float
            if (s >= len[n]) {
                tx = xs[n] - xs[n - 1]
                tz = zs[n] - zs[n - 1]
                val l = sqrt(tx * tx + tz * tz)
                tx /= l; tz /= l
                x = xs[n] + tx * (s - len[n])
                z = zs[n] + tz * (s - len[n])
            } else {
                var i = 1
                while (i < n && len[i] < s) i++
                val k = (s - len[i - 1]) / max(1e-6f, len[i] - len[i - 1])
                x = xs[i - 1] + (xs[i] - xs[i - 1]) * k
                z = zs[i - 1] + (zs[i] - zs[i - 1]) * k
                tx = xs[i] - xs[i - 1]
                tz = zs[i] - zs[i - 1]
                val l = sqrt(tx * tx + tz * tz)
                tx /= l; tz /= l
            }
            // Outward = tangent turned away from the arch centre.
            val rx = -tz
            val rz = tx
            return if (side > 0) Frame(x, z, tx, tz, rx, rz) else Frame(-x, z, -tx, tz, -rx, rz)
        }
    }
}

/**
 * Collects vertices and triangles. Each triangle's winding is turned to face away from a given inside point,
 * then [build] computes smooth, area-weighted vertex normals.
 */
internal class MeshBuilder {
    private var data = FloatArray(1 shl 16)
    private var size = 0
    private var tris = IntArray(1 shl 16)
    private var triSize = 0

    val vertexCount: Int get() = size / JawMesh.STRIDE

    fun vertex(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, ao: Float, zone: Float, outer: Float): Int {
        if (size + JawMesh.STRIDE > data.size) data = data.copyOf(data.size * 2)
        val o = size
        data[o] = x; data[o + 1] = y; data[o + 2] = z
        data[o + 3] = 0f; data[o + 4] = 0f; data[o + 5] = 0f
        data[o + 6] = r; data[o + 7] = g; data[o + 8] = b
        data[o + 9] = ao; data[o + 10] = zone; data[o + 11] = outer
        size += JawMesh.STRIDE
        return vertexCount - 1
    }

    private fun p(i: Int, c: Int) = data[i * JawMesh.STRIDE + c]

    fun triangle(a: Int, b: Int, c: Int, inside: FloatArray) {
        val ux = p(b, 0) - p(a, 0); val uy = p(b, 1) - p(a, 1); val uz = p(b, 2) - p(a, 2)
        val vx = p(c, 0) - p(a, 0); val vy = p(c, 1) - p(a, 1); val vz = p(c, 2) - p(a, 2)
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        val cx = (p(a, 0) + p(b, 0) + p(c, 0)) / 3 - inside[0]
        val cy = (p(a, 1) + p(b, 1) + p(c, 1)) / 3 - inside[1]
        val cz = (p(a, 2) + p(b, 2) + p(c, 2)) / 3 - inside[2]
        if (triSize + 3 > tris.size) tris = tris.copyOf(tris.size * 2)
        if (nx * cx + ny * cy + nz * cz < 0) {
            tris[triSize] = a; tris[triSize + 1] = c; tris[triSize + 2] = b
        } else {
            tris[triSize] = a; tris[triSize + 1] = b; tris[triSize + 2] = c
        }
        triSize += 3
    }

    fun build(): JawMesh {
        val v = data.copyOf(size)
        val idx = tris.copyOf(triSize)
        val s = JawMesh.STRIDE
        // Area-weighted face normals accumulated into vertices.
        for (t in 0 until triSize / 3) {
            val a = idx[t * 3]; val b = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
            val ux = v[b * s] - v[a * s]; val uy = v[b * s + 1] - v[a * s + 1]; val uz = v[b * s + 2] - v[a * s + 2]
            val wx = v[c * s] - v[a * s]; val wy = v[c * s + 1] - v[a * s + 1]; val wz = v[c * s + 2] - v[a * s + 2]
            val nx = uy * wz - uz * wy
            val ny = uz * wx - ux * wz
            val nz = ux * wy - uy * wx
            for (k in intArrayOf(a, b, c)) {
                v[k * s + 3] += nx; v[k * s + 4] += ny; v[k * s + 5] += nz
            }
        }
        for (i in 0 until size / s) {
            val l = sqrt(v[i * s + 3] * v[i * s + 3] + v[i * s + 4] * v[i * s + 4] + v[i * s + 5] * v[i * s + 5])
            if (l > 1e-9f) { v[i * s + 3] /= l; v[i * s + 4] /= l; v[i * s + 5] /= l }
        }
        // Zone of each triangle for picking: the tooth's outer or inner zone by the mean outer weight.
        val triZone = IntArray(triSize / 3) { t ->
            val a = idx[t * 3]; val b = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
            val base = v[a * s + 10].toInt()
            when {
                base < 0 -> JawMesh.GUM
                (v[a * s + 11] + v[b * s + 11] + v[c * s + 11]) / 3 >= 0.5f -> base
                else -> base + 1
            }
        }
        return JawMesh(v, idx, triZone)
    }
}
