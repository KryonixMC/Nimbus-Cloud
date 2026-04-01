package dev.kryonix.nimbus.perms.display;

import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Legacy chat handler using Bukkit's AsyncPlayerChatEvent.
 * Works on all Spigot/Paper versions from 1.8+ to latest.
 */
@SuppressWarnings("deprecation")
class LegacyChatHandler implements Listener {

    private final ChatRenderer chatRenderer;

    LegacyChatHandler(ChatRenderer chatRenderer) {
        this.chatRenderer = chatRenderer;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        String formatted = chatRenderer.formatChat(event.getPlayer(), event.getMessage());
        if (formatted == null) return;

        String colored = ChatColor.translateAlternateColorCodes('&', formatted);
        // Escape % signs that aren't format specifiers
        event.setFormat(colored.replace("%", "%%"));
    }
}
