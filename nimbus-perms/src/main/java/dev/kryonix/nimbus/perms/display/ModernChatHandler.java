package dev.kryonix.nimbus.perms.display;

import dev.kryonix.nimbus.sdk.ColorUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Modern chat handler using Paper's AsyncChatEvent and Adventure API.
 * Only loaded on Paper 1.16.5+ — never referenced directly on legacy servers.
 */
class ModernChatHandler implements Listener, io.papermc.paper.chat.ChatRenderer {

    private final ChatRenderer chatRenderer;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    ModernChatHandler(ChatRenderer chatRenderer) {
        this.chatRenderer = chatRenderer;
    }

    void register(JavaPlugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (!chatRenderer.isChatEnabled()) return;
        event.renderer(this);
    }

    @Override
    public @NotNull Component render(@NotNull Player source, @NotNull Component sourceDisplayName,
                                      @NotNull Component message, @NotNull Audience viewer) {
        String plainMessage = PlainTextComponentSerializer.plainText().serialize(message);
        String formatted = chatRenderer.formatChat(source, plainMessage);
        if (formatted == null) return message;
        return miniMessage.deserialize(ColorUtil.translate(formatted));
    }
}
