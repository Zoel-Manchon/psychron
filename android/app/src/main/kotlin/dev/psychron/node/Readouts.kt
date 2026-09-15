package dev.psychron.node

import java.util.Locale
import kotlin.math.log10

/**
 * What each tile on the node's screen says, decided apart from the views that say it.
 *
 * The words carry as much as the numbers. "Station, not sea level" and "paused while
 * the phone is handled" are the difference between a reading and a misreading, and
 * a tile that shows a dash without saying why reads as a broken sensor. Pure, so each
 * sentence is checked on the JVM rather than by looking at a phone.
 */
object Readouts {

    /** The number (null shows a dash), the note beneath it, and a meter's fill when the tile has one. */
    data class Readout(val value: String?, val note: String, val fraction: Float? = null)

    /** A fix younger than this is the one the window sends; older, the screen says its age. */
    private const val FRESH_MS = 10_000L

    fun pressure(m: Contract.Summary?) = Readout(fmt(m?.pressureHpa, 1), "station, not sea level")

    /** Logarithmic: from a dark room to noon sun is six orders of magnitude. */
    fun light(m: Contract.Summary?) = Readout(
        fmt(m?.illuminanceLux, 0), "log · 0.1–100 000 lx",
        m?.illuminanceLux?.let { ((log10(it.coerceAtLeast(0.1)) + 1) / 6).toFloat() },
    )

    fun noise(m: Contract.Summary?, running: Boolean, microphone: Boolean): Readout {
        val level = m?.laeqDbfs ?: m?.soundRmsDbfs
        val note = when {
            m?.l90Dbfs != null -> "L10 ${fmt(m.l10Dbfs, 1)} · L90 ${fmt(m.l90Dbfs, 1)} over the last minute · relative, not dB SPL"
            m?.laeqDbfs != null -> "LAeq · L10 and L90 after 30 s of listening"
            running && !microphone -> "microphone not granted"
            else -> "relative to full scale, not dB SPL"
        }
        return Readout(fmt(level, 1), note, level?.let { ((it + 90) / 90).toFloat() })
    }

    /** The same rule the web panel uses, so the two never disagree about a phone lying still. */
    fun moving(m: Contract.Summary?) = (m?.accelRms ?: 0.0) > 0.25 || (m?.gyroRms ?: 0.0) > 0.2

    fun motion(m: Contract.Summary?) = Readout(
        fmt(m?.accelRms, 2),
        if (m?.gyroRms == null) "gravity removed"
        else "rot ${fmt(m.gyroRms, 3)} rad/s · ${if (moving(m)) "moving" else "still"}",
    )

    fun headingDegrees(m: Contract.Summary?): Double? = m?.headingDeg?.let(Contract::normaliseHeading)

    fun heading(m: Contract.Summary?) = Readout(
        fmt(headingDegrees(m), 0),
        if (m?.magneticUt != null) "magnetic · ${fmt(m.magneticUt, 1)} µT" else "magnetic north",
    )

    fun battery(m: Contract.Summary?) = Readout(fmt(m?.batteryTempC, 1), "the phone, not the room")

    /**
     * The latest position however old, with its age. Indoors the phone can go half a
     * minute between fixes; a dash in between would say it has no idea where it is.
     */
    fun altitude(s: NodeBus.State): Readout {
        val f = s.fix ?: return Readout(null, when {
            s.running && s.sensors["location"] == false -> "location not granted"
            s.running && !s.locationEnabled -> "location is off in the phone's settings"
            else -> "no fix yet"
        })
        val parts = mutableListOf<String>()
        if (f.altMslM == null) parts += "no altitude in this fix"
        parts += "±${fmt(f.altAccM ?: f.accM, 0)} m"
        f.speedMs?.let { parts += "${fmt(it * 3.6, 1)} km/h" }
        parts += if (f.ageMs <= FRESH_MS) "above sea level" else "fix ${ago(f.ageMs)} old"
        return Readout(fmt(f.altMslM, 0), parts.joinToString(" · "))
    }

    /** RSRP in the bands operators and field tools use; the web panel colours its track by the same. */
    fun signalWord(rsrpDbm: Double): String = when {
        rsrpDbm >= -80 -> "excellent"
        rsrpDbm >= -90 -> "good"
        rsrpDbm >= -100 -> "fair"
        else -> "poor"
    }

