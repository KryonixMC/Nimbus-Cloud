package dev.nimbuspowered.nimbus.module.api

/**
 * Optional caller identity attached to a [ModuleCommand] invocation.
 *
 * Bridge forwards this when a Velocity player runs `/nimbus <cmd>` (so
 * caller-scoped commands like `/nimbus dashboard login` know whose code to
 * issue). Console invocations and admin-only commands from the controller
 * REPL pass `null`.
 */
data class CommandCaller(
    val uuid: String,
    val name: String
)
