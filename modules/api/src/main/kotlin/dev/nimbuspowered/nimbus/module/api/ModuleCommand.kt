package dev.nimbuspowered.nimbus.module.api

/**
 * A console command registered by a module.
 * This mirrors the core Command interface so modules don't need
 * a compile-time dependency on nimbus-core.
 */
interface ModuleCommand {
    val name: String
    val description: String
    val usage: String
    suspend fun execute(args: List<String>)

    /**
     * Permission node required to use this command from the Bridge.
     * Empty string means the command is not exposed to the Bridge.
     * Example: "nimbus.cloud.perms"
     */
    val permission: String get() = ""

    /**
     * Subcommand metadata for Bridge tab completion and help display.
     * Only relevant for commands exposed to the Bridge ([permission] is set).
     */
    val subcommandMeta: List<SubcommandMeta> get() = emptyList()

    /**
     * Execute the command with typed output capture.
     * Used by the API to proxy command execution to the Bridge.
     *
     * @return true if the command handled the execution, false if not supported.
     */
    suspend fun execute(args: List<String>, output: CommandOutput): Boolean = false

    /**
     * Caller-aware overload. The default delegates to [execute] without the
     * caller — existing modules keep working untouched. Commands that need
     * the invoking player's identity (e.g. `/nimbus dashboard login`) should
     * override this.
     *
     * [caller] is `null` for console invocations and for Bridge calls from
     * non-player sources.
     */
    suspend fun execute(args: List<String>, output: CommandOutput, caller: CommandCaller?): Boolean =
        execute(args, output)
}
