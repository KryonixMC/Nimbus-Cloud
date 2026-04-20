package dev.nimbuspowered.nimbus.cluster

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists

class TlsHelperTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `ensureKeyStore generates a keystore when none exists`() {
        val path = tempDir.resolve("cluster.jks")
        val (ks, password) = TlsHelper.ensureKeyStore(
            path = path,
            configuredPassword = "test-password-12345",
            clusterToken = "",
            bind = "0.0.0.0"
        )

        assertTrue(path.exists(), "keystore file should be created")
        assertEquals("test-password-12345", password)
        assertTrue(ks.aliases().toList().isNotEmpty())
    }

    @Test
    fun `ensureKeyStore reuses existing keystore with correct password`() {
        val path = tempDir.resolve("cluster.jks")
        val (_, pw1) = TlsHelper.ensureKeyStore(path, "my-pw", "", "0.0.0.0")
        val (ks2, pw2) = TlsHelper.ensureKeyStore(path, "my-pw", "", "0.0.0.0")

        assertEquals(pw1, pw2)
        assertTrue(ks2.aliases().toList().isNotEmpty())
    }

    @Test
    fun `ensureKeyStore regenerates when password is wrong`() {
        val path = tempDir.resolve("cluster.jks")
        TlsHelper.ensureKeyStore(path, "pw1-long-enough", "", "0.0.0.0")
        // Different password — should regenerate
        val (ks2, pw2) = TlsHelper.ensureKeyStore(path, "pw2-also-long", "", "0.0.0.0")

        assertEquals("pw2-also-long", pw2)
        assertTrue(ks2.aliases().toList().isNotEmpty())
    }

    @Test
    fun `ensureKeyStore derives password from cluster token deterministically`() {
        val p1 = tempDir.resolve("a.jks")
        val p2 = tempDir.resolve("b.jks")
        val token = "my-cluster-token"

        val (_, pw1) = TlsHelper.ensureKeyStore(p1, "", token, "0.0.0.0")
        val (_, pw2) = TlsHelper.ensureKeyStore(p2, "", token, "0.0.0.0")

        assertEquals(pw1, pw2, "derived password must be deterministic for the same token")
        assertTrue(pw1.length == 64, "sha-256 hex length")
    }

    @Test
    fun `getCertInfo returns fingerprint pem and sans`() {
        val path = tempDir.resolve("cluster.jks")
        val (ks, _) = TlsHelper.ensureKeyStore(
            path = path,
            configuredPassword = "pw-12345",
            clusterToken = "",
            bind = "0.0.0.0",
            extraSans = listOf("nimbus.example.com", "10.0.0.5")
        )

        val info = TlsHelper.getCertInfo(ks)
        assertNotNull(info)
        info!!
        // SHA-256 fingerprint: 32 bytes → 32 hex pairs separated by 31 colons = 95 chars
        assertEquals(95, info.fingerprint.length)
        assertTrue(info.fingerprint.matches(Regex("[0-9A-F:]+")))
        assertTrue(info.pemEncoded.startsWith("-----BEGIN CERTIFICATE-----"))
        assertTrue(info.pemEncoded.trimEnd().endsWith("-----END CERTIFICATE-----"))
        assertNotNull(info.validUntil)
        assertTrue(info.sans.any { it.contains("nimbus.example.com") })
        assertTrue(info.sans.any { it.contains("10.0.0.5") })
        assertTrue(info.sans.any { it.startsWith("DNS:localhost") })
    }

    @Test
    fun `loadKeyStore autodetects PKCS12 from extension`() {
        val path = tempDir.resolve("cluster.jks")
        TlsHelper.ensureKeyStore(path, "pw-12345", "", "0.0.0.0")
        // Load should succeed with correct password
        val ks = TlsHelper.loadKeyStore(path, "pw-12345")
        assertTrue(ks.aliases().toList().isNotEmpty())
    }

    @Test
    fun `ensureKeyStore filters IP vs DNS SANs`() {
        val path = tempDir.resolve("cluster.jks")
        val (ks, _) = TlsHelper.ensureKeyStore(
            path = path,
            configuredPassword = "pw-12345",
            clusterToken = "",
            bind = "192.168.1.50",
            extraSans = listOf("custom.example.com", "172.16.0.1", "not.ip.example.org")
        )
        val info = TlsHelper.getCertInfo(ks)!!
        // The bind (IP) went into IP SANs
        assertTrue(info.sans.any { it == "IP:192.168.1.50" || it.endsWith("192.168.1.50") })
        assertTrue(info.sans.any { it.contains("custom.example.com") })
        assertTrue(info.sans.any { it.contains("172.16.0.1") })
    }
}
