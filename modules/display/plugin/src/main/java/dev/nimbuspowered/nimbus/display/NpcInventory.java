package dev.nimbuspowered.nimbus.display;

import dev.nimbuspowered.nimbus.sdk.Nimbus;
import dev.nimbuspowered.nimbus.sdk.NimbusDisplay;
import dev.nimbuspowered.nimbus.sdk.NimbusGroup;
import dev.nimbuspowered.nimbus.sdk.NimbusService;
import dev.nimbuspowered.nimbus.sdk.compat.TextCompat;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server selector GUI — opens a chest inventory showing all servers in a group.
 * Supports pagination for large server lists and filler items for empty slots.
 * Uses {@link InventoryHolder} for reliable click detection.
 * <p>
 * Uses {@link TextCompat} for cross-version inventory/item rendering.
 * Register {@link NpcInventory.ClickListener} once in the plugin to handle all inventory clicks.
 */
public class NpcInventory implements InventoryHolder {

    private static final Material FILLER_MATERIAL = Material.GRAY_STAINED_GLASS_PANE;
    private static final int NAV_PREV_SLOT_OFFSET = 0; // bottom-left
    private static final int NAV_NEXT_SLOT_OFFSET = 8; // bottom-right

    private final NimbusNpc npc;
    private final ConcurrentHashMap<String, NimbusDisplay> displayCache;
    private final ConcurrentHashMap<String, NimbusGroup> groupCache;
    private final Map<Integer, String> slotToService = new HashMap<>();
    private final List<NimbusService> allServices;
    private int currentPage;
    private int totalPages;
    private Inventory inventory;

