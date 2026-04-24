package dev.nimbuspowered.nimbus.module.api

/**
 * Declares a server-side plugin that a module needs deployed to services.
 *
 * Modules register these during [NimbusModule.init] via [ModuleContext.registerPluginDeployment].
 * The core ServiceFactory deploys registered plugins when creating new service instances.
 *
 * Default [target] is [PluginTarget.BACKEND] (Paper / Purpur / Folia / etc.). Set to
 * [PluginTarget.VELOCITY] for proxy plugins (Punishments enforcement, custom Velocity
 * hooks, etc.) — the JAR format differs (Velocity uses `@Plugin` annotation, not plugin.yml).
 */
data class PluginDeployment(
    /** Resource path inside the JAR, e.g. "plugins/nimbus-perms.jar" */
    val resourcePath: String,
    /** Target file name in the service plugins dir, e.g. "nimbus-perms.jar" */
    val fileName: String,
    /** Human-readable name shown in console, e.g. "NimbusPerms" */
    val displayName: String,
    /** Minimum Minecraft minor version required (e.g. 20 for 1.20+), null = no restriction */
    val minMinecraftVersion: Int? = null,
    /** If true, PacketEvents will be auto-deployed alongside this plugin on Folia servers */
    val foliaRequiresPacketEvents: Boolean = false,
    /** Which service type this plugin targets. Defaults to Paper-based backends. */
    val target: PluginTarget = PluginTarget.BACKEND
)

/** Which service kind a [PluginDeployment] belongs on. */
enum class PluginTarget {
    /** Paper / Purpur / Pufferfish / Leaf / Folia — the usual backend software. */
    BACKEND,
    /** Velocity proxy. */
    VELOCITY
}
