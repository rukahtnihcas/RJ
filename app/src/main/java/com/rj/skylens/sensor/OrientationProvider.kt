package com.rj.skylens.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Device orientation from the fused rotation-vector sensor (falls back to
 * accelerometer + magnetometer). Produces a smoothed rotation matrix that maps
 * device coordinates to world coordinates (X = magnetic east, Y = magnetic north, Z = up).
 */
class OrientationProvider(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val q = FloatArray(4)          // smoothed rotation vector x, y, z, w
    private var hasQ = false
    private val gravity = FloatArray(3)
    private val geomag = FloatArray(3)
    private var hasGravity = false
    private var hasGeomag = false
    private val tmpR = FloatArray(9)

    /** Latest smoothed device->world rotation matrix (row-major 3x3). */
    val matrix = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }
    @Volatile var hasReading = false
        private set
    /** SensorManager.SENSOR_STATUS_* of the magnetometer; low values mean "wave the phone in a figure 8". */
    @Volatile var magneticAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        private set

    /** 0..1, higher = more responsive, lower = smoother. */
    var smoothing = 0.25f

    val available: Boolean get() = rotationSensor != null || (accel != null && mag != null)

    fun start() {
        if (rotationSensor != null) {
            sm.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
            mag?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } // accuracy reports only
        } else {
            accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            mag?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
                val w = if (event.values.size > 3) event.values[3] else sqrt((1 - x * x - y * y - z * z).coerceAtLeast(0f))
                blend(x, y, z, w)
                SensorManager.getRotationMatrixFromVector(tmpR, q)
                synchronized(matrix) { System.arraycopy(tmpR, 0, matrix, 0, 9) }
                hasReading = true
            }
            Sensor.TYPE_ACCELEROMETER -> { lowPass(event.values, gravity, hasGravity); hasGravity = true; fallbackUpdate() }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (rotationSensor == null) { lowPass(event.values, geomag, hasGeomag); hasGeomag = true; fallbackUpdate() }
            }
        }
    }

    private fun blend(x: Float, y: Float, z: Float, w: Float) {
        if (!hasQ) { q[0] = x; q[1] = y; q[2] = z; q[3] = w; hasQ = true; return }
        // q and -q are the same rotation; pick the closer one before blending.
        val dot = q[0] * x + q[1] * y + q[2] * z + q[3] * w
        val s = if (dot < 0) -1f else 1f
        val a = smoothing
        q[0] += a * (s * x - q[0]); q[1] += a * (s * y - q[1]); q[2] += a * (s * z - q[2]); q[3] += a * (s * w - q[3])
        val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        for (i in 0..3) q[i] /= n
    }

    private fun lowPass(input: FloatArray, out: FloatArray, initialised: Boolean) {
        for (i in 0..2) out[i] = if (initialised) out[i] + 0.15f * (input[i] - out[i]) else input[i]
    }

    private fun fallbackUpdate() {
        if (!hasGravity || !hasGeomag) return
        if (SensorManager.getRotationMatrix(tmpR, null, gravity, geomag)) {
            synchronized(matrix) { System.arraycopy(tmpR, 0, matrix, 0, 9) }
            hasReading = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) magneticAccuracy = accuracy
    }
}
