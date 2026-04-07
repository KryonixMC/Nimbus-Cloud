package dev.nimbuspowered.nimbus.cli

import kotlinx.coroutines.*
import org.jline.reader.*
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import java.nio.file.Path

/**
 * JLine3-based interactive console for the Remote CLI.
 * Provides the same UX as the local controller console:
 * tab completion, command history, live event display, and screen sessions.
 */
class CliConsole(
    private val completionClient: CompletionClient,
    private val streamClient: StreamClient,
    private val profile: ConnectionProfile
) {
    private lateinit var terminal: Terminal
    private lateinit var lineReader: LineReader

    private val historyDir = Path.of(System.getProperty("user.home"), ".nimbus")
    private val historyFile = historyDir.resolve("cli_history")

    fun init() {
        terminal = TerminalBuilder.builder()
            .system(true)
            .dumb(true)
            .encoding(Charsets.UTF_8)
            .build()

        System.setOut(java.io.PrintStream(terminal.output(), true, terminal.encoding()))

        val completer = Completer { _, line, candidates ->
            val buffer = line.line().substring(0, line.cursor())
            // Use blocking coroutine for tab completion (JLine requires synchronous return)
            val completions = runBlocking {
                try {
                    completionClient.complete(buffer)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            completions.forEach { candidates.add(Candidate(it)) }
        }

        lineReader = LineReaderBuilder.builder()
            .terminal(terminal)
            .completer(completer)
            .variable(LineReader.HISTORY_FILE, historyFile)
            .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
            .build()
    }

    suspend fun start() {
        if (!::terminal.isInitialized) init()

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        // Connect WebSocket stream
        streamClient.connect(scope)

        // Wait briefly for connection
        delay(500)
        if (!streamClient.isConnected) {
            delay(1500)
        }

        if (!streamClient.isConnected) {
            println("${YELLOW}Warning: Could not connect to controller at ${profile.host}:${profile.port}$RESET")
            println("${DIM}Commands will fail until connection is established. Retrying in background...$RESET")
        }

        // Print connected banner
        printBanner()

        val prompt = "${BRIGHT_CYAN}${BOLD}nimbus${RESET} ${DIM}(remote)${RESET} ${CYAN}»${RESET} "
        var running = true

        while (running) {
            try {
                val line = lineReader.readLine(prompt)
                val trimmed = line.trim()

                if (trimmed.isEmpty()) continue

                // Local commands
                when (trimmed.lowercase()) {
                    "exit", "quit", "disconnect" -> {
                        running = false
                        continue
                    }
                    "clear" -> {
                        terminal.writer().print("\u001B[2J\u001B[H")
                        terminal.writer().flush()
                        continue
                    }
                    "reconnect" -> {
                        println("${CYAN}Reconnecting...$RESET")
                        streamClient.disconnect()
                        streamClient.connect(scope)
                        delay(1000)
                        if (streamClient.isConnected) {
                            println("${GREEN}Connected.$RESET")
                        } else {
                            println("${YELLOW}Still connecting...$RESET")
                        }
                        continue
                    }
                }

                // Handle screen command locally
                if (trimmed.lowercase().startsWith("screen ")) {
                    val serviceName = trimmed.substringAfter("screen ").trim()
                    if (serviceName.isNotEmpty()) {
                        handleScreenSession(serviceName, scope)
                        continue
                    }
                }

                if (!streamClient.isConnected) {
                    println("${RED}Not connected to controller.$RESET ${DIM}Type 'reconnect' to retry.$RESET")
                    continue
                }

                // Execute command remotely
                streamClient.executeCommand(trimmed) { line ->
                    val rendered = OutputRenderer.render(line.type, line.text)
                    println(rendered)
                }

            } catch (_: UserInterruptException) {
                // Ctrl+C
                if (streamClient.inScreenSession) {
                    runBlocking { streamClient.screenDetach() }
                }
                terminal.writer().println()
                continue
            } catch (_: EndOfFileException) {
                // Ctrl+D
                running = false
            } catch (e: Exception) {
                println("${RED}Error: ${e.message}$RESET")
            }
        }

        // Cleanup
        scope.cancel()
        terminal.close()
    }

    private suspend fun handleScreenSession(serviceName: String, scope: CoroutineScope) {
        if (!streamClient.isConnected) {
            println("${RED}Not connected to controller.$RESET")
            return
        }

        println("${CYAN}Attaching to $serviceName... ${DIM}(Ctrl+C or type 'exit' to detach)$RESET")
        streamClient.screenAttach(serviceName)

        // Wait for attach confirmation
        delay(500)

        if (!streamClient.inScreenSession) {
            return
        }

        val screenPrompt = "${DIM}[$serviceName]${RESET} ${CYAN}»${RESET} "

        while (streamClient.inScreenSession) {
            try {
                val input = lineReader.readLine(screenPrompt)
                val trimmed = input.trim()

                if (trimmed.lowercase() == "exit" || trimmed.lowercase() == "detach") {
                    streamClient.screenDetach()
                    delay(200)
                    println("${CYAN}Detached from $serviceName.$RESET")
                    break
                }

                if (trimmed.isNotEmpty()) {
                    streamClient.screenInput(serviceName, trimmed)
                }
            } catch (_: UserInterruptException) {
                streamClient.screenDetach()
                delay(200)
                println("${CYAN}Detached from $serviceName.$RESET")
                break
            } catch (_: EndOfFileException) {
                streamClient.screenDetach()
                break
            }
        }
    }

    private fun printBanner() {
        val status = if (streamClient.isConnected) "${GREEN}connected$RESET" else "${YELLOW}connecting...$RESET"
        println()
        println("$BOLD${BRIGHT_CYAN}  Nimbus Remote CLI$RESET")
        println("$DIM  Controller: ${profile.host}:${profile.port} — $RESET$status")
        println("$DIM  Type 'help' for commands, 'exit' to disconnect.$RESET")
        println()
    }

    /**
     * Prints a formatted line above the current prompt (for live events).
     */
    fun printAbove(text: String) {
        if (::lineReader.isInitialized) {
            lineReader.printAbove(text)
        }
    }

    companion object {
        private const val RESET = "\u001B[0m"
        private const val RED = "\u001B[31m"
        private const val GREEN = "\u001B[32m"
        private const val YELLOW = "\u001B[33m"
        private const val CYAN = "\u001B[36m"
        private const val BOLD = "\u001B[1m"
        private const val DIM = "\u001B[2m"
        private const val BRIGHT_CYAN = "\u001B[96m"
    }
}
