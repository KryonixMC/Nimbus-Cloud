package dev.nimbus.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * /cloud command — In-game bridge to Nimbus Core REST API.
 * Permission: nimbus.cloud (base) + nimbus.cloud.<subcommand>
 */
public class CloudCommand implements SimpleCommand {

    private final NimbusApiClient api;
    private final dev.nimbus.sdk.NimbusClient sdkClient;

    private static final Map<String, String> SUBCOMMAND_PERMISSIONS = Map.ofEntries(
            Map.entry("help",    "nimbus.cloud"),
            Map.entry("list",    "nimbus.cloud.list"),
            Map.entry("status",  "nimbus.cloud.status"),
            Map.entry("start",   "nimbus.cloud.start"),
            Map.entry("stop",    "nimbus.cloud.stop"),
            Map.entry("restart", "nimbus.cloud.restart"),
            Map.entry("exec",    "nimbus.cloud.exec"),
            Map.entry("players", "nimbus.cloud.players"),
            Map.entry("send",    "nimbus.cloud.send"),
            Map.entry("groups",  "nimbus.cloud.groups"),
            Map.entry("info",    "nimbus.cloud.info"),
            Map.entry("setstate","nimbus.cloud.setstate"),
            Map.entry("reload",  "nimbus.cloud.reload"),
            Map.entry("shutdown","nimbus.cloud.shutdown")
    );

    private static final List<String> SUBCOMMANDS = List.of(
            "help", "list", "status", "start", "stop", "restart",
            "exec", "players", "send", "groups", "info", "setstate", "reload", "shutdown"
    );

    public CloudCommand(NimbusApiClient api, dev.nimbus.sdk.NimbusClient sdkClient) {
        this.api = api;
        this.sdkClient = sdkClient;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("nimbus.cloud");
    }

    @Override
    public void execute(Invocation invocation) {
        var source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            sendHelp(invocation);
            return;
        }

        String sub = args[0].toLowerCase();

        // Permission check
        String perm = SUBCOMMAND_PERMISSIONS.get(sub);
        if (perm == null) {
            source.sendMessage(Component.text("Unknown subcommand: " + sub, NamedTextColor.RED));
            sendHelp(invocation);
            return;
        }
        if (!source.hasPermission(perm)) {
            source.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return;
        }

