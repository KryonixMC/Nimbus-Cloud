package dev.nimbuspowered.nimbus.sdk.compat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.block.Sign;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scoreboard.Team;

import java.util.List;

/**
 * Text compatibility layer that works on both modern Paper (Adventure) and legacy Spigot.
 * <p>
 * On Paper 1.16.5+: uses Adventure Component API for rich text.
 * On legacy Spigot (1.8.8+): uses ChatColor and legacy string-based methods.
 * <p>
 * All methods accept legacy-formatted text (using {@code &} color codes).
 */
public final class TextCompat {

    private TextCompat() {}

    /** Translate {@code &} color codes to section symbols. Works on all versions. */
    public static String colorize(String text) {
        if (text == null) return "";
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    // ── Player Messages ──────────────────────────────────────────────

    /** Send a legacy-colored message to a player. */
    public static void sendMessage(Player player, String legacyText) {
        if (VersionHelper.hasAdventure()) {
            AdventureHelper.sendMessage(player, colorize(legacyText));
        } else {
            player.sendMessage(colorize(legacyText));
        }
    }

    /**
     * Send a rich-colored message (color name, e.g. "red", "green", "gray").
     * On legacy servers: maps to nearest ChatColor.
     */
    public static void sendRich(Player player, String text, String colorName) {
        if (VersionHelper.hasAdventure()) {
            AdventureHelper.sendRich(player, text, colorName);
        } else {
            ChatColor color = mapColor(colorName);
            player.sendMessage(color + text);
        }
    }

    /**
     * Send a composite message with multiple colored parts.
     * Each part is a String[] of {text, colorName}.
     */
    public static void sendComposite(Player player, String[][] parts) {
        if (VersionHelper.hasAdventure()) {
            AdventureHelper.sendRichComposite(player, parts);
        } else {
            StringBuilder sb = new StringBuilder();
            for (String[] part : parts) {
                String colorName = part.length > 1 ? part[1] : "white";
                sb.append(mapColor(colorName)).append(part[0]);
            }
            player.sendMessage(sb.toString());
        }
    }

    // ── Signs ────────────────────────────────────────────────────────

    /** Set a sign line with legacy color codes. Works on all versions. */
    public static void setSignLine(Sign sign, int line, String legacyText) {
        if (VersionHelper.hasAdventure()) {
            AdventureHelper.setSignLine(sign, line, legacyText);
        } else {
            sign.setLine(line, colorize(legacyText));
        }
    }

    /** Set a sign line to empty. */
    public static void setSignLineEmpty(Sign sign, int line) {
        if (VersionHelper.hasAdventure()) {
            AdventureHelper.setSignLineEmpty(sign, line);
        } else {
            sign.setLine(line, "");
        }
    }

    // ── Entities ─────────────────────────────────────────────────────

    /** Set custom name on an entity with legacy color codes. */
    public static void setCustomName(Entity entity, String legacyText) {
        if (VersionHelper.hasAdventure()) {
            AdventureHelper.setCustomName(entity, legacyText);
        } else {
            entity.setCustomName(colorize(legacyText));
        }
    }

    // ── Scoreboards ──────────────────────────────────────────────────

    /** Set team prefix with legacy color codes. */
    @SuppressWarnings("deprecation")
    public static void setTeamPrefix(Team team, String legacyText) {
        if (VersionHelper.hasAdventure()) {
            AdventureHelper.setTeamPrefix(team, legacyText);
        } else {
            String colored = colorize(legacyText);
            // Legacy team prefix/suffix max 16 chars
            if (colored.length() > 16) colored = colored.substring(0, 16);
            team.setPrefix(colored);
        }
    }

    /** Set team suffix with legacy color codes. */
    @SuppressWarnings("deprecation")
    public static void setTeamSuffix(Team team, String legacyText) {
        if (VersionHelper.hasAdventure()) {
            AdventureHelper.setTeamSuffix(team, legacyText);
        } else {
            String colored = colorize(legacyText);
            if (colored.length() > 16) colored = colored.substring(0, 16);
            team.setSuffix(colored);
        }
    }

    // ── Inventories ──────────────────────────────────────────────────

    /** Create an inventory with a legacy-colored title. */
    @SuppressWarnings("deprecation")
    public static Inventory createInventory(InventoryHolder holder, int size, String legacyTitle) {
        if (VersionHelper.hasAdventure()) {
            return AdventureHelper.createInventory(holder, size, legacyTitle);
        } else {
            return Bukkit.createInventory(holder, size, colorize(legacyTitle));
        }
    }

    // ── Item Meta ────────────────────────────────────────────────────

    /** Set item display name with legacy color codes. */
    @SuppressWarnings("deprecation")
    public static void setItemDisplayName(ItemMeta meta, String legacyText) {
        if (VersionHelper.hasAdventure()) {
            AdventureHelper.setItemDisplayName(meta, legacyText);
        } else {
            meta.setDisplayName(colorize(legacyText));
        }
    }

    /** Set item lore with legacy color codes. */
    @SuppressWarnings("deprecation")
    public static void setItemLore(ItemMeta meta, List<String> legacyLines) {
        if (VersionHelper.hasAdventure()) {
            AdventureHelper.setItemLore(meta, legacyLines);
        } else {
            List<String> colored = new java.util.ArrayList<>();
            for (String line : legacyLines) {
                colored.add(colorize(line));
            }
            meta.setLore(colored);
        }
    }

    // ── ActionBar ────────────────────────────────────────────────────

    /** Send an action bar message with legacy color codes. */
    @SuppressWarnings("deprecation")
    public static void sendActionBar(Player player, String legacyText) {
        if (VersionHelper.hasAdventure()) {
            AdventureHelper.sendActionBar(player, legacyText);
        } else {
            // Fallback: send as chat message on legacy servers
            player.sendMessage(colorize(legacyText));
        }
    }

    // ── Title ───────────────────────────────────────────────────────

    /**
     * Send a title and subtitle to a player.
     * @param fadeIn  fade-in duration in ticks
     * @param stay    stay duration in ticks
     * @param fadeOut fade-out duration in ticks
     */
    @SuppressWarnings("deprecation")
    public static void sendTitle(Player player, String title, String subtitle,
                                  int fadeIn, int stay, int fadeOut) {
        if (VersionHelper.hasAdventure()) {
            AdventureHelper.sendTitle(player, title, subtitle, fadeIn, stay, fadeOut);
        } else {
            player.sendTitle(colorize(title), colorize(subtitle), fadeIn, stay, fadeOut);
        }
    }

    // ── Color Mapping ────────────────────────────────────────────────

    private static ChatColor mapColor(String name) {
        if (name == null) return ChatColor.WHITE;
        return switch (name.toLowerCase()) {
            case "red" -> ChatColor.RED;
            case "dark_red" -> ChatColor.DARK_RED;
            case "green" -> ChatColor.GREEN;
            case "dark_green" -> ChatColor.DARK_GREEN;
            case "blue" -> ChatColor.BLUE;
            case "dark_blue" -> ChatColor.DARK_BLUE;
            case "aqua" -> ChatColor.AQUA;
            case "dark_aqua" -> ChatColor.DARK_AQUA;
            case "yellow" -> ChatColor.YELLOW;
            case "gold" -> ChatColor.GOLD;
            case "gray", "grey" -> ChatColor.GRAY;
            case "dark_gray", "dark_grey" -> ChatColor.DARK_GRAY;
            case "light_purple" -> ChatColor.LIGHT_PURPLE;
            case "dark_purple" -> ChatColor.DARK_PURPLE;
            case "black" -> ChatColor.BLACK;
            default -> ChatColor.WHITE;
        };
    }
}
