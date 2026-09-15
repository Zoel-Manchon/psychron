package dev.psychron.node

import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * A PKCS#10 certificate signing request, in DER written by hand.
 *
 * The key it carries lives in the phone's secure hardware and signs from there; the
 * request is the only way its public half reaches the certificate authority. Built
 * here rather than with a library because the structure is small and fixed, and
 * because the one library that does it on Android (Bouncy Castle) would be larger
 * than the rest of the app. The host checks every request with `openssl req
 * -verify` before signing it, so a mistake here is refused, not trusted.
 *
 * Pure: [sign] is given the bytes to sign, so tests use a software key.
 */
object Csr {
    private val OID_COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)
    private val OID_ORGANIZATION = byteArrayOf(0x55, 0x04, 0x0A)
    // 1.2.840.10045.4.3.2, ecdsa-with-SHA256
    private val OID_ECDSA_SHA256 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)

    /**
     * @param subjectPublicKeyInfo the key's X.509 encoding, `PublicKey.getEncoded()`
     * @param sign SHA256withECDSA over the given bytes, returning the DER signature
     */
    fun build(organization: String, commonName: String, subjectPublicKeyInfo: ByteArray,
              sign: (ByteArray) -> ByteArray): ByteArray {
        val name = sequence(
            set(sequence(tlv(0x06, OID_ORGANIZATION), utf8(organization))),
            set(sequence(tlv(0x06, OID_COMMON_NAME), utf8(commonName))),
        )
        val info = sequence(
            tlv(0x02, byteArrayOf(0)),        // version 1, encoded as 0
            name,
            subjectPublicKeyInfo,
            tlv(0xA0, ByteArray(0)),          // no attributes, but the field is not optional
        )
        val signature = sign(info)
        return sequence(
            info,
            sequence(tlv(0x06, OID_ECDSA_SHA256)),   // no parameters for ECDSA, not even NULL
            tlv(0x03, byteArrayOf(0) + signature),   // a BIT STRING with no unused bits
        )
    }

    fun pem(der: ByteArray): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN CERTIFICATE REQUEST-----\n$body\n-----END CERTIFICATE REQUEST-----\n"
    }

    /** The CertificationRequestInfo inside a request, as signed. For tests. */
    fun signedPart(der: ByteArray): ByteArray {
        val (_, outer) = header(der, 0)
        val (infoLen, infoBody) = header(der, outer)
        return der.copyOfRange(outer, infoBody + infoLen)
    }

    /** The DER signature inside a request. For tests. */
    fun signature(der: ByteArray): ByteArray {
        val (_, outer) = header(der, 0)
        val (infoLen, infoBody) = header(der, outer)
        val (algLen, algBody) = header(der, infoBody + infoLen)
        val (bitsLen, bitsBody) = header(der, algBody + algLen)
        return der.copyOfRange(bitsBody + 1, bitsBody + bitsLen)
    }

    private fun header(der: ByteArray, at: Int): Pair<Int, Int> {
        var i = at + 1
        val first = der[i++].toInt() and 0xFF
        if (first < 0x80) return first to i
        var len = 0
        repeat(first and 0x7F) { len = (len shl 8) or (der[i++].toInt() and 0xFF) }
        return len to i
    }

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val n = value.size
        when {
            n < 0x80 -> out.write(n)
            n < 0x100 -> { out.write(0x81); out.write(n) }
            else -> { out.write(0x82); out.write(n shr 8); out.write(n and 0xFF) }
        }
        out.write(value)
        return out.toByteArray()
    }

    private fun sequence(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun utf8(s: String) = tlv(0x0C, s.toByteArray(Charsets.UTF_8))
}
