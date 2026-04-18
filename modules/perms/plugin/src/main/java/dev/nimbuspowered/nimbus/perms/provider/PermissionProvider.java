package dev.nimbuspowered.nimbus.perms.provider;

import dev.nimbuspowered.nimbus.perms.NimbusPermsPlugin;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Interface for permission providers. Implementations handle the actual permission
 * loading, syncing, and display data retrieval.
 */
public interface PermissionProvider {

    void enable(NimbusPermsPlugin plugin);

    void disable();

    void onJoin(Player player);

    void onQuit(Player player);

    void refresh(UUID uuid);

    void refreshAll();

    String getPrefix(UUID uuid);

    String getSuffix(UUID uuid);

    int getPriority(UUID uuid);

    /** Set a callback invoked on the main thread after display data is loaded for a player. */
    default void setDisplayLoadedCallback(java.util.function.Consumer<Player> callback) {}
}
