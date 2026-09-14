package dev.psychron.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SntpTest {
    private val nonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)

    /** A well-formed server reply: received at t2, sent at t3 (Unix ms). */
    private fun reply(t2: Long, t3: Long, stratum: Int = 2, mode: Int = 4, leap: Int = 0,
                      echo: ByteArray = nonce): ByteArray {
        val b = ByteArray(48)
        b[0] = ((leap shl 6) or (4 shl 3) or mode).toByte()
        b[1] = stratum.toByte()
        echo.copyInto(b, 24)
        Sntp.writeTimestamp(b, 32, t2)
        Sntp.writeTimestamp(b, 40, t3)
        return b
    }

    private fun rejects(r: ByteArray, message: String) {
        try {
            Sntp.interpret(r, nonce, 1000, 1100)
            fail("expected rejection: $message")
        } catch (e: Sntp.Rejected) {
            assertTrue("${e.message} should mention $message", e.message!!.contains(message))
        }
    }

    @Test
    fun `server time is corrected by half the network round trip`() {
        // Sent at device-monotonic 1000, answered at 1100: 100 ms round trip, of which
        // the server held the packet for 20 ms. 80 ms on the wire, 40 ms each way.
        val t2 = 1_789_406_501_000L
        val s = Sntp.interpret(reply(t2, t2 + 20), nonce, 1000, 1100)
        assertEquals(80, s.rttMillis)
        assertEquals(t2 + 20 + 40, s.serverMillis)
        assertEquals(1100, s.atElapsed)
    }

    @Test
    fun `milliseconds survive the NTP fraction encoding`() {
        val t = 1_789_406_501_437L
        val s = Sntp.interpret(reply(t, t), nonce, 0, 0)
        assertEquals(t, s.serverMillis)
    }

    @Test
    fun `a reply to someone else's request is not believed`() =
        rejects(reply(1_789_406_501_000L, 1_789_406_501_000L, echo = ByteArray(8)), "originate")

    @Test
    fun `an unsynchronised server is not believed`() =
        rejects(reply(1_789_406_501_000L, 1_789_406_501_000L, leap = 3), "unsynchronised")

    @Test
    fun `a kiss-of-death stratum is not believed`() =
        rejects(reply(1_789_406_501_000L, 1_789_406_501_000L, stratum = 0), "stratum")

    @Test
    fun `a client-mode packet is not a reply`() =
        rejects(reply(1_789_406_501_000L, 1_789_406_501_000L, mode = 3), "mode")

    @Test
    fun `an impossible round trip is not believed`() {
        // The server claims to have held the packet longer than the whole exchange took.
        val t2 = 1_789_406_501_000L
        rejects(reply(t2, t2 + 500), "round trip")
    }

    @Test
    fun `the trusted clock advances with the monotonic clock, not the wall clock`() {
        var now = 5_000L
        val clock = TrustedClock { now }
        assertNull(clock.nowMillis())

        // Simulate a successful synchronisation directly through its result.
        val sample = Sntp.interpret(reply(1_789_406_501_000L, 1_789_406_501_000L), nonce, 4_900, 5_000)
        clock.adopt(sample, "test")

        val atSync = clock.nowMillis()!!
        now += 2_000
        assertEquals(atSync + 2_000, clock.nowMillis())
        assertEquals(50L, clock.uncertaintyMillis())
    }
}
