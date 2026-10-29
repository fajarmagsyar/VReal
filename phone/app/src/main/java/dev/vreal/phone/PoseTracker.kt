package dev.vreal.phone

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3DoF head orientation from the fused gyro+accelerometer sensor, converted to OpenVR axes
 * (+X right, +Y up, +Z backward). Assumes the phone sits in landscape with its top edge on the
 * left (USB port on the right), which is what android:screenOrientation="landscape" gives.
 *
 * Quaternions are FloatArray(x, y, z, w).
 */
class PoseTracker(context: Context, private val onPose: (timestampUs: Long, q: FloatArray) -> Unit) :
    SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    // GAME_ROTATION_VECTOR ignores the magnetometer, so steel in a sim rig can't drag the yaw.
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private var thread: HandlerThread? = null

    private val raw = FloatArray(4)
    private val androidQ = FloatArray(4)
    private val tmp = FloatArray(4)
    private val head = FloatArray(4)
    private val recenter = floatArrayOf(0f, 0f, 0f, 1f)
    @Volatile private var recenterRequested = true

    @Volatile var sampleCount = 0L
        private set

    val available: Boolean get() = sensor != null

    fun start() {
        val s = sensor ?: return
        val t = HandlerThread("pose").apply { start() }
        thread = t
        sensorManager.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST, Handler(t.looper))
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    /** Makes the current view direction "forward" (yaw only; pitch and roll stay absolute). */
    fun recenter() {
        recenterRequested = true
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getQuaternionFromVector(raw, event.values) // (w, x, y, z)
        androidQ[0] = raw[1]; androidQ[1] = raw[2]; androidQ[2] = raw[3]; androidQ[3] = raw[0]

        // head = worldToVr * androidQ * headToDevice
        mul(WORLD_TO_VR, androidQ, tmp)
        mul(tmp, HEAD_TO_DEVICE, head)

        if (recenterRequested) {
            recenterRequested = false
            val yaw = yawOf(head)
            recenter[0] = 0f; recenter[1] = sin(-yaw / 2); recenter[2] = 0f; recenter[3] = cos(-yaw / 2)
        }
        mul(recenter, head, tmp)
        sampleCount++
        onPose(event.timestamp / 1000, tmp)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        val H = sqrt(0.5f)
        // Android world (X east, Y north, Z up) -> VR world (Y up, -Z forward): rotate -90° about X.
        val WORLD_TO_VR = floatArrayOf(-H, 0f, 0f, H)
        // Head axes expressed in device axes for landscape (top edge left): rotate -90° about Z.
        val HEAD_TO_DEVICE = floatArrayOf(0f, 0f, -H, H)

        fun mul(a: FloatArray, b: FloatArray, out: FloatArray) {
            val x = a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1]
            val y = a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0]
            val z = a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3]
            val w = a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
            out[0] = x; out[1] = y; out[2] = z; out[3] = w
        }

        /** Rotation about +Y that takes -Z to the quaternion's forward direction. */
        fun yawOf(q: FloatArray): Float {
            val (x, y, z, w) = q
            val fx = -2f * (x * z + w * y)
            val fz = -(1f - 2f * (x * x + y * y))
            return atan2(-fx, -fz)
        }
    }
}
