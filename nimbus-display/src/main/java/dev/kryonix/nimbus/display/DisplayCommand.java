package dev.kryonix.nimbus.display;

import dev.kryonix.nimbus.sdk.Nimbus;
import dev.kryonix.nimbus.sdk.NimbusService;
import dev.kryonix.nimbus.sdk.RoutingStrategy;
import dev.kryonix.nimbus.sdk.compat.TextCompat;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Unified /ndisplay command.
 *
 * <pre>
 * /ndisplay sign set &lt;target&gt; [strategy]
 * /ndisplay sign remove
 * /ndisplay npc set &lt;target&gt; [strategy] [type] [skin]
 * /ndisplay npc remove
 * /ndisplay npc edit &lt;property&gt; &lt;value...&gt;
 * /ndisplay list
 * /ndisplay reload
 * </pre>
 */
public class DisplayCommand implements CommandExecutor, TabCompleter {

    private final SignManager signManager;
    private final NpcManager npcManager;

    public DisplayCommand(SignManager signManager, NpcManager npcManager) {
        this.signManager = signManager;
        this.npcManager = npcManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }
        if (args.length == 0) { sendHelp(player); return true; }

        switch (args[0].toLowerCase()) {
            case "sign" -> handleSign(player, drop(args));
            case "npc" -> handleNpc(player, drop(args));
            case "list" -> handleList(player);
            case "reload" -> handleReload(player);
            default -> sendHelp(player);
        }
        return true;
    }

    // ── Sign ──────────────────────────────────────────────────────────

    private void handleSign(Player player, String[] args) {
        if (args.length == 0) { sendSignHelp(player); return; }
        switch (args[0].toLowerCase()) {
            case "set" -> handleSignSet(player, drop(args));
            case "remove" -> handleSignRemove(player);
            default -> sendSignHelp(player);
        }
    }

    private void handleSignSet(Player player, String[] args) {
        if (!perm(player, "nimbus.display.sign")) return;
        if (args.length < 1) { msg(player, "&cUsage: /ndisplay sign set <target> [strategy]"); return; }

        Block block = player.getTargetBlockExact(5);
        if (block == null || !(block.getState() instanceof Sign)) { msg(player, "&cLook at a sign first!"); return; }

        String target = args[0];
        boolean isService = !Nimbus.cache().getGroupNames().contains(target);
        if (isService && Nimbus.cache().get(target) == null) { msg(player, "&cUnknown group or service: " + target); return; }

        String groupName = isService ? target.replaceAll("-\\d+$", "") : target;
        if (!signManager.hasDisplay(groupName)) { msg(player, "&cNo display config for " + groupName); return; }

        RoutingStrategy strategy = !isService && args.length >= 2 ? parseStrategy(args[1]) : RoutingStrategy.LEAST_PLAYERS;
        var loc = block.getLocation();
        String id = target.toLowerCase() + "-" + loc.getBlockX() + "-" + loc.getBlockY() + "-" + loc.getBlockZ();
        signManager.addSign(new NimbusSign(id, loc, target, isService, strategy));
        msg(player, "&aSign set for &f" + target);
    }

    private void handleSignRemove(Player player) {
        if (!perm(player, "nimbus.display.sign")) return;
        Block block = player.getTargetBlockExact(5);
        if (block == null || !(block.getState() instanceof Sign)) { msg(player, "&cLook at a sign first!"); return; }
        NimbusSign nSign = signManager.getSign(block.getLocation());
        if (nSign == null) { msg(player, "&cNot a Nimbus sign."); return; }
        signManager.removeSign(block.getLocation());
        msg(player, "&eSign removed: &f" + nSign.target());
    }

    // ── NPC ───────────────────────────────────────────────────────────

    private void handleNpc(Player player, String[] args) {
        if (args.length == 0) { sendNpcHelp(player); return; }
        switch (args[0].toLowerCase()) {
            case "set" -> handleNpcSet(player, drop(args));
            case "remove" -> handleNpcRemove(player);
            case "edit" -> handleNpcEdit(player, drop(args));
            case "info" -> handleNpcInfo(player);
            default -> sendNpcHelp(player);
        }
    }

    private void handleNpcSet(Player player, String[] args) {
        if (!perm(player, "nimbus.display.npc")) return;
        if (args.length < 1) { msg(player, "&cUsage: /ndisplay npc set <target> [strategy] [type] [skin]"); return; }

        String target = args[0];
        boolean isService = !Nimbus.cache().getGroupNames().contains(target);
        if (isService && Nimbus.cache().get(target) == null) { msg(player, "&cUnknown: " + target); return; }

        String groupName = isService ? target.replaceAll("-\\d+$", "") : target;
        if (!npcManager.hasDisplay(groupName)) { msg(player, "&cNo display config for " + groupName); return; }

        RoutingStrategy strategy = !isService && args.length >= 2 ? parseStrategy(args[1]) : RoutingStrategy.LEAST_PLAYERS;

        EntityType entityType = EntityType.VILLAGER;
        String skin = null;
        if (args.length >= 3) {
            String typeArg = args[2].toUpperCase();
            if (typeArg.equals("PLAYER")) {
                entityType = EntityType.PLAYER;
                skin = args.length >= 4 ? args[3] : null;
            } else {
                try { entityType = EntityType.valueOf(typeArg); } catch (IllegalArgumentException e) {
                    msg(player, "&cUnknown entity type: " + args[2]); return;
                }
            }
        }

        var loc = player.getLocation();
        String id = target.toLowerCase() + "-npc-" + (int) loc.getX() + "-" + (int) loc.getY() + "-" + (int) loc.getZ();
        List<String> hologram = List.of("&b&l" + target, "&7{players}/{max_players} online", "&7{state}");

        npcManager.addNpc(new NimbusNpc(id, loc, target, isService, strategy, entityType,
                skin, true, NpcAction.CONNECT, NpcAction.INVENTORY, null, null, hologram, "true", Map.of(), false, null));

        String typeLabel = entityType == EntityType.PLAYER
                ? "player" + (skin != null ? ", skin: " + skin : "") : entityType.name().toLowerCase();
        msg(player, "&aNPC set for &f" + target + " &7(" + typeLabel + ")");
    }

    private void handleNpcRemove(Player player) {
        if (!perm(player, "nimbus.display.npc")) return;
        NimbusNpc npc = npcManager.getNearestNpc(player.getLocation(), 5.0);
        if (npc == null) { msg(player, "&cNo NPC within 5 blocks."); return; }
        npcManager.removeNpc(npc.id());
        msg(player, "&eNPC removed: &f" + npc.target());
    }

    // ── NPC Edit (live update + save) ─────────────────────────────────

    private void handleNpcEdit(Player player, String[] args) {
        if (!perm(player, "nimbus.display.npc")) return;
        if (args.length < 2) { sendEditHelp(player); return; }

        NimbusNpc npc = npcManager.getNearestNpc(player.getLocation(), 5.0);
        if (npc == null) { msg(player, "&cNo NPC within 5 blocks."); return; }

        String prop = args[0].toLowerCase();
        String value = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        NimbusNpc updated = switch (prop) {
            case "type" -> {
                EntityType type;
                try { type = EntityType.valueOf(value.toUpperCase()); } catch (IllegalArgumentException e) {
                    msg(player, "&cUnknown entity type: " + value); yield null;
                }
                yield new NimbusNpc(npc.id(), npc.location(), npc.target(), npc.serviceTarget(),
                        npc.strategy(), type, npc.skin(), npc.lookAtPlayer(),
                        npc.leftClick(), npc.rightClick(), npc.leftClickValue(), npc.rightClickValue(),
                        npc.hologramLines(), npc.floatingItem(), npc.equipment(), npc.burning(), npc.pose());
            }
            case "skin" -> new NimbusNpc(npc.id(), npc.location(), npc.target(), npc.serviceTarget(),
                    npc.strategy(), npc.entityType(), value, npc.lookAtPlayer(),
                    npc.leftClick(), npc.rightClick(), npc.leftClickValue(), npc.rightClickValue(),
                    npc.hologramLines(), npc.floatingItem(), npc.equipment(), npc.burning(), npc.pose());
            case "target" -> {
                boolean isService = !Nimbus.cache().getGroupNames().contains(value);
                yield new NimbusNpc(npc.id(), npc.location(), value, isService,
                        npc.strategy(), npc.entityType(), npc.skin(), npc.lookAtPlayer(),
                        npc.leftClick(), npc.rightClick(), npc.leftClickValue(), npc.rightClickValue(),
                        npc.hologramLines(), npc.floatingItem(), npc.equipment(), npc.burning(), npc.pose());
            }
            case "strategy" -> new NimbusNpc(npc.id(), npc.location(), npc.target(), npc.serviceTarget(),
                    parseStrategy(value), npc.entityType(), npc.skin(), npc.lookAtPlayer(),
                    npc.leftClick(), npc.rightClick(), npc.leftClickValue(), npc.rightClickValue(),
                    npc.hologramLines(), npc.floatingItem(), npc.equipment(), npc.burning(), npc.pose());
            case "lookat" -> new NimbusNpc(npc.id(), npc.location(), npc.target(), npc.serviceTarget(),
                    npc.strategy(), npc.entityType(), npc.skin(), Boolean.parseBoolean(value),
                    npc.leftClick(), npc.rightClick(), npc.leftClickValue(), npc.rightClickValue(),
                    npc.hologramLines(), npc.floatingItem(), npc.equipment(), npc.burning(), npc.pose());
            case "left_click", "leftclick" -> {
                NpcAction action; String actionValue = null;
                String[] parts = value.split(" ", 2);
                try { action = NpcAction.valueOf(parts[0].toUpperCase()); } catch (IllegalArgumentException e) {
                    msg(player, "&cUnknown action: " + parts[0]); yield null;
                }
                if (parts.length > 1) actionValue = parts[1];
                yield new NimbusNpc(npc.id(), npc.location(), npc.target(), npc.serviceTarget(),
                        npc.strategy(), npc.entityType(), npc.skin(), npc.lookAtPlayer(),
                        action, npc.rightClick(), actionValue, npc.rightClickValue(),
                        npc.hologramLines(), npc.floatingItem(), npc.equipment(), npc.burning(), npc.pose());
            }
            case "right_click", "rightclick" -> {
                NpcAction action; String actionValue = null;
                String[] parts = value.split(" ", 2);
                try { action = NpcAction.valueOf(parts[0].toUpperCase()); } catch (IllegalArgumentException e) {
                    msg(player, "&cUnknown action: " + parts[0]); yield null;
                }
                if (parts.length > 1) actionValue = parts[1];
                yield new NimbusNpc(npc.id(), npc.location(), npc.target(), npc.serviceTarget(),
                        npc.strategy(), npc.entityType(), npc.skin(), npc.lookAtPlayer(),
                        npc.leftClick(), action, npc.leftClickValue(), actionValue,
                        npc.hologramLines(), npc.floatingItem(), npc.equipment(), npc.burning(), npc.pose());
            }
            case "hologram" -> {
                List<String> lines;
                if (value.equalsIgnoreCase("clear")) {
                    lines = List.of();
                } else {
                    lines = List.of(value.split("\\|"));
                }
                yield new NimbusNpc(npc.id(), npc.location(), npc.target(), npc.serviceTarget(),
                        npc.strategy(), npc.entityType(), npc.skin(), npc.lookAtPlayer(),
                        npc.leftClick(), npc.rightClick(), npc.leftClickValue(), npc.rightClickValue(),
                        lines, npc.floatingItem(), npc.equipment(), npc.burning(), npc.pose());
            }
            case "floating_item", "floatingitem" -> {
                String fi = value.equalsIgnoreCase("false") || value.equalsIgnoreCase("off")
                        || value.equalsIgnoreCase("none") ? null : value;
                yield new NimbusNpc(npc.id(), npc.location(), npc.target(), npc.serviceTarget(),
                        npc.strategy(), npc.entityType(), npc.skin(), npc.lookAtPlayer(),
                        npc.leftClick(), npc.rightClick(), npc.leftClickValue(), npc.rightClickValue(),
                        npc.hologramLines(), fi, npc.equipment(), npc.burning(), npc.pose());
            }
            case "mainhand", "offhand", "head", "helmet", "chest", "chestplate", "legs", "leggings", "feet", "boots" -> {
                String slot = switch (prop) {
                    case "helmet" -> "head";
                    case "chestplate" -> "chest";
                    case "leggings" -> "legs";
                    case "boots" -> "feet";
                    default -> prop;
                };
                var eq = new java.util.HashMap<>(npc.equipment() != null ? npc.equipment() : Map.of());
                if (value.equalsIgnoreCase("none") || value.equalsIgnoreCase("clear")) {
                    eq.remove(slot);
                } else {
                    eq.put(slot, value.toUpperCase());
                }
                yield new NimbusNpc(npc.id(), npc.location(), npc.target(), npc.serviceTarget(),
                        npc.strategy(), npc.entityType(), npc.skin(), npc.lookAtPlayer(),
                        npc.leftClick(), npc.rightClick(), npc.leftClickValue(), npc.rightClickValue(),
                        npc.hologramLines(), npc.floatingItem(), Map.copyOf(eq), npc.burning(), npc.pose());
            }
            case "burning" -> new NimbusNpc(npc.id(), npc.location(), npc.target(), npc.serviceTarget(),
                    npc.strategy(), npc.entityType(), npc.skin(), npc.lookAtPlayer(),
                    npc.leftClick(), npc.rightClick(), npc.leftClickValue(), npc.rightClickValue(),
                    npc.hologramLines(), npc.floatingItem(), npc.equipment(),
                    Boolean.parseBoolean(value), npc.pose());
            case "pose" -> new NimbusNpc(npc.id(), npc.location(), npc.target(), npc.serviceTarget(),
                    npc.strategy(), npc.entityType(), npc.skin(), npc.lookAtPlayer(),
                    npc.leftClick(), npc.rightClick(), npc.leftClickValue(), npc.rightClickValue(),
                    npc.hologramLines(), npc.floatingItem(), npc.equipment(), npc.burning(),
                    value.equalsIgnoreCase("none") || value.equalsIgnoreCase("standing") ? null : value.toLowerCase());
            case "pos", "position", "location" -> {
                yield new NimbusNpc(npc.id(), player.getLocation(), npc.target(), npc.serviceTarget(),
                        npc.strategy(), npc.entityType(), npc.skin(), npc.lookAtPlayer(),
                        npc.leftClick(), npc.rightClick(), npc.leftClickValue(), npc.rightClickValue(),
                        npc.hologramLines(), npc.floatingItem(), npc.equipment(), npc.burning(), npc.pose());
            }
            default -> { msg(player, "&cUnknown property: " + prop); sendEditHelp(player); yield null; }
        };

        if (updated == null) return;

        npcManager.updateNpc(npc.id(), updated);
        msg(player, "&aNPC updated: &7" + prop + " &f→ " + value);
    }

    // ── NPC Info ──────────────────────────────────────────────────────

    private void handleNpcInfo(Player player) {
        if (!perm(player, "nimbus.display.npc")) return;
        NimbusNpc npc = npcManager.getNearestNpc(player.getLocation(), 5.0);
        if (npc == null) { msg(player, "&cNo NPC within 5 blocks."); return; }

        msg(player, "&bNPC: &f" + npc.id());
        msg(player, "&7  target: &f" + npc.target());
        msg(player, "&7  type: &f" + npc.entityType().name().toLowerCase());
        if (npc.skin() != null) msg(player, "&7  skin: &f" + npc.skin());
        msg(player, "&7  strategy: &f" + npc.strategy().name().toLowerCase());
        msg(player, "&7  lookat: &f" + npc.lookAtPlayer());
        msg(player, "&7  left_click: &f" + npc.leftClick().name()
                + (npc.leftClickValue() != null ? " " + npc.leftClickValue() : ""));
        msg(player, "&7  right_click: &f" + npc.rightClick().name()
                + (npc.rightClickValue() != null ? " " + npc.rightClickValue() : ""));
        if (npc.hologramLines() != null && !npc.hologramLines().isEmpty())
            msg(player, "&7  hologram: &f" + String.join(" | ", npc.hologramLines()));
        msg(player, "&7  floating_item: &f" + (npc.floatingItem() != null ? npc.floatingItem() : "off"));
        if (npc.equipment() != null && !npc.equipment().isEmpty()) {
            for (var entry : npc.equipment().entrySet()) {
                msg(player, "&7  " + entry.getKey() + ": &f" + entry.getValue());
            }
        }
        msg(player, "&7  burning: &f" + npc.burning());
        if (npc.pose() != null)
            msg(player, "&7  pose: &f" + npc.pose());
        var loc = npc.location();
        msg(player, "&7  location: &f" + String.format("%.1f %.1f %.1f (%.0f/%.0f)",
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()));
    }

    // ── List / Reload ─────────────────────────────────────────────────

    private void handleList(Player player) {
        if (!perm(player, "nimbus.display.list")) return;
        var signs = signManager.getSigns();
        var npcs = npcManager.getNpcs();

        msg(player, "&bNimbus Display (" + signs.size() + " signs, " + npcs.size() + " NPCs)");

        for (NimbusSign sign : signs) {
            var loc = sign.location();
            msg(player, "&7  [S] " + sign.id() + " → &a" + sign.target()
                    + "&7 @ " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ());
        }
        for (NimbusNpc npc : npcs) {
            msg(player, "&7  [N] " + npc.id() + " → &a" + npc.target()
                    + "&7 [" + npc.entityType().name().toLowerCase() + "] L:" + npc.leftClick() + " R:" + npc.rightClick());
        }
        if (signs.isEmpty() && npcs.isEmpty()) msg(player, "&7  No signs or NPCs configured.");
    }

    private void handleReload(Player player) {
        if (!perm(player, "nimbus.display.reload")) return;
        signManager.reload();
        npcManager.reload();
        msg(player, "&aReloaded " + signManager.getSignCount() + " sign(s), " + npcManager.getNpcCount() + " NPC(s).");
    }

    // ── Help ──────────────────────────────────────────────────────────

    private void sendHelp(Player p) {
        msg(p, "&b&lNimbus Display");
        msg(p, "&f  /ndisplay sign set <target> [strategy]&7 — set sign");
        msg(p, "&f  /ndisplay sign remove&7 — remove sign");
        msg(p, "&f  /ndisplay npc set <target> [strategy] [type] [skin]&7 — spawn NPC");
        msg(p, "&f  /ndisplay npc remove&7 — remove nearest NPC");
        msg(p, "&f  /ndisplay npc edit <property> <value>&7 — live edit NPC");
        msg(p, "&f  /ndisplay npc info&7 — show NPC properties");
        msg(p, "&f  /ndisplay list&7 — list all");
        msg(p, "&f  /ndisplay reload&7 — reload config");
    }

    private void sendSignHelp(Player p) {
        msg(p, "&f  /ndisplay sign set <target> [strategy]");
        msg(p, "&f  /ndisplay sign remove");
    }

    private void sendNpcHelp(Player p) {
        msg(p, "&bNPC Commands:");
        msg(p, "&f  /ndisplay npc set <target> [strategy] [type] [skin]");
        msg(p, "&f  /ndisplay npc remove");
        msg(p, "&f  /ndisplay npc edit <property> <value>");
        msg(p, "&f  /ndisplay npc info");
    }

    private void sendEditHelp(Player p) {
        msg(p, "&bEditable properties:");
        msg(p, "&7  type &f<PLAYER|VILLAGER|ZOMBIE|...>");
        msg(p, "&7  skin &f<player_name>");
        msg(p, "&7  target &f<group|service>");
        msg(p, "&7  strategy &f<least|fill|random>");
        msg(p, "&7  lookat &f<true|false>");
        msg(p, "&7  left_click &f<CONNECT|COMMAND|INVENTORY|NONE> [value]");
        msg(p, "&7  right_click &f<CONNECT|COMMAND|INVENTORY|NONE> [value]");
        msg(p, "&7  hologram &f<Line1|Line2|Line3> or 'clear'");
        msg(p, "&7  floating_item &f<true|MATERIAL|off> (true=from display config)");
        msg(p, "&7  mainhand/offhand/head/chest/legs/feet &f<MATERIAL|none>");
        msg(p, "&7  burning &f<true|false>");
        msg(p, "&7  pose &f<crouching|sleeping|swimming|spin_attack|none>");
        msg(p, "&7  position &fhere");
    }

    // ── Tab Complete ──────────────────────────────────────────────────

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) return filter(List.of("sign", "npc", "list", "reload"), args[0]);

        if (args.length == 2) return switch (args[0].toLowerCase()) {
            case "sign" -> filter(List.of("set", "remove"), args[1]);
            case "npc" -> filter(List.of("set", "remove", "edit", "info"), args[1]);
            default -> List.of();
        };

        if (args.length == 3 && args[1].equalsIgnoreCase("set")) {
            List<String> s = new ArrayList<>(Nimbus.cache().getGroupNames());
            try { for (NimbusService sv : Nimbus.services()) s.add(sv.getName()); } catch (Exception ignored) {}
            return filter(s, args[2]);
        }

        if (args.length == 4 && args[1].equalsIgnoreCase("set")) {
            if (Nimbus.cache().getGroupNames().contains(args[2]))
                return filter(List.of("least", "fill", "random"), args[3]);
        }

        if (args.length == 5 && args[0].equalsIgnoreCase("npc") && args[1].equalsIgnoreCase("set"))
            return filter(List.of("PLAYER", "VILLAGER", "ZOMBIE", "SKELETON", "ARMOR_STAND", "PILLAGER", "IRON_GOLEM", "ALLAY"), args[4]);

        if (args.length == 3 && args[0].equalsIgnoreCase("npc") && args[1].equalsIgnoreCase("edit"))
            return filter(List.of("type", "skin", "target", "strategy", "lookat", "left_click", "right_click", "hologram", "floating_item", "mainhand", "offhand", "head", "chest", "legs", "feet", "burning", "pose", "position"), args[2]);

        if (args.length == 4 && args[0].equalsIgnoreCase("npc") && args[1].equalsIgnoreCase("edit")) {
            return switch (args[2].toLowerCase()) {
                case "type" -> filter(List.of("PLAYER", "VILLAGER", "ZOMBIE", "SKELETON", "ARMOR_STAND", "PILLAGER", "IRON_GOLEM"), args[3]);
                case "strategy" -> filter(List.of("least", "fill", "random"), args[3]);
                case "lookat" -> filter(List.of("true", "false"), args[3]);
                case "left_click", "leftclick", "right_click", "rightclick" -> filter(List.of("CONNECT", "COMMAND", "INVENTORY", "NONE"), args[3]);
                case "floating_item", "floatingitem" -> filter(List.of("true", "off", "RED_BED", "DIAMOND_SWORD", "NETHER_STAR"), args[3]);
                case "mainhand", "offhand", "head", "helmet", "chest", "chestplate", "legs", "leggings", "feet", "boots" ->
                        filter(List.of("DIAMOND_SWORD", "IRON_SWORD", "BOW", "DIAMOND_HELMET", "DIAMOND_CHESTPLATE", "DIAMOND_LEGGINGS", "DIAMOND_BOOTS", "SHIELD", "none"), args[3]);
                case "burning" -> filter(List.of("true", "false"), args[3]);
                case "pose" -> filter(List.of("crouching", "sleeping", "swimming", "spin_attack", "sitting", "none"), args[3]);
                case "position", "pos", "location" -> filter(List.of("here"), args[3]);
                case "hologram" -> filter(List.of("clear"), args[3]);
                default -> List.of();
            };
        }

        return List.of();
    }

    // ── Utilities ─────────────────────────────────────────────────────

    private static void msg(Player player, String legacyText) {
        TextCompat.sendMessage(player, legacyText);
    }

    private static RoutingStrategy parseStrategy(String s) {
        return switch (s.toLowerCase()) {
            case "fill", "fill_first" -> RoutingStrategy.FILL_FIRST;
            case "random" -> RoutingStrategy.RANDOM;
            default -> RoutingStrategy.LEAST_PLAYERS;
        };
    }

    private static String[] drop(String[] a) {
        return a.length <= 1 ? new String[0] : Arrays.copyOfRange(a, 1, a.length);
    }

    private static List<String> filter(List<String> opts, String prefix) {
        String l = prefix.toLowerCase();
        return opts.stream().filter(s -> s.toLowerCase().startsWith(l)).toList();
    }

    private static boolean perm(Player p, String permission) {
        if (p.hasPermission(permission)) return true;
        msg(p, "&cNo permission.");
        return false;
    }
}
