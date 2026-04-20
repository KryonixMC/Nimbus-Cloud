package dev.nimbuspowered.nimbus.update

import dev.nimbuspowered.nimbus.update.UpdateChecker.UpdateType
import dev.nimbuspowered.nimbus.update.UpdateChecker.VersionInfo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

class UpdateCheckerTest {

    // --- VersionInfo.parse ---

    @Test
    fun `parse plain semver`() {
        val v = VersionInfo.parse("1.2.3")!!
        assertEquals(1, v.major)
        assertEquals(2, v.minor)
        assertEquals(3, v.patch)
        assertNull(v.preReleaseSuffix)
        assertFalse(v.isPreRelease)
    }

    @Test
    fun `parse strips leading v`() {
        val v = VersionInfo.parse("v0.11.1")!!
        assertEquals(0, v.major)
        assertEquals(11, v.minor)
        assertEquals(1, v.patch)
    }

    @Test
    fun `parse pre-release suffix`() {
        val v = VersionInfo.parse("1.0.0-beta.2")!!
        assertEquals("beta.2", v.preReleaseSuffix)
        assertTrue(v.isPreRelease)
    }

    @Test
    fun `parse rejects non-semver`() {
        assertNull(VersionInfo.parse("not-a-version"))
        assertNull(VersionInfo.parse("1.2"))
        assertNull(VersionInfo.parse("1.x.3"))
        assertNull(VersionInfo.parse(""))
    }

    @Test
    fun `parse rejects dev placeholder`() {
        // "dev" is not a valid semver — checkForUpdate handles the dev short-circuit
        // separately, but VersionInfo.parse itself returns null for it.
        assertNull(VersionInfo.parse("dev"))
    }

    // --- VersionInfo.compareTo ---

    @Test
    fun `compareTo orders by major minor patch`() {
        val a = VersionInfo.parse("1.2.3")!!
        val b = VersionInfo.parse("1.2.4")!!
        val c = VersionInfo.parse("1.3.0")!!
        val d = VersionInfo.parse("2.0.0")!!

        assertTrue(a < b)
        assertTrue(b < c)
        assertTrue(c < d)
        assertTrue(d > a)
    }

    @Test
    fun `compareTo pre-release is lower than stable`() {
        val stable = VersionInfo.parse("1.0.0")!!
        val preRelease = VersionInfo.parse("1.0.0-beta.1")!!
        assertTrue(preRelease < stable)
        assertTrue(stable > preRelease)
    }

    @Test
    fun `compareTo orders pre-release suffixes lexicographically`() {
        val alpha = VersionInfo.parse("1.0.0-alpha")!!
        val beta = VersionInfo.parse("1.0.0-beta")!!
        assertTrue(alpha < beta)
    }

    @Test
    fun `toString round-trips`() {
        assertEquals("1.2.3", VersionInfo.parse("1.2.3")!!.toString())
        assertEquals("0.11.1", VersionInfo.parse("v0.11.1")!!.toString())
        assertEquals("1.0.0-beta.1", VersionInfo.parse("1.0.0-beta.1")!!.toString())
    }

    // --- classifyUpdate (private — exercised via reflection) ---

    @Test
    fun `classifyUpdate returns PATCH for patch-level bump`() {
        assertEquals(UpdateType.PATCH, classify("1.2.3", "1.2.4"))
    }

    @Test
    fun `classifyUpdate returns MINOR for minor-level bump`() {
        assertEquals(UpdateType.MINOR, classify("1.2.3", "1.3.0"))
    }

    @Test
    fun `classifyUpdate returns MAJOR for major-level bump`() {
        assertEquals(UpdateType.MAJOR, classify("1.2.3", "2.0.0"))
    }

    // Note: the documented "skip when version=dev" invariant is checked directly in
    // UpdateChecker.checkForUpdate() as `if (currentVersionStr == "dev") return null`.
    // That line is trivially correct by inspection; exercising it in a unit test
    // would require mocking NimbusVersion's lazy val (JVM-statics). Not worth the
    // complexity for a one-line guard — would make the test more fragile than the
    // code it's protecting.

    private fun classify(from: String, to: String): UpdateType {
        val checker = UpdateChecker(baseDir = Path.of("."))
        val method = UpdateChecker::class.java.getDeclaredMethod(
            "classifyUpdate", VersionInfo::class.java, VersionInfo::class.java
        )
        method.isAccessible = true
        return method.invoke(
            checker,
            VersionInfo.parse(from)!!,
            VersionInfo.parse(to)!!
        ) as UpdateType
    }
}
