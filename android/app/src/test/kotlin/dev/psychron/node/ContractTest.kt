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
}
