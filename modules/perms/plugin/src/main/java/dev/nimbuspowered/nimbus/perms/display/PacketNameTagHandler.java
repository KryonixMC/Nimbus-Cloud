package dev.nimbuspowered.nimbus.perms.display;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Packet-based name tag handler using PacketEvents.
 * Used on Folia where the Scoreboard API is read-only for teams.
 * Sends scoreboard team packets directly to clients.
 */
public class PacketNameTagHandler {

    private final Logger logger;

    // playerUUID -> teamName
    private final Map<UUID, String> playerTeams = new ConcurrentHashMap<>();
    // teamName -> cached team data (for resending to new joiners)
    private final Map<String, TeamData> teamCache = new ConcurrentHashMap<>();

    private static final String TEAM_PREFIX_STR = "nimbus_";

    public PacketNameTagHandler(Logger logger) {
        this.logger = logger;
    }

    /**
     * Apply a name tag (prefix/suffix above head) for a player via packets.
     * Sends team packets to all online players.
     */
    public void applyNameTag(Player player, String prefix, String suffix, int priority) {
        UUID uuid = player.getUniqueId();
        String playerName = player.getName();

        // Remove from old team first
        String oldTeam = playerTeams.get(uuid);
        if (oldTeam != null) {
            removeFromTeam(playerName, oldTeam);
        }

        // Calculate team name (sorted by priority for tab order)
        String sortKey = String.format("%04d", 9999 - Math.max(0, Math.min(9999, priority)));
        String teamName = TEAM_PREFIX_STR + sortKey;
        if (teamName.length() > 16) {
            teamName = teamName.substring(0, 16);
        }

        // Build team info with prefix/suffix
        Component prefixComp = LegacyComponentSerializer.legacySection().deserialize(
                prefix.replace('&', '\u00A7')
        );
        Component suffixComp = LegacyComponentSerializer.legacySection().deserialize(
                suffix.replace('&', '\u00A7')
        );

        WrapperPlayServerTeams.ScoreBoardTeamInfo info = new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                Component.text(teamName),
                prefixComp,
                suffixComp,
                WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
                WrapperPlayServerTeams.CollisionRule.ALWAYS,
                null,
                WrapperPlayServerTeams.OptionData.NONE
        );

        TeamData existing = teamCache.get(teamName);
        if (existing == null) {
            // Create new team with this player
            WrapperPlayServerTeams createPacket = new WrapperPlayServerTeams(
                    teamName,
                    WrapperPlayServerTeams.TeamMode.CREATE,
                    Optional.of(info),
                    List.of(playerName)
            );
            broadcastPacket(createPacket);
            teamCache.put(teamName, new TeamData(info, new HashSet<>(Set.of(playerName))));
        } else {
            // Update team info
            WrapperPlayServerTeams updatePacket = new WrapperPlayServerTeams(
                    teamName,
                    WrapperPlayServerTeams.TeamMode.UPDATE,
                    Optional.of(info),
                    Collections.emptyList()
            );
            broadcastPacket(updatePacket);

            // Add player to team
            if (!existing.members.contains(playerName)) {
                WrapperPlayServerTeams addPacket = new WrapperPlayServerTeams(
                        teamName,
                        WrapperPlayServerTeams.TeamMode.ADD_ENTITIES,
                        Optional.empty(),
                        List.of(playerName)
                );
                broadcastPacket(addPacket);
                existing.members.add(playerName);
            }

            existing.info = info;
        }

        playerTeams.put(uuid, teamName);
    }

    /**
     * Remove a player's name tag when they quit.
     */
    public void removePlayer(Player player) {
        String teamName = playerTeams.remove(player.getUniqueId());
        if (teamName != null) {
            removeFromTeam(player.getName(), teamName);
        }
    }

    /**
     * Send all existing team data to a newly joined player so they see
     * other players' name tags immediately.
     */
    public void sendExistingTeams(Player player) {
        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        if (user == null) return;

        for (Map.Entry<String, TeamData> entry : teamCache.entrySet()) {
            WrapperPlayServerTeams packet = new WrapperPlayServerTeams(
                    entry.getKey(),
                    WrapperPlayServerTeams.TeamMode.CREATE,
                    Optional.of(entry.getValue().info),
                    new ArrayList<>(entry.getValue().members)
            );
            user.sendPacket(packet);
        }
    }

    private void removeFromTeam(String playerName, String teamName) {
        TeamData team = teamCache.get(teamName);
        if (team == null) return;

        team.members.remove(playerName);

        // Send remove-entity packet
        WrapperPlayServerTeams removePacket = new WrapperPlayServerTeams(
                teamName,
                WrapperPlayServerTeams.TeamMode.REMOVE_ENTITIES,
                Optional.empty(),
                List.of(playerName)
        );
        broadcastPacket(removePacket);

        // If team is now empty, destroy it
        if (team.members.isEmpty()) {
            WrapperPlayServerTeams destroyPacket = new WrapperPlayServerTeams(
                    teamName,
                    WrapperPlayServerTeams.TeamMode.REMOVE,
                    Optional.empty(),
                    Collections.emptyList()
            );
            broadcastPacket(destroyPacket);
            teamCache.remove(teamName);
        }
    }

    private void broadcastPacket(WrapperPlayServerTeams packet) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(online);
            if (user != null) {
                user.sendPacket(packet);
            }
        }
    }

    private static class TeamData {
        WrapperPlayServerTeams.ScoreBoardTeamInfo info;
        final Set<String> members;

        TeamData(WrapperPlayServerTeams.ScoreBoardTeamInfo info, Set<String> members) {
            this.info = info;
            this.members = members;
        }
    }
}
