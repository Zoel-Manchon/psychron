package dev.psychron.node

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class CsrTest {
    private val pair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun request(cn: String = "phone-01") = Csr.build("psychron", cn, pair.public.encoded) { bytes ->
        Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(bytes)
            sign()
        }
    }

    @Test
    fun `the signature verifies over the request info with the key it carries`() {
        val der = request()
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(pair.public)
            update(Csr.signedPart(der))
            verify(Csr.signature(der))
        }
        assertTrue(ok)
    }

    @Test
    fun `the request carries the public key and the subject verbatim`() {
        val der = request()
        val info = Csr.signedPart(der)
        assertTrue(info.windowed(pair.public.encoded))
        assertTrue(info.windowed("phone-01".toByteArray()))
        assertTrue(info.windowed("psychron".toByteArray()))
    }

    @Test
    fun `lengths above 127 bytes use the long form`() {
        // A P-256 request is ~230 bytes: its outer SEQUENCE needs two length bytes.
        val der = request()
        assertEquals(0x30, der[0].toInt() and 0xFF)
        assertEquals(0x81, der[1].toInt() and 0xFF)
        assertEquals(der.size - 3, der[2].toInt() and 0xFF)
    }

    @Test
    fun `the PEM armour is what openssl expects`() {
        val der = request()
        val pem = Csr.pem(der)
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE REQUEST-----\n"))
        assertTrue(pem.endsWith("\n-----END CERTIFICATE REQUEST-----\n"))
        val body = pem.lines().drop(1).takeWhile { !it.startsWith("-----") }
        assertTrue(body.all { it.length <= 64 })
        assertArrayEquals(der, java.util.Base64.getMimeDecoder().decode(body.joinToString("")))
    }

    @Test
    fun `a request is left in build for openssl to judge`() {
        // The unit tests prove the signature and the structure to Java; verify.sh
        // hands this file to `openssl req -verify`, which is what the host runs
        // before signing a real one.
        java.io.File("build/csr-probe.pem").writeText(Csr.pem(request()))
    }

    private fun ByteArray.windowed(needle: ByteArray): Boolean =
        (0..size - needle.size).any { i -> needle.indices.all { this[i + it] == needle[it] } }
}
