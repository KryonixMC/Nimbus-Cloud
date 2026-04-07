package dev.nimbuspowered.nimbus.sdk;

import java.util.List;
import java.util.Map;

/**
 * Display configuration for a group (signs + NPCs).
 * Loaded from the Nimbus API ({@code GET /api/displays/{name}}).
 */
public class NimbusDisplay {

    private final String name;

    // Sign
    private final String signLine1;
    private final String signLine2;
    private final String signLine3;
    private final String signLine4Online;
    private final String signLine4Offline;

    // NPC
    private final String npcDisplayName;
    private final String npcSubtitle;
    private final String npcSubtitleOffline;
    private final String npcFloatingItem;
    private final Map<String, String> statusItems;

    // NPC Inventory
    private final String inventoryTitle;
    private final int inventorySize;
    private final String inventoryItemName;
    private final List<String> inventoryItemLore;

    // State labels
    private final Map<String, String> states;

    public NimbusDisplay(String name,
                         String signLine1, String signLine2, String signLine3,
                         String signLine4Online, String signLine4Offline,
                         String npcDisplayName,
                         String npcSubtitle, String npcSubtitleOffline,
                         String npcFloatingItem,
                         Map<String, String> statusItems,
                         String inventoryTitle, int inventorySize,
                         String inventoryItemName, List<String> inventoryItemLore,
                         Map<String, String> states) {
        this.name = name;
        this.signLine1 = signLine1;
        this.signLine2 = signLine2;
        this.signLine3 = signLine3;
        this.signLine4Online = signLine4Online;
        this.signLine4Offline = signLine4Offline;
        this.npcDisplayName = npcDisplayName;
        this.npcSubtitle = npcSubtitle;
        this.npcSubtitleOffline = npcSubtitleOffline;
        this.npcFloatingItem = npcFloatingItem;
        this.statusItems = statusItems != null ? statusItems : Map.of();
        this.inventoryTitle = inventoryTitle;
        this.inventorySize = inventorySize;
        this.inventoryItemName = inventoryItemName;
        this.inventoryItemLore = inventoryItemLore;
        this.states = states;
    }

    public String getName() { return name; }

    // Sign
    public String getSignLine1() { return signLine1; }
    public String getSignLine2() { return signLine2; }
    public String getSignLine3() { return signLine3; }
    public String getSignLine4Online() { return signLine4Online; }
    public String getSignLine4Offline() { return signLine4Offline; }

    // NPC
    public String getNpcDisplayName() { return npcDisplayName; }
    public String getNpcSubtitle() { return npcSubtitle; }
    public String getNpcSubtitleOffline() { return npcSubtitleOffline; }
    public String getNpcFloatingItem() { return npcFloatingItem != null ? npcFloatingItem : "GRASS_BLOCK"; }
    public Map<String, String> getStatusItems() { return statusItems; }

    // NPC Inventory
    public String getInventoryTitle() { return inventoryTitle != null ? inventoryTitle : "&8» &b&l{name} Servers"; }
    public int getInventorySize() { return inventorySize > 0 ? inventorySize : 27; }
    public String getInventoryItemName() { return inventoryItemName != null ? inventoryItemName : "&b{name}"; }
    public List<String> getInventoryItemLore() { return inventoryItemLore; }

    // States
    public Map<String, String> getStates() { return states; }

    /** Resolve a raw state to its display label. */
    public String resolveState(String rawState) {
        if (rawState == null) return "ONLINE";
        return states.getOrDefault(rawState, rawState);
    }

    /** Resolve a display state label to its status item material name. */
    public String resolveStatusItem(String resolvedState) {
        if (resolvedState == null) return "GRAY_WOOL";
        return statusItems.getOrDefault(resolvedState, "GRAY_WOOL");
    }
}
