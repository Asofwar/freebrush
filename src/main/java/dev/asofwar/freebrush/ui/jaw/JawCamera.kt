package dev.asofwar.freebrush.ui.jaw

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Turntable camera shared by the GPU renderer and touch picking, so a tap selects exactly what is drawn under
 * the finger. Pure Kotlin, column-major 4x4 matrices as in OpenGL.
 *
 * The model spins around its vertical axis (yaw) on a floor; the camera looks at it from an elevation (pitch).
 * [modelView] with `mirrored` gives the reflection of the model in that floor.
 */
class JawCamera(private val mesh: JawMesh) {
    private val cx: Float
    private val cy: Float
    private val cz: Float
    private val radius: Float

    /** Lowest point of the model in model space; the floor sits just below it. */
    val minY: Float

    init {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var lowY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until mesh.vertexCount) {
            minX = min(minX, mesh.x(i)); maxX = max(maxX, mesh.x(i))
            lowY = min(lowY, mesh.y(i)); maxY = max(maxY, mesh.y(i))
            minZ = min(minZ, mesh.z(i)); maxZ = max(maxZ, mesh.z(i))
        }
        cx = (minX + maxX) / 2; cy = (lowY + maxY) / 2; cz = (minZ + maxZ) / 2
        minY = lowY
        var r = 0f
        for (i in 0 until mesh.vertexCount) {
            val dx = mesh.x(i) - cx; val dy = mesh.y(i) - cy; val dz = mesh.z(i) - cz
            r = max(r, dx * dx + dy * dy + dz * dz)
        }
        radius = sqrt(r)
    }

    private val tanHalf = tan(Math.toRadians(FOV_Y / 2.0)).toFloat()

    /** Distance that fits the model's bounding sphere; wide views fit the height, tall ones the width. */
    private fun distance(aspect: Float): Float = radius / (tanHalf * min(1f, aspect)) * FIT

    fun projection(aspect: Float): FloatArray {
        val d = distance(aspect)
        val near = max(1f, d - radius * 3f)
        val far = d + radius * 3f
        val f = 1f / tanHalf
        return floatArrayOf(
            f / aspect, 0f, 0f, 0f,
            0f, f, 0f, 0f,
            0f, 0f, (far + near) / (near - far), -1f,
            0f, 0f, 2f * far * near / (near - far), 0f,
        )
    }

    /**
     * translate(0, lift, -distance) * rotX(pitch) * [mirror in floor] * rotY(yaw) * scale * translate(-centre).
     * The model is lifted a little in the frame to leave room for its reflection.
     */
    fun modelView(yaw: Float, pitch: Float, scale: Float, aspect: Float, mirrored: Boolean = false): FloatArray {
        var m = translate(-cx, -cy, -cz)
        m = mul(scaleM(scale), m)
        m = mul(rotY(yaw), m)
        if (mirrored) {
            val floor = (minY - FLOOR_GAP - cy) * scale
            m = mul(floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 2 * floor, 0f, 1f), m)
        }
        m = mul(rotX(pitch), m)
        return mul(translate(0f, radius * LIFT * scale, -distance(aspect)), m)
    }

    /** Screen position of a model point, and how much it is on the near side (1) versus the far side (0). */
    fun project(x: Float, y: Float, z: Float, width: Float, height: Float, yaw: Float, pitch: Float, scale: Float): Triple<Float, Float, Float> {
        val aspect = width / height
        val mv = modelView(yaw, pitch, scale, aspect)
        val vx = mv[0] * x + mv[4] * y + mv[8] * z + mv[12]
        val vy = mv[1] * x + mv[5] * y + mv[9] * z + mv[13]
        val vz = mv[2] * x + mv[6] * y + mv[10] * z + mv[14]
        val ndcX = vx / (-vz * tanHalf * aspect)
        val ndcY = vy / (-vz * tanHalf)
        val near = ((vz + distance(aspect)) / radius + 1f) / 2f
        return Triple((ndcX + 1f) / 2f * width, (1f - ndcY) / 2f * height, near.coerceIn(0f, 1f))
    }

    /**
     * Zone under a screen point: nearest pickable triangle hit by the ray through it (teeth and light rings).
     * [JawMesh.GUM] for gums, null for nothing.
     */
    fun pick(px: Float, py: Float, width: Float, height: Float, yaw: Float, pitch: Float, scale: Float): Int? {
        val aspect = width / height
        val dvx = (2f * px / width - 1f) * tanHalf * aspect
        val dvy = (1f - 2f * py / height) * tanHalf
        val dvz = -1f
        val mv = modelView(yaw, pitch, scale, aspect)
        // The model-view is s*R plus a translation, so its inverse is R^T/s with the translation undone.
        val s2 = scale * scale
        fun inv(x: Float, y: Float, z: Float) = floatArrayOf(
            (mv[0] * x + mv[1] * y + mv[2] * z) / s2,
            (mv[4] * x + mv[5] * y + mv[6] * z) / s2,
            (mv[8] * x + mv[9] * y + mv[10] * z) / s2,
        )
        val o = inv(-mv[12], -mv[13], -mv[14])
        val d = inv(dvx, dvy, dvz)

        var best = Float.MAX_VALUE
        var hit: Int? = null
        val idx = mesh.indices
        for (t in 0 until mesh.pickableTriangles) {
            val a = idx[t * 3]; val b = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
            val e1x = mesh.x(b) - mesh.x(a); val e1y = mesh.y(b) - mesh.y(a); val e1z = mesh.z(b) - mesh.z(a)
            val e2x = mesh.x(c) - mesh.x(a); val e2y = mesh.y(c) - mesh.y(a); val e2z = mesh.z(c) - mesh.z(a)
            val px2 = d[1] * e2z - d[2] * e2y
            val py2 = d[2] * e2x - d[0] * e2z
            val pz2 = d[0] * e2y - d[1] * e2x
            val det = e1x * px2 + e1y * py2 + e1z * pz2
            if (det > -1e-9f && det < 1e-9f) continue
            val invDet = 1f / det
            val sx = o[0] - mesh.x(a); val sy = o[1] - mesh.y(a); val sz = o[2] - mesh.z(a)
            val u = (sx * px2 + sy * py2 + sz * pz2) * invDet
            if (u < 0f || u > 1f) continue
            val qx = sy * e1z - sz * e1y
            val qy = sz * e1x - sx * e1z
            val qz = sx * e1y - sy * e1x
            val v = (d[0] * qx + d[1] * qy + d[2] * qz) * invDet
            if (v < 0f || u + v > 1f) continue
            val dist = (e2x * qx + e2y * qy + e2z * qz) * invDet
            if (dist > 0f && dist < best) {
                best = dist
                hit = mesh.triZone[t]
            }
        }
        return hit
    }

    companion object {
        const val FOV_Y = 24.0
        private const val FIT = 0.84f
        private const val LIFT = 0.21f
        const val FLOOR_GAP = 2.5f

        private fun translate(x: Float, y: Float, z: Float) =
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, x, y, z, 1f)

        private fun scaleM(s: Float) = floatArrayOf(s, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, 1f)

        private fun rotY(a: Float): FloatArray {
            val c = cos(a); val s = sin(a)
            // x' = x c + z s, z' = -x s + z c
            return floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, 0f, 0f, 0f, 1f)
        }

        private fun rotX(a: Float): FloatArray {
            val c = cos(a); val s = sin(a)
            // y' = y c - z s, z' = y s + z c
            return floatArrayOf(1f, 0f, 0f, 0f, 0f, c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f)
        }

        fun mul(a: FloatArray, b: FloatArray): FloatArray {
            val out = FloatArray(16)
            for (col in 0 until 4) for (row in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) s += a[k * 4 + row] * b[col * 4 + k]
                out[col * 4 + row] = s
            }
            return out
        }
    }
}
