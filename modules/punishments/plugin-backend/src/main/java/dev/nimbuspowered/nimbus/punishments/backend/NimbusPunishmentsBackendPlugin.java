package dev.nimbuspowered.nimbus.punishments.backend;

import dev.nimbuspowered.nimbus.sdk.Nimbus;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Backend-side companion to the Velocity punishments plugin.
 *
 * <p>The Velocity side handles login/connect blocks and the warn/live-kick
 * flow. Chat mute enforcement <em>can't</em> live there reliably on modern
 * clients — cancelling a signed chat message at the proxy trips the
 * <i>"A Proxy Plugin caused an illegal protocol state"</i> disconnect.
 * The canonical fix is to cancel on the backend where {@code AsyncChatEvent}
 * fires before the message is broadcast, and signature bookkeeping stays
 * consistent.
 *
 * <p>Deployment is opt-in via {@code [punishments] deploy_plugin = true} and
 * the JAR is auto-installed into every Paper/Spigot/Folia service by the
 * punishments module. The plugin has no commands, no config — just one
 * listener and a tiny HTTP cache.
 */
public class NimbusPunishmentsBackendPlugin extends JavaPlugin {

    private MuteApiClient api;

    @Override
    public void onEnable() {
        if (!Nimbus.isManaged()) {
            getLogger().warning("Not running in a Nimbus-managed service — NimbusPunishmentsBackend disabled");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        String apiUrl = System.getProperty("nimbus.api.url");
        String envToken = System.getenv("NIMBUS_API_TOKEN");
        String token = (envToken != null && !envToken.isEmpty())
                ? envToken
                : System.getProperty("nimbus.api.token", "");

        if (apiUrl == null || apiUrl.isEmpty()) {
            getLogger().warning("No nimbus.api.url — NimbusPunishmentsBackend disabled");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (apiUrl.endsWith("/")) apiUrl = apiUrl.substring(0, apiUrl.length() - 1);

        String serviceName = System.getProperty("nimbus.service.name", "");
        String groupName = System.getProperty("nimbus.service.group", serviceName);

        this.api = new MuteApiClient(apiUrl, token, serviceName, groupName, getLogger());

        getServer().getPluginManager().registerEvents(new ChatListener(this, api), this);
        getLogger().info("NimbusPunishmentsBackend enabled — muting chat for group=" + groupName + " service=" + serviceName);
    }

    public MuteApiClient getApi() {
        return api;
    }
}
