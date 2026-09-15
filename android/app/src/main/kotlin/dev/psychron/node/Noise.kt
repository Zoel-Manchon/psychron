package dev.psychron.node

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The A-weighting curve of IEC 61672 as a digital filter.
 *
 * The analogue definition is four poles — 20.6 Hz twice, 107.7 Hz, 737.9 Hz and
 * 12 194 Hz twice — over four zeros at the origin. Here it is three biquads made
 * by the bilinear transform, scaled to exactly 0 dB at 1 kHz. At 48 kHz the
 * transform's frequency warping stays under 1 dB below 8 kHz, which is where the
 * energy of a room, a street or a voice lives; above that the filter falls a
 * little faster than the standard, and that is the known error of this meter.
 *
 * Pure, so its response is tested against the standard's table on the JVM.
 */
class AWeighting(private val sampleRate: Double) {

    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        var z1 = 0.0
        var z2 = 0.0

        // Transposed direct form II: the form with the best behaviour when a
        // coefficient set this steep meets double precision.
        fun step(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        /** |H| at frequency f, straight from the coefficients. */
        fun magnitude(f: Double, fs: Double): Double {
            val w = 2 * PI * f / fs
            val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
            val nr = b0 + b1 * c1 + b2 * c2
            val ni = -(b1 * s1 + b2 * s2)
            val dr = 1 + a1 * c1 + a2 * c2
            val di = -(a1 * s1 + a2 * s2)
            return sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
        }
    }

    private val sections: List<Biquad>
    private val gain: Double

    init {
        val w1 = 2 * PI * 20.598997
        val w2 = 2 * PI * 107.65265
        val w3 = 2 * PI * 737.86223
        val w4 = 2 * PI * 12194.217
        sections = listOf(
            bilinear(1.0, 0.0, 0.0, 1.0, 2 * w1, w1 * w1),       // s² / (s + w1)²
            bilinear(1.0, 0.0, 0.0, 1.0, w2 + w3, w2 * w3),      // s² / ((s + w2)(s + w3))
            bilinear(0.0, 0.0, 1.0, 1.0, 2 * w4, w4 * w4),       // 1 / (s + w4)²
        )
        gain = 1.0 / sections.fold(1.0) { acc, s -> acc * s.magnitude(1000.0, sampleRate) }
    }

    /** (b2·s² + b1·s + b0) / (a2·s² + a1·s + a0) through s = 2·fs·(z − 1)/(z + 1). */
    private fun bilinear(b2: Double, b1: Double, b0: Double, a2: Double, a1: Double, a0: Double): Biquad {
        val c = 2 * sampleRate
        val cc = c * c
        val n0 = b2 * cc + b1 * c + b0
        val n1 = 2 * b0 - 2 * b2 * cc
        val n2 = b2 * cc - b1 * c + b0
        val d0 = a2 * cc + a1 * c + a0
        val d1 = 2 * a0 - 2 * a2 * cc
        val d2 = a2 * cc - a1 * c + a0
        return Biquad(n0 / d0, n1 / d0, n2 / d0, d1 / d0, d2 / d0)
    }

    fun step(x: Double): Double {
        var y = x * gain
        for (s in sections) y = s.step(y)
        return y
    }

    /** The filter's gain at [f] in dB, for tests and for anyone doubting the curve. */
    fun responseDb(f: Double): Double =
        20 * log10(gain * sections.fold(1.0) { acc, s -> acc * s.magnitude(f, sampleRate) })
}

/**
 * Time-weighted A levels: 125 ms blocks — the standard's "fast" — folded into the
 * figures environmental noise is reported in.
 *
 * LAeq is the energy average of a window, not the average of its decibels: two
 * seconds at −40 and two at −80 are −43 dB, because the loud half carries almost
 * all the energy. L10 and L90 are levels exceeded 10 % and 90 % of the trailing
 * minute — the intrusive peaks and the background under them.
 */
class NoiseLevels(sampleRate: Int, private val minuteSeconds: Int = 60) {
    data class Window(val laeq: Double, val lamax: Double, val l10: Double?, val l90: Double?)

    private val blockSamples = sampleRate / 8
    private val minuteBlocks = minuteSeconds * 8
    private var blockSum = 0.0
    private var blockCount = 0

    private var windowEnergy = 0.0
    private var windowBlocks = 0
    private var windowMax = Double.NEGATIVE_INFINITY

    private val minute = DoubleArray(minuteBlocks)
    private var minuteNext = 0
    private var minuteFilled = 0

    /** One A-weighted sample, in 16-bit PCM units. */
    fun add(weighted: Double) {
        blockSum += weighted * weighted
        if (++blockCount < blockSamples) return
        val meanSquare = blockSum / blockCount
        blockSum = 0.0
        blockCount = 0

        val level = levelDb(meanSquare)
        windowEnergy += meanSquare
        windowBlocks++
        windowMax = max(windowMax, level)
        minute[minuteNext] = level
        minuteNext = (minuteNext + 1) % minuteBlocks
        if (minuteFilled < minuteBlocks) minuteFilled++
    }

    /**
     * The levels since the last call. Null until a whole block has been heard.
     * The percentiles need half a minute before they mean anything, and are null
     * until then rather than a statistic of five seconds dressed as one of sixty.
     */
    fun drain(): Window? {
        if (windowBlocks == 0) return null
        val laeq = levelDb(windowEnergy / windowBlocks)
        val lamax = windowMax
        windowEnergy = 0.0; windowBlocks = 0; windowMax = Double.NEGATIVE_INFINITY

        if (minuteFilled < minuteBlocks / 2) return Window(laeq, lamax, null, null)
        val sorted = minute.copyOf(minuteFilled).also { it.sort() }
        // Nearest rank. L10 is exceeded 10 % of the time: the 90th percentile.
        val l10 = sorted[((sorted.size - 1) * 0.9).toInt()]
        val l90 = sorted[((sorted.size - 1) * 0.1).toInt()]
        return Window(laeq, lamax, l10, l90)
    }

    companion object {
        /** Mean square of 16-bit samples as dB relative to full scale, floored like SoundMeter. */
        fun levelDb(meanSquare: Double): Double =
            if (meanSquare <= 0.0) -160.0 else max(-160.0, 10 * log10(meanSquare / (32768.0 * 32768.0)))
    }
}
