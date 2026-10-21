package dev.asofwar.freebrush.ui.jaw

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Software 3D for [JawMesh]: rotates, lights and projects the mesh into flat-shaded 2D triangles sorted back to
 * front (painter's algorithm), ready for `Canvas.drawVertices`. Pure Kotlin, so it is unit-tested on the JVM.
 *
 * Output after [render]: [triangleCount] triangles in [verts] (x, y pairs) and [colors] (ARGB per vertex).
 */
class JawRenderer(private val mesh: JawMesh) {
    private val n = mesh.triangleCount
    private val vx = FloatArray(mesh.vertexCount)
    private val vy = FloatArray(mesh.vertexCount)
    private val vz = FloatArray(mesh.vertexCount)
    private val sx = FloatArray(mesh.vertexCount)
    private val sy = FloatArray(mesh.vertexCount)
    private val keys = LongArray(n)
    private val objectCount = (mesh.triObject.maxOrNull() ?: 0) + 1
    private val objX = FloatArray(objectCount)
    private val objY = FloatArray(objectCount)
    private val objZ = FloatArray(objectCount)
    private val objDepth = FloatArray(objectCount)
    private val outZone = IntArray(n)

    val verts = FloatArray(n * 6)
    val colors = IntArray(n * 3)
    var triangleCount = 0
        private set

    private val cx: Float
    private val cy: Float
    private val cz: Float

    init {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        val p = mesh.positions
        for (i in 0 until mesh.vertexCount) {
            minX = min(minX, p[i * 3]); maxX = max(maxX, p[i * 3])
            minY = min(minY, p[i * 3 + 1]); maxY = max(maxY, p[i * 3 + 1])
            minZ = min(minZ, p[i * 3 + 2]); maxZ = max(maxZ, p[i * 3 + 2])
        }
        cx = (minX + maxX) / 2; cy = (minY + maxY) / 2; cz = (minZ + maxZ) / 2

        // Object centres: mean of their triangles' centroids, relative to the model centre.
        val weight = IntArray(objectCount)
        val t = mesh.triangles
        for (k in 0 until n) {
            val o = mesh.triObject[k]
            for (j in 0 until 3) {
                val v = t[k * 3 + j]
                objX[o] += p[v * 3] - cx; objY[o] += p[v * 3 + 1] - cy; objZ[o] += p[v * 3 + 2] - cz
            }
            weight[o] += 3
        }
        for (o in 0 until objectCount) {
            if (weight[o] > 0) { objX[o] /= weight[o]; objY[o] /= weight[o]; objZ[o] /= weight[o] }
        }
    }

    /**
     * @param yaw rotation around the vertical axis, radians
     * @param pitch tilt towards the viewer, radians
     * @param zoneColors ARGB base colour per zone (8 entries)
     * @param gumColor ARGB colour of the gums
     * @param selected highlighted zone, or -1; other zones are dimmed while one is selected
     * @param glow 0..1 extra brightness of the selected zone (for a pulse)
     */
    fun render(
        width: Float,
        height: Float,
        yaw: Float,
        pitch: Float,
        scale: Float,
        zoneColors: IntArray,
        gumColor: Int,
        selected: Int,
        glow: Float,
    ) {
        val cyaw = cos(yaw); val syaw = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        val focal = min(width, height * 1.25f) * 1.22f * scale
        lastYaw = yaw; lastPitch = pitch; lastFocal = focal; lastW = width; lastH = height
        val p = mesh.positions
        for (i in 0 until mesh.vertexCount) {
            val x0 = p[i * 3] - cx
            val y0 = p[i * 3 + 1] - cy
            val z0 = p[i * 3 + 2] - cz
            // Yaw around Y, then pitch around X.
            val x1 = x0 * cyaw + z0 * syaw
            val z1 = -x0 * syaw + z0 * cyaw
            val y2 = y0 * cp - z1 * sp
            val z2 = y0 * sp + z1 * cp
            vx[i] = x1; vy[i] = y2; vz[i] = z2
            val d = CAMERA - z2
            sx[i] = width / 2 + focal * x1 / d
            sy[i] = height / 2 - focal * y2 / d
        }

        for (o in 0 until objectCount) {
            val z1 = -objX[o] * syaw + objZ[o] * cyaw
            objDepth[o] = objY[o] * sp + z1 * cp
        }

        var count = 0
        val t = mesh.triangles
        val nm = mesh.normals
        for (k in 0 until n) {
            val a = t[k * 3]; val b = t[k * 3 + 1]; val c = t[k * 3 + 2]
            val nx0 = nm[k * 3]; val ny0 = nm[k * 3 + 1]; val nz0 = nm[k * 3 + 2]
            val nx1 = nx0 * cyaw + nz0 * syaw
            val nz1 = -nx0 * syaw + nz0 * cyaw
            val ny2 = ny0 * cp - nz1 * sp
            val nz2 = ny0 * sp + nz1 * cp
            val mx = (vx[a] + vx[b] + vx[c]) / 3
            val my = (vy[a] + vy[b] + vy[c]) / 3
            val mz = (vz[a] + vz[b] + vz[c]) / 3
            // Back-face culling: skip triangles turned away from the camera.
            if (nx1 * -mx + ny2 * -my + nz2 * (CAMERA - mz) <= 0f) continue
            // Whole objects first (far tooth behind near tooth), then triangles within an object.
            keys[count] = (depthKey(objDepth[mesh.triObject[k]]) shl 40) or (depthKey(mz) shl 20) or k.toLong()
            count++
        }
        keys.sort(0, count)

        for (o in 0 until count) {
            val k = (keys[o] and 0xFFFFF).toInt()
            val a = t[k * 3]; val b = t[k * 3 + 1]; val c = t[k * 3 + 2]
            verts[o * 6] = sx[a]; verts[o * 6 + 1] = sy[a]
            verts[o * 6 + 2] = sx[b]; verts[o * 6 + 3] = sy[b]
            verts[o * 6 + 4] = sx[c]; verts[o * 6 + 5] = sy[c]
            val zone = mesh.triZone[k]
            outZone[o] = zone

            val nx0 = nm[k * 3]; val ny0 = nm[k * 3 + 1]; val nz0 = nm[k * 3 + 2]
            val nx = nx0 * cyaw + nz0 * syaw
            val nz1 = -nx0 * syaw + nz0 * cyaw
            val ny = ny0 * cp - nz1 * sp
            val nz = ny0 * sp + nz1 * cp
            val diffuse = max(0f, nx * LX + ny * LY + nz * LZ)
            val fill = max(0f, nz) * 0.22f
            val tooth = zone >= 0
            val spec = if (tooth) max(0f, nx * HX + ny * HY + nz * HZ).pow(28) * 0.5f else 0f
            var light = 0.34f + 0.62f * diffuse + fill

            val base = if (tooth) zoneColors[zone] else gumColor
            var r = (base shr 16 and 0xFF) / 255f
            var g = (base shr 8 and 0xFF) / 255f
            var bl = (base and 0xFF) / 255f
            if (selected >= 0 && tooth) {
                if (zone == selected) {
                    light += 0.18f * glow
                } else {
                    // Fade other zones towards grey enamel so the selected one stands out.
                    val grey = (r + g + bl) / 3
                    r = r * 0.35f + grey * 0.65f
                    g = g * 0.35f + grey * 0.65f
                    bl = bl * 0.35f + grey * 0.65f
                    light *= 0.72f
                }
            }
            val color = argb(r * light + spec, g * light + spec, bl * light + spec)
            colors[o * 3] = color; colors[o * 3 + 1] = color; colors[o * 3 + 2] = color
        }
        triangleCount = count
    }

    private var lastYaw = 0f
    private var lastPitch = 0f
    private var lastFocal = 1f
    private var lastW = 0f
    private var lastH = 0f

    /**
     * Screen position of a model point in the last frame, and how much it faces the viewer
     * (1 = nearest side, 0 = farthest), for labels that follow the model.
     */
    fun project(x: Float, y: Float, z: Float): Triple<Float, Float, Float> {
        val x0 = x - cx; val y0 = y - cy; val z0 = z - cz
        val cyaw = cos(lastYaw); val syaw = sin(lastYaw)
        val cp = cos(lastPitch); val sp = sin(lastPitch)
        val x1 = x0 * cyaw + z0 * syaw
        val z1 = -x0 * syaw + z0 * cyaw
        val y2 = y0 * cp - z1 * sp
        val z2 = y0 * sp + z1 * cp
        val d = CAMERA - z2
        val facing = ((z2 / RADIUS) + 1f) / 2f
        return Triple(lastW / 2 + lastFocal * x1 / d, lastH / 2 - lastFocal * y2 / d, facing.coerceIn(0f, 1f))
    }

    /** Zone under a point of the last frame: the front-most triangle wins. -1 for gums, -2 for nothing. */
    fun hitTest(x: Float, y: Float): Int {
        for (o in triangleCount - 1 downTo 0) {
            if (inside(x, y, verts[o * 6], verts[o * 6 + 1], verts[o * 6 + 2], verts[o * 6 + 3], verts[o * 6 + 4], verts[o * 6 + 5])) {
                return outZone[o]
            }
        }
        return NOTHING
    }

    private fun inside(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Boolean {
        val d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
        val d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
        val d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
        val neg = d1 < 0 || d2 < 0 || d3 < 0
        val pos = d1 > 0 || d2 > 0 || d3 > 0
        return !(neg && pos)
    }

    private fun depthKey(z: Float): Long = ((z + 50f) * 8192f).toLong().coerceIn(0L, 0xFFFFFL)

    private fun argb(r: Float, g: Float, b: Float): Int {
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    companion object {
        const val NOTHING = -2
        private const val CAMERA = 11f
        private const val RADIUS = 3.2f

        // Key light from the upper left front, and the half-vector towards the viewer for highlights.
        private val L = normalize(-0.45f, 0.6f, 0.66f)
        private val LX = L[0]
        private val LY = L[1]
        private val LZ = L[2]
        private val H = normalize(LX, LY, LZ + 1f)
        private val HX = H[0]
        private val HY = H[1]
        private val HZ = H[2]

        private fun normalize(x: Float, y: Float, z: Float): FloatArray {
            val l = sqrt(x * x + y * y + z * z)
            return floatArrayOf(x / l, y / l, z / l)
        }
    }
}
