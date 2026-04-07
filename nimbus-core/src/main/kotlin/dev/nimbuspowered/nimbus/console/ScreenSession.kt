package dev.nimbuspowered.nimbus.console

import dev.nimbuspowered.nimbus.service.ServiceHandle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    suspend fun attach(serviceName: String, processHandle: ServiceHandle, terminal: Terminal) {
        val writer = terminal.writer()
        writer.println()
        writer.println(ConsoleFormatter.info("Attached to $serviceName") +
                " " + ConsoleFormatter.colorize("(ESC or Ctrl+Q to detach)", ConsoleFormatter.DIM))
        writer.println(ConsoleFormatter.separator())
        writer.flush()

        val reader = terminal.reader()
        val previousAttributes = terminal.enterRawMode()

        try {
            coroutineScope {
                val prompt = "${ConsoleFormatter.CYAN}$serviceName${ConsoleFormatter.RESET}${ConsoleFormatter.DIM} >${ConsoleFormatter.RESET} "
                val buffer = StringBuilder()
                val mutex = Mutex()

                // Clear current prompt line, print content, then redraw prompt + buffer
                fun printWithPrompt(text: String) {
                    // Move to start of line and clear it
                    writer.print("\r\u001B[2K")
                    writer.println(text)
                    // Redraw prompt + current input buffer
                    writer.print("$prompt$buffer")
                    writer.flush()
                }

                // Show initial prompt
                writer.print(prompt)
                writer.flush()

                // Collect stdout and print to terminal
                val outputJob = launch {
                    processHandle.stdoutLines.collect { line ->
                        mutex.withLock { printWithPrompt(line) }
                    }
                }

                // Read terminal input and send to process
                val inputJob = launch(Dispatchers.IO) {
                    while (isActive) {
                        val ch = reader.read()
                        if (ch == -1) break
                        if (ch == CTRL_Q || ch == ESC) {
                            cancel()
                            break
                        }
                        mutex.withLock {
                            if (ch == '\r'.code || ch == '\n'.code) {
                                val command = buffer.toString()
                                buffer.clear()
                                processHandle.sendCommand(command)
                                writer.println()
                                writer.print(prompt)
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
                }

                // Wait for either job to finish, then cancel the other
                kotlinx.coroutines.selects.select {
                    outputJob.onJoin {}
                    inputJob.onJoin {}
                }
                outputJob.cancel()
                inputJob.cancel()
            }
        } catch (_: CancellationException) {
            // Normal detach
        } finally {
            terminal.setAttributes(previousAttributes)
            writer.println()
            writer.println(ConsoleFormatter.separator())
            writer.println(ConsoleFormatter.info("Detached from $serviceName"))
            writer.flush()
        }
    }

}