    public NpcInventory(JavaPlugin plugin, NimbusNpc npc,
                        ConcurrentHashMap<String, NimbusDisplay> displayCache,
                        ConcurrentHashMap<String, NimbusGroup> groupCache) {
        this.npc = npc;
        this.displayCache = displayCache;
        this.groupCache = groupCache;
        this.allServices = new ArrayList<>();
        this.currentPage = 0;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open(Player player) {
        open(player, 0);
    }

    public void open(Player player, int page) {
        String groupName = npc.serviceTarget()
                ? npc.target().replaceAll("-\\d+$", "")
                : npc.target();

        NimbusDisplay display = displayCache.get(groupName);
        if (display == null) {
            TextCompat.sendRich(player, "No display config for " + groupName + ".", "red");
            return;
        }

        NimbusGroup group = groupCache.get(groupName);
        int maxPlayers = group != null ? group.getMaxPlayers() : 0;

        String title = display.getInventoryTitle();
        int size = display.getInventorySize();
        size = Math.min(54, Math.max(9, (size / 9) * 9));
        if (size == 0) size = 27;

        // Collect and sort services
        allServices.clear();
        allServices.addAll(Nimbus.services(groupName));
        allServices.sort((a, b) -> {
            if (a.isReady() != b.isReady()) return a.isReady() ? -1 : 1;
            return a.getName().compareTo(b.getName());
        });

        int totalPlayers = allServices.stream().mapToInt(NimbusService::getPlayerCount).sum();

        // Calculate pagination
        boolean needsPagination = allServices.size() > size;
        int contentSlots = needsPagination ? size - 9 : size; // Reserve bottom row for nav
        totalPages = Math.max(1, (int) Math.ceil((double) allServices.size() / contentSlots));
        currentPage = Math.min(page, totalPages - 1);

        String renderedTitle = title
                .replace("{name}", groupName)
                .replace("{players}", String.valueOf(totalPlayers))
                .replace("{max_players}", String.valueOf(maxPlayers))
                .replace("{servers}", String.valueOf(allServices.size()));

        if (needsPagination) {
            renderedTitle += " &8(" + (currentPage + 1) + "/" + totalPages + ")";
        }

        inventory = TextCompat.createInventory(this, size, renderedTitle);
        slotToService.clear();

        // Populate server items
        int startIndex = currentPage * contentSlots;
        int slot = 0;
        for (int i = startIndex; i < allServices.size() && slot < contentSlots; i++, slot++) {
            NimbusService service = allServices.get(i);

            String rawState = service.getCustomState() != null ? service.getCustomState() : service.getState();
            String state = display.resolveState(rawState);
            String materialName = display.resolveStatusItem(state);
            Material material = Material.matchMaterial(materialName != null ? materialName : "GRAY_WOOL");
            if (material == null) material = Material.GRAY_WOOL;

            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();

            TextCompat.setItemDisplayName(meta, renderText(display.getInventoryItemName(),
                    service.getName(), service.getPlayerCount(), maxPlayers, 1, state));

            List<String> loreTemplates = display.getInventoryItemLore();
            if (loreTemplates != null) {
                List<String> loreLines = new ArrayList<>();
                for (String template : loreTemplates) {
                    loreLines.add(renderText(template, service.getName(), service.getPlayerCount(), maxPlayers, 1, state));
                }
                TextCompat.setItemLore(meta, loreLines);
            }

            item.setItemMeta(meta);
            inventory.setItem(slot, item);
            slotToService.put(slot, service.getName());
        }

        // Fill empty content slots with filler
        for (int i = slot; i < contentSlots; i++) {
            inventory.setItem(i, createFiller());
        }

        // Navigation row (only if paginated)
        if (needsPagination) {
            int navRowStart = size - 9;

            // Fill nav row with filler
            for (int i = navRowStart; i < size; i++) {
                inventory.setItem(i, createFiller());
            }

            // Previous page button
            if (currentPage > 0) {
                ItemStack prev = new ItemStack(Material.ARROW);
                ItemMeta prevMeta = prev.getItemMeta();
                TextCompat.setItemDisplayName(prevMeta, "&e← Previous Page");
                TextCompat.setItemLore(prevMeta, List.of("&7Page " + currentPage + "/" + totalPages));
                prev.setItemMeta(prevMeta);
                inventory.setItem(navRowStart + NAV_PREV_SLOT_OFFSET, prev);
            }

            // Next page button
            if (currentPage < totalPages - 1) {
                ItemStack next = new ItemStack(Material.ARROW);
                ItemMeta nextMeta = next.getItemMeta();
                TextCompat.setItemDisplayName(nextMeta, "&eNext Page →");
                TextCompat.setItemLore(nextMeta, List.of("&7Page " + (currentPage + 2) + "/" + totalPages));
                next.setItemMeta(nextMeta);
                inventory.setItem(navRowStart + NAV_NEXT_SLOT_OFFSET, next);
            }

            // Page indicator (center)
            ItemStack pageInfo = new ItemStack(Material.PAPER);
            ItemMeta pageMeta = pageInfo.getItemMeta();
            TextCompat.setItemDisplayName(pageMeta, "&fPage " + (currentPage + 1) + "/" + totalPages);
            TextCompat.setItemLore(pageMeta, List.of("&7" + allServices.size() + " servers total"));
            pageInfo.setItemMeta(pageMeta);
            inventory.setItem(navRowStart + 4, pageInfo);
        }

        player.openInventory(inventory);
    }

    /** Handle a click in this inventory. Called by {@link ClickListener}. */
    void handleClick(Player player, int rawSlot) {
        if (inventory == null) return;
        int size = inventory.getSize();
        boolean needsPagination = allServices.size() > size;

        // Navigation clicks
        if (needsPagination && rawSlot >= size - 9) {
            int navOffset = rawSlot - (size - 9);
            if (navOffset == NAV_PREV_SLOT_OFFSET && currentPage > 0) {
                open(player, currentPage - 1);
                return;
            }
            if (navOffset == NAV_NEXT_SLOT_OFFSET && currentPage < totalPages - 1) {
                open(player, currentPage + 1);
                return;
            }
            return; // Filler or page indicator click
        }

        String serviceName = slotToService.get(rawSlot);
        if (serviceName == null) return;

        NimbusService service = Nimbus.cache().get(serviceName);
        if (service == null || !service.isReady()) {
            TextCompat.sendRich(player, serviceName + " is not available.", "red");
            return;
        }

        player.closeInventory();
        TextCompat.sendActionBar(player, "&a&l▶ &fConnecting to &b" + serviceName + "&f...");
        try {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
        } catch (Exception ignored) {}
        Nimbus.client().sendPlayer(player.getName(), serviceName)
                .exceptionally(e -> {
                    TextCompat.sendRich(player, "Failed to connect.", "red");
                    return null;
                });
    }

    private static ItemStack createFiller() {
        ItemStack filler = new ItemStack(FILLER_MATERIAL);
        ItemMeta meta = filler.getItemMeta();
        TextCompat.setItemDisplayName(meta, " ");
        filler.setItemMeta(meta);
        return filler;
    }

    private String renderText(String template, String name, int players, int maxPlayers,
                               int servers, String state) {
        if (template == null) return "";
        return template
                .replace("{name}", name)
                .replace("{target}", name)
                .replace("{players}", String.valueOf(players))
                .replace("{max_players}", String.valueOf(maxPlayers))
                .replace("{servers}", String.valueOf(servers))
                .replace("{state}", state);
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
