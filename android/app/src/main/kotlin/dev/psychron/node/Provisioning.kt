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
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/** [hosts] in the order provisioning listed them; [Endpoints] decides the order tried. */
data class NodeConfig(val hosts: List<String>, val port: Int, val device: String)

/**
 * The node's identity and where to send it, read from the app's private storage.
 *
 * Nothing here is compiled into the APK. infra/provision-phone.sh pushes four files
 * over adb into a directory only this app can read; an APK carrying a private key
 * would carry it to anyone the APK is ever shared with.
 *
 * The key is a PKCS#8 file in the app sandbox — protected from other apps, not from
 * someone holding the unlocked phone with a debugger. Moving it into StrongBox, where
 * it is generated and never exported, is the next step and a change confined to
 * this file.
 */
object Provisioning {
    val REQUIRED = listOf("ca.crt", "client.crt", "client.pk8", "node.properties")

    fun dir(ctx: Context) = File(ctx.filesDir, "certs")

    fun missing(ctx: Context): List<String> = REQUIRED.filter { !File(dir(ctx), it).isFile }

    fun config(ctx: Context): NodeConfig {
        val props = Properties().apply { File(dir(ctx), "node.properties").inputStream().use { load(it) } }
        return NodeConfig(
            hosts = Endpoints.parse(props.getProperty("hosts"), props.getProperty("host"))
                .ifEmpty { error("node.properties has no hosts") },
            port = props.getProperty("port")?.toInt() ?: 8883,
            device = props.getProperty("device") ?: error("node.properties has no device"),
        )
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
        val ca = File(d, "ca.crt").inputStream().use { cf.generateCertificate(it) as X509Certificate }
        val chain = File(d, "client.crt").inputStream().use { input ->
            cf.generateCertificates(input).map { it as X509Certificate }
        }
        val key = KeyFactory.getInstance("EC").generatePrivate(
            PKCS8EncodedKeySpec(pemBody(File(d, "client.pk8").readText(), "PRIVATE KEY")),
        )

        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("psychron-ca", ca)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(trust) }

        // The key store exists only in memory for the life of this process, so its
        // password protects nothing on disk. It is random rather than empty because
        // some key store implementations refuse an empty one.
        val password = CharArray(24).also { p ->
            val rnd = SecureRandom()
            for (i in p.indices) p[i] = ('a' + rnd.nextInt(26))
        }
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setKeyEntry("client", key, password, chain.toTypedArray())
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keys, password) }

        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, tmf.trustManagers, SecureRandom())
        }.socketFactory
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
