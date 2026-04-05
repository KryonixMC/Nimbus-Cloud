package dev.kryonix.nimbus.cluster

import io.ktor.network.tls.certificates.*
import org.slf4j.LoggerFactory
import java.io.FileInputStream
import java.net.InetAddress
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal
import kotlin.io.path.exists

object TlsHelper {

    private val logger = LoggerFactory.getLogger(TlsHelper::class.java)

    private const val DEFAULT_ALIAS = "nimbus-cluster"
    private const val DEFAULT_PASSWORD = "nimbus-auto"

    /**
     * Loads a JKS or PKCS12 keystore from disk.
     * Auto-detects format from file extension (.p12/.pfx -> PKCS12, else JKS).
     */
    fun loadKeyStore(path: Path, password: String): KeyStore {
        val type = when {
            path.toString().endsWith(".p12", ignoreCase = true) ||
            path.toString().endsWith(".pfx", ignoreCase = true) -> "PKCS12"
            else -> "JKS"
        }
        val ks = KeyStore.getInstance(type)
        FileInputStream(path.toFile()).use { fis ->
            ks.load(fis, password.toCharArray())
        }
        return ks
    }

    /**
     * Ensures a keystore exists at [path]. If not, generates a self-signed certificate.
     * Returns a pair of (KeyStore, password).
     */
    fun ensureKeyStore(path: Path, configuredPassword: String, bind: String): Pair<KeyStore, String> {
        if (path.exists()) {
            val password = configuredPassword.ifBlank { DEFAULT_PASSWORD }
            val ks = loadKeyStore(path, password)
            logCertificateFingerprint(ks)
            return ks to password
        }

        // Auto-generate self-signed certificate
        logger.info("No keystore found at {} — generating self-signed certificate...", path)

        val password = configuredPassword.ifBlank { DEFAULT_PASSWORD }

        val keyStore = buildKeyStore {
            certificate(DEFAULT_ALIAS) {
                this.password = password
                daysValid = 3650 // 10 years
                keySizeInBits = 4096
                subject = X500Principal("CN=nimbus-cluster, O=Nimbus, L=Cloud")
                domains = listOf("localhost")
                ipAddresses = buildList {
                    add(InetAddress.getByName("127.0.0.1"))
                    if (bind != "127.0.0.1" && bind != "localhost") {
                        try {
                            add(InetAddress.getByName(bind))
                        } catch (_: Exception) {}
                    }
                    try {
                        add(InetAddress.getLocalHost())
                    } catch (_: Exception) {}
                }
            }
        }

        // Save to disk
        path.parent?.toFile()?.mkdirs()
        keyStore.saveToFile(path.toFile(), password)
        logger.info("Self-signed keystore saved to {}", path)
        logCertificateFingerprint(keyStore)

        return keyStore to password
    }

    /**
     * Logs the SHA-256 fingerprint of the first certificate in the keystore.
     * Agents can use this to verify the server identity.
     */
    private fun logCertificateFingerprint(keyStore: KeyStore) {
        try {
            val alias = keyStore.aliases().nextElement()
            val cert = keyStore.getCertificate(alias) as? X509Certificate ?: return
            val sha256 = MessageDigest.getInstance("SHA-256")
            val fingerprint = sha256.digest(cert.encoded)
                .joinToString(":") { "%02X".format(it) }
            logger.info("Cluster TLS certificate fingerprint (SHA-256): {}", fingerprint)
            logger.info("Certificate valid until: {}", cert.notAfter)
        } catch (e: Exception) {
            logger.debug("Could not read certificate fingerprint: {}", e.message)
        }
    }
}
