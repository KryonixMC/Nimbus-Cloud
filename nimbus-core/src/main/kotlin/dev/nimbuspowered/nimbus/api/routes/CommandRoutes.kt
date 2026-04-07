package dev.nimbuspowered.nimbus.api.routes

import dev.nimbuspowered.nimbus.api.*
import dev.nimbuspowered.nimbus.api.ApiErrors
import dev.nimbuspowered.nimbus.api.apiError
import dev.nimbuspowered.nimbus.console.CommandDispatcher
import dev.nimbuspowered.nimbus.module.CommandOutput
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.commandRoutes(dispatcher: CommandDispatcher) {

    route("/api/commands") {

        // GET /api/commands — List all commands exposed to the Bridge
        get {
            val commands = dispatcher.getCommands()
                .filter { it.permission.isNotEmpty() }
                .map { cmd ->
                    CommandMetaResponse(
                        name = cmd.name,
                        description = cmd.description,
                        usage = cmd.usage,
                        permission = cmd.permission,
                        subcommands = cmd.subcommandMeta.map { sub ->
                            CommandSubcommandResponse(
                                path = sub.path,
                                description = sub.description,
                                usage = sub.usage,
                                completions = sub.completions.map { c ->
                                    CommandCompletionResponse(
                                        position = c.position,
                                        type = c.type.name
                                    )
                                }
                            )
                        }
                    )
                }
            call.respond(CommandListResponse(commands, commands.size))
        }

        // POST /api/commands/{name}/execute — Execute a command with output capture
        post("{name}/execute") {
            val name = call.parameters["name"]!!
            val command = dispatcher.getCommand(name)

            if (command == null || command.permission.isEmpty()) {
                call.respond(HttpStatusCode.NotFound, apiError("Command '$name' not found", ApiErrors.COMMAND_NOT_FOUND))
                return@post
            }

            val request = call.receive<CommandExecuteRequest>()
            val collector = CollectorOutput()

            try {
                val handled = command.execute(request.args, collector)
                if (!handled) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        apiError("Command '$name' does not support remote execution", ApiErrors.COMMAND_NOT_REMOTE)
                    )
                    return@post
                }
                call.respond(CommandExecuteResponse(success = true, lines = collector.lines))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    CommandExecuteResponse(
                        success = false,
                        lines = listOf(OutputLineResponse("error", e.message ?: "Unknown error"))
                    )
                )
            }
        }
    }
}

/**
 * [CommandOutput] implementation that collects typed output lines
 * for serialization in API responses.
 */
private class CollectorOutput : CommandOutput {
    val lines = mutableListOf<OutputLineResponse>()

    override fun header(text: String) { lines.add(OutputLineResponse("header", text)) }
    override fun info(text: String) { lines.add(OutputLineResponse("info", text)) }
    override fun success(text: String) { lines.add(OutputLineResponse("success", text)) }
    override fun error(text: String) { lines.add(OutputLineResponse("error", text)) }
    override fun item(text: String) { lines.add(OutputLineResponse("item", text)) }
    override fun text(text: String) { lines.add(OutputLineResponse("text", text)) }
}
