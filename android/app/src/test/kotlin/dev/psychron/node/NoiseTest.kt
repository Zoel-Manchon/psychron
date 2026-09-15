package dev.psychron.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class NoiseTest {
    private val fs = 48000

    @Test
    fun `the filter follows the IEC 61672 table`() {
        val a = AWeighting(fs.toDouble())
        // Frequency to the standard's A-weighting, with this implementation's
        // tolerance: tight through the speech band, looser where the bilinear
        // transform is known to warp.
        val table = listOf(
            31.5 to -39.4, 63.0 to -26.2, 125.0 to -16.1, 250.0 to -8.6, 500.0 to -3.2,
            1000.0 to 0.0, 2000.0 to 1.2, 4000.0 to 1.0,
        )
        for ((f, expected) in table) assertEquals("at $f Hz", expected, a.responseDb(f), 0.3)
        assertEquals("at 8 kHz", -1.1, a.responseDb(8000.0), 1.0)
        assertTrue("above 10 kHz it falls", a.responseDb(12000.0) < a.responseDb(8000.0))
    }

    @Test
    fun `a 1 kHz sine keeps its level through the filter`() {
        val a = AWeighting(fs.toDouble())
        var sum = 0.0
        var n = 0
        for (i in 0 until fs) {
            val y = a.step(10000.0 * sin(2 * PI * 1000.0 * i / fs))
            if (i >= fs / 2) { sum += y * y; n++ }       // after the filter settles
        }
        assertEquals(10000.0 / sqrt(2.0), sqrt(sum / n), 10000.0 * 0.01)
    }

    @Test
    fun `a 50 Hz hum is mostly removed`() {
        val a = AWeighting(fs.toDouble())
        var sum = 0.0
        for (i in 0 until fs) {
            val y = a.step(10000.0 * sin(2 * PI * 50.0 * i / fs))
            if (i >= fs / 2) sum += y * y
        }
        val db = 20 * kotlin.math.log10(sqrt(sum / (fs / 2)) / (10000.0 / sqrt(2.0)))
        assertEquals(-30.2, db, 1.0)
    }

    private fun feed(levels: NoiseLevels, amplitude: Double, seconds: Double) {
        // A constant-amplitude square wave: its RMS equals its amplitude, so the
        // expected level is exactly 20·log10(amplitude / 32768).
        val n = (seconds * fs).toInt()
        for (i in 0 until n) levels.add(if (i % 48 < 24) amplitude else -amplitude)
    }

    @Test
    fun `LAeq is an energy average, not an average of decibels`() {
        val levels = NoiseLevels(fs)
        feed(levels, 32768.0 * 0.01, 1.0)           // −40 dBFS
        feed(levels, 32768.0 * 0.0001, 1.0)         // −80 dBFS
        val w = levels.drain()!!
        assertEquals(-43.0, w.laeq, 0.05)
        assertEquals(-40.0, w.lamax, 0.05)
    }

    @Test
    fun `percentiles wait for half a minute`() {
        val levels = NoiseLevels(fs)
        feed(levels, 3276.8, 10.0)
        assertNull(levels.drain()!!.l10)
        feed(levels, 3276.8, 25.0)
        assertNotNull(levels.drain()!!.l10)
    }

    @Test
    fun `L10 catches the intrusion and L90 the background`() {
        val levels = NoiseLevels(fs)
        // A minute of background at −60 dBFS with twelve seconds of −30 in it.
        feed(levels, 32.768, 24.0)
        feed(levels, 1036.2, 12.0)
        feed(levels, 32.768, 24.0)
        val w = levels.drain()!!
        assertEquals(-30.0, w.l10!!, 0.1)
        assertEquals(-60.0, w.l90!!, 0.1)
    }

    @Test
    fun `nothing heard is no window`() {
        assertNull(NoiseLevels(fs).drain())
    }
}
