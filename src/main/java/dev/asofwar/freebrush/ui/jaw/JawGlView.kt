package dev.asofwar.freebrush.ui.jaw

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.TextureView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * TextureView that draws the jaw with OpenGL ES 3 on its own thread. Unlike a SurfaceView it scrolls, clips and
 * animates like any other view, so it works inside scrolling screens and bottom sheets.
 *
 * Each frame has three passes: the model mirrored in a glossy floor (faded), the model itself, and the
 * transparent halos of the light rings. Callers [submit] frames; only the latest one is drawn.
 */
@SuppressLint("ViewConstructor")
class JawGlView(context: Context, private val mesh: JawMesh, private val camera: JawCamera) :
    TextureView(context), TextureView.SurfaceTextureListener {

    /**
     * Everything that changes between frames. [rings] holds, per zone, an sRGB colour and a brightness (r, g, b, level).
     */
    class Frame(
        val yaw: Float,
        val pitch: Float,
        val scale: Float,
        val rings: FloatArray,
        val selected: Int,
        val glow: Float,
    )

    private val thread = HandlerThread("jaw-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val latest = AtomicReference<Frame?>(null)
    private val scheduled = AtomicBoolean(false)
    private var egl: Egl? = null
    private var renderer: Renderer? = null
    private var width = 1
    private var height = 1

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    fun submit(frame: Frame) {
        latest.set(frame)
        if (!scheduled.getAndSet(true)) handler.post(drawLatest)
    }

    private val drawLatest = Runnable {
        scheduled.set(false)
        val f = latest.get() ?: return@Runnable
        val e = egl ?: return@Runnable
        val r = renderer ?: return@Runnable
        r.draw(f, camera, width, height)
        e.swap()
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
        handler.post {
            width = w; height = h
            try {
                egl = Egl(surface)
                renderer = Renderer(mesh, camera.minY)
                drawLatest.run()
            } catch (t: Throwable) {
                Log.e(TAG, "GL init failed", t)
            }
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {
        handler.post {
            width = w; height = h
            drawLatest.run()
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // Tear down on the GL thread, then release the texture ourselves (hence false).
        handler.post {
            renderer?.release()
            renderer = null
            egl?.release()
            egl = null
            surface.release()
        }
        return false
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.post { thread.quitSafely() }
    }

    /** EGL display, context and window surface for one SurfaceTexture, with 4x MSAA when available. */
    private class Egl(texture: SurfaceTexture) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val surface: EGLSurface

        init {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
            val config = choose(4) ?: choose(0) ?: error("no EGL config")
            context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
            )
            surface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent" }
        }

        private fun choose(samples: Int): EGLConfig? {
            val attrs = mutableListOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 24, EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            )
            if (samples > 0) attrs += listOf(EGL14.EGL_SAMPLE_BUFFERS, 1, EGL14.EGL_SAMPLES, samples)
            attrs += EGL14.EGL_NONE
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(display, attrs.toIntArray(), 0, configs, 0, 1, count, 0)
            return if (count[0] > 0) configs[0] else null
        }

        fun swap() {
            EGL14.eglSwapBuffers(display, surface)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private class Renderer(mesh: JawMesh, private val minY: Float) {
        private val program: Int
        private val vao = IntArray(1)
        private val buffers = IntArray(2)
        private val opaqueCount = mesh.pickableTriangles * 3
        private val haloCount = mesh.indices.size - opaqueCount
        private val uMvp: Int
        private val uMv: Int
        private val uRing: Int
        private val uSelected: Int
        private val uGlow: Int
        private val uPass: Int
        private val uMinY: Int

        init {
            program = link(compile(GLES30.GL_VERTEX_SHADER, VERTEX), compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT))
            uMvp = GLES30.glGetUniformLocation(program, "uMvp")
            uMv = GLES30.glGetUniformLocation(program, "uMv")
            uRing = GLES30.glGetUniformLocation(program, "uRing")
            uSelected = GLES30.glGetUniformLocation(program, "uSelected")
            uGlow = GLES30.glGetUniformLocation(program, "uGlow")
            uPass = GLES30.glGetUniformLocation(program, "uPass")
            uMinY = GLES30.glGetUniformLocation(program, "uMinY")

            GLES30.glGenVertexArrays(1, vao, 0)
            GLES30.glBindVertexArray(vao[0])
            GLES30.glGenBuffers(2, buffers, 0)
            val vb = ByteBuffer.allocateDirect(mesh.vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            vb.put(mesh.vertices).position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0])
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.vertices.size * 4, vb, GLES30.GL_STATIC_DRAW)
            val ib = ByteBuffer.allocateDirect(mesh.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            ib.put(mesh.indices).position(0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1])
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 4, ib, GLES30.GL_STATIC_DRAW)
            val stride = JawMesh.STRIDE * 4
            // position, normal, colour, ao, zone, outer
            val layout = listOf(0 to 3, 3 to 3, 6 to 3, 9 to 1, 10 to 1, 11 to 1)
            layout.forEachIndexed { loc, (offset, size) ->
                GLES30.glEnableVertexAttribArray(loc)
                GLES30.glVertexAttribPointer(loc, size, GLES30.GL_FLOAT, false, stride, offset * 4)
            }
            GLES30.glBindVertexArray(0)
        }

        fun draw(f: Frame, camera: JawCamera, w: Int, h: Int) {
            GLES30.glViewport(0, 0, w, h)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glEnable(GLES30.GL_CULL_FACE)
            GLES30.glUseProgram(program)
            GLES30.glUniform4fv(uRing, 8, f.rings, 0)
            GLES30.glUniform1i(uSelected, f.selected)
            GLES30.glUniform1f(uGlow, f.glow)
            GLES30.glUniform1f(uMinY, minY)
            GLES30.glBindVertexArray(vao[0])
            val aspect = w.toFloat() / h
            val proj = camera.projection(aspect)

            // 1. Reflection in the floor: mirrored (so winding flips), faded with height, blended premultiplied.
            setMatrices(proj, camera.modelView(f.yaw, f.pitch, f.scale, aspect, mirrored = true))
            GLES30.glFrontFace(GLES30.GL_CW)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glUniform1i(uPass, 1)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, opaqueCount, GLES30.GL_UNSIGNED_INT, 0)
            GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)

            // 2. The model.
            setMatrices(proj, camera.modelView(f.yaw, f.pitch, f.scale, aspect))
            GLES30.glFrontFace(GLES30.GL_CCW)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glUniform1i(uPass, 0)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, opaqueCount, GLES30.GL_UNSIGNED_INT, 0)

            // 3. Halos of the light rings: transparent, depth-tested but not written.
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glDepthMask(false)
            GLES30.glUniform1i(uPass, 2)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, haloCount, GLES30.GL_UNSIGNED_INT, opaqueCount * 4)
            GLES30.glDepthMask(true)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glBindVertexArray(0)
        }

        private fun setMatrices(proj: FloatArray, mv: FloatArray) {
            GLES30.glUniformMatrix4fv(uMvp, 1, false, JawCamera.mul(proj, mv), 0)
            GLES30.glUniformMatrix4fv(uMv, 1, false, mv, 0)
        }

        fun release() {
            GLES30.glDeleteBuffers(2, buffers, 0)
            GLES30.glDeleteVertexArrays(1, vao, 0)
            GLES30.glDeleteProgram(program)
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES30.glGetShaderInfoLog(s) }
            return s
        }

        private fun link(vs: Int, fs: Int): Int {
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, vs)
            GLES30.glAttachShader(p, fs)
            GLES30.glLinkProgram(p)
            val ok = IntArray(1)
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "link: " + GLES30.glGetProgramInfoLog(p) }
            return p
        }
    }

    companion object {
        private const val TAG = "JawGl"

        private val VERTEX = """
            #version 300 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec3 aColor;
            layout(location = 3) in float aAo;
            layout(location = 4) in float aZone;
            layout(location = 5) in float aOuter;
            uniform mat4 uMvp;
            uniform mat4 uMv;
            uniform float uMinY;
            out vec3 vN;
            out vec3 vPos;
            out vec3 vColor;
            out float vAo;
            flat out int vZone;
            out float vOuter;
            out float vHeight;
            void main() {
                vec4 p = uMv * vec4(aPos, 1.0);
                vPos = p.xyz;
                vN = mat3(uMv) * aNormal;
                vColor = aColor;
                vAo = aAo;
                vZone = int(floor(aZone + 0.5));
                vOuter = aOuter;
                vHeight = aPos.y - uMinY;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """.trimIndent()

        // A studio, not an operating theatre. Teeth are polished porcelain lit by a large overhead softbox and two
        // strip lights, whose reflections slide over the glaze as the model turns. Gums are matte soft-touch
        // silicone with a little light scattered through. Zone data lives in the light rings; teeth are only
        // tinted, gently, for the selected zone. Filmic tone mapping keeps the whites from clipping.
        private val FRAGMENT = """
            #version 300 es
            precision highp float;
            in vec3 vN;
            in vec3 vPos;
            in vec3 vColor;
            in float vAo;
            flat in int vZone;
            in float vOuter;
            in float vHeight;
            uniform vec4 uRing[8];
            uniform int uSelected;
            uniform float uGlow;
            uniform int uPass;
            out vec4 fragColor;

            vec3 lin(vec3 c) { return c * c; }

            // Studio environment as seen in a reflection direction (view space).
            vec3 studio(vec3 r) {
                float top = smoothstep(0.35, 0.8, r.y) * (1.0 - smoothstep(0.93, 1.0, r.y));
                float strips = smoothstep(0.72, 0.9, abs(r.x)) * (1.0 - smoothstep(0.35, 0.7, abs(r.y)));
                float floorGlow = smoothstep(-0.2, -0.75, r.y) * 0.12;
                return vec3(0.10, 0.105, 0.115) + vec3(1.35, 1.33, 1.30) * top + vec3(0.70, 0.74, 0.80) * strips + floorGlow;
            }

            vec3 tonemap(vec3 x) {
                x *= 0.92;
                return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
            }

            void main() {
                vec3 N = normalize(vN);
                if (!gl_FrontFacing) N = -N;
                vec3 V = normalize(-vPos);
                float ndv = max(0.0, dot(N, V));
                float fres = pow(1.0 - ndv, 5.0);

                // Light rings and their halos: data shown as light.
                if (vZone >= 10) {
                    int z = vZone >= 20 ? vZone - 20 : vZone - 10;
                    vec3 c = lin(uRing[z].rgb);
                    float level = uRing[z].a;
                    float sel = (uSelected == z) ? 1.0 : 0.0;
                    float dim = (uSelected >= 0 && sel == 0.0) ? 0.28 : 1.0;
                    float energy = level * dim * (1.0 + sel * (0.35 + 0.45 * uGlow)) * mix(0.15, 1.0, vOuter);
                    if (vZone >= 20) {
                        if (uPass != 2) discard;
                        float a = pow(ndv, 3.0) * 0.22 * energy * vOuter;
                        fragColor = vec4(sqrt(c) * a * 1.6, a);
                        return;
                    }
                    vec3 core = c * (0.9 + 1.1 * pow(ndv, 0.6)) * energy + vec3(0.18) * pow(ndv, 8.0) * energy;
                    vec3 outc = tonemap(core);
                    if (uPass == 1) {
                        float f = exp(-vHeight / 7.0) * 0.22;
                        fragColor = vec4(sqrt(outc) * f, f);
                    } else {
                        fragColor = vec4(sqrt(outc), 1.0);
                    }
                    return;
                }
                if (uPass == 2) discard;

                bool gum = vZone < 0;
                vec3 Lk = normalize(vec3(-0.25, 0.85, 0.45));
                vec3 Lf = normalize(vec3(0.8, 0.25, 0.55));
                vec3 Lr = normalize(vec3(-0.2, 0.45, -0.87));
                vec3 R = reflect(-V, N);
                vec3 col;

                if (!gum) {
                    // Porcelain.
                    vec3 albedo = lin(vColor);
                    float sel = 0.0;
                    if (uSelected >= 0) {
                        sel = (uSelected == vZone ? vOuter : 0.0) + (uSelected == vZone + 1 ? 1.0 - vOuter : 0.0);
                        albedo = mix(albedo, albedo * lin(uRing[uSelected].rgb) * 1.25, 0.38 * sel);
                    }
                    float wrapK = max(0.0, (dot(N, Lk) + 0.3) / 1.3);
                    float wrapF = max(0.0, (dot(N, Lf) + 0.3) / 1.3);
                    vec3 ambient = mix(vec3(0.14, 0.13, 0.13), vec3(0.42, 0.44, 0.47), N.y * 0.5 + 0.5) * vAo;
                    vec3 diffuse = albedo * (ambient + vec3(1.05, 1.03, 1.0) * wrapK * 0.85 + vec3(0.62, 0.68, 0.78) * wrapF * 0.30);
                    float spec = pow(max(0.0, dot(N, normalize(Lk + V))), 240.0) * 1.6
                        + pow(max(0.0, dot(N, normalize(Lf + V))), 120.0) * 0.5;
                    vec3 refl = studio(R) * (0.06 + 0.75 * fres) * mix(0.6, 1.0, vAo);
                    col = diffuse * (1.0 - 0.6 * fres) + refl + vec3(spec);
                    col += vec3(0.75, 0.85, 1.0) * fres * max(0.0, dot(N, Lr) + 0.3) * 0.25;
                    if (sel > 0.0) col += lin(uRing[uSelected].rgb) * sel * (0.10 + 0.12 * uGlow);
                    col *= mix(1.0, vAo, 0.45);
                } else {
                    // Soft-touch silicone.
                    vec3 albedo = lin(vColor);
                    float ndl = dot(N, Lk);
                    float wrapK = max(0.0, (ndl + 0.6) / 1.6);
                    float wrapF = max(0.0, (dot(N, Lf) + 0.6) / 1.6);
                    vec3 ambient = mix(vec3(0.15, 0.12, 0.12), vec3(0.40, 0.40, 0.43), N.y * 0.5 + 0.5) * vAo;
                    col = albedo * (ambient + vec3(1.0, 0.98, 0.96) * wrapK * 0.80 + vec3(0.65, 0.68, 0.75) * wrapF * 0.25);
                    col += albedo * vec3(1.0, 0.42, 0.40) * pow(1.0 - max(0.0, ndl), 2.0) * 0.10;
                    col += vec3(1.0, 0.93, 0.93) * pow(1.0 - ndv, 2.5) * 0.12;
                    col += vec3(1.0) * pow(max(0.0, dot(N, normalize(Lk + V))), 18.0) * 0.06;
                    col += studio(R) * fres * 0.10;
                    col *= mix(1.0, vAo, 0.5);
                }

                vec3 outc = tonemap(col);
                if (uPass == 1) {
                    // Glossy floor: the reflection fades out quickly with height above the floor.
                    float f = exp(-vHeight / 6.0) * 0.16;
                    fragColor = vec4(sqrt(outc) * f, f);
                } else {
                    fragColor = vec4(sqrt(outc), 1.0);
                }
            }
        """.trimIndent()
    }
}
