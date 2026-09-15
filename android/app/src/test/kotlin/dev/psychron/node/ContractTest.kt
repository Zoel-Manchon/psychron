package dev.psychron.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractTest {
    private val env = Contract.Envelope(
        device = "phone-01", firmware = "0.1.0", boot = 3141592653L, seq = 42,
        tsSeconds = 1789012345L, uptimeMs = 84000, windowMs = 2000, quality = 0,
    )

    private val full = Contract.Summary(
        pressureHpa = 1013.42, illuminanceLux = 312.0,
        soundRmsDbfs = -48.2, soundPeakDbfs = -31.0,
        accelRms = 0.042, accelPeak = 0.31, gyroRms = 0.011, gyroPeak = 0.09,
        magneticUt = 41.2, headingDeg = 212.5, batteryTempC = 31.4,
    )

    @Test
    fun `a full window matches the contract example field for field`() {
        val json = Contract.encode(env, full)!!
        assertEquals(
            "{\"v\":2,\"dev\":\"phone-01\",\"fw\":\"0.1.0\",\"boot\":3141592653,\"seq\":42," +
                "\"ts\":1789012345,\"up\":84000,\"win\":2000,\"q\":0," +
                "\"baro\":{\"hpa\":1013.42},\"light\":{\"lux\":312.0}," +
                "\"sound\":{\"rms_dbfs\":-48.2,\"peak_dbfs\":-31.0}," +
                "\"accel\":{\"rms\":0.042,\"peak\":0.31},\"gyro\":{\"rms\":0.011,\"peak\":0.09}," +
                "\"mag\":{\"ut\":41.2,\"heading\":212.5},\"batt\":{\"c\":31.4}}",
            json,
        )
    }

    @Test
    fun `a boot id above 2^31 stays unsigned`() {
        // Drawn as a random uint32; a signed Int would put a minus sign on half of them.
        assertTrue(Contract.encode(env, full)!!.contains("\"boot\":3141592653"))
    }

    @Test
    fun `milliseconds follow the second they belong to`() {
        val json = Contract.encode(env.copy(tsMillis = 437), full)!!
        assertTrue(json.contains("\"ts\":1789012345,\"ms\":437,\"up\""))
    }

    @Test
    fun `milliseconds are never sent without a clock`() {
        val json = Contract.encode(env.copy(tsSeconds = null, tsMillis = 437), full)!!
        assertFalse(json.contains("\"ms\""))
    }

    @Test
    fun `an out-of-range millisecond is dropped, not sent`() {
        assertFalse(Contract.encode(env.copy(tsMillis = 1000), full)!!.contains("\"ms\""))
        assertTrue(Contract.encode(env.copy(tsMillis = 0), full)!!.contains("\"ms\":0"))
    }

    @Test
    fun `an unknown clock is written as null, not zero`() {
        val json = Contract.encode(env.copy(tsSeconds = null), full)!!
        assertTrue(json.contains("\"ts\":null"))
    }

    @Test
    fun `a missing sensor omits its group entirely`() {
        val json = Contract.encode(env, full.copy(pressureHpa = null))!!
        assertFalse(json.contains("baro"))
        assertTrue(json.contains("\"light\""))
    }

    @Test
    fun `a half-filled group is omitted rather than sent with a null`() {
        val json = Contract.encode(env, full.copy(soundPeakDbfs = null))!!
        assertFalse(json.contains("sound"))
        assertFalse(json.contains("null,"))
    }

    @Test
    fun `an out-of-range value drops its group, not the window`() {
        val json = Contract.encode(env, full.copy(pressureHpa = 50.0))!!
        assertFalse(json.contains("baro"))
        assertTrue(json.contains("batt"))
    }

    @Test
    fun `non-finite values are never sent`() {
        val json = Contract.encode(env, full.copy(soundRmsDbfs = Double.NEGATIVE_INFINITY))!!
        assertFalse(json.contains("sound"))
        assertFalse(json.contains("Infinity"))
    }

    @Test
    fun `a window with nothing valid is not a message`() {
        assertNull(Contract.encode(env, Contract.Summary()))
    }

    @Test
    fun `heading wraps into zero to three hundred sixty`() {
        assertEquals(0.0, Contract.normaliseHeading(360.0), 0.0)
        assertEquals(10.0, Contract.normaliseHeading(-350.0), 1e-9)
        // Rounds to 360.0 on the wire, which the contract rejects: sent as 0.0.
        assertEquals(0.0, Contract.normaliseHeading(359.97), 0.0)
        assertTrue(Contract.encode(env, full.copy(headingDeg = 359.97))!!.contains("\"heading\":0.0"))
    }

    @Test
    fun `negative zero is written without a sign`() {
        val json = Contract.encode(env, full.copy(accelRms = -0.0001, accelPeak = 0.0))!!
        assertTrue(json.contains("\"accel\":{\"rms\":0.0,"))
    }

    @Test
    fun `strings are escaped`() {
        val json = Contract.encode(env.copy(firmware = "0.1\"x"), full)!!
        assertTrue(json.contains("\"fw\":\"0.1\\\"x\""))
    }

    // ── revision 2 ──────────────────────────────────────────────────────────

    private val revision = Contract.Summary(
        lat = 40.416775, lon = -3.70379, locAccM = 4.5, altMslM = 657.2, altAccM = 3.1, speedMs = 1.2,
        laeqDbfs = -52.4, lamaxDbfs = -41.0, l10Dbfs = -47.9, l90Dbfs = -58.3,
        cellRat = "nr", rsrpDbm = -97.0, rsrqDb = -11.0, sinrDb = 8.0, cellBand = 78,
        netVia = "cell", netVpn = true, rttMs = 84.0,
    )

    @Test
    fun `the revision groups serialise field by field`() {
        val json = Contract.encode(env, revision)!!
        assertTrue(json, json.endsWith(
            "\"loc\":{\"lat\":40.416775,\"lon\":-3.70379,\"acc\":4.5,\"alt\":657.2,\"alt_acc\":3.1,\"spd\":1.2}," +
                "\"noise\":{\"laeq\":-52.4,\"lamax\":-41.0,\"l10\":-47.9,\"l90\":-58.3}," +
                "\"cell\":{\"rat\":\"nr\",\"rsrp\":-97,\"rsrq\":-11,\"sinr\":8,\"band\":78}," +
                "\"net\":{\"via\":\"cell\",\"vpn\":true,\"rtt\":84.0}}"))
    }

    @Test
    fun `an optional field that is missing leaves the rest of its group`() {
        val json = Contract.encode(env, revision.copy(sinrDb = null, cellBand = null, l10Dbfs = null))!!
        assertTrue(json.contains("\"cell\":{\"rat\":\"nr\",\"rsrp\":-97,\"rsrq\":-11}"))
        assertTrue(json.contains("\"noise\":{\"laeq\":-52.4,\"lamax\":-41.0,\"l90\":-58.3}"))
    }

    @Test
    fun `an optional field out of range is dropped alone`() {
        val json = Contract.encode(env, revision.copy(sinrDb = 99.0))!!
        assertTrue(json.contains("\"cell\":{\"rat\":\"nr\",\"rsrp\":-97,\"rsrq\":-11,\"band\":78}"))
    }

    @Test
    fun `a missing required field still drops the whole group`() {
        val json = Contract.encode(env, revision.copy(rsrqDb = null))!!
        assertFalse(json.contains("\"cell\":{"))
        assertTrue(json.contains("\"net\""))
    }

    @Test
    fun `an altitude accuracy never travels without its altitude`() {
        val json = Contract.encode(env, revision.copy(altMslM = null))!!
        assertTrue(json.contains("\"loc\":{\"lat\":40.416775,\"lon\":-3.70379,\"acc\":4.5,\"spd\":1.2}"))
    }

    @Test
    fun `an unknown radio technology drops the cell group`() {
        assertFalse(Contract.encode(env, revision.copy(cellRat = "umts"))!!.contains("\"cell\":{"))
    }

    @Test
    fun `tiny coordinates are not written in exponent notation`() {
        val json = Contract.encode(env, revision.copy(lon = 0.000012))!!
        assertTrue(json, json.contains("\"lon\":0.000012,"))
    }

    @Test
    fun `a vibration event matches the contract example`() {
        val json = Contract.encodeEvent(
            env.copy(boot = 2718281828L, seq = 7, tsSeconds = 1789413255L, tsMillis = 250, uptimeMs = 3600000,
                     firmware = "android-0.5.0"),
            Contract.Vibration(durationMs = 1840, pgaMs2 = 0.412, staLta = 6.3, freqHz = 11.5),
        )
        assertEquals(
            "{\"v\":2,\"dev\":\"phone-01\",\"fw\":\"android-0.5.0\",\"boot\":2718281828,\"seq\":7," +
                "\"ts\":1789413255,\"ms\":250,\"up\":3600000,\"q\":0," +
                "\"kind\":\"vibration\",\"dur\":1840,\"pga\":0.412,\"ratio\":6.3,\"freq\":11.5}",
            json,
        )
    }

    @Test
    fun `an event outside the contract is not a message`() {
        assertNull(Contract.encodeEvent(env, Contract.Vibration(0, 0.4, 5.0, null)))
        assertNull(Contract.encodeEvent(env, Contract.Vibration(900, 0.4, 0.5, null)))
    }

    @Test
    fun `marking replayed sets one bit of the envelope and nothing else`() {
        val json = Contract.encode(env.copy(quality = 0x10), full)!!
        val marked = Contract.markReplayed(json)
        assertTrue(marked.contains("\"q\":18,"))
        assertEquals(json.replace("\"q\":16,", "\"q\":18,"), marked)
        assertEquals(marked, Contract.markReplayed(marked))
    }

    @Test
    fun `marking replayed works on an event, whose quality ends the envelope`() {
        val json = Contract.encodeEvent(env, Contract.Vibration(900, 0.4, 5.0, null))!!
        assertTrue(Contract.markReplayed(json).contains("\"q\":2,\"kind\""))
    }
}
