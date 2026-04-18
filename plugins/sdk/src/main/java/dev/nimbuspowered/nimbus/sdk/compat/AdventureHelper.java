package dev.nimbuspowered.nimbus.sdk.compat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.block.Sign;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.List;

/**
 * Adventure API implementations — only loaded on Paper 1.16.5+ where Adventure is available.
 * Never reference this class directly; always go through {@link TextCompat}.
 */
class AdventureHelper {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final LegacyComponentSerializer LEGACY_AMP = LegacyComponentSerializer.legacyAmpersand();

    static void sendMessage(Player player, String coloredText) {
        player.sendMessage(LEGACY.deserialize(coloredText));
    }

    static void sendRich(Player player, String text, String colorName) {
        NamedTextColor color = NamedTextColor.NAMES.value(colorName);
        if (color == null) color = NamedTextColor.WHITE;
        player.sendMessage(Component.text(text, color));
    }

    static void sendRichComposite(Player player, String[][] parts) {
        Component msg = Component.empty();
        for (String[] part : parts) {
            String text = part[0];
            String colorName = part.length > 1 ? part[1] : "white";
            NamedTextColor color = NamedTextColor.NAMES.value(colorName);
            if (color == null) color = NamedTextColor.WHITE;
            msg = msg.append(Component.text(text, color));
        }
        player.sendMessage(msg);
    }

    static void setSignLine(Sign sign, int line, String legacyText) {
        sign.line(line, LEGACY_AMP.deserialize(legacyText));
    }

    static void setSignLineEmpty(Sign sign, int line) {
        sign.line(line, Component.empty());
    }

    static void setCustomName(Entity entity, String legacyText) {
        entity.customName(LEGACY_AMP.deserialize(legacyText));
    }

    static void setTeamPrefix(Team team, String legacyText) {
        team.prefix(LEGACY_AMP.deserialize(legacyText));
    }

    static void setTeamSuffix(Team team, String legacyText) {
        team.suffix(LEGACY_AMP.deserialize(legacyText));
    }

    static Inventory createInventory(InventoryHolder holder, int size, String legacyTitle) {
        return Bukkit.createInventory(holder, size, LEGACY_AMP.deserialize(legacyTitle));
    }

    static void setItemDisplayName(ItemMeta meta, String legacyText) {
        meta.displayName(LEGACY_AMP.deserialize(legacyText));
    }

    static void setItemLore(ItemMeta meta, List<String> legacyLines) {
        List<Component> lore = new ArrayList<>();
        for (String line : legacyLines) {
            lore.add(LEGACY_AMP.deserialize(line));
        }
        meta.lore(lore);
    }

    static void sendActionBar(Player player, String legacyText) {
        player.sendActionBar(LEGACY_AMP.deserialize(legacyText));
    }

    static void sendClickableLink(Player player, String template, String clickLabel, String url, String hover) {
        Component clickable = LEGACY_AMP.deserialize(clickLabel)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(url));
        if (hover != null && !hover.isEmpty()) {
            clickable = clickable.hoverEvent(
                net.kyori.adventure.text.event.HoverEvent.showText(LEGACY_AMP.deserialize(hover))
            );
        }

        int placeholderIdx = template.indexOf("{click}");
        Component msg;
        if (placeholderIdx >= 0) {
            Component before = LEGACY_AMP.deserialize(template.substring(0, placeholderIdx));
            Component after = LEGACY_AMP.deserialize(template.substring(placeholderIdx + "{click}".length()));
            msg = Component.empty().append(before).append(clickable).append(after);
        } else {
            msg = LEGACY_AMP.deserialize(template).append(Component.text(" ")).append(clickable);
        }
        player.sendMessage(msg);
    }

    static void sendTitle(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        net.kyori.adventure.title.Title.Times times = net.kyori.adventure.title.Title.Times.times(
                java.time.Duration.ofMillis(fadeIn * 50L),
                java.time.Duration.ofMillis(stay * 50L),
                java.time.Duration.ofMillis(fadeOut * 50L)
        );
        player.showTitle(net.kyori.adventure.title.Title.title(
                LEGACY_AMP.deserialize(title),
                LEGACY_AMP.deserialize(subtitle),
                times
        ));
    }
}
