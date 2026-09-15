package dev.psychron.node

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * The node's identity key, generated inside the phone's secure hardware and never
 * anywhere else.
 *
 * StrongBox first — a separate secure element with its own processor, storage and
 * random number generator — and the TEE when the phone has none. Either way the
 * private key cannot be exported: the app holds a handle, the hardware does the
 * signing, and a copy of the app's files, a backup or a rooted debugger gets a
 * handle to nothing. The only thing that leaves the phone is a signing request.
 */
object DeviceKey {
    private const val ALIAS = "psychron-node"
    private const val STORE = "AndroidKeyStore"

    enum class Where { STRONGBOX, TEE, SOFTWARE }

    private fun store() = KeyStore.getInstance(STORE).apply { load(null) }

    fun exists(): Boolean = store().containsAlias(ALIAS)

    fun publicKey(): PublicKey = store().getCertificate(ALIAS).publicKey

    fun privateKey(): PrivateKey = store().getKey(ALIAS, null) as PrivateKey

    /** Generates the key if there is none. Returns where it lives. */
    fun ensure(): Where {
        if (exists()) return where()
        try {
            generate(strongBox = true)
        } catch (e: Exception) {
            // Thrown at generation rather than at configuration, as a
            // StrongBoxUnavailableException or as a ProviderException wrapping the
            // keystore's refusal, when the phone has no StrongBox or it declines these
            // parameters. The TEE is the next best place, and still one the key cannot
            // be copied out of; if that fails too, the second failure is the one raised.
            android.util.Log.w("psychron-key", "StrongBox refused the key: ${e.javaClass.simpleName} ${e.message}")
            runCatching { store().deleteEntry(ALIAS) }
            generate(strongBox = false)
        }
        return where()
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // SHA-256 for the request; NONE because the TLS stack hashes the handshake
            // itself and asks the keystore to sign the digest.
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            .setIsStrongBoxBacked(strongBox)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE).run {
            initialize(spec)
            generateKeyPair()
        }
    }

    fun where(): Where {
        val info = KeyFactory.getInstance(privateKey().algorithm, STORE)
            .getKeySpec(privateKey(), KeyInfo::class.java)
        return if (Build.VERSION.SDK_INT >= 31) when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> Where.STRONGBOX
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> Where.TEE
            else -> Where.SOFTWARE
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) Where.TEE else Where.SOFTWARE
        }
    }

    /** A PEM signing request for [commonName], signed inside the hardware. */
    fun requestPem(commonName: String): String {
        val der = Csr.build("psychron", commonName, publicKey().encoded) { bytes ->
            Signature.getInstance("SHA256withECDSA").run {
                initSign(privateKey())
                update(bytes)
                sign()
            }
        }
        return Csr.pem(der)
    }

    /**
     * Presents the hardware key and the certificate the CA issued for it. A key
     * manager of its own rather than the key store's: the certificate arrives as a
     * file from provisioning, and writing it into the AndroidKeyStore entry would
     * replace the self-signed placeholder the platform keeps there for nothing.
     */
    class Manager(private val chain: Array<X509Certificate>) : X509ExtendedKeyManager() {
        private val key = privateKey()
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?) = chain
        override fun getPrivateKey(alias: String?) = key
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }
}
