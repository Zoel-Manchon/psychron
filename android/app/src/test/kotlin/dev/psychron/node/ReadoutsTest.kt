package dev.psychron.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadoutsTest {
    private val running = NodeBus.State(running = true, link = "connected · mTLS", sensors = mapOf("location" to true))

    private fun fix(ageMs: Long, alt: Double? = 557.4, altAcc: Double? = 12.0, speed: Double? = null) =
        LocationTracker.Fix(lat = 40.0, lon = -3.0, accM = 8.0, altMslM = alt, altAccM = altAcc,
                            speedMs = speed, ageMs = ageMs)

    @Test
    fun `a fresh fix shows its altitude above sea level`() {
        val r = Readouts.altitude(running.copy(fix = fix(ageMs = 1_500)))
        assertEquals("557", r.value)
        assertEquals("±12 m · above sea level", r.note)
    }

    @Test
    fun `an old fix keeps its altitude on screen and says how old it is`() {
        // Indoors the fused provider can go half a minute between fixes; the window
        // leaves it out, the screen does not blank it.
        val r = Readouts.altitude(running.copy(fix = fix(ageMs = 25_000, altAcc = null, speed = 1.0)))
        assertEquals("557", r.value)
        assertEquals("±8 m · 3.6 km/h · fix 25 s old", r.note)
    }

    @Test
    fun `no fix says why`() {
        assertEquals("location is off in the phone's settings",
                     Readouts.altitude(running.copy(locationEnabled = false)).note)
        assertEquals("location not granted",
                     Readouts.altitude(running.copy(sensors = mapOf("location" to false))).note)
        assertEquals("no fix yet", Readouts.altitude(running).note)
        assertNull(Readouts.altitude(running).value)
    }

    @Test
    fun `a fix without altitude is not shown as sea level`() {
        val r = Readouts.altitude(running.copy(fix = fix(ageMs = 0, alt = null, altAcc = null)))
        assertNull(r.value)
        assertTrue(r.note, r.note.startsWith("no altitude in this fix"))
    }

    @Test
    fun `the vibration tile shows the detector at work before any event`() {
        val listening = VibrationDetector.Status(VibrationDetector.Readiness.LISTENING,
                                                 shortRms = 0.0061, backgroundRms = 0.0052, ratio = 1.4, triggerRatio = 4.0)
        val r = Readouts.vibration(running.copy(vibration = listening), nowElapsed = 0)
        assertEquals("0.006", r.value)
        assertEquals(0.35f, r.fraction!!, 1e-6f)
        assertEquals("listening · ×1.4 the background, fires at ×4\nno events yet · knock on the table it lies on", r.note)
    }

    @Test
    fun `the vibration tile says when it is paused, and what it last heard`() {
        val moving = VibrationDetector.Status(VibrationDetector.Readiness.MOVING, 0.2, 0.01, 400.0, 4.0)
        val s = running.copy(vibration = moving, events = 3, lastEvent = Contract.Vibration(90, 0.42, 38.0, 60.0),
                             lastEventElapsed = 1_000)
        val r = Readouts.vibration(s, nowElapsed = 13_000)
        assertEquals("paused while the phone is handled\n3 events · last 0.42 m/s², strong, 12 s ago", r.note)
    }

    @Test
    fun `a detector with no sensor behind it does not claim to be listening`() {
        val warming = VibrationDetector.Status(VibrationDetector.Readiness.WARMING_UP, 0.0, 0.0, 0.0, 4.0)
        val s = running.copy(sensors = mapOf("vibration" to false), vibration = warming)
        assertTrue(Readouts.vibration(s, 0).note.startsWith("no raw accelerometer"))
    }

    @Test
    fun `an empty outbox proves what arrived`() {
        val s = running.copy(sent = 1234, queued = 0, lastAckElapsed = 9_000)
        assertEquals(Readouts.Readout("0", "all acknowledged · last 1 s ago\n1234 sent · kept across restarts"),
                     Readouts.outbox(s, nowWallMs = 0, nowElapsed = 10_000))
    }

    @Test
    fun `a backlog says how old it is and what it waits for`() {
        val s = running.copy(sent = 10, replayed = 4, queued = 12, oldestQueuedMs = 1_000_000, batching = true)
        val r = Readouts.outbox(s, nowWallMs = 1_025_000, nowElapsed = 0)
        assertEquals("12", r.value)
        assertEquals("oldest 25 s · leaves in the next 30 s batch\n10 sent · 4 replayed · kept across restarts", r.note)

        val offline = s.copy(batching = false, link = "reconnecting · SocketException")
        assertTrue(Readouts.outbox(offline, 1_025_000, 0).note.startsWith("oldest 25 s · waiting for the broker"))
    }

    @Test
    fun `the cell tile names its band and how good the signal is`() {
        val m = Contract.Summary(cellRat = "lte", rsrpDbm = -111.0, sinrDb = -1.0, cellBand = 7, rttMs = 31.0)
        assertEquals(Readouts.Readout("-111", "LTE B7 · poor · SINR -1 · broker 31 ms"), Readouts.cell(m))
        assertEquals("5G NR n78 · good", Readouts.cell(Contract.Summary(cellRat = "nr", rsrpDbm = -85.0, cellBand = 78)).note)
        assertEquals("no serving cell", Readouts.cell(null).note)
    }

    @Test
    fun `numbers use a decimal point whatever the phone's language`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("es-ES"))
            assertEquals("958.3", Readouts.pressure(Contract.Summary(pressureHpa = 958.29)).value)
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `durations read in the coarsest exact unit`() {
        assertEquals("0 s", Readouts.ago(400))
        assertEquals("89 s", Readouts.ago(89_999))
        assertEquals("2 min", Readouts.ago(150_000))
        assertEquals("1.5 h", Readouts.ago(5_400_000))
    }
}
