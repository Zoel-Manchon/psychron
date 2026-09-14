package dev.psychron.node

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom

/**
 * A minimal SNTP client (RFC 4330), because the phone's own clock is not good enough.
 *
 * Android sets its wall clock from the mobile network's time signal, which is
 * accurate to roughly a second. Measured on a Galaxy S26, it ran ~475 ms ahead of
 * NTP while the ESP32 and the server agreed to within tens of milliseconds. An app
 * cannot set the system clock without root, but it does not have to believe it:
 * it can ask the same NTP pool the other nodes use and keep its own time.
 */
object Sntp {
    private const val NTP_PORT = 123
    private const val PACKET = 48
    /** Seconds between the NTP era (1900) and the Unix epoch (1970). */
    private const val EPOCH_DELTA_S = 2_208_988_800L

    /**
     * One exchange. `serverMillis` is the server's time at the moment `atElapsed`
     * was read from the device's monotonic clock, corrected for the network delay.
     */
    data class Sample(val serverMillis: Long, val atElapsed: Long, val rttMillis: Long)

    class Rejected(reason: String) : Exception(reason)

    /**
     * Interprets a response. Pure, so the arithmetic and the validation — the part
     * that decides whether a packet from the network is believed — can be tested
     * without a network.
     *
     * The originate field must echo the nonce this client sent: a reply that does
     * not is either for a different request or forged, and either way is not a
     * statement about the time of this one.
     */
    fun interpret(response: ByteArray, nonce: ByteArray, t1Elapsed: Long, t4Elapsed: Long): Sample {
        if (response.size < PACKET) throw Rejected("short packet")
        val leap = (response[0].toInt() shr 6) and 0x3
        val mode = response[0].toInt() and 0x7
        val stratum = response[1].toInt() and 0xFF
        if (mode != 4) throw Rejected("not a server reply (mode $mode)")
        if (leap == 3) throw Rejected("server clock unsynchronised")
        // Stratum 0 is a kiss-of-death packet; above 15 is unsynchronised.
        if (stratum !in 1..15) throw Rejected("stratum $stratum")
        for (i in 0 until 8) if (response[24 + i] != nonce[i]) throw Rejected("originate does not match request")

        val t2 = millis(response, 32)   // server received
        val t3 = millis(response, 40)   // server transmitted
        if (t3 <= 0 || t2 <= 0) throw Rejected("empty timestamps")

        // Round trip minus the time the server held the packet. Both intervals come
        // from one clock each — the device's monotonic clock and the server's — so no
        // wall clock that might jump is involved.
        val rtt = (t4Elapsed - t1Elapsed) - (t3 - t2)
        if (rtt < 0 || rtt > 2000) throw Rejected("implausible round trip $rtt ms")

        // The reply spent roughly half the round trip in transit back.
        return Sample(serverMillis = t3 + rtt / 2, atElapsed = t4Elapsed, rttMillis = rtt)
    }

    /** Sends one request. `elapsed` is the monotonic clock, passed in for testability. */
    fun query(host: String, elapsed: () -> Long, timeoutMs: Int = 2500): Sample {
        val request = ByteArray(PACKET)
        request[0] = 0b00_100_011            // LI 0, version 4, mode 3 (client)
        // A random transmit timestamp as nonce, not the local time: the server echoes
        // it back verbatim, and leaking the device clock into the request is useless.
        val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }
        nonce.copyInto(request, 40)

        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val address = InetAddress.getByName(host)
            val t1 = elapsed()
            socket.send(DatagramPacket(request, PACKET, address, NTP_PORT))
            val reply = DatagramPacket(ByteArray(PACKET), PACKET)
            socket.receive(reply)
            val t4 = elapsed()
            return interpret(reply.data, nonce, t1, t4)
        }
    }

    private fun millis(b: ByteArray, offset: Int): Long {
        var seconds = 0L
        var fraction = 0L
        for (i in 0 until 4) seconds = (seconds shl 8) or (b[offset + i].toLong() and 0xFF)
        for (i in 4 until 8) fraction = (fraction shl 8) or (b[offset + i].toLong() and 0xFF)
        if (seconds == 0L) return 0
        // Rounded to the nearest millisecond, not truncated: truncating turns an
        // encoded 437 ms back into 436, a whole millisecond of error introduced by
        // the code whose only job is to remove error.
        return (seconds - EPOCH_DELTA_S) * 1000 + ((fraction * 1000 + (1L shl 31)) shr 32)
    }

    /** For tests: writes an NTP timestamp. */
    fun writeTimestamp(b: ByteArray, offset: Int, unixMillis: Long) {
        val seconds = unixMillis / 1000 + EPOCH_DELTA_S
        val fraction = ((unixMillis % 1000) shl 32) / 1000
        for (i in 0 until 4) b[offset + i] = (seconds shr (24 - 8 * i)).toByte()
        for (i in 0 until 4) b[offset + 4 + i] = (fraction shr (24 - 8 * i)).toByte()
    }
}

/**
 * Wall time from NTP, carried forward by the monotonic clock.
 *
 * Between synchronisations the time is the last server time plus elapsed realtime,
 * never System.currentTimeMillis(): the system clock can be stepped at any moment by
 * the mobile network, and a window stamped across such a step would move by however
 * far the network decided to move it.
 */
class TrustedClock(private val elapsed: () -> Long) {
    @Volatile var anchor: Sntp.Sample? = null
        private set
    @Volatile var server: String? = null
        private set

    /**
     * Several exchanges per server, keeping the one with the shortest round trip. The
     * error of an SNTP estimate is bounded by half its round trip, so the fastest
     * reply is the most trustworthy, and outliers from a congested moment drop out.
     */
    fun synchronise(servers: List<String>, samplesPerServer: Int = 4): Boolean {
        for (host in servers) {
            val best = (1..samplesPerServer).mapNotNull {
                runCatching { Sntp.query(host, elapsed) }.getOrNull()
            }.minByOrNull { it.rttMillis }
            if (best != null) {
                adopt(best, host)
                return true
            }
        }
        return false
    }

    internal fun adopt(sample: Sntp.Sample, host: String) {
        anchor = sample
        server = host
    }

    /** Current NTP time, or null if no server has ever answered. */
    fun nowMillis(): Long? = anchor?.let { it.serverMillis + (elapsed() - it.atElapsed) }

    /** Upper bound on the error of nowMillis() at the moment of synchronisation. */
    fun uncertaintyMillis(): Long? = anchor?.let { it.rttMillis / 2 }
}