        switch (sub) {
            case "help"    -> sendHelp(invocation);
            case "list"    -> handleList(invocation);
            case "status"  -> handleStatus(invocation);
            case "start"   -> handleStart(invocation, args);
            case "stop"    -> handleStop(invocation, args);
            case "restart" -> handleRestart(invocation, args);
            case "exec"    -> handleExec(invocation, args);
            case "players" -> handlePlayers(invocation);
            case "send"    -> handleSend(invocation, args);
            case "groups"  -> handleGroups(invocation);
            case "info"    -> handleInfo(invocation, args);
            case "setstate"-> handleSetState(invocation, args);
            case "reload"  -> handleReload(invocation);
            case "shutdown"-> handleShutdown(invocation);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();

        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : args[0].toLowerCase();
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(partial))
                    .filter(s -> {
                        String perm = SUBCOMMAND_PERMISSIONS.get(s);
                        return perm != null && invocation.source().hasPermission(perm);
                    })
                    .toList();
        }

        // For subcommands that take service/group names, fetch suggestions async
        // Return empty for now — could add live tab completion later
        return List.of();
    }

    // ── Subcommand handlers ──────────────────────────────────────────

    private void sendHelp(Invocation invocation) {
        var source = invocation.source();
        source.sendMessage(Component.empty());
        source.sendMessage(Component.text("  Nimbus Cloud Commands", NamedTextColor.AQUA).decorate(TextDecoration.BOLD));
        source.sendMessage(Component.empty());

        record HelpEntry(String cmd, String desc, String perm) {}
        var entries = List.of(
                new HelpEntry("/cloud list",                  "List running services",     "nimbus.cloud.list"),
                new HelpEntry("/cloud status",                "Cluster overview",          "nimbus.cloud.status"),
                new HelpEntry("/cloud groups",                "List all groups",           "nimbus.cloud.groups"),
                new HelpEntry("/cloud info <group>",          "Group details",             "nimbus.cloud.info"),
                new HelpEntry("/cloud start <group>",         "Start a new instance",      "nimbus.cloud.start"),
                new HelpEntry("/cloud stop <service>",        "Stop a service",            "nimbus.cloud.stop"),
                new HelpEntry("/cloud restart <service>",     "Restart a service",         "nimbus.cloud.restart"),
                new HelpEntry("/cloud exec <service> <cmd>",  "Execute command on service","nimbus.cloud.exec"),
                new HelpEntry("/cloud players",               "List online players",       "nimbus.cloud.players"),
                new HelpEntry("/cloud send <player> <target>","Transfer a player",         "nimbus.cloud.send"),
                new HelpEntry("/cloud setstate <svc> <state>", "Set custom state on service","nimbus.cloud.setstate"),
                new HelpEntry("/cloud reload",                "Reload group configs",      "nimbus.cloud.reload"),
                new HelpEntry("/cloud shutdown",              "Shutdown the cloud",        "nimbus.cloud.shutdown")
        );

        for (var entry : entries) {
            if (source.hasPermission(entry.perm())) {
                source.sendMessage(
                        Component.text("  " + entry.cmd(), NamedTextColor.WHITE)
                                .clickEvent(ClickEvent.suggestCommand(entry.cmd().split(" <")[0]))
                                .append(Component.text(" — " + entry.desc(), NamedTextColor.GRAY))
                );
            }
        }
        source.sendMessage(Component.empty());
    }

    private void handleList(Invocation invocation) {
        var source = invocation.source();
        api.get("/api/services").thenAccept(result -> {
            if (!result.isSuccess()) {
                source.sendMessage(apiError(result));
                return;
            }
            JsonObject json = result.asJson();
            JsonArray services = json.getAsJsonArray("services");
            int count = json.get("count").getAsInt();

            source.sendMessage(Component.text("Services (" + count + ")", NamedTextColor.AQUA).decorate(TextDecoration.BOLD));

            if (count == 0) {
                source.sendMessage(Component.text("  No services running.", NamedTextColor.GRAY));
                return;
            }

            for (JsonElement el : services) {
                JsonObject svc = el.getAsJsonObject();
                String name = svc.get("name").getAsString();
                String state = svc.get("state").getAsString();
                int players = svc.get("playerCount").getAsInt();
                int port = svc.get("port").getAsInt();
                String customState = svc.has("customState") && !svc.get("customState").isJsonNull()
                        ? svc.get("customState").getAsString() : null;

                NamedTextColor stateColor = switch (state) {
                    case "READY" -> NamedTextColor.GREEN;
                    case "STARTING" -> NamedTextColor.YELLOW;
                    case "STOPPING" -> NamedTextColor.RED;
                    default -> NamedTextColor.GRAY;
                };

                var line = Component.text("  " + name, NamedTextColor.WHITE)
                        .append(Component.text(" [" + state + "]", stateColor));

                if (customState != null) {
                    NamedTextColor csColor = switch (customState) {
                        case "WAITING" -> NamedTextColor.AQUA;
                        case "INGAME" -> NamedTextColor.GOLD;
                        case "ENDING" -> NamedTextColor.LIGHT_PURPLE;
                        default -> NamedTextColor.YELLOW;
                    };
                    line = line.append(Component.text(" [" + customState + "]", csColor));
                }

                source.sendMessage(
                        line.append(Component.text(" " + players + " players", NamedTextColor.GRAY))
                                .append(Component.text(" :" + port, NamedTextColor.DARK_GRAY))
                );
            }
        });
    }

    private void handleStatus(Invocation invocation) {
        var source = invocation.source();
        api.get("/api/status").thenAccept(result -> {
            if (!result.isSuccess()) {
                source.sendMessage(apiError(result));
                return;
            }
            JsonObject json = result.asJson();

            String network = json.get("networkName").getAsString();
            boolean online = json.get("online").getAsBoolean();
            int totalServices = json.get("totalServices").getAsInt();
            int totalPlayers = json.get("totalPlayers").getAsInt();
            long uptime = json.get("uptimeSeconds").getAsLong();

            source.sendMessage(Component.empty());
            source.sendMessage(
                    Component.text("  " + network, NamedTextColor.AQUA).decorate(TextDecoration.BOLD)
                            .append(Component.text(online ? " ONLINE" : " OFFLINE", online ? NamedTextColor.GREEN : NamedTextColor.RED).decorate(TextDecoration.BOLD))
            );
            source.sendMessage(Component.text("  Services: ", NamedTextColor.GRAY).append(Component.text(totalServices, NamedTextColor.WHITE)));
            source.sendMessage(Component.text("  Players:  ", NamedTextColor.GRAY).append(Component.text(totalPlayers, NamedTextColor.WHITE)));
            source.sendMessage(Component.text("  Uptime:   ", NamedTextColor.GRAY).append(Component.text(formatUptime(uptime), NamedTextColor.WHITE)));

            JsonArray groups = json.getAsJsonArray("groups");
            if (groups != null && !groups.isEmpty()) {
                source.sendMessage(Component.empty());
                for (JsonElement el : groups) {
                    JsonObject g = el.getAsJsonObject();
                    String name = g.get("name").getAsString();
                    int instances = g.get("instances").getAsInt();
                    int maxInst = g.get("maxInstances").getAsInt();
                    int players = g.get("players").getAsInt();

                    source.sendMessage(
                            Component.text("  " + name, NamedTextColor.WHITE)
                                    .append(Component.text(" " + instances + "/" + maxInst + " instances", NamedTextColor.GRAY))
                                    .append(Component.text(" " + players + " players", NamedTextColor.GRAY))
                    );
                }
            }
            source.sendMessage(Component.empty());
        });
    }

    private void handleStart(Invocation invocation, String[] args) {
        var source = invocation.source();
        if (args.length < 2) {
            source.sendMessage(Component.text("Usage: /cloud start <group>", NamedTextColor.RED));
            return;
        }
        String group = args[1];
        source.sendMessage(Component.text("Starting instance of " + group + "...", NamedTextColor.GRAY));
        api.post("/api/services/" + group + "/start").thenAccept(result -> {
            if (result.isSuccess()) {
                String msg = result.asJson().get("message").getAsString();
                source.sendMessage(Component.text(msg, NamedTextColor.GREEN));
            } else {
                source.sendMessage(apiError(result));
            }
        });
    }

    private void handleStop(Invocation invocation, String[] args) {
        var source = invocation.source();
        if (args.length < 2) {
            source.sendMessage(Component.text("Usage: /cloud stop <service>", NamedTextColor.RED));
            return;
        }
        String service = args[1];
        source.sendMessage(Component.text("Stopping " + service + "...", NamedTextColor.GRAY));
        api.post("/api/services/" + service + "/stop").thenAccept(result -> {
            if (result.isSuccess()) {
                source.sendMessage(Component.text("Service '" + service + "' stopped.", NamedTextColor.GREEN));
            } else {
                source.sendMessage(apiError(result));
            }
        });
    }

    private void handleRestart(Invocation invocation, String[] args) {
        var source = invocation.source();
        if (args.length < 2) {
            source.sendMessage(Component.text("Usage: /cloud restart <service>", NamedTextColor.RED));
            return;
        }
        String service = args[1];
        source.sendMessage(Component.text("Restarting " + service + "...", NamedTextColor.GRAY));
        api.post("/api/services/" + service + "/restart").thenAccept(result -> {
            if (result.isSuccess()) {
                String msg = result.asJson().get("message").getAsString();
                source.sendMessage(Component.text(msg, NamedTextColor.GREEN));
            } else {
                source.sendMessage(apiError(result));
            }
        });
    }

    private void handleExec(Invocation invocation, String[] args) {
        var source = invocation.source();
        if (args.length < 3) {
            source.sendMessage(Component.text("Usage: /cloud exec <service> <command...>", NamedTextColor.RED));
            return;
        }
        String service = args[1];
        String command = String.join(" ", Arrays.copyOfRange(args, 2, args.length));

        JsonObject body = new JsonObject();
        body.addProperty("command", command);

        api.post("/api/services/" + service + "/exec", body).thenAccept(result -> {
            if (result.isSuccess()) {
                source.sendMessage(
                        Component.text("Executed on " + service + ": ", NamedTextColor.GREEN)
                                .append(Component.text(command, NamedTextColor.WHITE))
                );
            } else {
                source.sendMessage(apiError(result));
            }
        });
    }

    private void handlePlayers(Invocation invocation) {
        var source = invocation.source();
        api.get("/api/players").thenAccept(result -> {
            if (!result.isSuccess()) {
                source.sendMessage(apiError(result));
                return;
            }
            JsonObject json = result.asJson();
            JsonArray players = json.getAsJsonArray("players");
            int count = json.get("count").getAsInt();

            source.sendMessage(Component.text("Players (" + count + ")", NamedTextColor.AQUA).decorate(TextDecoration.BOLD));

            if (count == 0) {
                source.sendMessage(Component.text("  No players online.", NamedTextColor.GRAY));
                return;
            }

            for (JsonElement el : players) {
                JsonObject p = el.getAsJsonObject();
                String name = p.get("name").getAsString();
                String server = p.get("server").getAsString();

                source.sendMessage(
                        Component.text("  " + name, NamedTextColor.WHITE)
                                .append(Component.text(" @ " + server, NamedTextColor.GRAY))
                );
            }
        });
    }

    private void handleSend(Invocation invocation, String[] args) {
        var source = invocation.source();
        if (args.length < 3) {
            source.sendMessage(Component.text("Usage: /cloud send <player> <target>", NamedTextColor.RED));
            return;
        }
        String player = args[1];
        String target = args[2];

        JsonObject body = new JsonObject();
        body.addProperty("targetService", target);

        api.post("/api/players/" + player + "/send", body).thenAccept(result -> {
            if (result.isSuccess()) {
                source.sendMessage(
                        Component.text("Sent " + player + " to " + target, NamedTextColor.GREEN)
                );
            } else {
                source.sendMessage(apiError(result));
            }
        });
    }

    private void handleGroups(Invocation invocation) {
        var source = invocation.source();
        api.get("/api/groups").thenAccept(result -> {
            if (!result.isSuccess()) {
                source.sendMessage(apiError(result));
                return;
            }
            JsonArray groups = result.asJson().getAsJsonArray("groups");

            source.sendMessage(Component.text("Groups (" + groups.size() + ")", NamedTextColor.AQUA).decorate(TextDecoration.BOLD));

            for (JsonElement el : groups) {
                JsonObject g = el.getAsJsonObject();
                String name = g.get("name").getAsString();
                String software = g.get("software").getAsString();
                String version = g.get("version").getAsString();

                source.sendMessage(
                        Component.text("  " + name, NamedTextColor.WHITE)
                                .clickEvent(ClickEvent.runCommand("/cloud info " + name))
                                .append(Component.text(" " + software + " " + version, NamedTextColor.GRAY))
                );
            }
        });
    }

    private void handleInfo(Invocation invocation, String[] args) {
        var source = invocation.source();
        if (args.length < 2) {
            source.sendMessage(Component.text("Usage: /cloud info <group>", NamedTextColor.RED));
            return;
        }
        String group = args[1];
        api.get("/api/groups/" + group).thenAccept(result -> {
            if (!result.isSuccess()) {
                source.sendMessage(apiError(result));
                return;
            }
            JsonObject g = result.asJson();
            String name = g.get("name").getAsString();
            String software = g.get("software").getAsString();
            String version = g.get("version").getAsString();
            int minInst = g.get("minInstances").getAsInt();
            int maxInst = g.get("maxInstances").getAsInt();
            boolean isStatic = g.get("static").getAsBoolean();

            JsonObject resources = g.getAsJsonObject("resources");
            String memory = resources.get("memory").getAsString();
            int maxPlayers = resources.get("maxPlayers").getAsInt();

            source.sendMessage(Component.empty());
            source.sendMessage(Component.text("  " + name, NamedTextColor.AQUA).decorate(TextDecoration.BOLD));
            source.sendMessage(Component.text("  Software:  ", NamedTextColor.GRAY).append(Component.text(software + " " + version, NamedTextColor.WHITE)));
            source.sendMessage(Component.text("  Instances: ", NamedTextColor.GRAY).append(Component.text(minInst + "-" + maxInst, NamedTextColor.WHITE)));
            source.sendMessage(Component.text("  Memory:    ", NamedTextColor.GRAY).append(Component.text(memory, NamedTextColor.WHITE)));
            source.sendMessage(Component.text("  Max slots: ", NamedTextColor.GRAY).append(Component.text(maxPlayers, NamedTextColor.WHITE)));
            source.sendMessage(Component.text("  Static:    ", NamedTextColor.GRAY).append(Component.text(isStatic ? "Yes" : "No", NamedTextColor.WHITE)));
            source.sendMessage(Component.empty());
        });
    }

    private void handleSetState(Invocation invocation, String[] args) {
        var source = invocation.source();
        if (args.length < 3) {
            source.sendMessage(Component.text("Usage: /cloud setstate <service> <state|clear>", NamedTextColor.RED));
            return;
        }
        String service = args[1];
        String state = args[2];

        if (state.equalsIgnoreCase("clear") || state.equalsIgnoreCase("null")) {
            sdkClient.clearCustomState(service).thenRun(() -> {
                source.sendMessage(Component.text("Cleared custom state on " + service, NamedTextColor.GREEN));
            }).exceptionally(e -> {
                source.sendMessage(Component.text("Error: " + e.getMessage(), NamedTextColor.RED));
                return null;
            });
        } else {
            sdkClient.setCustomState(service, state.toUpperCase()).thenRun(() -> {
                source.sendMessage(
                        Component.text("Set custom state on " + service + ": ", NamedTextColor.GREEN)
                                .append(Component.text(state.toUpperCase(), NamedTextColor.WHITE))
                );
            }).exceptionally(e -> {
                source.sendMessage(Component.text("Error: " + e.getMessage(), NamedTextColor.RED));
                return null;
            });
        }
    }

    private void handleReload(Invocation invocation) {
        var source = invocation.source();
        source.sendMessage(Component.text("Reloading group configs...", NamedTextColor.GRAY));
        api.post("/api/reload").thenAccept(result -> {
            if (result.isSuccess()) {
                source.sendMessage(Component.text("Configs reloaded.", NamedTextColor.GREEN));
            } else {
                source.sendMessage(apiError(result));
            }
        });
    }

    private void handleShutdown(Invocation invocation) {
        var source = invocation.source();

        // Extra confirmation — only players can accidentally trigger this
        if (invocation.source() instanceof Player) {
            source.sendMessage(
                    Component.text("Are you sure? This will shut down the entire cloud.", NamedTextColor.RED).decorate(TextDecoration.BOLD)
            );
            source.sendMessage(
                    Component.text("  Click to confirm: ", NamedTextColor.GRAY)
                            .append(Component.text("[CONFIRM SHUTDOWN]", NamedTextColor.DARK_RED)
                                    .decorate(TextDecoration.BOLD)
                                    .clickEvent(ClickEvent.runCommand("/cloud-shutdown-confirm")))
            );
            return;
        }

        executeShutdown(source);
    }

    void executeShutdown(com.velocitypowered.api.command.CommandSource source) {
        source.sendMessage(Component.text("Shutting down Nimbus...", NamedTextColor.RED));
        api.post("/api/shutdown").thenAccept(result -> {
            if (result.isSuccess()) {
                source.sendMessage(Component.text("Shutdown initiated.", NamedTextColor.GREEN));
            } else {
                source.sendMessage(apiError(result));
            }
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static Component apiError(NimbusApiClient.ApiResult result) {
        if (result.statusCode() == -1) {
            return Component.text("Could not reach Nimbus API: " + result.body(), NamedTextColor.RED);
        }
        try {
            JsonObject json = result.asJson();
            if (json.has("message")) {
                return Component.text(json.get("message").getAsString(), NamedTextColor.RED);
            }
        } catch (Exception ignored) {}
        return Component.text("API error (HTTP " + result.statusCode() + ")", NamedTextColor.RED);
    }

    private static String formatUptime(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) return hours + "h " + minutes + "m " + secs + "s";
        if (minutes > 0) return minutes + "m " + secs + "s";
        return secs + "s";
    }
}
