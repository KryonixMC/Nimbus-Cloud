package dev.nimbuspowered.nimbus.sdk.compat;

import org.bukkit.ChatColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextCompatTest {

    @Test
    void colorizeTranslatesAmpersandCodes() {
        String out = TextCompat.colorize("&cred &ayellow");
        assertEquals(ChatColor.RED + "red " + ChatColor.GREEN + "yellow", out);
    }

    @Test
    void colorizeNullReturnsEmpty() {
        assertEquals("", TextCompat.colorize(null));
    }

    @Test
    void colorizeLeavesPlainTextUnchanged() {
        assertEquals("plain text", TextCompat.colorize("plain text"));
    }

    @Test
    void colorizePreservesSectionSymbols() {
        // &k..&r sequence -> section symbols
        String out = TextCompat.colorize("&lbold&r");
        assertEquals(ChatColor.BOLD + "bold" + ChatColor.RESET, out);
    }
}
