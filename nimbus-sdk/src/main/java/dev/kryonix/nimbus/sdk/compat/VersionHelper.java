package dev.kryonix.nimbus.sdk.compat;

/**
 * Runtime detection of server environment capabilities.
 * Used to adapt plugin behavior for different server versions and platforms.
 */
public final class VersionHelper {

    private static final boolean FOLIA;
    private static final boolean ADVENTURE;
    private static final boolean MINIMESSAGE;
    private static final boolean ASYNC_CHAT_EVENT;

    static {
        FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");
        ADVENTURE = classExists("net.kyori.adventure.text.Component");
        MINIMESSAGE = classExists("net.kyori.adventure.text.minimessage.MiniMessage");
        ASYNC_CHAT_EVENT = classExists("io.papermc.paper.event.player.AsyncChatEvent");
    }

    private VersionHelper() {}

    /** True if running on Folia (regionized multithreading). */
    public static boolean isFolia() { return FOLIA; }

    /** True if Adventure text API is available (Paper 1.16.5+). */
    public static boolean hasAdventure() { return ADVENTURE; }

    /** True if MiniMessage is available (Paper 1.16.5+). */
    public static boolean hasMiniMessage() { return MINIMESSAGE; }

    /** True if Paper's AsyncChatEvent is available (Paper 1.16.5+). */
    public static boolean hasAsyncChatEvent() { return ASYNC_CHAT_EVENT; }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
