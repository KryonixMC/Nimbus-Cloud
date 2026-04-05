package dev.kryonix.nimbus.agent

import java.io.FileInputStream
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object TlsHelper {

    /**
     * Loads a trust store from disk, or returns null to use the system default.
     */
    fun loadTrustStore(path: String, password: String): KeyStore? {
        if (path.isBlank()) return null
        val type = when {
            path.endsWith(".p12", ignoreCase = true) ||
            path.endsWith(".pfx", ignoreCase = true) -> "PKCS12"
            else -> "JKS"
        }
        val ks = KeyStore.getInstance(type)
        FileInputStream(path).use { fis ->
            ks.load(fis, password.toCharArray())
        }
        return ks
    }

    /**
     * Creates a TrustManager that trusts all certificates.
     * Only for development/testing — NOT for production use.
     */
    fun trustAllManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Creates a TrustManager from the given trust store, or falls back to system default.
     */
    fun trustManagerFor(trustStore: KeyStore?): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(trustStore) // null = system default
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
