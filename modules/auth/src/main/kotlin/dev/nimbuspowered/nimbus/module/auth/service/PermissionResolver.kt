package dev.nimbuspowered.nimbus.module.auth.service

import dev.nimbuspowered.nimbus.module.ModuleContext
import dev.nimbuspowered.nimbus.module.PermissionSet
import org.slf4j.LoggerFactory

/**
 * Bridges the Auth module to the Perms module across classloaders.
 *
 * Each Nimbus module JAR is loaded with its own `URLClassLoader`, so the
 * Auth module cannot hold a compile-time reference to
 * `dev.nimbuspowered.nimbus.module.perms.PermissionManager` — the class
 * simply isn't visible on the Auth classloader. We therefore locate it via
 * [ModuleContext.getServiceByClassName] and invoke `getEffectivePermissions`
 * reflectively, caching the `Method` on first success.
 */
class PermissionResolver(private val context: ModuleContext) {

    private val logger = LoggerFactory.getLogger(PermissionResolver::class.java)

    private companion object {
        const val PERMS_FQCN = "dev.nimbuspowered.nimbus.module.perms.PermissionManager"
    }

    @Volatile
    private var cachedMethod: java.lang.reflect.Method? = null

    private fun permissionManager(): Any? = context.getServiceByClassName(PERMS_FQCN)

    /** `true` when the Perms module is loaded and its manager is reachable. */
    fun isPermsAvailable(): Boolean = permissionManager() != null

    /**
     * Resolve the full set of permission nodes for the given MC UUID, ignoring
     * server/world context (dashboard operates network-wide).
     *
     * Returns [PermissionSet.EMPTY] if Perms is unavailable or the player has
     * no assignments yet — callers treat that as "no dashboard access" unless
     * the principal is an API token (which bypasses permission checks).
     */
    fun resolve(uuid: String): PermissionSet {
        val mgr = permissionManager() ?: run {
            logger.debug("Perms module not available — returning empty permission set for {}", uuid)
            return PermissionSet.EMPTY
        }
        val method = cachedMethod ?: runCatching {
            mgr.javaClass.getMethod(
                "getEffectivePermissions",
                String::class.java,
                String::class.java,
                String::class.java
            )
        }.getOrNull()?.also { cachedMethod = it } ?: run {
            logger.warn("PermissionManager present but getEffectivePermissions(String,String,String) not found")
            return PermissionSet.EMPTY
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            val nodes = method.invoke(mgr, uuid, null, null) as? Set<String> ?: emptySet()
            PermissionSet(nodes)
        } catch (e: Exception) {
            logger.warn("Failed to resolve permissions for {}: {}", uuid, e.message)
            PermissionSet.EMPTY
        }
    }
}
