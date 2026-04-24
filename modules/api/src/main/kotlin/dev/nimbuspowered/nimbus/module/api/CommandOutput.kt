package dev.nimbuspowered.nimbus.module.api

/**
 * Abstraction for command output that supports typed lines.
 * Console implementations map to ANSI colors, API implementations collect structured output.
 */
interface CommandOutput {
    /** Bold header line (e.g. section titles). */
    fun header(text: String)

    /** Informational line (neutral/gray). */
    fun info(text: String)

    /** Success message (green). */
    fun success(text: String)

    /** Error message (red). */
    fun error(text: String)

    /** List item (white/default). */
    fun item(text: String)

    /** Plain text line (no special formatting). */
    fun text(text: String)
}

/**
 * Metadata for a subcommand, used by the Bridge for tab completion and help.
 */
data class SubcommandMeta(
    /** Subcommand path relative to the parent command, e.g. "group list" or "user info". */
    val path: String,
    /** Short description of what this subcommand does. */
    val description: String,
    /** Full usage string, e.g. "perms group info <name>". */
    val usage: String,
    /** Argument completion hints for this subcommand. */
    val completions: List<CompletionMeta> = emptyList()
)

/**
 * Describes how to complete a specific argument position.
 */
data class CompletionMeta(
    /** Argument position (0-based) within the subcommand's own args. */
    val position: Int,
    /** The type of completion to offer. */
    val type: CompletionType
)

/** Types of argument completion the Bridge can provide. */
enum class CompletionType {
    /** Online player names. */
    PLAYER,
    /** Server group names. */
    GROUP,
    /** Permission group names (fetched from /api/permissions/groups). */
    PERMISSION_GROUP,
    /** No specific completion — free text input. */
    FREE_TEXT
}
