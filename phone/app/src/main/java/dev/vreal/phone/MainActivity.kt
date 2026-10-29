package dev.vreal.phone

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Fullscreen landscape VR view. Tap the screen to recenter.
 *
 * Settings can be passed once via adb and are remembered:
 *   adb shell am start -n dev.vreal.phone/.MainActivity --es host 192.168.42.10 --ef k1 0.3 --ef k2 0.2
 * (`--es host ""` clears the host and goes back to broadcast discovery.)
 */
class MainActivity : Activity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var statsView: TextView
    private lateinit var renderer: VrRenderer
    private lateinit var pose: PoseTracker

    @Volatile private var net: NetClient? = null
    @Volatile private var decoder: VideoDecoder? = null
    private var streamConfig: StreamConfig? = null

    private val ui = Handler(Looper.getMainLooper())
    private var lastStats = Stats()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        saveIntentSettings(intent)
        preferHighestRefreshRate()

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val lens = LensConfig(
            k1 = prefs.getFloat("k1", LensConfig().k1),
            k2 = prefs.getFloat("k2", LensConfig().k2),
            scale = prefs.getFloat("scale", LensConfig().scale),
        )

        pose = PoseTracker(this) { ts, q -> net?.sendPose(ts, q) }
        renderer = VrRenderer(lens) { surface -> ui.post { onDecoderSurface(surface) } }
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setOnClickListener { pose.recenter() }
        }
        statsView = TextView(this).apply {
            setTextColor(Color.GREEN)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        setContentView(FrameLayout(this).apply {
            addView(glView)
            addView(statsView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.TOP,
            ))
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveIntentSettings(intent)
        recreate()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        pose.start()
        net = NetClient(helloInfo(), getSharedPreferences(PREFS, MODE_PRIVATE).getString("host", null), netListener)
            .also { it.start() }
        glView.onResume() // recreates the GL context, which calls onDecoderSurface
        ui.post(statsTick)
    }

    override fun onPause() {
        ui.removeCallbacks(statsTick)
        glView.onPause()
        pose.stop()
        net?.stop()
        net = null
        decoder?.release()
        decoder = null
        super.onPause()
    }

    private fun onDecoderSurface(surface: Surface) {
        decoder?.release()
        decoder = VideoDecoder(surface) { net?.requestKeyframe() }.also { d ->
            streamConfig?.let(d::configure)
        }
    }

    private val netListener = object : NetClient.Listener {
        override fun onConfig(config: StreamConfig) {
            ui.post {
                streamConfig = config
                decoder?.configure(config)
            }
        }

        override fun onFrame(frame: Frame) {
            decoder?.submit(frame)
        }
    }

    private data class Stats(
        val atMs: Long = 0, val bytes: Long = 0, val decoded: Long = 0, val displayed: Long = 0, val poses: Long = 0,
    )

    private val statsTick = object : Runnable {
        override fun run() {
            val n = net
            val d = decoder
            val now = Stats(
                System.currentTimeMillis(), n?.bytesReceived ?: 0, d?.decodedFrames ?: 0,
                renderer.displayedFrames, pose.sampleCount,
            )
            val dt = (now.atMs - lastStats.atMs).coerceAtLeast(1) / 1000.0
            val latency = renderer.takeAverageLatencyMs()
            statsView.text = buildString {
                append(n?.connectedTo?.let { "PC $it (${n.linkName})" } ?: "searching for PC…")
                streamConfig?.let { append("  ${it.width}x${it.height}@${it.fps} ${it.mime.substringAfter('/')}") }
                append("  ${d?.decoderName ?: "-"}\n")
                append("decode %.0f fps  show %.0f fps  ".format(
                    (now.decoded - lastStats.decoded) / dt, (now.displayed - lastStats.displayed) / dt))
                append(if (latency.isNaN()) "latency -  " else "latency %.1f ms  ".format(latency))
                append("%.1f Mbps  lost %d  pose %.0f Hz".format(
                    (now.bytes - lastStats.bytes) * 8 / dt / 1e6, n?.lostFrames ?: 0, (now.poses - lastStats.poses) / dt))
                if (!pose.available) append("\nNO ROTATION SENSOR")
            }
            lastStats = now
            ui.postDelayed(this, 500)
        }
    }

    private fun helloInfo(): NetClient.Hello {
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
            windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            DisplayMetrics().also { @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(it) }
                .let { it.widthPixels to it.heightPixels }
        }
        @Suppress("DEPRECATION")
        val refresh = windowManager.defaultDisplay.refreshRate
        return NetClient.Hello(maxOf(w, h), minOf(w, h), refresh, VideoDecoder.supportedCodecMask())
    }

    private fun preferHighestRefreshRate() {
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    private fun saveIntentSettings(intent: Intent) {
        val extras = intent.extras ?: return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().apply {
            if (extras.containsKey("host")) putString("host", extras.getString("host"))
            for (key in listOf("k1", "k2", "scale")) {
                if (extras.containsKey(key)) putFloat(key, extras.getFloat(key))
            }
        }.apply()
    }

    private companion object {
        const val PREFS = "vreal"
    }
}
