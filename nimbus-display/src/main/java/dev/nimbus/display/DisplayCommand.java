package dev.nimbus.display;

import dev.nimbus.sdk.Nimbus;
import dev.nimbus.sdk.NimbusService;
import dev.nimbus.sdk.RoutingStrategy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
        if (args.length < 1) { player.sendMessage(err("Usage: /ndisplay sign set <target> [strategy]")); return; }

        Block block = player.getTargetBlockExact(5);
        if (block == null || !(block.getState() instanceof Sign)) { player.sendMessage(err("Look at a sign first!")); return; }

        String target = args[0];
        boolean isService = !Nimbus.cache().getGroupNames().contains(target);
        if (isService && Nimbus.cache().get(target) == null) { player.sendMessage(err("Unknown group or service: " + target)); return; }

        String groupName = isService ? target.replaceAll("-\\d+$", "") : target;
        if (!signManager.hasDisplay(groupName)) { player.sendMessage(err("No display config for " + groupName)); return; }

        RoutingStrategy strategy = !isService && args.length >= 2 ? parseStrategy(args[1]) : RoutingStrategy.LEAST_PLAYERS;
        var loc = block.getLocation();
        String id = target.toLowerCase() + "-" + loc.getBlockX() + "-" + loc.getBlockY() + "-" + loc.getBlockZ();
        signManager.addSign(new NimbusSign(id, loc, target, isService, strategy));
        player.sendMessage(ok("Sign set for ").append(white(target)));
    }

    private void handleSignRemove(Player player) {
        if (!perm(player, "nimbus.display.sign")) return;
        Block block = player.getTargetBlockExact(5);
        if (block == null || !(block.getState() instanceof Sign)) { player.sendMessage(err("Look at a sign first!")); return; }
        NimbusSign nSign = signManager.getSign(block.getLocation());
        if (nSign == null) { player.sendMessage(err("Not a Nimbus sign.")); return; }
        signManager.removeSign(block.getLocation());
        player.sendMessage(Component.text("Sign removed: ", NamedTextColor.YELLOW).append(white(nSign.target())));
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
        if (args.length < 1) { player.sendMessage(err("Usage: /ndisplay npc set <target> [strategy] [type] [skin]")); return; }

        String target = args[0];
        boolean isService = !Nimbus.cache().getGroupNames().contains(target);
        if (isService && Nimbus.cache().get(target) == null) { player.sendMessage(err("Unknown: " + target)); return; }

        String groupName = isService ? target.replaceAll("-\\d+$", "") : target;
        if (!npcManager.hasDisplay(groupName)) { player.sendMessage(err("No display config for " + groupName)); return; }

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
                    player.sendMessage(err("Unknown entity type: " + args[2])); return;
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
        player.sendMessage(ok("NPC set for ").append(white(target)).append(gray(" (" + typeLabel + ")")));
    }

    private void handleNpcRemove(Player player) {
        if (!perm(player, "nimbus.display.npc")) return;
        NimbusNpc npc = npcManager.getNearestNpc(player.getLocation(), 5.0);
        if (npc == null) { player.sendMessage(err("No NPC within 5 blocks.")); return; }
        npcManager.removeNpc(npc.id());
        player.sendMessage(Component.text("NPC removed: ", NamedTextColor.YELLOW).append(white(npc.target())));
    }

    // ── NPC Edit (live update + save) ─────────────────────────────────

    private void handleNpcEdit(Player player, String[] args) {
        if (!perm(player, "nimbus.display.npc")) return;
        if (args.length < 2) { sendEditHelp(player); return; }

        NimbusNpc npc = npcManager.getNearestNpc(player.getLocation(), 5.0);
        if (npc == null) { player.sendMessage(err("No NPC within 5 blocks.")); return; }

        String prop = args[0].toLowerCase();
        String value = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        NimbusNpc updated = switch (prop) {
            case "type" -> {
                EntityType type;
                try { type = EntityType.valueOf(value.toUpperCase()); } catch (IllegalArgumentException e) {
                    player.sendMessage(err("Unknown entity type: " + value)); yield null;
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
                    player.sendMessage(err("Unknown action: " + parts[0])); yield null;
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
                    player.sendMessage(err("Unknown action: " + parts[0])); yield null;
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
                    // Split on | for multiple lines: "Line 1|Line 2|Line 3"
                    lines = List.of(value.split("\\|"));
                }
                yield new NimbusNpc(npc.id(), npc.location(), npc.target(), npc.serviceTarget(),
                        npc.strategy(), npc.entityType(), npc.skin(), npc.lookAtPlayer(),
                        npc.leftClick(), npc.rightClick(), npc.leftClickValue(), npc.rightClickValue(),
                        lines, npc.floatingItem(), npc.equipment(), npc.burning(), npc.pose());
            }
            case "floating_item", "floatingitem" -> {
                // "false"/"off"/"none" = disable, "true" = display config material, "MATERIAL" = override
                String fi = value.equalsIgnoreCase("false") || value.equalsIgnoreCase("off")
                        || value.equalsIgnoreCase("none") ? null : value;
                yield new NimbusNpc(npc.id(), npc.location(), npc.target(), npc.serviceTarget(),
                        npc.strategy(), npc.entityType(), npc.skin(), npc.lookAtPlayer(),
                        npc.leftClick(), npc.rightClick(), npc.leftClickValue(), npc.rightClickValue(),
                        npc.hologramLines(), fi, npc.equipment(), npc.burning(), npc.pose());
            }
            case "mainhand", "offhand", "head", "helmet", "chest", "chestplate", "legs", "leggings", "feet", "boots" -> {
                // Equipment slot edit: /ndisplay npc edit mainhand DIAMOND_SWORD
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
                // Move NPC to player's current position
                yield new NimbusNpc(npc.id(), player.getLocation(), npc.target(), npc.serviceTarget(),
                        npc.strategy(), npc.entityType(), npc.skin(), npc.lookAtPlayer(),
                        npc.leftClick(), npc.rightClick(), npc.leftClickValue(), npc.rightClickValue(),
                        npc.hologramLines(), npc.floatingItem(), npc.equipment(), npc.burning(), npc.pose());
            }
            default -> { player.sendMessage(err("Unknown property: " + prop)); sendEditHelp(player); yield null; }
        };

        if (updated == null) return;

        npcManager.updateNpc(npc.id(), updated);
        player.sendMessage(ok("NPC updated: ").append(gray(prop)).append(white(" → " + value)));
    }

    // ── NPC Info ──────────────────────────────────────────────────────

    private void handleNpcInfo(Player player) {
        if (!perm(player, "nimbus.display.npc")) return;
        NimbusNpc npc = npcManager.getNearestNpc(player.getLocation(), 5.0);
        if (npc == null) { player.sendMessage(err("No NPC within 5 blocks.")); return; }

        player.sendMessage(Component.text("NPC: ", NamedTextColor.AQUA).append(white(npc.id())));
        player.sendMessage(gray("  target: ").append(white(npc.target())));
        player.sendMessage(gray("  type: ").append(white(npc.entityType().name().toLowerCase())));
        if (npc.skin() != null) player.sendMessage(gray("  skin: ").append(white(npc.skin())));
        player.sendMessage(gray("  strategy: ").append(white(npc.strategy().name().toLowerCase())));
        player.sendMessage(gray("  lookat: ").append(white(String.valueOf(npc.lookAtPlayer()))));
        player.sendMessage(gray("  left_click: ").append(white(npc.leftClick().name()
                + (npc.leftClickValue() != null ? " " + npc.leftClickValue() : ""))));
        player.sendMessage(gray("  right_click: ").append(white(npc.rightClick().name()
                + (npc.rightClickValue() != null ? " " + npc.rightClickValue() : ""))));
        if (npc.hologramLines() != null && !npc.hologramLines().isEmpty())
            player.sendMessage(gray("  hologram: ").append(white(String.join(" | ", npc.hologramLines()))));
        player.sendMessage(gray("  floating_item: ").append(white(npc.floatingItem() != null ? npc.floatingItem() : "off")));
        if (npc.equipment() != null && !npc.equipment().isEmpty()) {
            for (var entry : npc.equipment().entrySet()) {
                player.sendMessage(gray("  " + entry.getKey() + ": ").append(white(entry.getValue())));
            }
        }
        player.sendMessage(gray("  burning: ").append(white(String.valueOf(npc.burning()))));
        if (npc.pose() != null)
            player.sendMessage(gray("  pose: ").append(white(npc.pose())));
        var loc = npc.location();
        player.sendMessage(gray("  location: ").append(white(
                String.format("%.1f %.1f %.1f (%.0f/%.0f)", loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch()))));
    }

    // ── List / Reload ─────────────────────────────────────────────────

    private void handleList(Player player) {
        if (!perm(player, "nimbus.display.list")) return;
        var signs = signManager.getSigns();
        var npcs = npcManager.getNpcs();

        player.sendMessage(Component.text("Nimbus Display (" + signs.size() + " signs, " + npcs.size() + " NPCs)", NamedTextColor.AQUA));

        for (NimbusSign sign : signs) {
            var loc = sign.location();
            player.sendMessage(gray("  [S] " + sign.id() + " → ").append(Component.text(sign.target(), NamedTextColor.GREEN))
                    .append(gray(" @ " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ())));
        }
        for (NimbusNpc npc : npcs) {
            player.sendMessage(gray("  [N] " + npc.id() + " → ").append(Component.text(npc.target(), NamedTextColor.GREEN))
                    .append(gray(" [" + npc.entityType().name().toLowerCase() + "] L:" + npc.leftClick() + " R:" + npc.rightClick())));
        }
        if (signs.isEmpty() && npcs.isEmpty()) player.sendMessage(gray("  No signs or NPCs configured."));
    }

    private void handleReload(Player player) {
        if (!perm(player, "nimbus.display.reload")) return;
        signManager.reload();
        npcManager.reload();
        player.sendMessage(ok("Reloaded " + signManager.getSignCount() + " sign(s), " + npcManager.getNpcCount() + " NPC(s)."));
    }

    // ── Help ──────────────────────────────────────────────────────────

    private void sendHelp(Player p) {
        p.sendMessage(Component.text("Nimbus Display", NamedTextColor.AQUA, TextDecoration.BOLD));
        p.sendMessage(white("  /ndisplay sign set <target> [strategy]").append(gray(" — set sign")));
        p.sendMessage(white("  /ndisplay sign remove").append(gray(" — remove sign")));
        p.sendMessage(white("  /ndisplay npc set <target> [strategy] [type] [skin]").append(gray(" — spawn NPC")));
        p.sendMessage(white("  /ndisplay npc remove").append(gray(" — remove nearest NPC")));
        p.sendMessage(white("  /ndisplay npc edit <property> <value>").append(gray(" — live edit NPC")));
        p.sendMessage(white("  /ndisplay npc info").append(gray(" — show NPC properties")));
        p.sendMessage(white("  /ndisplay list").append(gray(" — list all")));
        p.sendMessage(white("  /ndisplay reload").append(gray(" — reload config")));
    }

    private void sendSignHelp(Player p) {
        p.sendMessage(white("  /ndisplay sign set <target> [strategy]"));
        p.sendMessage(white("  /ndisplay sign remove"));
    }

    private void sendNpcHelp(Player p) {
        p.sendMessage(Component.text("NPC Commands:", NamedTextColor.AQUA));
        p.sendMessage(white("  /ndisplay npc set <target> [strategy] [type] [skin]"));
        p.sendMessage(white("  /ndisplay npc remove"));
        p.sendMessage(white("  /ndisplay npc edit <property> <value>"));
        p.sendMessage(white("  /ndisplay npc info"));
    }

    private void sendEditHelp(Player p) {
        p.sendMessage(Component.text("Editable properties:", NamedTextColor.AQUA));
        p.sendMessage(gray("  type ").append(white("<PLAYER|VILLAGER|ZOMBIE|...>")));
        p.sendMessage(gray("  skin ").append(white("<player_name>")));
        p.sendMessage(gray("  target ").append(white("<group|service>")));
        p.sendMessage(gray("  strategy ").append(white("<least|fill|random>")));
        p.sendMessage(gray("  lookat ").append(white("<true|false>")));
        p.sendMessage(gray("  left_click ").append(white("<CONNECT|COMMAND|INVENTORY|NONE> [value]")));
        p.sendMessage(gray("  right_click ").append(white("<CONNECT|COMMAND|INVENTORY|NONE> [value]")));
        p.sendMessage(gray("  hologram ").append(white("<Line1|Line2|Line3> or 'clear'")));
        p.sendMessage(gray("  floating_item ").append(white("<true|MATERIAL|off> (true=from display config)")));
        p.sendMessage(gray("  mainhand/offhand/head/chest/legs/feet ").append(white("<MATERIAL|none>")));
        p.sendMessage(gray("  burning ").append(white("<true|false>")));
        p.sendMessage(gray("  pose ").append(white("<crouching|sleeping|swimming|spin_attack|none>")));
        p.sendMessage(gray("  position ").append(white("here")));
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

        // sign/npc set <target>
        if (args.length == 3 && args[1].equalsIgnoreCase("set")) {
            List<String> s = new ArrayList<>(Nimbus.cache().getGroupNames());
            try { for (NimbusService sv : Nimbus.services()) s.add(sv.getName()); } catch (Exception ignored) {}
            return filter(s, args[2]);
        }

        // set <target> [strategy]
        if (args.length == 4 && args[1].equalsIgnoreCase("set")) {
            if (Nimbus.cache().getGroupNames().contains(args[2]))
                return filter(List.of("least", "fill", "random"), args[3]);
        }

        // npc set <target> <strategy> [type]
        if (args.length == 5 && args[0].equalsIgnoreCase("npc") && args[1].equalsIgnoreCase("set"))
            return filter(List.of("PLAYER", "VILLAGER", "ZOMBIE", "SKELETON", "ARMOR_STAND", "PILLAGER", "IRON_GOLEM", "ALLAY"), args[4]);

        // npc edit <property>
        if (args.length == 3 && args[0].equalsIgnoreCase("npc") && args[1].equalsIgnoreCase("edit"))
            return filter(List.of("type", "skin", "target", "strategy", "lookat", "left_click", "right_click", "hologram", "floating_item", "mainhand", "offhand", "head", "chest", "legs", "feet", "burning", "pose", "position"), args[2]);

        // npc edit type <value>
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
        p.sendMessage(err("No permission."));
        return false;
    }

    private static Component ok(String msg) { return Component.text(msg, NamedTextColor.GREEN); }
    private static Component err(String msg) { return Component.text(msg, NamedTextColor.RED); }
    private static Component white(String msg) { return Component.text(msg, NamedTextColor.WHITE); }
    private static Component gray(String msg) { return Component.text(msg, NamedTextColor.GRAY); }
}
