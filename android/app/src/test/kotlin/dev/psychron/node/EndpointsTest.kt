package dev.psychron.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointsTest {
    private val lan = "192.168.1.66"
    private val tailnet = "100.101.102.103"
    private val both = listOf(lan, tailnet)

    @Test
    fun `private ranges are local, overlay and public addresses are not`() {
        for (h in listOf("10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.1.66", "169.254.3.4", "broker.local")) {
            assertTrue(h, Endpoints.isLocal(h))
        }
        for (h in listOf("100.101.102.103", "172.15.0.1", "172.32.0.1", "8.8.8.8", "node.tail1234.ts.net",
                         "192.168.1", "192.168.1.300", "fd00::1")) {
            assertFalse(h, Endpoints.isLocal(h))
        }
    }

    @Test
    fun `on the local network the configured order stands`() =
        assertEquals(both, Endpoints.order(both, onLocalNetwork = true, lastWorked = null))

    @Test
    fun `on mobile data the LAN address goes last, not away`() =
        // Still tried: a VPN may route the LAN, and a slow answer beats none.
        assertEquals(listOf(tailnet, lan), Endpoints.order(both, onLocalNetwork = false, lastWorked = null))

    @Test
    fun `the address that last worked is tried first`() =
        assertEquals(listOf(tailnet, lan), Endpoints.order(both, onLocalNetwork = true, lastWorked = tailnet))

    @Test
    fun `a remembered address that is no longer configured is ignored`() =
        assertEquals(both, Endpoints.order(both, onLocalNetwork = true, lastWorked = "10.9.9.9"))

    @Test
    fun `older provisioning with a single host still parses`() {
        assertEquals(listOf(lan), Endpoints.parse(hosts = null, host = lan))
        assertEquals(both, Endpoints.parse(hosts = " $lan , $tailnet ,, $lan", host = "ignored"))
        assertEquals(emptyList<String>(), Endpoints.parse(null, null))
    }
}
