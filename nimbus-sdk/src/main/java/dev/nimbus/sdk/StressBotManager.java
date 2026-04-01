package dev.nimbus.sdk;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import dev.nimbus.sdk.compat.SchedulerCompat;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Spawns fake player entities on the server during stress tests using ProtocolLib.
 * Bots appear in the tab list and as entities in the world.
 */
public class StressBotManager {

    private final JavaPlugin plugin;
    private final Logger logger;
    private boolean protocolLibAvailable = false;

    private final Map<String, BotInfo> activeBots = new ConcurrentHashMap<>();
    private int botIdCounter = 0;
    private int entityIdCounter = 50000;

    private static final class BotInfo {
        final UUID uuid;
        final int entityId;
        final WrappedGameProfile profile;
        final double x, y, z;
        BotInfo(UUID uuid, int entityId, WrappedGameProfile profile, double x, double y, double z) {
            this.uuid = uuid; this.entityId = entityId; this.profile = profile;
            this.x = x; this.y = y; this.z = z;
        }
    }

    public StressBotManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void start() {
        if (!Nimbus.isManaged()) return;

        protocolLibAvailable = true;
        logger.info("[StressBot] ProtocolLib detected — fake players enabled");

        Nimbus.on("STRESS_TEST_UPDATED", event -> {
            String targetGroup = event.get("targetGroup");
            String myGroup = Nimbus.group();

            if (targetGroup != null && !targetGroup.equals(myGroup)) {
                if (!activeBots.isEmpty()) {
                    SchedulerCompat.runTask(plugin, this::removeAllBots);
                }
                return;
            }

            int botsForMe = 0;
            String perServiceStr = event.get("perService");
            String myService = Nimbus.name();

            if (perServiceStr != null && !perServiceStr.isEmpty()) {
                for (String entry : perServiceStr.split(",")) {
                    String[] kv = entry.split("=", 2);
                    if (kv.length == 2 && kv[0].equals(myService)) {
                        try { botsForMe = Integer.parseInt(kv[1]); } catch (NumberFormatException ignored) {}
                        break;
                    }
                }
            }

            String simStr = event.get("simulatedPlayers");
            int totalSimulated = 0;
            if (simStr != null) {
                try { totalSimulated = Integer.parseInt(simStr); } catch (NumberFormatException ignored) {}
            }
            if (totalSimulated == 0) botsForMe = 0;

            final int target = botsForMe;
            SchedulerCompat.runTask(plugin, () -> adjustBots(target));
        });

        logger.info("[StressBot] Manager started — listening for stress test events");
    }

    private void adjustBots(int targetCount) {
        if (!protocolLibAvailable) return;

        if (targetCount == 0) {
            removeAllBots();
            return;
        }

        int current = activeBots.size();

        if (targetCount > current) {
            int toAdd = targetCount - current;
            int added = 0;
            for (int i = 0; i < toAdd; i++) {
                botIdCounter++;
                String botName = "Bot-" + botIdCounter;
                if (spawnBot(botName)) added++;
            }
            if (added > 0) {
                logger.info("[StressBot] Spawned " + added + " bots (total: " + activeBots.size() + ")");
            }
        } else if (targetCount < current) {
            int toRemove = current - targetCount;
            List<String> names = new ArrayList<>(activeBots.keySet());
            int removed = 0;
            for (String name : names) {
                if (removed >= toRemove) break;
                despawnBot(name);
                removed++;
            }
            if (removed > 0) {
                logger.info("[StressBot] Removed " + removed + " bots (total: " + activeBots.size() + ")");
            }
        }
    }

    private boolean spawnBot(String name) {
        try {
            World world = Bukkit.getWorlds().get(0);
            Location spawn = world.getSpawnLocation();

            UUID botUuid = UUID.nameUUIDFromBytes(("NimbusStress:" + name).getBytes());
            int entityId = entityIdCounter++;

            double offsetX = (activeBots.size() % 10) * 1.5 - 7.5;
            double offsetZ = (activeBots.size() / 10) * 1.5 - 7.5;
            double x = spawn.getX() + offsetX;
            double y = spawn.getY();
            double z = spawn.getZ() + offsetZ;

            WrappedGameProfile profile = new WrappedGameProfile(botUuid, name);
            BotInfo bot = new BotInfo(botUuid, entityId, profile, x, y, z);
            activeBots.put(name, bot);

            for (Player player : Bukkit.getOnlinePlayers()) {
                sendBotSpawnPackets(player, bot);
            }

            return true;
        } catch (Exception e) {
            logger.log(Level.WARNING, "[StressBot] Failed to spawn bot '" + name + "'", e);
            return false;
        }
    }

