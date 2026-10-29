package dev.vreal.phone

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Lens parameters for the headset viewer. k1/k2 are radial distortion coefficients. */
data class LensConfig(val k1: Float = 0.22f, val k2: Float = 0.24f, val scale: Float = 1.0f)

/**
 * Draws the side-by-side stream (left half → left eye) into the two halves of the screen,
 * barrel-distorted to cancel the viewer lenses' pincushion distortion.
 */
class VrRenderer(
    private val lens: LensConfig,
    private val onSurfaceReady: (Surface) -> Unit,
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    private var surfaceTexture: SurfaceTexture? = null
    private var program = 0
    private var width = 0
    private var height = 0
    private val texMatrix = FloatArray(16)
    @Volatile private var frameAvailable = false

    // Motion-to-photon stats, read by the UI thread.
    @Volatile var displayedFrames = 0L
        private set
    private var latencySumUs = 0L
    private var latencyCount = 0

    /** Average pose→display latency since the last call, in ms (NaN if no frames). */
    @Synchronized
    fun takeAverageLatencyMs(): Double {
        val avg = if (latencyCount == 0) Double.NaN else latencySumUs / 1000.0 / latencyCount
        latencySumUs = 0
        latencyCount = 0
        return avg
    }

    @Synchronized
    private fun recordLatency(us: Long) {
        if (us in 0..1_000_000) {
            latencySumUs += us
            latencyCount++
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(tex[0])
        st.setOnFrameAvailableListener(this)
        surfaceTexture = st
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        onSurfaceReady(Surface(st))
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w
        height = h
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        if (frameAvailable) {
            frameAvailable = false
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            displayedFrames++
            recordLatency(SystemClock.elapsedRealtimeNanos() / 1000 - st.timestamp / 1000)
        }

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (displayedFrames == 0L) return

        GLES20.glUseProgram(program)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, QUAD)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uK"), lens.k1, lens.k2)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uScale"), lens.scale)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uAspect"), (width / 2f) / height)
        val uEye = GLES20.glGetUniformLocation(program, "uEyeOffset")

        val eyeWidth = width / 2
        for (eye in 0..1) {
            GLES20.glViewport(eye * eyeWidth, 0, eyeWidth, height)
            GLES20.glUniform1f(uEye, eye * 0.5f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    override fun onFrameAvailable(st: SurfaceTexture) {
        frameAvailable = true
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) Log.e(TAG, "shader: ${GLES20.glGetShaderInfoLog(shader)}")
            return shader
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    private companion object {
        const val TAG = "VReal.Renderer"

        val QUAD = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).apply { position(0) }

        const val VERTEX_SHADER = """
            attribute vec2 aPos;
            varying vec2 vUv;
            void main() {
                vUv = aPos * 0.5 + 0.5;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        // For each screen pixel, sample the source further out as the radius grows (barrel
        // pre-distortion). Radius is measured in a square space so the distortion stays round.
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            uniform mat4 uTexMatrix;
            uniform vec2 uK;
            uniform float uScale;
            uniform float uAspect;
            uniform float uEyeOffset;
            varying vec2 vUv;
            void main() {
                vec2 d = vUv - 0.5;
                d.x *= uAspect;
                float r2 = dot(d, d);
                d *= (1.0 + uK.x * r2 + uK.y * r2 * r2) * uScale;
                d.x /= uAspect;
                vec2 uv = d + 0.5;
                if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                vec2 src = vec2(uEyeOffset + uv.x * 0.5, uv.y);
                gl_FragColor = texture2D(uTex, (uTexMatrix * vec4(src, 0.0, 1.0)).xy);
            }
        """
    }
}
