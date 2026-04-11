package dev.nimbuspowered.nimbus.console

import dev.nimbuspowered.nimbus.api.NimbusApi
import dev.nimbuspowered.nimbus.cluster.NodeManager
import dev.nimbuspowered.nimbus.config.NimbusConfig
import dev.nimbuspowered.nimbus.console.commands.*
import dev.nimbuspowered.nimbus.event.EventBus
import dev.nimbuspowered.nimbus.event.NimbusEvent
import dev.nimbuspowered.nimbus.group.GroupManager
import dev.nimbuspowered.nimbus.loadbalancer.TcpLoadBalancer
import dev.nimbuspowered.nimbus.service.CompatibilityChecker
import dev.nimbuspowered.nimbus.service.DedicatedServiceManager
import dev.nimbuspowered.nimbus.service.PortAllocator
import dev.nimbuspowered.nimbus.service.ServiceManager
import dev.nimbuspowered.nimbus.service.ServiceRegistry
import dev.nimbuspowered.nimbus.module.ModuleManager
import dev.nimbuspowered.nimbus.scaling.ScalingEngine
import dev.nimbuspowered.nimbus.stress.StressTestManager
import dev.nimbuspowered.nimbus.template.SoftwareResolver
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
    private val softwareResolver: SoftwareResolver? = null,
    private val api: NimbusApi? = null,
    private val proxySyncManager: dev.nimbuspowered.nimbus.proxy.ProxySyncManager? = null,
    private val nodeManager: NodeManager? = null,
    private val clusterServer: dev.nimbuspowered.nimbus.cluster.ClusterServer? = null,
    private val loadBalancer: TcpLoadBalancer? = null,
    private val configPath: Path? = null,
    private val stressTestManager: StressTestManager? = null,
    private val moduleManager: ModuleManager? = null,
    private val scalingEngine: ScalingEngine? = null,
    private val sharedDispatcher: CommandDispatcher? = null,
    private val dedicatedServiceManager: DedicatedServiceManager? = null,
    private val dedicatedDir: Path? = null,
    private val portAllocator: PortAllocator? = null
) {

    private val logger = LoggerFactory.getLogger(NimbusConsole::class.java)

    private val dispatcher = sharedDispatcher ?: CommandDispatcher()
    private lateinit var terminal: Terminal
    private lateinit var lineReader: LineReader
    private var eventListenerJob: Job? = null
    @Volatile var eventsPaused: Boolean = false
    private val eventBuffer = mutableListOf<String>()

    private fun setupTerminal() {
        terminal = TerminalBuilder.builder()
            .system(true)
            .dumb(true)
            .encoding(Charsets.UTF_8)
            .build()

        // Route System.out through JLine's terminal output so that println() in commands
        // uses the same encoding as the terminal (fixes Unicode symbols on Windows)
        System.setOut(java.io.PrintStream(terminal.output(), true, terminal.encoding()))

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
        dispatcher.register(StatusCommand(config, registry, groupManager, nodeManager, loadBalancer))
        dispatcher.register(ListCommand(registry, clusterEnabled = nodeManager != null))
        dispatcher.register(GroupsCommand(groupManager, registry))
        dispatcher.register(InfoCommand(groupManager, registry, config))
        dispatcher.register(StartCommand(serviceManager, groupManager))
        dispatcher.register(StopCommand(serviceManager, registry))
        dispatcher.register(RestartCommand(serviceManager, registry))
        dispatcher.register(MigrateCommand(serviceManager, registry))
        dispatcher.register(PurgeCommand(serviceManager, registry))
        dispatcher.register(ScreenCommand(serviceManager, registry, terminal))
        dispatcher.register(ExecCommand(serviceManager, registry))
        dispatcher.register(HealthCommand(registry, groupManager, serviceManager))
        dispatcher.register(SendCommand(serviceManager, registry, groupManager))
        dispatcher.register(LogsCommand(serviceManager, registry))
        if (groupsDir != null) {
            dispatcher.register(ReloadCommand(groupManager, registry, groupsDir, proxySyncManager, eventBus))
        }
        if (groupsDir != null && softwareResolver != null) {
            val templatesDir = java.nio.file.Path.of(config.paths.templates)
            dispatcher.register(CreateGroupCommand(terminal, groupManager, serviceManager, softwareResolver, groupsDir, templatesDir, this, config.curseforge.apiKey))
            dispatcher.register(ImportCommand(terminal, groupManager, serviceManager, softwareResolver, groupsDir, templatesDir, this, config.curseforge.apiKey))
            dispatcher.register(UpdateCommand(terminal, groupManager, registry, softwareResolver, groupsDir, templatesDir, this))
            dispatcher.register(PluginsCommand(config, registry, groupManager, softwareResolver, terminal))
        }
        if (groupsDir != null) {
            dispatcher.register(StaticCommand(serviceManager, registry, groupManager, groupsDir))
            dispatcher.register(DynamicCommand(registry, groupManager, groupsDir))
        }
        if (api != null) {
            dispatcher.register(ApiCommand(api))
        }
        if (proxySyncManager != null) {
            dispatcher.register(MaintenanceCommand(proxySyncManager, groupManager, eventBus))
        }
        if (nodeManager != null) {
            dispatcher.register(NodesCommand(nodeManager, registry))
        }
        if (configPath != null) {
            dispatcher.register(LbCommand(config, configPath, loadBalancer, registry, groupManager))
            dispatcher.register(ClusterCommand(config, configPath, nodeManager, registry, clusterServer))
        }
        if (stressTestManager != null) {
            dispatcher.register(StressCommand(stressTestManager, registry, groupManager))
        }
        if (dedicatedServiceManager != null && dedicatedDir != null && portAllocator != null) {
            dispatcher.register(DedicatedCommand(terminal, dedicatedServiceManager, serviceManager, registry, groupManager, portAllocator, dedicatedDir, this, eventBus))
        }
        if (moduleManager != null) {
            val templatesPath = java.nio.file.Path.of(config.paths.templates)
            dispatcher.register(ModulesCommand(moduleManager, terminal, groupManager, templatesPath))
        }
        dispatcher.register(VersionCommand())
        dispatcher.register(ClearCommand(terminal))
        dispatcher.register(ShutdownCommand(serviceManager, registry, scalingEngine))

        logger.info("Registered {} commands", dispatcher.getCommands().size)
    }

    private fun setupEventListener() {
        if (!config.console.logEvents) return

        eventListenerJob = scope.launch {
            eventBus.subscribe().collect { event ->
                // Suppress high-frequency / noisy events to avoid console spam
                if (event is NimbusEvent.PlayerConnected) return@collect
                if (event is NimbusEvent.PlayerDisconnected) return@collect
                if (event is NimbusEvent.PlayerServerSwitch) return@collect
                if (event is NimbusEvent.StressTestUpdated) return@collect
                if (event is NimbusEvent.MotdUpdated && stressTestManager?.isActive() == true) return@collect

                val formatted = ConsoleFormatter.formatEvent(event)
                if (eventsPaused) {
                    synchronized(eventBuffer) { eventBuffer.add(formatted) }
                } else {
                    lineReader.printAbove(formatted)
                }
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

        // Clear screen and print banner at the top
        terminal.writer().print("\u001B[2J\u001B[H")
        terminal.writer().print(ConsoleFormatter.banner(config.network.name))
        terminal.writer().flush()

        setupEventListener()
    }

    /**
     * Clears the screen and reprints the banner — used to return from wizard screens.
     */
    fun reprintBanner() {
        terminal.writer().print("\u001B[2J\u001B[H")
        terminal.writer().print(ConsoleFormatter.banner(config.network.name))
        terminal.writer().flush()
    }

    /**
     * Prints compatibility warnings directly to the console terminal.
     */
    fun printCompatWarnings(warnings: List<CompatibilityChecker.CompatWarning>) {
        if (warnings.isEmpty()) return
        val w = terminal.writer()

        for (warning in warnings) {
            val (icon, color) = when (warning.level) {
                CompatibilityChecker.CompatWarning.Level.ERROR -> "[!]" to ConsoleFormatter.RED
                CompatibilityChecker.CompatWarning.Level.WARN -> "[!]" to ConsoleFormatter.YELLOW
                CompatibilityChecker.CompatWarning.Level.INFO -> "[i]" to ConsoleFormatter.CYAN
            }

            w.print("$color$icon${ConsoleFormatter.RESET} ")
            w.print("$color${ConsoleFormatter.BOLD}${warning.title}${ConsoleFormatter.RESET}")
            if (warning.detail.isNotEmpty()) {
                w.print(" ${ConsoleFormatter.DIM}— ${warning.detail.lines().joinToString(" ")}${ConsoleFormatter.RESET}")
            }
            w.println()
        }
        w.flush()
    }

    /**
     * Flushes buffered events that were collected while events were paused.
     * Call this after a wizard finishes to show what happened in the background.
     */
    fun flushBufferedEvents() {
        val buffered: List<String>
        synchronized(eventBuffer) {
            buffered = eventBuffer.toList()
            eventBuffer.clear()
        }
        if (buffered.isNotEmpty()) {
            for (event in buffered) {
                lineReader.printAbove(event)
            }
        }
    }

    /**
     * Starts the interactive console REPL. Blocks until the user issues "shutdown".
     * Must call init() first.
     */
    suspend fun start() {
        if (!::terminal.isInitialized) init()

        val prompt = "${ConsoleFormatter.BRIGHT_CYAN}${ConsoleFormatter.BOLD}nimbus${ConsoleFormatter.RESET} ${ConsoleFormatter.CYAN}»${ConsoleFormatter.RESET} "
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
