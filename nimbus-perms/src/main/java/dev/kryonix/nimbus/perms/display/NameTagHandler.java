package dev.kryonix.nimbus.perms.display;

import dev.kryonix.nimbus.perms.provider.PermissionProvider;
import dev.kryonix.nimbus.sdk.Nimbus;
import dev.kryonix.nimbus.sdk.compat.SchedulerCompat;
import dev.kryonix.nimbus.sdk.compat.TextCompat;
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
 * Compatible with both modern Paper (Adventure) and legacy Spigot (1.8+).
 */
public class NameTagHandler {

    private final JavaPlugin plugin;
    private final Logger logger;
    private final PermissionProvider provider;

    private final ConcurrentHashMap<UUID, String> tabOverrides = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> playerTeams = new ConcurrentHashMap<>();

    private static final String TEAM_PREFIX = "nimbus_";

    public NameTagHandler(JavaPlugin plugin, PermissionProvider provider) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.provider = provider;
    }

    public void start() {
        if (Nimbus.events() != null) {
            Nimbus.events().onEvent("PERMISSION_GROUP_UPDATED", e -> {
                // Schedule per-player to run on each player's region thread (Folia)
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

        logger.info("Name tag handler started");
    }

    public void onJoin(Player player) {
        // Defer to allow provider to load display data first
        // Use entity-bound scheduling so the task runs on the player's region thread (Folia)
        SchedulerCompat.runForEntityLater(plugin, player, () -> {
            if (player.isOnline()) applyNameTag(player);
        }, 5L);
    }

    /** Refresh the name tag for a player (e.g. after display data is loaded). */
    public void refresh(Player player) {
        SchedulerCompat.runForEntity(plugin, player, () -> {
            if (player.isOnline()) applyNameTag(player);
        });
    }

    public void onQuit(Player player) {
        tabOverrides.remove(player.getUniqueId());
        String teamName = playerTeams.remove(player.getUniqueId());
        if (teamName != null) {
            Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
            Team team = scoreboard.getTeam(teamName);
            if (team != null) {
                team.removeEntry(player.getName());
                if (team.getEntries().isEmpty()) {
                    team.unregister();
                }
            }
        }
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

        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();

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
        if (override != null) {
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
