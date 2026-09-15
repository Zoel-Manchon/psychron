package dev.psychron.node

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A seismograph's trigger, on a phone lying still.
 *
 * The classic STA/LTA detector: a short-term average of ground-motion energy over
 * half a second, a long-term one over thirty, and an event whenever the short
 * stands well above the long. It adapts to its surroundings by construction — a
 * washing machine two rooms away raises the background it is compared with — and
 * it is what seismic networks still run first, before anything cleverer.
 *
 * The phone is not a seismometer, and the one thing that would swamp everything is
 * the phone itself being handled. So nothing is detected unless the gyroscope says
 * it has been still for the last three seconds, and an event in progress ends the
 * moment it is picked up.
 *
 * Pure: sample timestamps come in with the samples, so the tests drive it with
 * synthetic signals at exactly the rate and amplitude they intend.
 */
class VibrationDetector(
    private val staSeconds: Double = 0.5,
    private val ltaSeconds: Double = 30.0,
    private val triggerRatio: Double = 4.0,
    private val detriggerRatio: Double = 1.5,
    /** RMS below which a ratio is noise dividing noise: 0.02 m/s² is a footstep nearby. */
    private val minimumRms: Double = 0.02,
    private val stillRadPerS: Double = 0.05,
    private val stillSeconds: Double = 3.0,
    private val maxSeconds: Double = 60.0,
    /**
     * Strong shaking shorter than this is a glitch, not an event: 30 ms, six samples at
     * 200 Hz and three at the 100 Hz the S26 grants. A knock on the table rings for most
     * of a tenth of a second, and the 100 ms this used to be threw away every knock.
     */
    private val minimumSeconds: Double = 0.03,
    /** Seconds of the phone lying still before anything can fire: the background has to be measured first. */
    private val warmupSeconds: Double = 10.0,
) {
    /** A finished event. `startNanos` is on the clock the samples were stamped with. */
    data class Event(val startNanos: Long, val durationMs: Int, val pgaMs2: Double,
                     val staLta: Double, val freqHz: Double?)

    enum class Readiness {
        /** The background is still being measured: the first seconds lying still. */
        WARMING_UP,
        /** Paused: the gyroscope says the phone was handled in the last few seconds. */
        MOVING,
        LISTENING,
        /** Inside an event. */
        SHAKING,
    }

    /**
     * What the detector is doing, for the screen. Without it a detector that has
     * heard nothing and a detector that cannot hear anything look the same.
     */
    data class Status(val readiness: Readiness, val shortRms: Double, val backgroundRms: Double,
                      val ratio: Double, val triggerRatio: Double)

    private val gravity = DoubleArray(3)
    private var lastNanos = 0L
    private var sta = 0.0
    private var lta = 0.0
    /** Seconds the background has been learnt over: time lying still, outside events. */
    private var stillElapsed = 0.0

    private var movedNanos = Long.MIN_VALUE / 2
    private var wasStill = false

    // State of an event in progress.
    private var active = false
    private var startNanos = 0L
    private var strongNanos = 0L
    private var peak = 0.0
    private var ratioMax = 0.0
    private val axisEnergy = DoubleArray(3)
    private val axisPeak = DoubleArray(3)
    private val crossings = IntArray(3)
    private val lastSign = IntArray(3)
    private val firstCross = LongArray(3)
    private val lastCross = LongArray(3)

    /** Angular speed magnitude from the gyroscope, whenever it reports. */
    fun onRotation(nanos: Long, radPerS: Double) {
        if (radPerS > stillRadPerS) {
            movedNanos = nanos
            // Picked up mid-event: whatever was measured is now the hand, not the floor.
            active = false
        }
    }

    /** One accelerometer sample, gravity included, in m/s². */
    fun onAcceleration(nanos: Long, x: Double, y: Double, z: Double): Event? {
        if (lastNanos == 0L) {
            gravity[0] = x; gravity[1] = y; gravity[2] = z
            lastNanos = nanos
            return null
        }
        val dt = (nanos - lastNanos) / 1e9
        lastNanos = nanos
        if (dt <= 0.0 || dt > 1.0) return null            // out of order, or a gap: skip it

        // Gravity as a 0.5 Hz low-pass, per axis; what is left is motion.
        val alpha = 1 - exp(-2 * Math.PI * 0.5 * dt)
        val hp = DoubleArray(3)
        val raw = doubleArrayOf(x, y, z)
        for (i in 0..2) {
            gravity[i] += alpha * (raw[i] - gravity[i])
            hp[i] = raw[i] - gravity[i]
        }
        val energy = hp[0] * hp[0] + hp[1] * hp[1] + hp[2] * hp[2]
        val magnitude = sqrt(energy)

        val still = (nanos - movedNanos) / 1e9 >= stillSeconds
        // Put down: the short average still holds the hand's shaking, which against
        // the floor's background would fire the moment the phone came to rest.
        if (still && !wasStill) sta = lta
        wasStill = still

        // Recursive averages. The short one always runs, so the screen can show the
        // shaking in any state. The long one is the background and learns only from a
        // phone lying still outside an event: an event would raise its own background
        // and end itself early, and a phone being carried would teach it that walking
        // is quiet, deafening it for minutes after it was put down.
        sta += (energy - sta) * (dt / staSeconds).coerceAtMost(1.0)
        if (still && !active) {
            stillElapsed += dt
            // A running mean until a whole time constant has been seen: from a
            // standing start the background is right within seconds, instead of
            // creeping up on it for half a minute while every ratio reads high.
            lta += (energy - lta) * (dt / minOf(ltaSeconds, stillElapsed)).coerceAtMost(1.0)
        }

        val warmedUp = stillElapsed >= warmupSeconds
        val ratio = if (lta > 0) sta / lta else 0.0

        if (!active) {
            if (warmedUp && still && ratio >= triggerRatio && sqrt(sta) >= minimumRms) {
                active = true
                startNanos = nanos
                strongNanos = nanos
                peak = magnitude
                ratioMax = ratio
                axisEnergy.fill(0.0); axisPeak.fill(0.0); crossings.fill(0)
                for (i in 0..2) lastSign[i] = if (hp[i] >= 0) 1 else -1
            }
            return null
        }

        peak = maxOf(peak, magnitude)
        ratioMax = maxOf(ratioMax, ratio)
        // The shaking itself, as opposed to the ratio's decay after it: a recursive
        // average takes seconds to fall back, and a one-second shake would otherwise
        // be recorded as lasting five.
        if (magnitude >= maxOf(0.1 * peak, minimumRms)) strongNanos = nanos
        for (i in 0..2) {
            axisEnergy[i] += hp[i] * hp[i]
            axisPeak[i] = maxOf(axisPeak[i], abs(hp[i]))
            // A crossing counts only once the signal has swung past a quarter of its
            // own peak on the other side. Without that hysteresis the quiet tail of
            // an event, all sensor noise around zero, adds a crossing every sample
            // or two and doubles the frequency.
            val band = 0.25 * axisPeak[i]
            val sign = when {
                hp[i] > band -> 1
                hp[i] < -band -> -1
                else -> lastSign[i]
            }
            if (sign != lastSign[i]) {
                if (crossings[i] == 0) firstCross[i] = nanos
                lastCross[i] = nanos
                crossings[i]++
                lastSign[i] = sign
            }
        }

        val quietFor = (nanos - strongNanos) / 1e9
        if (ratio > detriggerRatio && quietFor < 1.0 && (nanos - startNanos) / 1e9 < maxSeconds) return null

        active = false
        val seconds = (strongNanos - startNanos) / 1e9
        if (seconds < minimumSeconds) return null          // a glitch, not an event
        val axis = axisEnergy.indices.maxByOrNull { axisEnergy[it] }!!
        // Two crossings a cycle, over the time between the first and the last, on the
        // axis carrying the most energy: the pitch of the shaking, not of its tail.
        val span = (lastCross[axis] - firstCross[axis]) / 1e9
        val freq = ((crossings[axis] - 1) / 2.0 / span).takeIf { crossings[axis] >= 4 && span > 0 }
        return Event(startNanos, (seconds * 1000).toInt(), peak, ratioMax, freq)
    }

    /** As of the latest sample. */
    fun status(): Status {
        val readiness = when {
            lastNanos == 0L -> Readiness.WARMING_UP
            active -> Readiness.SHAKING
            (lastNanos - movedNanos) / 1e9 < stillSeconds -> Readiness.MOVING
            stillElapsed < warmupSeconds -> Readiness.WARMING_UP
            else -> Readiness.LISTENING
        }
        return Status(readiness, sqrt(sta), sqrt(lta), if (lta > 0) sta / lta else 0.0, triggerRatio)
    }

    companion object {
        /** For the screen: a peak acceleration as a word. */
        fun describe(pga: Double): String = when {
            pga < 0.05 -> "faint"
            pga < 0.2 -> "light"
            pga < 1.0 -> "strong"
            else -> "violent"
        }
    }
}
