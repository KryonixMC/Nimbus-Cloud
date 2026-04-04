package dev.kryonix.nimbus.module

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
}
