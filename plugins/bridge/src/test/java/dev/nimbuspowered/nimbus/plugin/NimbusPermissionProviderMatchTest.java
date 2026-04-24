package dev.nimbuspowered.nimbus.plugin;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for NimbusPermissionProvider.matchesPermission(Set, String).
 * The method is package-private static — callable directly from this package.
 */
class NimbusPermissionProviderMatchTest {

    // ── Exact match ─────────────────────────────────────────────────

    @Test
    void exactMatchReturnsTrue() {
        assertTrue(NimbusPermissionProvider.matchesPermission(
                Set.of("nimbus.cloud.list"),
                "nimbus.cloud.list"));
    }

    @Test
    void nonMatchReturnsFalse() {
        assertFalse(NimbusPermissionProvider.matchesPermission(
                Set.of("nimbus.cloud.list"),
                "nimbus.cloud.status"));
    }

    @Test
    void emptyPermissionSetReturnsFalse() {
        assertFalse(NimbusPermissionProvider.matchesPermission(
                Set.of(),
                "nimbus.cloud.list"));
    }

    // ── Root wildcard ────────────────────────────────────────────────

    @Test
    void rootWildcardMatchesEverything() {
        assertTrue(NimbusPermissionProvider.matchesPermission(
                Set.of("*"),
                "nimbus.anything.at.all"));
    }

    @Test
    void rootWildcardMatchesSingleSegment() {
        assertTrue(NimbusPermissionProvider.matchesPermission(
                Set.of("*"),
                "admin"));
    }

    // ── Segment wildcard ─────────────────────────────────────────────

    @Test
    void wildcardStarMatchesDescendantNodes() {
        // "nimbus.*" must match "nimbus.cloud.list" (multi-level descend)
        assertTrue(NimbusPermissionProvider.matchesPermission(
                Set.of("nimbus.*"),
                "nimbus.cloud.list"));
    }

    @Test
    void multiLevelWildcardMatchesCorrectSubtree() {
        // "nimbus.cloud.*" matches "nimbus.cloud.list"
        assertTrue(NimbusPermissionProvider.matchesPermission(
                Set.of("nimbus.cloud.*"),
                "nimbus.cloud.list"));
    }

    @Test
    void multiLevelWildcardDoesNotMatchSiblingSubtree() {
        // "nimbus.cloud.*" must NOT match "nimbus.admin.list"
        assertFalse(NimbusPermissionProvider.matchesPermission(
                Set.of("nimbus.cloud.*"),
                "nimbus.admin.list"));
    }

    @Test
    void exactNodeDoesNotMatchChild() {
        // "nimbus.cloud" must NOT grant "nimbus.cloud.list" — no implicit wildcard
        assertFalse(NimbusPermissionProvider.matchesPermission(
                Set.of("nimbus.cloud"),
                "nimbus.cloud.list"));
    }

    @Test
    void wildcardDoesNotMatchParent() {
        // "nimbus.cloud.*" must NOT match "nimbus.cloud" itself
        assertFalse(NimbusPermissionProvider.matchesPermission(
                Set.of("nimbus.cloud.*"),
                "nimbus.cloud"));
    }

    // ── Multiple permissions in set ───────────────────────────────────

    @Test
    void matchesWhenOneOfManyPermissionsMatches() {
        assertTrue(NimbusPermissionProvider.matchesPermission(
                Set.of("nimbus.admin.kick", "nimbus.cloud.*", "chat.color"),
                "nimbus.cloud.list"));
    }

    @Test
    void noMatchWhenNoneOfManyPermissionsMatch() {
        assertFalse(NimbusPermissionProvider.matchesPermission(
                Set.of("nimbus.admin.kick", "chat.color"),
                "nimbus.cloud.list"));
    }

    // ── Edge cases ────────────────────────────────────────────────────

    @Test
    void wildcardAtFirstLevelMatchesTwoSegmentPermission() {
        // "nimbus.*" matches "nimbus.cloud"
        assertTrue(NimbusPermissionProvider.matchesPermission(
                Set.of("nimbus.*"),
                "nimbus.cloud"));
    }

    @Test
    void singleSegmentPermissionNotGrantedByUnrelatedWildcard() {
        assertFalse(NimbusPermissionProvider.matchesPermission(
                Set.of("other.*"),
                "nimbus"));
    }

    @Test
    void exactWildcardStringMatchesThatLiteralString() {
        // If "nimbus.*" is the checked permission (not a grant), exact match fires first
        assertTrue(NimbusPermissionProvider.matchesPermission(
                Set.of("nimbus.*"),
                "nimbus.*"));
    }
}
