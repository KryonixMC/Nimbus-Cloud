package dev.kryonix.nimbus.perms.display;

import dev.kryonix.nimbus.perms.provider.PermissionProvider;
import dev.kryonix.nimbus.sdk.Nimbus;
import dev.kryonix.nimbus.sdk.compat.SchedulerCompat;
import dev.kryonix.nimbus.sdk.compat.TextCompat;
import dev.kryonix.nimbus.sdk.compat.VersionHelper;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Manages player name tags (above head) using Scoreboard Teams.
 * Syncs prefix/suffix from the permission provider.
 * <p>
 * On Folia with PacketEvents: uses packet-based teams via {@link PacketNameTagHandler}.
 * On Folia without PacketEvents: disabled (scoreboard API is read-only).
 * On Bukkit/Paper: uses the main scoreboard (shared, efficient).
 */
public class NameTagHandler {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final PermissionProvider provider;

    private final ConcurrentHashMap<UUID, String> tabOverrides = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> playerTeams = new ConcurrentHashMap<>();

    private boolean disabled = false;

    /** Packet-based handler for Folia (null if not on Folia or PacketEvents not available). */
    private PacketNameTagHandler packetHandler;

    private static final String TEAM_PREFIX = "nimbus_";

    public NameTagHandler(JavaPlugin plugin, PermissionProvider provider) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.provider = provider;
    }

    public void start() {
        if (VersionHelper.isFolia()) {
            // Folia: scoreboard API is read-only — need PacketEvents for name tags
            if (Bukkit.getPluginManager().getPlugin("packetevents") != null) {
                try {
                    packetHandler = new PacketNameTagHandler(logger);
                    logger.info("Folia detected — using PacketEvents for packet-based name tags");
                } catch (Exception e) {
                    disabled = true;
                    logger.warning("Folia detected — PacketEvents found but failed to initialize: " + e.getMessage());
                    return;
                }
            } else {
                disabled = true;
                logger.info("Folia detected — name tag handler disabled (install PacketEvents for name tag support)");
                return;
            }
        }

        if (Nimbus.events() != null) {
            Nimbus.events().onEvent("PERMISSION_GROUP_UPDATED", e -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    SchedulerCompat.runForEntity(plugin, player, () -> applyNameTag(player));
                }
            });

            Nimbus.events().onEvent("PLAYER_PERMISSIONS_UPDATED", e -> {
                String uuid = e.get("uuid");
                if (uuid != null) {
                    try {
                        UUID playerUuid = UUID.fromString(uuid);
                        SchedulerCompat.runTask(plugin, () -> {
                            Player player = Bukkit.getPlayer(playerUuid);
                            if (player != null) {
                                SchedulerCompat.runForEntity(plugin, player, () -> applyNameTag(player));
                            }
                        });
                    } catch (IllegalArgumentException ignored) {}
                }
            });

            Nimbus.events().onEvent("PLAYER_TAB_UPDATED", e -> {
                String uuid = e.get("uuid");
                String format = e.get("format");
                if (uuid == null) return;
                try {
                    UUID playerUuid = UUID.fromString(uuid);
                    if (format != null && !format.isEmpty()) {
                        tabOverrides.put(playerUuid, format);
                    } else {
                        tabOverrides.remove(playerUuid);
                    }
                    SchedulerCompat.runTask(plugin, () -> {
                        Player player = Bukkit.getPlayer(playerUuid);
                        if (player != null) {
                            SchedulerCompat.runForEntity(plugin, player, () -> applyNameTag(player));
                        }
                    });
                } catch (IllegalArgumentException ignored) {}
            });
        }

        logger.info("Name tag handler started" + (packetHandler != null ? " (packet mode)" : ""));
    }

    public void onJoin(Player player) {
        if (disabled) return;

        // Send existing teams to the new player (packet mode only)
        if (packetHandler != null) {
            packetHandler.sendExistingTeams(player);
        }

        // Defer to allow provider to load display data first
        SchedulerCompat.runForEntityLater(plugin, player, () -> {
            if (!player.isOnline()) return;
            // Only apply if display data has been loaded — applying with empty cache
            // would overwrite shared team prefixes with empty values
            UUID uuid = player.getUniqueId();
            if (!provider.getPrefix(uuid).isEmpty() || !provider.getSuffix(uuid).isEmpty() || provider.getPriority(uuid) != 0) {
                applyNameTag(player);
            }
        }, 5L);
    }

    /** Refresh the name tag for a player (e.g. after display data is loaded). */
    public void refresh(Player player) {
        if (disabled) return;
        SchedulerCompat.runForEntity(plugin, player, () -> {
            if (player.isOnline()) applyNameTag(player);
        });
    }

    public void onQuit(Player player) {
        tabOverrides.remove(player.getUniqueId());
        if (disabled) return;

        if (packetHandler != null) {
            packetHandler.removePlayer(player);
        } else {
            String teamName = playerTeams.remove(player.getUniqueId());
            if (teamName != null) {
                Scoreboard scoreboard = getScoreboard();
                Team team = scoreboard.getTeam(teamName);
                if (team != null) {
                    team.removeEntry(player.getName());
                    if (team.getEntries().isEmpty()) {
                        team.unregister();
                    }
                }
            }
        }
    }

    private Scoreboard getScoreboard() {
        return Bukkit.getScoreboardManager().getMainScoreboard();
    }

    private void applyNameTag(Player player) {
        UUID uuid = player.getUniqueId();
        String override = tabOverrides.get(uuid);

        String prefix;
        String suffix;
        int priority = provider.getPriority(uuid);

        if (override != null) {
            int playerIdx = override.indexOf("{player}");
            if (playerIdx >= 0) {
                prefix = override.substring(0, playerIdx);
                suffix = override.substring(playerIdx + "{player}".length());
            } else {
                prefix = override + " ";
                suffix = "";
            }
        } else {
            prefix = provider.getPrefix(uuid);
            suffix = provider.getSuffix(uuid);
        }

        if (packetHandler != null) {
            // Packet-based (Folia with PacketEvents)
            packetHandler.applyNameTag(player, prefix, suffix, priority);
        } else {
            // Scoreboard-based (Bukkit/Paper)
            applyScoreboardNameTag(player, prefix, suffix, priority, override != null);
        }
    }

    private void applyScoreboardNameTag(Player player, String prefix, String suffix, int priority, boolean isOverride) {
        UUID uuid = player.getUniqueId();
        Scoreboard scoreboard = getScoreboard();

        String oldTeamName = playerTeams.get(uuid);
        if (oldTeamName != null) {
            Team oldTeam = scoreboard.getTeam(oldTeamName);
            if (oldTeam != null) {
                oldTeam.removeEntry(player.getName());
                if (oldTeam.getEntries().isEmpty()) {
                    oldTeam.unregister();
                }
            }
        }

        String teamName;
        if (isOverride) {
            teamName = TEAM_PREFIX + player.getName().substring(0, Math.min(player.getName().length(), 8));
        } else {
            String sortKey = String.format("%04d", 9999 - Math.max(0, Math.min(9999, priority)));
            teamName = TEAM_PREFIX + sortKey;
        }
        if (teamName.length() > 16) {
            teamName = teamName.substring(0, 16);
        }

        Team team = scoreboard.getTeam(teamName);
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName);
        }

        // Use TextCompat for cross-version prefix/suffix support
        TextCompat.setTeamPrefix(team, prefix);
        TextCompat.setTeamSuffix(team, suffix);

        team.addEntry(player.getName());
        playerTeams.put(uuid, teamName);
    }
}
