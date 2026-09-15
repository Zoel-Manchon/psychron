package dev.psychron.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class VibrationDetectorTest {
    private val rate = 200.0
    private val stepNanos = (1e9 / rate).toLong()

    /** Drives the detector: quiet table noise, plus whatever [shake] adds on z. */
    private fun run(d: VibrationDetector, seconds: Double, from: Long = 0L, rotation: Double = 0.0,
                    shake: (Double) -> Double = { 0.0 }): Pair<List<VibrationDetector.Event>, Long> {
        val rng = Random(7)
        val events = mutableListOf<VibrationDetector.Event>()
        var t = from
        val n = (seconds * rate).toInt()
        for (i in 0 until n) {
            t += stepNanos
            val s = i / rate
            if (i % 4 == 0) d.onRotation(t, rotation)
            d.onAcceleration(t, 0.003 * rng.nextGaussian(), 0.003 * rng.nextGaussian(),
                             9.81 + 0.003 * rng.nextGaussian() + shake(s))?.let(events::add)
        }
        return events to t
    }

    @Test
    fun `a quiet table raises nothing`() {
        val (events, _) = run(VibrationDetector(), 120.0)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a one-second 20 Hz shake is one event with its size and pitch`() {
        val d = VibrationDetector()
        val (_, t) = run(d, 40.0)
        val (events, t2) = run(d, 1.0, from = t) { s -> 0.5 * sin(2 * PI * 20.0 * s) }
        val (after, _) = run(d, 5.0, from = t2)
        val all = events + after
        assertEquals(1, all.size)
        val e = all.single()
        assertEquals(0.5, e.pgaMs2, 0.1)
        assertNotNull(e.freqHz)
        assertEquals(20.0, e.freqHz!!, 2.0)
        assertTrue("lasted ${e.durationMs} ms", e.durationMs in 800..3000)
        assertTrue(e.staLta >= 4.0)
    }

    @Test
    fun `a phone being handled is not an earthquake`() {
        val d = VibrationDetector()
        val (_, t) = run(d, 40.0)
        val (events, _) = run(d, 3.0, from = t, rotation = 0.8) { s -> 0.8 * sin(2 * PI * 5.0 * s) }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `nothing is detected before the background has been measured`() {
        val (events, _) = run(VibrationDetector(), 8.0) { s -> if (s > 5) 0.5 * sin(2 * PI * 15.0 * s) else 0.0 }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a shake smaller than the floor is ignored however quiet the room`() {
        val d = VibrationDetector()
        val (_, t) = run(d, 40.0)
        val (events, t2) = run(d, 1.0, from = t) { s -> 0.012 * sin(2 * PI * 20.0 * s) }
        val (after, _) = run(d, 5.0, from = t2)
        assertTrue((events + after).isEmpty())
    }
}
