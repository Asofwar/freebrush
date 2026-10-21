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
 * Callers set the mesh once and then [submit] frames; only the latest frame is drawn.
 */
@SuppressLint("ViewConstructor")
class JawGlView(context: Context, private val mesh: JawMesh) : TextureView(context), TextureView.SurfaceTextureListener {

    /** Everything that changes between frames. [zoneColors] holds 8 sRGB colours as r, g, b triples. */
    class Frame(
        val yaw: Float,
        val pitch: Float,
        val scale: Float,
        val zoneColors: FloatArray,
        val selected: Int,
        val glow: Float,
        val tint: Float,
    )

    private val thread = HandlerThread("jaw-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val latest = AtomicReference<Frame?>(null)
    private val scheduled = AtomicBoolean(false)
    private val camera = JawCamera(mesh)
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
                val e = Egl(surface)
                egl = e
                renderer = Renderer(mesh)
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

    private class Renderer(mesh: JawMesh) {
        private val program: Int
        private val vao = IntArray(1)
        private val buffers = IntArray(2)
        private val indexCount = mesh.indices.size
        private val uMvp: Int
        private val uMv: Int
        private val uZone: Int
        private val uSelected: Int
        private val uGlow: Int
        private val uTint: Int
        private val mvp = FloatArray(16)

        init {
            program = link(compile(GLES30.GL_VERTEX_SHADER, VERTEX), compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT))
            uMvp = GLES30.glGetUniformLocation(program, "uMvp")
            uMv = GLES30.glGetUniformLocation(program, "uMv")
            uZone = GLES30.glGetUniformLocation(program, "uZone")
            uSelected = GLES30.glGetUniformLocation(program, "uSelected")
            uGlow = GLES30.glGetUniformLocation(program, "uGlow")
            uTint = GLES30.glGetUniformLocation(program, "uTint")

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
            GLES30.glCullFace(GLES30.GL_BACK)
            val aspect = w.toFloat() / h
            val mv = camera.modelView(f.yaw, f.pitch, f.scale, aspect)
            multiply(mvp, camera.projection(aspect), mv)
            GLES30.glUseProgram(program)
            GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES30.glUniformMatrix4fv(uMv, 1, false, mv, 0)
            GLES30.glUniform3fv(uZone, 8, f.zoneColors, 0)
            GLES30.glUniform1i(uSelected, f.selected)
            GLES30.glUniform1f(uGlow, f.glow)
            GLES30.glUniform1f(uTint, f.tint)
            GLES30.glBindVertexArray(vao[0])
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
            GLES30.glBindVertexArray(0)
        }

        fun release() {
            GLES30.glDeleteBuffers(2, buffers, 0)
            GLES30.glDeleteVertexArrays(1, vao, 0)
            GLES30.glDeleteProgram(program)
        }

        private fun multiply(out: FloatArray, a: FloatArray, b: FloatArray) {
            for (c in 0 until 4) for (r in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) s += a[k * 4 + r] * b[c * 4 + k]
                out[c * 4 + r] = s
            }
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
            out vec3 vN;
            out vec3 vPos;
            out vec3 vColor;
            out float vAo;
            flat out int vZone;
            out float vOuter;
            void main() {
                vec4 p = uMv * vec4(aPos, 1.0);
                vPos = p.xyz;
                vN = mat3(uMv) * aNormal;
                vColor = aColor;
                vAo = aAo;
                vZone = int(floor(aZone + 0.5));
                vOuter = aOuter;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """.trimIndent()

        // Linear-space lighting: wrapped diffuse from a warm key and a cool fill, sky/ground ambient, glossy
        // enamel highlights with a Fresnel sheen, softer wet highlights and a red scatter tint on the gums, and a
        // rim light from behind. Zones tint the natural enamel; a selected zone glows.
        private val FRAGMENT = """
            #version 300 es
            precision highp float;
            in vec3 vN;
            in vec3 vPos;
            in vec3 vColor;
            in float vAo;
            flat in int vZone;
            in float vOuter;
            uniform vec3 uZone[8];
            uniform int uSelected;
            uniform float uGlow;
            uniform float uTint;
            out vec4 fragColor;

            vec3 lin(vec3 c) { return c * c; }

            void main() {
                vec3 N = normalize(vN);
                if (!gl_FrontFacing) N = -N;
                vec3 V = normalize(-vPos);
                bool gum = vZone < 0;
                vec3 base = lin(vColor);
                float sel = 0.0;
                if (!gum) {
                    vec3 zc = mix(lin(uZone[vZone + 1]), lin(uZone[vZone]), vOuter);
                    float amt = uTint;
                    if (uSelected >= 0) {
                        sel = (uSelected == vZone ? vOuter : 0.0) + (uSelected == vZone + 1 ? 1.0 - vOuter : 0.0);
                        amt = mix(uTint * 0.2, 0.8, sel);
                    }
                    base = mix(base, zc, amt);
                }

                vec3 L1 = normalize(vec3(-0.45, 0.65, 0.62));
                vec3 L2 = normalize(vec3(0.75, 0.05, 0.45));
                vec3 L3 = normalize(vec3(0.1, 0.55, -0.83));
                float wrap = gum ? 0.5 : 0.25;
                float d1 = max(0.0, (dot(N, L1) + wrap) / (1.0 + wrap));
                float d2 = max(0.0, (dot(N, L2) + wrap) / (1.0 + wrap));
                vec3 ambient = mix(vec3(0.16, 0.13, 0.13), vec3(0.40, 0.43, 0.48), N.y * 0.5 + 0.5);
                vec3 col = base * (ambient * vAo + vec3(1.0, 0.96, 0.90) * d1 * 0.95 + vec3(0.70, 0.80, 0.95) * d2 * 0.32);

                float ndv = max(0.0, dot(N, V));
                float fres = pow(1.0 - ndv, 3.0);
                float ndh = max(0.0, dot(N, normalize(L1 + V)));
                float ndh2 = max(0.0, dot(N, normalize(L2 + V)));
                if (gum) {
                    col += vec3(1.0, 0.92, 0.92) * (pow(ndh, 36.0) * 0.28 + pow(ndh2, 36.0) * 0.10);
                    col += vec3(0.55, 0.10, 0.12) * pow(1.0 - max(0.0, dot(N, L1)), 2.0) * 0.10;
                } else {
                    col += vec3(1.0) * (pow(ndh, 110.0) * 0.75 + pow(ndh, 16.0) * 0.06 + pow(ndh2, 80.0) * 0.25);
                    col += vec3(0.55, 0.65, 0.78) * fres * 0.22;
                }
                col += (gum ? vec3(0.95, 0.55, 0.55) : vec3(0.85, 0.92, 1.0)) * fres * max(0.0, dot(N, L3) + 0.2) * 0.30;
                col *= mix(1.0, vAo, 0.55);
                col += vec3(0.05, 0.16, 0.15) * sel * (0.5 + 0.5 * uGlow);

                col = col * 1.25 / (1.0 + col * 0.35);
                fragColor = vec4(sqrt(clamp(col, 0.0, 1.0)), 1.0);
            }
        """.trimIndent()
    }
}
