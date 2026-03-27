package dev.nimbus.console

import dev.nimbus.service.ProcessHandle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.jline.terminal.Terminal

class ScreenSession {

    companion object {
        private const val CTRL_Q: Int = 17
        private const val ESC: Int = 27
    }

    /**
     * Attaches to a running service's process, streaming its stdout to the terminal
     * and forwarding terminal input to the process stdin.
     *
     * Blocks until the user presses Ctrl+Q to detach or the process ends.
     */
    suspend fun attach(serviceName: String, processHandle: ProcessHandle, terminal: Terminal) {
        val writer = terminal.writer()
        writer.println()
        writer.println(ConsoleFormatter.info("Attached to $serviceName") +
                " " + ConsoleFormatter.colorize("(ESC or Ctrl+Q to detach)", ConsoleFormatter.DIM))
        writer.println(ConsoleFormatter.colorize("-".repeat(60), ConsoleFormatter.DIM))
        writer.flush()

        val reader = terminal.reader()
        val previousAttributes = terminal.enterRawMode()

        try {
            coroutineScope {
                // Collect stdout and print to terminal
                val outputJob = launch {
                    processHandle.stdoutLines.collect { line ->
                        writer.println(line)
                        writer.flush()
                    }
                }

                // Read terminal input and send to process
                val inputJob = launch(Dispatchers.IO) {
                    val buffer = StringBuilder()
                    while (isActive) {
                        val ch = reader.read()
                        if (ch == -1) break
                        if (ch == CTRL_Q || ch == ESC) {
                            cancel()
                            break
                        }
                        if (ch == '\r'.code || ch == '\n'.code) {
                            val command = buffer.toString()
                            buffer.clear()
                            processHandle.sendCommand(command)
                            writer.println()
                            writer.flush()
                        } else if (ch == 127 || ch == 8) {
                            // Backspace
                            if (buffer.isNotEmpty()) {
                                buffer.deleteCharAt(buffer.length - 1)
                                writer.print("\b \b")
                                writer.flush()
                            }
                        } else {
                            buffer.append(ch.toChar())
                            writer.print(ch.toChar())
                            writer.flush()
                        }
                    }
                }

                // Wait for either job to finish
                select(outputJob, inputJob)
                outputJob.cancel()
                inputJob.cancel()
            }
        } catch (_: CancellationException) {
            // Normal detach
        } finally {
            terminal.setAttributes(previousAttributes)
            writer.println()
            writer.println(ConsoleFormatter.colorize("-".repeat(60), ConsoleFormatter.DIM))
            writer.println(ConsoleFormatter.info("Detached from $serviceName"))
            writer.flush()
        }
    }

    private suspend fun select(vararg jobs: Job) {
        // Wait for the first job to complete
        while (jobs.all { it.isActive }) {
            delay(50)
        }
    }
}
