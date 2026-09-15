package dev.psychron.node

import java.math.BigDecimal
import java.math.RoundingMode
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
        // ── revision 2 ──────────────────────────────────────────────────────
        val lat: Double? = null,
        val lon: Double? = null,
        val locAccM: Double? = null,
        /** Above mean sea level, never the WGS84 ellipsoid. */
        val altMslM: Double? = null,
        val altAccM: Double? = null,
        val speedMs: Double? = null,
        val laeqDbfs: Double? = null,
        val lamaxDbfs: Double? = null,
        val l10Dbfs: Double? = null,
        val l90Dbfs: Double? = null,
        /** "lte" or "nr". */
        val cellRat: String? = null,
        val rsrpDbm: Double? = null,
        val rsrqDb: Double? = null,
        val sinrDb: Double? = null,
        val cellBand: Int? = null,
        /** "wifi", "cell", "ethernet" or "other". */
        val netVia: String? = null,
        val netVpn: Boolean? = null,
        val rttMs: Double? = null,
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
        /**
         * Milliseconds within that second, 0–999. Sent only alongside `ts`. Last and
         * defaulted, so every existing positional construction keeps its meaning.
         */
        val tsMillis: Int? = null,
    )

    /** A vibration, as the detector closed it. `ts` and `up` of its envelope mark the start. */
    data class Vibration(
        val durationMs: Int,
        val pgaMs2: Double,
        val staLta: Double,
        val freqHz: Double?,
    )

    /**
     * One field of one group. Required fields decide whether the group exists at
     * all; an optional one is written when valid and left out when not, without
     * taking the rest of the group with it.
     */
    private sealed class Field(val name: String, val optional: Boolean) {
        abstract fun valid(): Boolean
        abstract fun json(): String
    }

    private class Num(name: String, val value: Double?, val lo: Double, val hi: Double,
                      val decimals: Int, val hiInclusive: Boolean = true,
                      optional: Boolean = false) : Field(name, optional) {
        override fun valid(): Boolean {
            val v = value ?: return false
            if (!v.isFinite()) return false
            // Checked after rounding, because that is the value that goes on the wire:
            // a heading of 359.96 rounds to 360.0, which is out of range.
            val r = roundTo(v, decimals)
            return r >= lo && (if (hiInclusive) r <= hi else r < hi)
        }
        override fun json() = number(value!!, decimals)
    }

    private class Whole(name: String, val value: Int?, val lo: Int, val hi: Int,
                        optional: Boolean = false) : Field(name, optional) {
        override fun valid() = value != null && value in lo..hi
        override fun json() = value.toString()
    }

    private class Choice(name: String, val value: String?, val choices: Set<String>,
                         optional: Boolean = false) : Field(name, optional) {
        override fun valid() = value != null && value in choices
        override fun json() = string(value!!)
    }

    private class Flag(name: String, val value: Boolean?, optional: Boolean = false) : Field(name, optional) {
        override fun valid() = value != null
        override fun json() = value.toString()
    }

    /**
     * Groups in contract order, each with the range ingestion enforces. The same
     * numbers as backend/src/psychron/domain/samples.py; if one side changes and the
     * other does not, every window carrying that group is rejected, which is loud,
     * rather than stored wrong, which is not.
     */
    private fun groups(s: Summary): List<Pair<String, List<Field>>> = listOf(
        "baro" to listOf(Num("hpa", s.pressureHpa, 300.0, 1100.0, 2)),
        "light" to listOf(Num("lux", s.illuminanceLux, 0.0, 200000.0, 1)),
        "sound" to listOf(
            Num("rms_dbfs", s.soundRmsDbfs, -160.0, 0.0, 1),
            Num("peak_dbfs", s.soundPeakDbfs, -160.0, 0.0, 1),
        ),
        "accel" to listOf(
            Num("rms", s.accelRms, 0.0, 160.0, 3),
            Num("peak", s.accelPeak, 0.0, 160.0, 3),
        ),
        "gyro" to listOf(
            Num("rms", s.gyroRms, 0.0, 40.0, 3),
            Num("peak", s.gyroPeak, 0.0, 40.0, 3),
        ),
        "mag" to listOf(
            Num("ut", s.magneticUt, 0.0, 2000.0, 1),
            Num("heading", s.headingDeg?.let(::normaliseHeading), 0.0, 360.0, 1, hiInclusive = false),
        ),
        "batt" to listOf(Num("c", s.batteryTempC, -40.0, 100.0, 1)),
        "loc" to listOf(
            // Six decimals is 11 cm, finer than any phone fix and coarse enough not
            // to pretend otherwise.
            Num("lat", s.lat, -90.0, 90.0, 6),
            Num("lon", s.lon, -180.0, 180.0, 6),
            Num("acc", s.locAccM, 0.0, 10000.0, 1),
            Num("alt", s.altMslM, -500.0, 9000.0, 1, optional = true),
            // An accuracy with no altitude to be the accuracy of is dropped with it.
            Num("alt_acc", s.altAccM?.takeIf { s.altMslM != null }, 0.0, 10000.0, 1, optional = true),
            Num("spd", s.speedMs, 0.0, 350.0, 2, optional = true),
        ),
        "noise" to listOf(
            Num("laeq", s.laeqDbfs, -160.0, 0.0, 1),
            Num("lamax", s.lamaxDbfs, -160.0, 0.0, 1),
            Num("l10", s.l10Dbfs, -160.0, 0.0, 1, optional = true),
            Num("l90", s.l90Dbfs, -160.0, 0.0, 1, optional = true),
        ),
        "cell" to listOf(
            Choice("rat", s.cellRat, setOf("lte", "nr")),
            Num("rsrp", s.rsrpDbm, -156.0, -31.0, 0),
            Num("rsrq", s.rsrqDb, -43.0, 20.0, 0),
            Num("sinr", s.sinrDb, -23.0, 40.0, 0, optional = true),
            Whole("band", s.cellBand, 1, 1024, optional = true),
        ),
        "net" to listOf(
            Choice("via", s.netVia, setOf("wifi", "cell", "ethernet", "other")),
            Flag("vpn", s.netVpn),
            Num("rtt", s.rttMs, 0.0, 60000.0, 1, optional = true),
        ),
    )

    /**
     * The JSON message, or null when the window holds nothing sendable.
     *
     * A group is included only when every required field in it is present, finite
     * and in range. Otherwise it is omitted whole — the contract has exactly one way
     * to say "no data", and a partial or out-of-range group would get the entire
     * window rejected, losing the quantities that were fine along with the one that
     * was not.
     */
    fun encode(e: Envelope, s: Summary): String? {
        val body = StringBuilder()
        var included = 0
        for ((name, fields) in groups(s)) {
            if (!fields.filterNot { it.optional }.all { it.valid() }) continue
            body.append(",\"").append(name).append("\":{")
            fields.filter { it.valid() }.forEachIndexed { i, f ->
                if (i > 0) body.append(',')
                body.append('"').append(f.name).append("\":").append(f.json())
            }
            body.append('}')
            included++
        }
        if (included == 0) return null

        return buildString {
            envelope(e, withWindow = true)
            append(body)
            append('}')
        }
    }

    /** An event message, or null if what the detector measured is outside the contract. */
    fun encodeEvent(e: Envelope, v: Vibration): String? {
        val fields = listOf(
            Whole("dur", v.durationMs, 1, 600000),
            Num("pga", v.pgaMs2, 0.0, 160.0, 3),
            Num("ratio", v.staLta, 1.0, 1000.0, 1),
            Num("freq", v.freqHz, 0.0, 100.0, 1, optional = true),
        )
        if (!fields.filterNot { it.optional }.all { it.valid() }) return null
        return buildString {
            envelope(e, withWindow = false)
            append(",\"kind\":\"vibration\"")
            for (f in fields.filter { it.valid() }) append(",\"").append(f.name).append("\":").append(f.json())
            append('}')
        }
    }

    private fun StringBuilder.envelope(e: Envelope, withWindow: Boolean) {
        append("{\"v\":").append(VERSION)
        append(",\"dev\":").append(string(e.device))
        append(",\"fw\":").append(string(e.firmware))
        append(",\"boot\":").append(e.boot)
        append(",\"seq\":").append(e.seq)
        append(",\"ts\":").append(e.tsSeconds?.toString() ?: "null")
        // Milliseconds only with a clock to belong to: the contract rejects them
        // on their own, and an out-of-range value is dropped rather than sent.
        if (e.tsSeconds != null && e.tsMillis != null && e.tsMillis in 0..999) {
            append(",\"ms\":").append(e.tsMillis)
        }
        append(",\"up\":").append(e.uptimeMs)
        if (withWindow) append(",\"win\":").append(e.windowMs)
        append(",\"q\":").append(e.quality)
    }

    /**
     * The same message with the replayed bit set in its quality word.
     *
     * Messages wait in the outbox already serialised, and whether one was replayed
     * is only known when it finally goes out. `,"q":` occurs exactly once in
     * anything [encode] or [encodeEvent] produce — no group has a field called `q`,
     * and a quote inside a string is always escaped — so the envelope's value is
     * found by that key and nothing else is touched.
     */
    fun markReplayed(json: String): String {
        val key = ",\"q\":"
        val at = json.indexOf(key)
        require(at >= 0 && json.indexOf(key, at + key.length) < 0) { "not a message this encoder wrote" }
        val start = at + key.length
        var end = start
        while (end < json.length && json[end].isDigit()) end++
        val q = json.substring(start, end).toInt() or Q_REPLAYED
        return json.substring(0, start) + q + json.substring(end)
    }

    /** 360° is 0° written differently; the contract accepts only [0, 360). */
    fun normaliseHeading(deg: Double): Double {
        val r = ((deg % 360.0) + 360.0) % 360.0
        return if (roundTo(r, 1) >= 360.0) 0.0 else r
    }

    private fun number(v: Double, decimals: Int): String {
        val r = roundTo(v, decimals)
        // -0.0 serialises as "-0.0"; valid JSON, but a sign on a zero reads as a
        // measurement of something negative.
        val clean = if (r == 0.0) 0.0 else r
        if (decimals == 0 && abs(clean - round(clean)) < 1e-12) return clean.toLong().toString()
        val text = clean.toString()
        // Double.toString switches to exponent notation below 10^-3, which JSON
        // allows and nobody reading the message expects: 0.000012° of longitude
        // would arrive as 1.2E-5.
        return if ('E' in text) BigDecimal(clean).setScale(decimals, RoundingMode.HALF_UP).toPlainString() else text
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
