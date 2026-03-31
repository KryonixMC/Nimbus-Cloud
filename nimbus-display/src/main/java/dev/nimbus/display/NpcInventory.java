package dev.nimbus.display;

import dev.nimbus.sdk.Nimbus;
import dev.nimbus.sdk.NimbusDisplay;
import dev.nimbus.sdk.NimbusGroup;
import dev.nimbus.sdk.NimbusService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server selector GUI — opens a chest inventory showing all servers in a group.
 * Uses {@link InventoryHolder} for reliable click detection.
 * <p>
 * Register {@link NpcInventory.ClickListener} once in the plugin to handle all inventory clicks.
 */
public class NpcInventory implements InventoryHolder {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final NimbusNpc npc;
    private final ConcurrentHashMap<String, NimbusDisplay> displayCache;
    private final ConcurrentHashMap<String, NimbusGroup> groupCache;
    private final Map<Integer, String> slotToService = new HashMap<>();
    private Inventory inventory;

    public NpcInventory(JavaPlugin plugin, NimbusNpc npc,
                        ConcurrentHashMap<String, NimbusDisplay> displayCache,
                        ConcurrentHashMap<String, NimbusGroup> groupCache) {
        this.npc = npc;
        this.displayCache = displayCache;
        this.groupCache = groupCache;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open(Player player) {
        String groupName = npc.serviceTarget()
                ? npc.target().replaceAll("-\\d+$", "")
                : npc.target();

        NimbusDisplay display = displayCache.get(groupName);
        if (display == null) {
            player.sendMessage(Component.text("No display config for " + groupName + ".", NamedTextColor.RED));
            return;
        }

        NimbusGroup group = groupCache.get(groupName);
        int maxPlayers = group != null ? group.getMaxPlayers() : 0;

        String title = display.getInventoryTitle();
        int size = display.getInventorySize();
        size = Math.min(54, Math.max(9, (size / 9) * 9));
        if (size == 0) size = 27;

        List<NimbusService> services = new ArrayList<>(Nimbus.services(groupName));
        int totalPlayers = services.stream().mapToInt(NimbusService::getPlayerCount).sum();

        String renderedTitle = title
                .replace("{name}", groupName)
                .replace("{players}", String.valueOf(totalPlayers))
                .replace("{max_players}", String.valueOf(maxPlayers))
                .replace("{servers}", String.valueOf(services.size()));

        // Create inventory with this as holder for reliable click detection
        inventory = Bukkit.createInventory(this, size, LEGACY.deserialize(renderedTitle));

        // Sort: READY first, then by name
        services.sort((a, b) -> {
            if (a.isReady() != b.isReady()) return a.isReady() ? -1 : 1;
            return a.getName().compareTo(b.getName());
        });

        int slot = 0;
        for (NimbusService service : services) {
            if (slot >= size) break;

            String rawState = service.getCustomState() != null ? service.getCustomState() : service.getState();
            String state = display.resolveState(rawState);
            String materialName = display.resolveStatusItem(state);
            Material material = Material.matchMaterial(materialName != null ? materialName : "GRAY_WOOL");
            if (material == null) material = Material.GRAY_WOOL;

            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();

            meta.displayName(renderItem(display.getInventoryItemName(), service.getName(),
                    service.getPlayerCount(), maxPlayers, 1, state));

            List<String> loreTemplates = display.getInventoryItemLore();
            if (loreTemplates != null) {
                List<Component> lore = new ArrayList<>();
                for (String template : loreTemplates) {
                    lore.add(renderItem(template, service.getName(), service.getPlayerCount(), maxPlayers, 1, state));
                }
                meta.lore(lore);
            }

            item.setItemMeta(meta);
            inventory.setItem(slot, item);
            slotToService.put(slot, service.getName());
            slot++;
        }

        player.openInventory(inventory);
    }

    /** Handle a click in this inventory. Called by {@link ClickListener}. */
    void handleClick(Player player, int rawSlot) {
        String serviceName = slotToService.get(rawSlot);
        if (serviceName == null) return;

        NimbusService service = Nimbus.cache().get(serviceName);
        if (service == null || !service.isReady()) {
            player.sendMessage(Component.text(serviceName + " is not available.", NamedTextColor.RED));
            return;
        }

        player.closeInventory();
        player.sendMessage(Component.text("Connecting to ", NamedTextColor.GREEN)
                .append(Component.text(serviceName, NamedTextColor.WHITE))
                .append(Component.text("...", NamedTextColor.GREEN)));
        Nimbus.client().sendPlayer(player.getName(), serviceName)
                .exceptionally(e -> {
                    player.sendMessage(Component.text("Failed to connect.", NamedTextColor.RED));
                    return null;
                });
    }

    private Component renderItem(String template, String name, int players, int maxPlayers,
                                  int servers, String state) {
        if (template == null) return Component.empty();
        String text = template
                .replace("{name}", name)
                .replace("{target}", name)
                .replace("{players}", String.valueOf(players))
                .replace("{max_players}", String.valueOf(maxPlayers))
                .replace("{servers}", String.valueOf(servers))
                .replace("{state}", state);
        return LEGACY.deserialize(text);
    }

    // ── Static Listener (register once) ───────────────────────────────

    /**
     * Global click listener for NPC inventories. Register once in the plugin.
     */
    public static class ClickListener implements Listener {
        @EventHandler
        public void onInventoryClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof NpcInventory npcInv)) return;
            event.setCancelled(true);

            if (!(event.getWhoClicked() instanceof Player player)) return;
            npcInv.handleClick(player, event.getRawSlot());
        }
    }
}