    fun cell(m: Contract.Summary?): Readout {
        val rsrp = m?.rsrpDbm
        if (m?.cellRat == null || rsrp == null) return Readout(fmt(rsrp, 0), "no serving cell")
        val nr = m.cellRat == "nr"
        val note = buildString {
            append(if (nr) "5G NR" else "LTE")
            m.cellBand?.let { append(if (nr) " n$it" else " B$it") }
            append(" · ").append(signalWord(rsrp))
            m.sinrDb?.let { append(" · SINR ${fmt(it, 0)}") }
            m.rttMs?.let { append(" · broker ${fmt(it, 0)} ms") }
        }
        return Readout(fmt(rsrp, 0), note)
    }

    /**
     * The shaking right now, with the detector's state and its history. The value is
     * the short-term RMS the trigger compares, and the meter how close it stands to
     * firing, so a knock on the table visibly moves both even when it is too small
     * to become an event.
     */
    fun vibration(s: NodeBus.State, nowElapsed: Long): Readout {
        val st = s.vibration
        val state = if (s.running && s.sensors["vibration"] == false) {
            // Otherwise it would say "measuring the background" for ever.
            "no raw accelerometer · the phone refused every rate"
        } else if (st == null) {
            if (s.running) "starting" else "node stopped"
        } else when (st.readiness) {
            VibrationDetector.Readiness.WARMING_UP -> "measuring the background · 10 s lying still"
            VibrationDetector.Readiness.MOVING -> "paused while the phone is handled"
            VibrationDetector.Readiness.SHAKING -> "shaking now · ×${fmt(st.ratio, 0)} the background"
            VibrationDetector.Readiness.LISTENING ->
                "listening · ×${fmt(st.ratio, 1)} the background, fires at ×${fmt(st.triggerRatio, 0)}"
        }
        val last = s.lastEvent
        val history = if (last == null) "no events yet · knock on the table it lies on"
        else "${s.events} event${if (s.events == 1) "" else "s"} · last ${fmt(last.pgaMs2, 2)} m/s², " +
            "${VibrationDetector.describe(last.pgaMs2)}, ${ago(nowElapsed - s.lastEventElapsed)} ago"
        return Readout(fmt(st?.shortRms, 3), "$state\n$history", st?.let { (it.ratio / it.triggerRatio).toFloat() })
    }

    /** What is waiting on disk, and proof the rest arrived: an empty outbox says nothing on its own. */
    fun outbox(s: NodeBus.State, nowWallMs: Long, nowElapsed: Long): Readout {
        val oldest = s.oldestQueuedMs?.let { ago((nowWallMs - it).coerceAtLeast(0)) }
        val state = when {
            s.queued > 0 && s.batching -> "oldest ${oldest ?: "?"} · leaves in the next 30 s batch"
            s.queued > 0 -> "oldest ${oldest ?: "?"} · ${if (s.link.startsWith("connected")) "sending" else "waiting for the broker"}"
            s.lastAckElapsed == 0L -> "nothing acknowledged yet"
            else -> "all acknowledged · last ${ago(nowElapsed - s.lastAckElapsed)} ago"
        }
        val totals = buildString {
            append("${s.sent} sent")
            if (s.replayed > 0) append(" · ${s.replayed} replayed")
            if (s.dropped > 0) append(" · ${s.dropped} dropped")
            append(" · kept across restarts")
        }
        return Readout(s.queued.toString(), "$state\n$totals")
    }

    /** A duration in the coarsest unit that still reads exactly. */
    fun ago(ms: Long): String = when {
        ms < 90_000 -> "${ms / 1000} s"
        ms < 90 * 60_000 -> "${ms / 60_000} min"
        else -> String.format(Locale.ROOT, "%.1f h", ms / 3_600_000.0)
    }

    // Locale.ROOT: a Spanish phone would otherwise write 955,95 on a screen whose
    // every other number, and the JSON beneath it, uses a decimal point.
    fun fmt(v: Double?, decimals: Int): String? =
        v?.let { String.format(Locale.ROOT, "%.${decimals}f", it) }
}
