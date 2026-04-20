package dev.nimbuspowered.nimbus.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

/**
 * Produces a self-signed X.509 cert by shelling out to `keytool` (which
 * ships with every JDK). Writing the cert by hand with public APIs alone
 * isn't possible without BouncyCastle, and the `sun.security.x509`
 * package is sealed on JDK 17+. The keytool binary, however, is always
 * available alongside `java`.
 *
 * Returns null if keytool can't be located or fails — callers should
 * assumeTrue(cert != null) to skip tests in that environment.
 */
private fun generateKeytoolCert(): X509Certificate? {
    return try {
        val tmpDir = Files.createTempDirectory("nimbus-tls-test")
        val ksPath = tmpDir.resolve("ks.p12")
        val javaHome = System.getProperty("java.home")
        val keytool = when {
            System.getProperty("os.name").lowercase().contains("win") ->
                "$javaHome/bin/keytool.exe"
            else -> "$javaHome/bin/keytool"
        }
        val proc = ProcessBuilder(
            keytool, "-genkeypair",
            "-alias", "nimbus-test",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "3650",
            "-dname", "CN=nimbus-test",
            "-keystore", ksPath.toAbsolutePath().toString(),
            "-storetype", "PKCS12",
            "-storepass", "changeit",
            "-keypass", "changeit"
        ).redirectErrorStream(true).start()
        proc.inputStream.readAllBytes()
        if (!proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly(); return null
        }
        if (proc.exitValue() != 0) return null
        val ks = KeyStore.getInstance("PKCS12")
        Files.newInputStream(ksPath).use { ks.load(it, "changeit".toCharArray()) }
        ks.getCertificate("nimbus-test") as? X509Certificate
    } catch (_: Exception) {
        null
    }
}

private val TEST_CERT: X509Certificate? by lazy { generateKeytoolCert() }

class TlsHelperTest {

    private val cert: X509Certificate get() {
        val c = TEST_CERT
        assumeTrue(c != null, "keytool-generated cert unavailable on this JDK")
        return c!!
    }

    @Test
    fun `fingerprintOf produces uppercase colon-separated sha256`() {
        val fp = TlsHelper.fingerprintOf(cert)
        assertTrue(fp.contains(":"), "expected colon-separated, got $fp")
        val parts = fp.split(":")
        assertEquals(32, parts.size)
        for (p in parts) {
            assertEquals(2, p.length)
            assertTrue(p.all { it in '0'..'9' || it in 'A'..'F' }, "bad hex byte: $p")
        }
    }

    @Test
    fun `pinned trust manager accepts matching fingerprint`() {
        val fp = TlsHelper.fingerprintOf(cert)
        val tm = TlsHelper.pinnedTrustManager(fp)
        tm.checkServerTrusted(arrayOf(cert), "RSA")
        assertEquals(0, tm.acceptedIssuers.size)
        tm.checkClientTrusted(arrayOf(cert), "RSA")
    }

    @Test
    fun `pinned trust manager rejects mismatched fingerprint`() {
        val tm = TlsHelper.pinnedTrustManager("00:00:00")
        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(arrayOf(cert), "RSA")
        }
    }

    @Test
    fun `pinned trust manager rejects empty and null chain`() {
        val tm = TlsHelper.pinnedTrustManager("AA:BB")
        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(emptyArray(), "RSA")
        }
        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(null, "RSA")
        }
    }

    @Test
    fun `trustAllManager accepts anything including null`() {
        val tm = TlsHelper.trustAllManager()
        tm.checkClientTrusted(null, "RSA")
        tm.checkServerTrusted(null, "RSA")
        tm.checkServerTrusted(arrayOf(cert), "RSA")
        assertEquals(0, tm.acceptedIssuers.size)
    }

    @Test
    fun `loadTrustStore returns null for blank path`() {
        assertNull(TlsHelper.loadTrustStore("", ""))
    }

    @Test
    fun `trustManagerFor null falls back to system default`() {
        val tm = TlsHelper.trustManagerFor(null)
        // system default truststore normally has at least one accepted issuer
        assertTrue(tm.acceptedIssuers.isNotEmpty())
    }

    @Test
    fun `resolveTrustManager prefers fingerprint over truststore`() {
        val agent = AgentDefinition(
            trustedFingerprint = "AA:BB",
            truststorePath = "/does/not/matter",
            tlsVerify = true
        )
        val tm = TlsHelper.resolveTrustManager(agent)
        assertNotNull(tm)
        assertThrows(CertificateException::class.java) {
            tm!!.checkServerTrusted(arrayOf(cert), "RSA")
        }
    }

    @Test
    fun `resolveTrustManager uses trust-all when tlsVerify=false`() {
        val agent = AgentDefinition(tlsVerify = false)
        val tm = TlsHelper.resolveTrustManager(agent)
        assertNotNull(tm)
        tm!!.checkServerTrusted(arrayOf(cert), "RSA")
    }

    @Test
    fun `resolveTrustManager returns null when tlsVerify=true and no pinning`() {
        val agent = AgentDefinition(tlsVerify = true)
        assertNull(TlsHelper.resolveTrustManager(agent))
    }

    @Test
    fun `fingerprint normalization is case insensitive`() {
        val fp = TlsHelper.fingerprintOf(cert)
        val lower = fp.lowercase()
        val tm = TlsHelper.pinnedTrustManager(lower)
        tm.checkServerTrusted(arrayOf(cert), "RSA")
    }

    @Test
    fun `fingerprint trimmed whitespace still matches`() {
        val fp = TlsHelper.fingerprintOf(cert)
        val tm = TlsHelper.pinnedTrustManager("  $fp  \n")
        tm.checkServerTrusted(arrayOf(cert), "RSA")
    }

    @Test
    fun `mismatch error message contains expected fingerprint`() {
        val tm = TlsHelper.pinnedTrustManager("DE:AD")
        val ex = assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(arrayOf(cert), "RSA")
        }
        assertFalse(ex.message.isNullOrBlank())
        assertTrue(ex.message!!.contains("DE:AD"))
    }

    @Test
    fun `loadTrustStore fails for missing file`() {
        assertThrows(Exception::class.java) {
            TlsHelper.loadTrustStore("/definitely/not/here.jks", "x")
        }
    }
}
