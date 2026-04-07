package dev.nimbuspowered.nimbus.console

import dev.nimbuspowered.nimbus.module.CommandOutput

/**
 * [CommandOutput] implementation that prints to stdout with ANSI colors
 * using [ConsoleFormatter].
 */
class ConsoleOutput : CommandOutput {

    override fun header(text: String) {
        println(ConsoleFormatter.header(text))
    }

    override fun info(text: String) {
        println(ConsoleFormatter.info(text))
    }

    override fun success(text: String) {
        println(ConsoleFormatter.success(text))
    }

    override fun error(text: String) {
        println(ConsoleFormatter.error(text))
    }

    override fun item(text: String) {
        println(text)
    }

    override fun text(text: String) {
        println(text)
    }
}
