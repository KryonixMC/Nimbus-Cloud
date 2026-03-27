package dev.nimbus.console

import dev.nimbus.config.NimbusConfig
import dev.nimbus.console.commands.*
import dev.nimbus.event.EventBus
import dev.nimbus.event.NimbusEvent
import dev.nimbus.group.GroupManager
import dev.nimbus.service.ServiceManager
import dev.nimbus.service.ServiceRegistry
import dev.nimbus.template.SoftwareResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.slf4j.LoggerFactory
import java.nio.file.Path

class NimbusConsole(
    private val config: NimbusConfig,
    private val serviceManager: ServiceManager,
    private val registry: ServiceRegistry,
    private val groupManager: GroupManager,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
    private val groupsDir: Path? = null,
    private val softwareResolver: SoftwareResolver? = null
) {

    private val logger = LoggerFactory.getLogger(NimbusConsole::class.java)

    private val dispatcher = CommandDispatcher()
    private lateinit var terminal: Terminal
    private lateinit var lineReader: LineReader
    private var eventListenerJob: Job? = null

    private fun setupTerminal() {
        terminal = TerminalBuilder.builder()
            .system(true)
            .dumb(true)
            .build()

        val completer = Completer { reader, line, candidates ->
            val buffer = line.line().substring(0, line.cursor())
            val completions = dispatcher.complete(buffer)
            completions.forEach { candidates.add(Candidate(it)) }
        }

        lineReader = LineReaderBuilder.builder()
            .terminal(terminal)
            .completer(completer)
            .variable(LineReader.HISTORY_FILE, Path.of(config.console.historyFile))
            .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
            .build()
    }

    private fun registerCommands() {
        // Wire up contextual tab completion
        dispatcher.registry = registry
        dispatcher.groupManager = groupManager

        // Help is registered first so it appears at the top
        val helpCommand = HelpCommand(dispatcher)

        dispatcher.register(helpCommand)
        dispatcher.register(StatusCommand(config, registry, groupManager))
        dispatcher.register(ListCommand(registry))
        dispatcher.register(GroupsCommand(groupManager, registry))
        dispatcher.register(InfoCommand(groupManager, registry))
        dispatcher.register(StartCommand(serviceManager, groupManager))
        dispatcher.register(StopCommand(serviceManager, registry))
        dispatcher.register(RestartCommand(serviceManager, registry))
        dispatcher.register(ScreenCommand(serviceManager, registry, terminal))
        dispatcher.register(ExecCommand(serviceManager, registry))
        dispatcher.register(PlayersCommand(registry))
        dispatcher.register(SendCommand(serviceManager, registry, groupManager))
        dispatcher.register(LogsCommand(serviceManager, registry))
        if (groupsDir != null) {
            dispatcher.register(ReloadCommand(groupManager, registry, groupsDir))
        }
        if (groupsDir != null && softwareResolver != null) {
            val templatesDir = java.nio.file.Path.of(config.paths.templates)
            dispatcher.register(CreateGroupCommand(terminal, groupManager, serviceManager, softwareResolver, groupsDir, templatesDir))
        }
        dispatcher.register(ClearCommand(terminal))
        dispatcher.register(ShutdownCommand(serviceManager, registry))

        logger.info("Registered {} commands", dispatcher.getCommands().size)
    }

    private fun setupEventListener() {
        if (!config.console.logEvents) return

        eventListenerJob = scope.launch {
            eventBus.subscribe().collect { event ->
                val formatted = ConsoleFormatter.formatEvent(event)
                // Print above the current input line
                lineReader.printAbove(formatted)
            }
        }
    }

    /**
     * Initializes the terminal, registers commands, prints banner, and starts event listener.
     * Call this before startMinimumInstances() so events are visible during startup.
     */
    fun init() {
        setupTerminal()
        registerCommands()

        // Print banner
        terminal.writer().print(ConsoleFormatter.banner(config.network.name))
        terminal.writer().flush()

        setupEventListener()
    }

    /**
     * Starts the interactive console REPL. Blocks until the user issues "shutdown".
     * Must call init() first.
     */
    suspend fun start() {
        if (!::terminal.isInitialized) init()

        val prompt = "${ConsoleFormatter.colorize("nimbus", ConsoleFormatter.CYAN)} > "
        var running = true

        while (running) {
            try {
                val line = lineReader.readLine(prompt)
                running = dispatcher.dispatch(line)
            } catch (_: UserInterruptException) {
                // Ctrl+C: ignore, just print a new prompt
                terminal.writer().println()
                continue
            } catch (_: EndOfFileException) {
                // Ctrl+D: treat as shutdown
                running = false
            } catch (e: Exception) {
                logger.error("Console error", e)
                println(ConsoleFormatter.error("Console error: ${e.message}"))
            }
        }

        // Cleanup
        eventListenerJob?.cancel()
        terminal.close()
        logger.info("Console shut down")
    }
}
