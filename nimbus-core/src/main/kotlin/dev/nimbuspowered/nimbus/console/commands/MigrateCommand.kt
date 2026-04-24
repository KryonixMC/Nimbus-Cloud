package dev.nimbuspowered.nimbus.console.commands

import dev.nimbuspowered.nimbus.console.Command
import dev.nimbuspowered.nimbus.console.ConsoleOutput
import dev.nimbuspowered.nimbus.module.api.CommandOutput
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry

/**
 * `migrate <service> <targetNode>` — moves a service from its current node to
 * another cluster node. For sync-enabled services, the stop triggers a state
 * push to the controller, and the start on the target node pulls that canonical
 * copy before launching the process. Data continuity is preserved as long as
 * the source node's graceful stop completed successfully.
 */
class MigrateCommand(
    private val serviceManager: ServiceManager,
    private val registry: ServiceRegistry
) : Command {

    override val name = "migrate"
    override val description = "Move a service between cluster nodes (stop on source, push state, start on target)"
    override val usage = "migrate <service> <target-node|local>"

    override suspend fun execute(args: List<String>, output: CommandOutput): Boolean {
        if (args.size < 2) {
            output.error("Usage: $usage")
            output.info("Example: migrate Lobby-1 worker-2")
            return true
        }
        val serviceName = args[0]
        val target = args[1]

        val service = registry.get(serviceName)
        if (service == null) {
            output.error("Service '$serviceName' not found.")
            return true
        }

        output.info("Migrating '$serviceName' from ${service.nodeId} → $target...")
        try {
            val migrated = serviceManager.migrateService(serviceName, if (target == "local") null else target)
            if (migrated != null) {
                output.success("Service '${migrated.name}' now running on node '${migrated.nodeId}' (port ${migrated.port}).")
            } else {
                output.error("Migration failed — check the log for details.")
            }
        } catch (e: Exception) {
            output.error("Migration error: ${e.message}")
        }
        return true
    }

    override suspend fun execute(args: List<String>) {
        execute(args, ConsoleOutput())
    }
}