    private void despawnBot(String name) {
        BotInfo bot = activeBots.remove(name);
        if (bot == null) return;

        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                sendBotRemovePackets(player, bot);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "[StressBot] Failed to remove bot '" + name + "'", e);
        }
    }

    private void sendBotSpawnPackets(Player target, BotInfo bot) {
        try {
            ProtocolManager pm = ProtocolLibrary.getProtocolManager();

            // 1. Add to tab list — ADD_PLAYER + UPDATE_LISTED so they appear in tab
            PacketContainer playerInfo = pm.createPacket(PacketType.Play.Server.PLAYER_INFO);
            playerInfo.getPlayerInfoActions().write(0, EnumSet.of(
                    EnumWrappers.PlayerInfoAction.ADD_PLAYER,
                    EnumWrappers.PlayerInfoAction.UPDATE_LISTED
            ));
            PlayerInfoData infoData = new PlayerInfoData(
                    bot.uuid,
                    0, // latency
                    true, // listed — MUST be true to show in tab
                    EnumWrappers.NativeGameMode.SURVIVAL,
                    bot.profile,
                    null // display name (null = use profile name)
            );
            playerInfo.getPlayerInfoDataLists().write(1, List.of(infoData));
            pm.sendServerPacket(target, playerInfo);

            // 2. Spawn player entity in the world (best-effort — tab list is the priority)
            try {
                PacketContainer spawnEntity = pm.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
                spawnEntity.getIntegers().write(0, bot.entityId);
                spawnEntity.getUUIDs().write(0, bot.uuid);
                spawnEntity.getEntityTypeModifier().write(0, org.bukkit.entity.EntityType.PLAYER);
                spawnEntity.getDoubles()
                        .write(0, bot.x)
                        .write(1, bot.y)
                        .write(2, bot.z);
                pm.sendServerPacket(target, spawnEntity);
            } catch (Exception spawnEx) {
                // Entity spawn failed — tab list entry still works
                logger.fine("[StressBot] Entity spawn skipped for " + bot.profile.getName() + ": " + spawnEx.getMessage());
            }

        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            logger.log(Level.WARNING, "[StressBot] Spawn packet error for " + target.getName() + ": " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private void sendBotRemovePackets(Player target, BotInfo bot) {
        try {
            ProtocolManager pm = ProtocolLibrary.getProtocolManager();

            // 1. Remove from tab list
            PacketContainer removeInfo = pm.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
            removeInfo.getUUIDLists().write(0, List.of(bot.uuid));
            pm.sendServerPacket(target, removeInfo);

            // 2. Remove entity
            PacketContainer removeEntity = pm.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
            removeEntity.getIntLists().write(0, List.of(bot.entityId));
            pm.sendServerPacket(target, removeEntity);
        } catch (Exception e) {
            logger.log(Level.WARNING, "[StressBot] Remove packet error for " + target.getName() + ": " + e.getMessage());
        }
    }

    public void onPlayerJoin(Player player) {
        if (activeBots.isEmpty() || !protocolLibAvailable) return;

        SchedulerCompat.runForEntityLater(plugin, player, () -> {
            for (BotInfo bot : activeBots.values()) {
                sendBotSpawnPackets(player, bot);
            }
        }, 10L);
    }

    public void shutdown() {
        // On Folia there's no single "primary thread" — always schedule via compat
        SchedulerCompat.runTask(plugin, this::removeAllBots);
    }

    private void removeAllBots() {
        if (activeBots.isEmpty()) return;
        int count = activeBots.size();
        List<String> names = new ArrayList<>(activeBots.keySet());
        for (String name : names) {
            despawnBot(name);
        }
        activeBots.clear();
        botIdCounter = 0;
        logger.info("[StressBot] Removed all " + count + " bots");
    }
}
