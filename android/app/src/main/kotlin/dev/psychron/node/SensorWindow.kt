package dev.psychron.node

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.util.Log
import kotlin.math.sqrt

/**
 * Accumulates every sensor over one window and reduces it to a summary, and runs
 * the vibration detector over the raw accelerometer between windows.
 *
 * All callbacks arrive on the handler passed in, and `take()` is called on the same
 * one, so the accumulators need no locking: one thread writes them and the same
 * thread reads and resets them.
 */
class SensorWindow(
    private val context: Context,
    private val handler: Handler,
    private val onVibration: (VibrationDetector.Event) -> Unit,
) : SensorEventListener {

    private class Stats {
        var n = 0
        var sum = 0.0
        var sumSq = 0.0
        var peak = 0.0
        fun add(v: Double) { n++; sum += v; sumSq += v * v; if (v > peak) peak = v }
        fun mean() = if (n == 0) null else sum / n
        fun rms() = if (n == 0) null else sqrt(sumSq / n)
        fun peakOrNull() = if (n == 0) null else peak
        fun reset() { n = 0; sum = 0.0; sumSq = 0.0; peak = 0.0 }
    }

    private val sm = context.getSystemService(SensorManager::class.java)

    private val pressure = Stats()
    private val light = Stats()
    private val accel = Stats()
    private val gyro = Stats()
    private val mag = Stats()

    // Light is an on-change sensor: the platform emits nothing while the value
    // holds steady, so a dark room that stays dark produces windows with no light
    // event at all. For a sensor declared on-change, silence means "unchanged", and
    // the last value is carried rather than the window reported as unmeasured.
    private var lastLux: Double? = null
    private var luxOnChange = false

    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)
    private var heading: Double? = null

    private val detector = VibrationDetector()

    /** Which sensors this phone actually has, for the screen and the log. */
    val present = mutableMapOf<String, Boolean>()

    fun start() {
        // Gravity removed by the platform's own sensor fusion. Raw acceleration
        // magnitude sits at 9.81 m/s² whatever the phone is doing, so its RMS
        // would measure the planet rather than the movement.
        register("barometer", Sensor.TYPE_PRESSURE, SensorManager.SENSOR_DELAY_NORMAL)
        register("light", Sensor.TYPE_LIGHT, SensorManager.SENSOR_DELAY_NORMAL)
        register("accelerometer", Sensor.TYPE_LINEAR_ACCELERATION, SensorManager.SENSOR_DELAY_GAME)
        register("gyroscope", Sensor.TYPE_GYROSCOPE, SensorManager.SENSOR_DELAY_GAME)
        register("magnetometer", Sensor.TYPE_MAGNETIC_FIELD, SensorManager.SENSOR_DELAY_UI)
        // Heading comes from the fused rotation vector, which already combines the
        // magnetometer with the gyroscope and so does not swing every time the
        // phone is tilted. Its azimuth is referenced to magnetic north.
        register("compass", Sensor.TYPE_ROTATION_VECTOR, SensorManager.SENSOR_DELAY_UI)
        // The detector wants the raw accelerometer, gravity and all, at 200 Hz: the
        // fused linear acceleration is filtered for gestures and smooths away the
        // few-tenths-of-a-second shaking an event is made of. 200 Hz is the ceiling
        // an app gets without asking for high-rate sensors, and enough to 100 Hz.
        //
        // Slower rates behind it, because a refused rate is silent: registerListener
        // returns false and logs a line nobody reads. The Galaxy S26 refuses anything
        // faster than 100 Hz from this app, and until this checked the answer its
        // detector never received a single sample while the screen said it was
        // listening. Never below 5 000 µs: without HIGH_SAMPLING_RATE_SENSORS that is
        // a SecurityException rather than a refusal, and the node would not start.
        register("vibration", Sensor.TYPE_ACCELEROMETER, 5_000, 10_000, 20_000)
    }

    fun stop() = sm.unregisterListener(this)

    /** Registers at the first of [delays] the phone accepts; [present] records whether any was. */
    private fun register(name: String, type: Int, vararg delays: Int) {
        val sensor = sm.getDefaultSensor(type)
        if (sensor != null && type == Sensor.TYPE_LIGHT) luxOnChange = sensor.reportingMode == Sensor.REPORTING_MODE_ON_CHANGE
        // A sensor the phone will not give is a smaller record, never a node that
        // fails to start: an exception here is logged like a refusal.
        val accepted = sensor != null && delays.any { delay ->
            runCatching { sm.registerListener(this, sensor, delay, handler) }
                .onFailure { Log.w(TAG, "$name at $delay µs: ${it.message}") }
                .getOrDefault(false)
        }
        present[name] = accepted
        if (sensor != null && !accepted) Log.w(TAG, "$name: ${sensor.name} refused every rate in ${delays.toList()}")
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_PRESSURE -> pressure.add(e.values[0].toDouble())
            Sensor.TYPE_LIGHT -> e.values[0].toDouble().let { light.add(it); lastLux = it }
            Sensor.TYPE_LINEAR_ACCELERATION -> accel.add(norm(e.values))
            Sensor.TYPE_GYROSCOPE -> norm(e.values).let { gyro.add(it); detector.onRotation(e.timestamp, it) }
            Sensor.TYPE_MAGNETIC_FIELD -> mag.add(norm(e.values))
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotation, e.values)
                SensorManager.getOrientation(rotation, orientation)
                heading = Math.toDegrees(orientation[0].toDouble())
            }
            Sensor.TYPE_ACCELEROMETER -> detector.onAcceleration(
                e.timestamp, e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble(),
            )?.let(onVibration)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    /** What the vibration detector is doing right now, for the screen. */
    fun vibrationStatus(): VibrationDetector.Status = detector.status()

    /** The summary of everything since the previous call, and a fresh window. */
    fun take(): Contract.Summary {
        val s = Contract.Summary(
            pressureHpa = pressure.mean(),
            illuminanceLux = light.mean() ?: lastLux?.takeIf { luxOnChange },
            accelRms = accel.rms(),
            accelPeak = accel.peakOrNull(),
            gyroRms = gyro.rms(),
            gyroPeak = gyro.peakOrNull(),
            magneticUt = mag.mean(),
            // Heading only once the magnetometer has reported in this window too:
            // a stale azimuth from minutes ago is not a reading of this one.
            headingDeg = if (mag.n > 0) heading else null,
            batteryTempC = batteryTemperature(),
        )
        pressure.reset(); light.reset(); accel.reset(); gyro.reset(); mag.reset()
        return s
    }

    /**
     * From the sticky battery broadcast, which the system keeps current and which
     * can be read without registering a receiver. Reported in tenths of a degree.
     * This is the battery, not the room, and the panel says so.
     */
    private fun batteryTemperature(): Double? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10.0
    }

    private fun norm(v: FloatArray): Double =
        sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())

    private companion object {
        const val TAG = "psychron-sensors"
    }
}
