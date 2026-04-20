package dev.nimbuspowered.nimbus.sdk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ColorUtilTest {

    @Test
    void nullInputReturnsNull() {
        assertNull(ColorUtil.translate(null));
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertEquals("", ColorUtil.translate(""));
    }

    @Test
    void translatesBasicColorCodes() {
        assertEquals("<gray>Player", ColorUtil.translate("&7Player"));
        assertEquals("<red><bold>Bold Red", ColorUtil.translate("&c&lBold Red"));
        assertEquals("<green>g<blue>b<yellow>y", ColorUtil.translate("&ag&9b&ey"));
    }

    @Test
    void translatesFormattingCodes() {
        assertEquals("<obfuscated>x", ColorUtil.translate("&kx"));
        assertEquals("<italic>i", ColorUtil.translate("&oi"));
        assertEquals("<reset>r", ColorUtil.translate("&rr"));
        assertEquals("<strikethrough>s<underlined>u",
                ColorUtil.translate("&ms&nu"));
    }

    @Test
    void translatesHexCodes() {
        assertEquals("<color:#ff5555>Custom", ColorUtil.translate("&#ff5555Custom"));
    }

    @Test
    void hexCodeCaseInsensitive() {
        assertEquals("<color:#ABCDEF>x", ColorUtil.translate("&#ABCDEFx"));
    }

    @Test
    void uppercaseLegacyCodeIsTranslated() {
        assertEquals("<red>X", ColorUtil.translate("&CX"));
    }

    @Test
    void unknownCodePreservedAsIs() {
        assertEquals("&zfoo", ColorUtil.translate("&zfoo"));
    }

    @Test
    void mixedHexAndLegacyInSameString() {
        String out = ColorUtil.translate("&7prefix &#abcdef body &lbold");
        assertEquals("<gray>prefix <color:#abcdef> body <bold>bold", out);
    }

    @Test
    void plainTextPassThrough() {
        assertEquals("no codes here", ColorUtil.translate("no codes here"));
    }
}
