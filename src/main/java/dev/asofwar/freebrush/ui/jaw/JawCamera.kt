package dev.asofwar.freebrush.ui.jaw

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Camera shared by the GPU renderer and touch picking, so a tap selects exactly what is drawn under the finger.
 * Pure Kotlin, column-major 4x4 matrices as in OpenGL.
 *
 * The model is centred on its bounding box, rotated by yaw (around the vertical axis) then pitch (towards the
 * viewer), and looked at from a distance that fits its bounding sphere into the view.
 */
class JawCamera(private val mesh: JawMesh) {
    private val cx: Float
    private val cy: Float
    private val cz: Float
    private val radius: Float

    init {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (i in 0 until mesh.vertexCount) {
            minX = min(minX, mesh.x(i)); maxX = max(maxX, mesh.x(i))
            minY = min(minY, mesh.y(i)); maxY = max(maxY, mesh.y(i))
            minZ = min(minZ, mesh.z(i)); maxZ = max(maxZ, mesh.z(i))
        }
        cx = (minX + maxX) / 2; cy = (minY + maxY) / 2; cz = (minZ + maxZ) / 2
        var r = 0f
        for (i in 0 until mesh.vertexCount) {
            val dx = mesh.x(i) - cx; val dy = mesh.y(i) - cy; val dz = mesh.z(i) - cz
            r = max(r, dx * dx + dy * dy + dz * dz)
        }
        radius = sqrt(r)
    }

    private val tanHalf = tan(Math.toRadians(FOV_Y / 2.0)).toFloat()

    /** Distance that fits the model's bounding sphere, scaled by [FIT]; wide views fit the height, tall ones the width. */
    private fun distance(aspect: Float): Float = radius / (tanHalf * min(1f, aspect)) * FIT

    fun projection(aspect: Float): FloatArray {
        val d = distance(aspect)
        val near = max(1f, d - radius * 2f)
        val far = d + radius * 2f
        val f = 1f / tanHalf
        return floatArrayOf(
            f / aspect, 0f, 0f, 0f,
            0f, f, 0f, 0f,
            0f, 0f, (far + near) / (near - far), -1f,
            0f, 0f, 2f * far * near / (near - far), 0f,
        )
    }

    /** Model-view: translate(0, 0, -distance) * rotX(pitch) * rotY(yaw) * scale * translate(-centre). */
    fun modelView(yaw: Float, pitch: Float, scale: Float, aspect: Float): FloatArray {
        val cyw = cos(yaw); val syw = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)
        // Rotation R = Rx(pitch) * Ry(yaw), rows written out.
        val r00 = cyw; val r01 = 0f; val r02 = syw
        val r10 = sp * syw; val r11 = cp; val r12 = -sp * cyw
        val r20 = -cp * syw; val r21 = sp; val r22 = cp * cyw
        val tx = -(r00 * cx + r01 * cy + r02 * cz) * scale
        val ty = -(r10 * cx + r11 * cy + r12 * cz) * scale
        val tz = -(r20 * cx + r21 * cy + r22 * cz) * scale - distance(aspect)
        return floatArrayOf(
            r00 * scale, r10 * scale, r20 * scale, 0f,
            r01 * scale, r11 * scale, r21 * scale, 0f,
            r02 * scale, r12 * scale, r22 * scale, 0f,
            tx, ty, tz, 1f,
        )
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

    /** Zone under a screen point: nearest triangle hit by the ray through it. [JawMesh.GUM] for gums, null for nothing. */
    fun pick(px: Float, py: Float, width: Float, height: Float, yaw: Float, pitch: Float, scale: Float): Int? {
        val aspect = width / height
        val ndcX = 2f * px / width - 1f
        val ndcY = 1f - 2f * py / height
        // Ray in view space from the eye, then into model space through the inverse model-view.
        val dvx = ndcX * tanHalf * aspect
        val dvy = ndcY * tanHalf
        val dvz = -1f
        val mv = modelView(yaw, pitch, scale, aspect)
        // Inverse of [R*s | t] is [R^T/s | -R^T t/s].
        val s2 = scale * scale
        fun invDir(x: Float, y: Float, z: Float) = floatArrayOf(
            (mv[0] * x + mv[1] * y + mv[2] * z) / s2,
            (mv[4] * x + mv[5] * y + mv[6] * z) / s2,
            (mv[8] * x + mv[9] * y + mv[10] * z) / s2,
        )
        val o = invDir(-mv[12], -mv[13], -mv[14])
        val d = invDir(dvx, dvy, dvz)

        var best = Float.MAX_VALUE
        var hit: Int? = null
        val idx = mesh.indices
        for (t in 0 until mesh.triangleCount) {
            val a = idx[t * 3]; val b = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
            val e1x = mesh.x(b) - mesh.x(a); val e1y = mesh.y(b) - mesh.y(a); val e1z = mesh.z(b) - mesh.z(a)
            val e2x = mesh.x(c) - mesh.x(a); val e2y = mesh.y(c) - mesh.y(a); val e2z = mesh.z(c) - mesh.z(a)
            val px2 = d[1] * e2z - d[2] * e2y
            val py2 = d[2] * e2x - d[0] * e2z
            val pz2 = d[0] * e2y - d[1] * e2x
            val det = e1x * px2 + e1y * py2 + e1z * pz2
            if (det > -1e-9f && det < 1e-9f) continue
            val inv = 1f / det
            val sx = o[0] - mesh.x(a); val sy = o[1] - mesh.y(a); val sz = o[2] - mesh.z(a)
            val u = (sx * px2 + sy * py2 + sz * pz2) * inv
            if (u < 0f || u > 1f) continue
            val qx = sy * e1z - sz * e1y
            val qy = sz * e1x - sx * e1z
            val qz = sx * e1y - sy * e1x
            val v = (d[0] * qx + d[1] * qy + d[2] * qz) * inv
            if (v < 0f || u + v > 1f) continue
            val dist = (e2x * qx + e2y * qy + e2z * qz) * inv
            if (dist > 0f && dist < best) {
                best = dist
                hit = mesh.triZone[t]
            }
        }
        return hit
    }

    companion object {
        const val FOV_Y = 26.0
        private const val FIT = 0.92f
    }
}
