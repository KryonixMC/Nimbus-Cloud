package dev.nimbuspowered.nimbus.cli

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds

private const val RESET = "\u001B[0m"
private const val RED = "\u001B[31m"
private const val GREEN = "\u001B[32m"
private const val CYAN = "\u001B[36m"
private const val BOLD = "\u001B[1m"
private const val DIM = "\u001B[2m"
private const val BRIGHT_CYAN = "\u001B[96m"

fun main(args: Array<String>) {
    val parsed = parseArgs(args)

    if (parsed.help) {
        printUsage()
        return
    }

    if (parsed.version) {
        val version = object {}::class.java.`package`?.implementationVersion ?: "dev"
        println("Nimbus CLI v$version")
        return
    }

    // Load config and resolve profile
    val config = CliConfig.load()

    if (parsed.saveProfile != null) {
        saveProfile(config, parsed)
        return
    }

    if (parsed.listProfiles) {
        listProfiles(config)
        return
    }

    val profileName = parsed.profile ?: config.defaultProfile
    val baseProfile = config.profiles[profileName] ?: ConnectionProfile()

    // CLI args override profile settings
    val profile = ConnectionProfile(
        host = parsed.host ?: baseProfile.host,
        port = parsed.port ?: baseProfile.port,
        token = parsed.token ?: baseProfile.token
    )

    // Interactive setup if token is missing (no args, no saved profile)
    val finalProfile = if (profile.token.isBlank()) {
        interactiveSetup(config, profileName, profile)
    } else {
        profile
    }

    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
        install(WebSockets) {
            pingInterval = 15_000.milliseconds
        }
        engine {
            requestTimeout = 10_000
        }
    }

    val baseUrl = "http://${finalProfile.host}:${finalProfile.port}"

    val completionClient = CompletionClient(httpClient, baseUrl, finalProfile.token)

    lateinit var console: CliConsole

    val streamClient = StreamClient(
        httpClient = httpClient,
        host = finalProfile.host,
        port = finalProfile.port,
        token = finalProfile.token,
        onEvent = { text -> console.printAbove(text) },
        onScreenLine = { text -> println(text) }
    )

    console = CliConsole(completionClient, streamClient, finalProfile)

    runBlocking {
        console.start()
    }

    httpClient.close()
}

private data class ParsedArgs(
    val host: String? = null,
    val port: Int? = null,
    val token: String? = null,
    val profile: String? = null,
    val saveProfile: String? = null,
    val listProfiles: Boolean = false,
    val help: Boolean = false,
    val version: Boolean = false
)

private fun parseArgs(args: Array<String>): ParsedArgs {
    var host: String? = null
    var port: Int? = null
    var token: String? = null
    var profile: String? = null
    var saveProfile: String? = null
    var listProfiles = false
    var help = false
    var version = false

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host", "-h" -> { host = args.getOrNull(++i); }
            "--port", "-p" -> { port = args.getOrNull(++i)?.toIntOrNull(); }
            "--token", "-t" -> { token = args.getOrNull(++i); }
            "--profile" -> { profile = args.getOrNull(++i); }
            "--save-profile" -> { saveProfile = args.getOrNull(++i); }
            "--list-profiles" -> { listProfiles = true; }
            "--help" -> { help = true; }
            "--version", "-v" -> { version = true; }
        }
        i++
    }

    return ParsedArgs(host, port, token, profile, saveProfile, listProfiles, help, version)
}

private fun printUsage() {
    println("${BOLD}${BRIGHT_CYAN}Nimbus Remote CLI$RESET")
    println()
    println("${BOLD}Usage:$RESET nimbus-cli [options]")
    println()
    println("${BOLD}Connection:$RESET")
    println("  --host, -h <host>      Controller host (default: 127.0.0.1)")
    println("  --port, -p <port>      Controller API port (default: 8080)")
    println("  --token, -t <token>    API authentication token")
    println()
    println("${BOLD}Profiles:$RESET")
    println("  --profile <name>       Use a saved connection profile")
    println("  --save-profile <name>  Save current connection as a profile")
    println("  --list-profiles        List all saved profiles")
    println()
    println("${BOLD}Other:$RESET")
    println("  --version, -v          Show version")
    println("  --help                 Show this help")
    println()
    println("${BOLD}Examples:$RESET")
    println("  ${DIM}# Connect with explicit credentials$RESET")
    println("  nimbus-cli --host 192.168.1.10 --port 8080 --token abc123")
    println()
    println("  ${DIM}# Save a profile for reuse$RESET")
    println("  nimbus-cli --host 192.168.1.10 --token abc123 --save-profile prod")
    println()
    println("  ${DIM}# Connect using saved profile$RESET")
    println("  nimbus-cli --profile prod")
}

private fun interactiveSetup(config: CliConfig, profileName: String, defaults: ConnectionProfile): ConnectionProfile {
    println()
    println("${BOLD}${BRIGHT_CYAN}Nimbus Remote CLI — Connection Setup$RESET")
    println("${DIM}No connection configured. Enter your controller details.$RESET")
    println()

    print("${CYAN}Host${RESET} ${DIM}[${defaults.host}]${RESET}: ")
    val hostInput = readlnOrNull()?.trim().orEmpty()
    val host = hostInput.ifEmpty { defaults.host }

    print("${CYAN}Port${RESET} ${DIM}[${defaults.port}]${RESET}: ")
    val portInput = readlnOrNull()?.trim().orEmpty()
    val port = portInput.toIntOrNull() ?: defaults.port

    print("${CYAN}API Token${RESET}: ")
    val tokenInput = readlnOrNull()?.trim().orEmpty()

    if (tokenInput.isBlank()) {
        println("${RED}Token is required.$RESET")
        System.exit(1)
    }

    val profile = ConnectionProfile(host = host, port = port, token = tokenInput)

    // Ask to save
    println()
    print("${CYAN}Save as profile?${RESET} ${DIM}(name or empty to skip)${RESET}: ")
    val saveName = readlnOrNull()?.trim().orEmpty()

    if (saveName.isNotEmpty()) {
        val newConfig = config.copy(
            defaultProfile = saveName,
            profiles = config.profiles + (saveName to profile)
        )
        CliConfig.save(newConfig)
        println("${GREEN}Saved as '$saveName' (set as default).$RESET")
    }

    println()
    return profile
}

private fun saveProfile(config: CliConfig, parsed: ParsedArgs) {
    val name = parsed.saveProfile ?: return
    val existing = config.profiles[name] ?: ConnectionProfile()
    val updated = ConnectionProfile(
        host = parsed.host ?: existing.host,
        port = parsed.port ?: existing.port,
        token = parsed.token ?: existing.token
    )

    val newConfig = config.copy(
        profiles = config.profiles + (name to updated)
    )
    CliConfig.save(newConfig)
    println("${GREEN}Profile '$name' saved.$RESET")
    println("${DIM}  Host:  ${updated.host}$RESET")
    println("${DIM}  Port:  ${updated.port}$RESET")
    println("${DIM}  Token: ${updated.token.take(8)}...$RESET")
}

private fun listProfiles(config: CliConfig) {
    if (config.profiles.isEmpty()) {
        println("${DIM}No profiles saved.$RESET")
        return
    }

    println("${BOLD}Saved profiles:$RESET")
    for ((name, profile) in config.profiles) {
        val isDefault = if (name == config.defaultProfile) " ${GREEN}(default)$RESET" else ""
        println("  ${BOLD}$name$RESET$isDefault")
        println("    ${DIM}${profile.host}:${profile.port}  token=${profile.token.take(8)}...$RESET")
    }
}
