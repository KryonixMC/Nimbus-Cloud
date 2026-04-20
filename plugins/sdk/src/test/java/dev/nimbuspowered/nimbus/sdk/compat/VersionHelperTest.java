package dev.nimbuspowered.nimbus.sdk.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paper API is on the test classpath, so Adventure/MiniMessage/AsyncChatEvent detection
 * resolves to true; Folia's RegionizedServer is not in paper-api, so Folia detection is false.
 */
class VersionHelperTest {

    @Test
    void adventureIsDetected() {
        assertTrue(VersionHelper.hasAdventure());
    }

    @Test
    void miniMessageIsDetected() {
        assertTrue(VersionHelper.hasMiniMessage());
    }

    @Test
    void asyncChatEventIsDetected() {
        assertTrue(VersionHelper.hasAsyncChatEvent());
    }

    @Test
    void foliaNotDetectedOnPlainPaperApi() {
        assertFalse(VersionHelper.isFolia());
    }
}
