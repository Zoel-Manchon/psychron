package dev.psychron.node

import kotlin.math.abs
import kotlin.math.round

/**
 * Contract v2 on the wire. See docs/CONTRACT-v2.md.
 *
 * Pure: no Android, no clock, no I/O. That is what lets it be tested on the JVM
 * against the same ranges ingestion enforces, which matters because this is the
 * one piece of the app whose mistakes cannot be fixed later — a month of windows
 * serialised wrongly is a month the backend rejected.
 */
object Contract {
    const val VERSION = 2

    const val Q_REPLAYED = 0x02

    /** What a window measured. Null means no sample of that quantity. */
    data class Summary(
        val pressureHpa: Double? = null,
        val illuminanceLux: Double? = null,
        val soundRmsDbfs: Double? = null,
        val soundPeakDbfs: Double? = null,
        val accelRms: Double? = null,
        val accelPeak: Double? = null,
        val gyroRms: Double? = null,
        val gyroPeak: Double? = null,
        val magneticUt: Double? = null,
        val headingDeg: Double? = null,
        val batteryTempC: Double? = null,
    )

    data class Envelope(
        val device: String,
        val firmware: String,
        val boot: Long,
        val seq: Long,
        /** Epoch seconds, or null if the clock could not be trusted. */
        val tsSeconds: Long?,
        val uptimeMs: Long,
        val windowMs: Int,
        val quality: Int,
    )

    private class Field(val name: String, val value: Double?, val lo: Double, val hi: Double,
                        val hiInclusive: Boolean, val decimals: Int)

    /**
     * Groups in contract order, each with the range ingestion enforces. The same
     * numbers as backend/src/psychron/domain/samples.py; if one side changes and the
     * other does not, every window carrying that group is rejected, which is loud,
     * rather than stored wrong, which is not.
     */
    private fun groups(s: Summary): List<Pair<String, List<Field>>> = listOf(
        "baro" to listOf(Field("hpa", s.pressureHpa, 300.0, 1100.0, true, 2)),
        "light" to listOf(Field("lux", s.illuminanceLux, 0.0, 200000.0, true, 1)),
        "sound" to listOf(
            Field("rms_dbfs", s.soundRmsDbfs, -160.0, 0.0, true, 1),
            Field("peak_dbfs", s.soundPeakDbfs, -160.0, 0.0, true, 1),
        ),
        "accel" to listOf(
            Field("rms", s.accelRms, 0.0, 160.0, true, 3),
            Field("peak", s.accelPeak, 0.0, 160.0, true, 3),
        ),
        "gyro" to listOf(
            Field("rms", s.gyroRms, 0.0, 40.0, true, 3),
            Field("peak", s.gyroPeak, 0.0, 40.0, true, 3),
        ),
        "mag" to listOf(
            Field("ut", s.magneticUt, 0.0, 2000.0, true, 1),
            Field("heading", s.headingDeg?.let(::normaliseHeading), 0.0, 360.0, false, 1),
        ),
        "batt" to listOf(Field("c", s.batteryTempC, -40.0, 100.0, true, 1)),
    )

    /**
     * The JSON message, or null when the window holds nothing sendable.
     *
     * A group is included only when every field in it is present, finite and in
     * range. Otherwise it is omitted whole — the contract has exactly one way to say
     * "no data", and a partial or out-of-range group would get the entire window
     * rejected, losing the quantities that were fine along with the one that was not.
     */
    fun encode(e: Envelope, s: Summary): String? {
        val body = StringBuilder()
        var included = 0
        for ((name, fields) in groups(s)) {
            if (!fields.all { valid(it) }) continue
            body.append(",\"").append(name).append("\":{")
            fields.forEachIndexed { i, f ->
                if (i > 0) body.append(',')
                body.append('"').append(f.name).append("\":").append(number(f.value!!, f.decimals, f))
            }
            body.append('}')
            included++
        }
        if (included == 0) return null

        return buildString {
            append("{\"v\":").append(VERSION)
            append(",\"dev\":").append(string(e.device))
            append(",\"fw\":").append(string(e.firmware))
            append(",\"boot\":").append(e.boot)
            append(",\"seq\":").append(e.seq)
            append(",\"ts\":").append(e.tsSeconds?.toString() ?: "null")
            append(",\"up\":").append(e.uptimeMs)
            append(",\"win\":").append(e.windowMs)
            append(",\"q\":").append(e.quality)
            append(body)
            append('}')
        }
    }

    /** 360° is 0° written differently; the contract accepts only [0, 360). */
    fun normaliseHeading(deg: Double): Double {
        val r = ((deg % 360.0) + 360.0) % 360.0
        return if (roundTo(r, 1) >= 360.0) 0.0 else r
    }

    private fun valid(f: Field): Boolean {
        val v = f.value ?: return false
        if (!v.isFinite()) return false
        // Checked after rounding, because that is the value that goes on the wire:
        // a heading of 359.96 rounds to 360.0, which is out of range.
        val r = roundTo(v, f.decimals)
        return r >= f.lo && (if (f.hiInclusive) r <= f.hi else r < f.hi)
    }

    private fun number(v: Double, decimals: Int, f: Field): String {
        val r = roundTo(v, decimals)
        // -0.0 serialises as "-0.0"; valid JSON, but a sign on a zero reads as a
        // measurement of something negative.
        val clean = if (r == 0.0) 0.0 else r
        return if (abs(clean - round(clean)) < 1e-12 && f.decimals == 0) clean.toLong().toString()
        else clean.toString()
    }

    private fun roundTo(v: Double, decimals: Int): Double {
        var scale = 1.0
        repeat(decimals) { scale *= 10.0 }
        return round(v * scale) / scale
    }

    private fun string(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c.code < 0x20 -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }
}
