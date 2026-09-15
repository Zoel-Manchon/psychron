package dev.psychron.node

import android.content.Context
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Properties
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/** [hosts] in the order provisioning listed them; [Endpoints] decides the order tried. */
data class NodeConfig(val hosts: List<String>, val port: Int, val device: String)

/**
 * The node's identity and where to send it.
 *
 * Nothing here is compiled into the APK. The private key is generated in the phone's
 * secure hardware ([DeviceKey]); infra/provision-phone.sh collects the signing
 * request, has the project CA sign it, and pushes back three files that are all
 * public: the CA certificate, the node's certificate and where the broker is.
 *
 * An older install may still hold a PKCS#8 key file pushed from the host. It is used
 * until the phone is enrolled, and deleted the moment a certificate for the hardware
 * key arrives.
 */
object Provisioning {
    const val CA = "ca.crt"
    const val CERT = "client.crt"
    const val LEGACY_KEY = "client.pk8"
    const val PROPERTIES = "node.properties"
    const val REQUEST = "client.csr"

    fun dir(ctx: Context) = File(ctx.filesDir, "certs")

    enum class Identity { HARDWARE, LEGACY_FILE, PENDING, NONE }

    /** Which key would sign a handshake right now. */
    fun identity(ctx: Context): Identity {
        val d = dir(ctx)
        val cert = File(d, CERT)
        // Compared as encodings: the certificate's key and the key store's handle are
        // objects from two different providers, and equals() between them says no
        // even when they are the same key.
        if (cert.isFile && runCatching {
                DeviceKey.exists() && certificate(ctx).publicKey.encoded.contentEquals(DeviceKey.publicKey().encoded)
            }.getOrDefault(false)) return Identity.HARDWARE
        if (cert.isFile && File(d, LEGACY_KEY).isFile) return Identity.LEGACY_FILE
        return if (runCatching { DeviceKey.exists() }.getOrDefault(false)) Identity.PENDING else Identity.NONE
    }

    fun missing(ctx: Context): List<String> {
        val d = dir(ctx)
        val files = listOf(CA, CERT, PROPERTIES).filter { !File(d, it).isFile }
        val key = when (identity(ctx)) {
            Identity.HARDWARE, Identity.LEGACY_FILE -> emptyList()
            else -> listOf("a certificate for the hardware key")
        }
        return (files + key).distinct()
    }

    fun config(ctx: Context): NodeConfig {
        val props = Properties().apply { File(dir(ctx), PROPERTIES).inputStream().use { load(it) } }
        return NodeConfig(
            hosts = Endpoints.parse(props.getProperty("hosts"), props.getProperty("host"))
                .ifEmpty { error("node.properties has no hosts") },
            port = props.getProperty("port")?.toInt() ?: 8883,
            device = props.getProperty("device") ?: error("node.properties has no device"),
        )
    }

    /**
     * Makes sure a hardware key exists and writes its signing request where the
     * provisioning script collects it. The device name comes from the request the
     * script makes, since a phone that has never been provisioned has no
     * node.properties to read it from.
     */
    fun enrol(ctx: Context, device: String): DeviceKey.Where {
        val where = DeviceKey.ensure()
        val d = dir(ctx).apply { mkdirs() }
        File(d, REQUEST).writeText(DeviceKey.requestPem(device))
        return where
    }

    /** Called once a certificate for the hardware key is in place: the file key has no further use. */
    fun retireLegacyKey(ctx: Context) {
        if (identity(ctx) == Identity.HARDWARE) {
            File(dir(ctx), LEGACY_KEY).delete()
            File(dir(ctx), REQUEST).delete()
        }
    }

    private fun certificate(ctx: Context): X509Certificate =
        File(dir(ctx), CERT).inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    /**
     * A TLS context that trusts exactly one authority — the project's CA — and
     * presents the node's client certificate.
     *
     * Not the platform trust store. A public CA has no business vouching for a
     * broker on a LAN address, and trusting the platform's hundred-odd roots would
     * let any of them issue a certificate this node would accept.
     */
    fun socketFactory(ctx: Context): SSLSocketFactory {
        val d = dir(ctx)
        val cf = CertificateFactory.getInstance("X.509")
        val ca = File(d, CA).inputStream().use { cf.generateCertificate(it) as X509Certificate }
        val chain = File(d, CERT).inputStream().use { input ->
            cf.generateCertificates(input).map { it as X509Certificate }.toTypedArray()
        }

        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("psychron-ca", ca)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(trust) }

        val keyManagers: Array<KeyManager> = when (identity(ctx)) {
            Identity.HARDWARE -> arrayOf(DeviceKey.Manager(chain))
            Identity.LEGACY_FILE -> legacyKeyManagers(File(d, LEGACY_KEY), chain)
            else -> error("no identity: enrol the phone with infra/provision-phone.sh")
        }

        return SSLContext.getInstance("TLS").apply {
            init(keyManagers, tmf.trustManagers, SecureRandom())
        }.socketFactory
    }

    private fun legacyKeyManagers(file: File, chain: Array<X509Certificate>): Array<KeyManager> {
        val key = KeyFactory.getInstance("EC").generatePrivate(
            PKCS8EncodedKeySpec(pemBody(file.readText(), "PRIVATE KEY")),
        )
        // The key store exists only in memory for the life of this process, so its
        // password protects nothing on disk. It is random rather than empty because
        // some key store implementations refuse an empty one.
        val password = CharArray(24).also { p ->
            val rnd = SecureRandom()
            for (i in p.indices) p[i] = ('a' + rnd.nextInt(26))
        }
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setKeyEntry("client", key, password, chain)
        }
        return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keys, password) }.keyManagers
    }

    private fun pemBody(pem: String, label: String): ByteArray {
        val begin = "-----BEGIN $label-----"
        val end = "-----END $label-----"
        val start = pem.indexOf(begin)
        val stop = pem.indexOf(end)
        require(start >= 0 && stop > start) { "not a PEM $label" }
        val base64 = pem.substring(start + begin.length, stop).filterNot { it.isWhitespace() }
        return Base64.getDecoder().decode(base64)
    }
}
