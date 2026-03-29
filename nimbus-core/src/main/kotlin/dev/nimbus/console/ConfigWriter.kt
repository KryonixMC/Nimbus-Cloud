package dev.nimbus.console

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Simple TOML config writer for updating nimbus.toml sections from CLI commands.
 */
object ConfigWriter {

    fun updateSection(configPath: Path, section: String, values: Map<String, String>) {
        if (!configPath.exists()) {
            val content = buildString {
                appendLine("[$section]")
                for ((key, value) in values) {
                    appendLine("$key = $value")
                }
            }
            configPath.writeText(content)
            return
        }

        val lines = configPath.readText().lines().toMutableList()
        val sectionHeader = "[$section]"
        val sectionIdx = lines.indexOfFirst { it.trim() == sectionHeader }

        val newSection = buildList {
            add(sectionHeader)
            for ((key, value) in values) {
                add("$key = $value")
            }
        }

        if (sectionIdx >= 0) {
            var endIdx = sectionIdx + 1
            while (endIdx < lines.size && !lines[endIdx].trim().startsWith("[")) {
                endIdx++
            }
            for (i in (sectionIdx until endIdx).reversed()) {
                lines.removeAt(i)
            }
            lines.addAll(sectionIdx, newSection)
        } else {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) {
                lines.add("")
            }
            lines.addAll(newSection)
        }

        configPath.writeText(lines.joinToString("\n"))
    }

    fun updateValue(configPath: Path, section: String, key: String, value: String) {
        if (!configPath.exists()) {
            updateSection(configPath, section, mapOf(key to value))
            return
        }

        val lines = configPath.readText().lines().toMutableList()
        val sectionHeader = "[$section]"
        val sectionIdx = lines.indexOfFirst { it.trim() == sectionHeader }

        if (sectionIdx < 0) {
            updateSection(configPath, section, mapOf(key to value))
            return
        }

        var found = false
        for (i in (sectionIdx + 1) until lines.size) {
            if (lines[i].trim().startsWith("[")) break
            if (lines[i].trim().startsWith("$key ") || lines[i].trim().startsWith("$key=")) {
                lines[i] = "$key = $value"
                found = true
                break
            }
        }

        if (!found) {
            lines.add(sectionIdx + 1, "$key = $value")
        }

        configPath.writeText(lines.joinToString("\n"))
    }

    fun printRestartHint(configPath: Path) {
        println(ConsoleFormatter.YELLOW + ConsoleFormatter.BOLD +
            "  Restart Nimbus for changes to take effect." +
            ConsoleFormatter.RESET)
        println(ConsoleFormatter.colorize("  Config saved to ${configPath.fileName}", ConsoleFormatter.DIM))
    }
}
