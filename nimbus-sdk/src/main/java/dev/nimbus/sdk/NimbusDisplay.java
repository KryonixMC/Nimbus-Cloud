package dev.nimbus.sdk;

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
    private final String npcItem;
    private final String npcSubtitle;
    private final String npcSubtitleOffline;

    // State labels
    private final Map<String, String> states;

    public NimbusDisplay(String name,
                         String signLine1, String signLine2, String signLine3,
                         String signLine4Online, String signLine4Offline,
                         String npcDisplayName, String npcItem,
                         String npcSubtitle, String npcSubtitleOffline,
                         Map<String, String> states) {
        this.name = name;
        this.signLine1 = signLine1;
        this.signLine2 = signLine2;
        this.signLine3 = signLine3;
        this.signLine4Online = signLine4Online;
        this.signLine4Offline = signLine4Offline;
        this.npcDisplayName = npcDisplayName;
        this.npcItem = npcItem;
        this.npcSubtitle = npcSubtitle;
        this.npcSubtitleOffline = npcSubtitleOffline;
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
    public String getNpcItem() { return npcItem; }
    public String getNpcSubtitle() { return npcSubtitle; }
    public String getNpcSubtitleOffline() { return npcSubtitleOffline; }

    // States
    public Map<String, String> getStates() { return states; }

    /** Resolve a raw state to its display label. */
    public String resolveState(String rawState) {
        if (rawState == null) return "ONLINE";
        return states.getOrDefault(rawState, rawState);
    }
}
