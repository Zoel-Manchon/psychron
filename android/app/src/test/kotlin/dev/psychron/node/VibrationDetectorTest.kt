package dev.psychron.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class VibrationDetectorTest {

    /**
     * Drives the detector: quiet table noise, plus whatever [shake] adds on z. 200 Hz
     * unless told otherwise; the Galaxy S26 gives this app 100.
     */
    private fun run(d: VibrationDetector, seconds: Double, from: Long = 0L, rotation: Double = 0.0,
                    rate: Double = 200.0,
                    shake: (Double) -> Double = { 0.0 }): Pair<List<VibrationDetector.Event>, Long> {
        val stepNanos = (1e9 / rate).toLong()
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

    /** A knock on a table: ringing at [hz] that dies away with a 40 ms time constant. */
    private fun knock(hz: Double): (Double) -> Double = { s -> 1.5 * exp(-s / 0.04) * sin(2 * PI * hz * s) }

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

    @Test
    fun `a knock on the table is an event`() {
        // Strong for under a tenth of a second: shorter than the 100 ms the detector
        // once demanded, which is why a phone on a desk never reported one.
        val d = VibrationDetector()
        val (_, t) = run(d, 40.0)
        val (events, t2) = run(d, 0.3, from = t, shake = knock(60.0))
        val (after, _) = run(d, 3.0, from = t2)
        val e = (events + after).single()
        assertTrue("lasted ${e.durationMs} ms", e.durationMs in 30..300)
        assertTrue("peak ${e.pgaMs2}", e.pgaMs2 in 0.8..1.6)
    }

    @Test
    fun `a knock is an event at the 100 Hz this phone allows`() {
        val d = VibrationDetector()
        val (_, t) = run(d, 20.0, rate = 100.0)
        val (events, t2) = run(d, 0.3, from = t, rate = 100.0, shake = knock(30.0))
        val (after, _) = run(d, 3.0, from = t2, rate = 100.0)
        assertEquals(1, (events + after).size)
    }

    @Test
    fun `a single spike from the sensor is not an event`() {
        val d = VibrationDetector()
        val (_, t) = run(d, 40.0)
        val (events, t2) = run(d, 0.01, from = t) { 2.0 }
        val (after, _) = run(d, 3.0, from = t2)
        assertTrue((events + after).isEmpty())
    }

    @Test
    fun `carrying the phone does not deafen it once it is put down`() {
        val d = VibrationDetector()
        val (_, t) = run(d, 40.0)
        // Ten seconds in a hand: strong, slow shaking, and rotation the whole time.
        val (carried, t2) = run(d, 10.0, from = t, rotation = 0.8) { s -> 2.0 * sin(2 * PI * 3.0 * s) }
        // Put down. Three seconds of stillness arm it again, against the old background.
        val (settling, t3) = run(d, 4.0, from = t2)
        val status = d.status()
        assertEquals(VibrationDetector.Readiness.LISTENING, status.readiness)
        assertTrue("background ${status.backgroundRms}", status.backgroundRms < 0.01)

        val (knocked, t4) = run(d, 0.3, from = t3, shake = knock(60.0))
        val (after, _) = run(d, 3.0, from = t4)
        assertTrue((carried + settling).isEmpty())
        assertEquals(1, (knocked + after).size)
    }

    @Test
    fun `the status says why nothing is being detected`() {
        val d = VibrationDetector()
        assertEquals(VibrationDetector.Readiness.WARMING_UP, d.status().readiness)

        val (_, t) = run(d, 40.0)
        val quiet = d.status()
        assertEquals(VibrationDetector.Readiness.LISTENING, quiet.readiness)
        // Table noise of 3 mm/s² on each of three axes.
        assertEquals(0.003 * sqrt(3.0), quiet.backgroundRms, 0.002)
        assertEquals(1.0, quiet.ratio, 0.8)

        run(d, 1.0, from = t, rotation = 0.8)
        assertEquals(VibrationDetector.Readiness.MOVING, d.status().readiness)
    }
}
