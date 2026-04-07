package dev.nimbuspowered.nimbus.console

import dev.nimbuspowered.nimbus.console.ConsoleFormatter.CYAN
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.DIM
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.GREEN
import dev.nimbuspowered.nimbus.console.ConsoleFormatter.RESET
import org.jline.terminal.Terminal
import org.jline.utils.NonBlockingReader

/**
 * Reusable interactive terminal pickers (single-select and multi-select).
 * Uses arrow keys for navigation, space/enter for selection.
 */
object InteractivePicker {

    data class Option(val id: String, val label: String, val hint: String = "")

    enum class Key { UP, DOWN, SPACE, ENTER, ESCAPE, OTHER }

    /** Special return value for pickOne() when user presses ESC (back). */
    const val BACK = -1

    /**
     * Single-select picker: ↑↓ navigate, enter to confirm, ESC to go back.
     * Returns the selected option index, or [BACK] if ESC was pressed.
     */
    fun pickOne(terminal: Terminal, options: List<Option>, default: Int = 0): Int {
        var cursor = default.coerceIn(0, options.size - 1)
        val w = terminal.writer()
        val originalAttrs = terminal.enterRawMode()
        val reader = terminal.reader()

        try {
            w.print("\u001B[?25l")
            w.flush()

            val lines = options.size + 1
            drawSinglePicker(w, options, cursor)

            while (true) {
                val key = readKey(reader)
                when (key) {
                    Key.UP -> cursor = (cursor - 1 + options.size) % options.size
                    Key.DOWN -> cursor = (cursor + 1) % options.size
                    Key.ENTER, Key.SPACE -> {
                        clearLines(w, lines)
                        restore(w, originalAttrs, terminal)
                        return cursor
                    }
                    Key.ESCAPE -> {
                        clearLines(w, lines)
                        restore(w, originalAttrs, terminal)
                        return BACK
                    }
                    else -> {}
                }
                clearLines(w, lines)
                drawSinglePicker(w, options, cursor)
            }
        } catch (e: Exception) {
            restore(w, originalAttrs, terminal)
            throw e
        }
    }

    /**
     * Multi-select picker: ↑↓ navigate, space toggle, enter confirm, ESC to go back.
     * Modifies [selected] in place. Returns true if confirmed, false if ESC (back).
     */
    fun pickMany(terminal: Terminal, options: List<Option>, selected: MutableSet<String>): Boolean {
        var cursor = 0
        val w = terminal.writer()
        val originalAttrs = terminal.enterRawMode()
        val reader = terminal.reader()

        try {
            w.print("\u001B[?25l")
            w.flush()

            val lines = options.size + 1
            drawMultiPicker(w, options, selected, cursor)

            while (true) {
                val key = readKey(reader)
                when (key) {
                    Key.UP -> cursor = (cursor - 1 + options.size) % options.size
                    Key.DOWN -> cursor = (cursor + 1) % options.size
                    Key.SPACE -> {
                        val id = options[cursor].id
                        if (id in selected) selected.remove(id) else selected.add(id)
                    }
                    Key.ENTER -> {
                        clearLines(w, lines)
                        restore(w, originalAttrs, terminal)
                        return true
                    }
                    Key.ESCAPE -> {
                        clearLines(w, lines)
                        restore(w, originalAttrs, terminal)
                        return false
                    }
                    else -> {}
                }
                clearLines(w, lines)
                drawMultiPicker(w, options, selected, cursor)
            }
        } catch (e: Exception) {
            restore(w, originalAttrs, terminal)
            throw e
        }
    }

    // ── Drawing ────────────────────────────────────────────

    private fun drawSinglePicker(w: java.io.Writer, options: List<Option>, cursor: Int) {
        w.write("  ${ConsoleFormatter.hint("↑↓ navigate  ·  enter select  ·  esc back")}\n")
        for ((i, opt) in options.withIndex()) {
            val isCursor = i == cursor
            val radio = if (isCursor) "${CYAN}●$RESET" else "${DIM}○$RESET"
            val pointer = if (isCursor) "${CYAN}›$RESET " else "  "
            val nameColor = if (isCursor) CYAN else ""
            val nameReset = if (isCursor) RESET else ""
            val hint = if (opt.hint.isNotEmpty()) "  ${DIM}${opt.hint}$RESET" else ""
            w.write("    $pointer$radio  $nameColor${opt.label}$nameReset$hint\n")
        }
        w.flush()
    }

    private fun drawMultiPicker(w: java.io.Writer, options: List<Option>, selected: Set<String>, cursor: Int) {
        w.write("  ${ConsoleFormatter.hint("↑↓ navigate  ·  space toggle  ·  enter confirm  ·  esc back")}\n")
        for ((i, opt) in options.withIndex()) {
            val isSelected = opt.id in selected
            val isCursor = i == cursor
            val checkbox = if (isSelected) "${GREEN}✓$RESET" else "${DIM}○$RESET"
            val pointer = if (isCursor) "${CYAN}›$RESET " else "  "
            val nameColor = if (isCursor) CYAN else ""
            val nameReset = if (isCursor) RESET else ""
            val hint = if (opt.hint.isNotEmpty()) "  ${DIM}${opt.hint}$RESET" else ""
            w.write("    $pointer$checkbox  $nameColor${opt.label}$nameReset$hint\n")
        }
        w.flush()
    }

    // ── Utilities ──────────────────────────────────────────

    fun readKey(reader: NonBlockingReader): Key {
        val c = reader.read()
        return when (c) {
            13, 10 -> Key.ENTER
            32 -> Key.SPACE
            3 -> Key.ESCAPE // Ctrl+C
            27 -> {
                val next = reader.peek(50)
                if (next == -2 || next == -1) return Key.ESCAPE
                reader.read() // consume '['
                when (reader.read()) {
                    65 -> Key.UP    // ESC[A
                    66 -> Key.DOWN  // ESC[B
                    else -> Key.OTHER
                }
            }
            else -> Key.OTHER
        }
    }

    private fun clearLines(w: java.io.Writer, count: Int) {
        for (i in 0 until count) w.write("\u001B[A")
        for (i in 0 until count) {
            w.write("\u001B[2K")
            if (i < count - 1) w.write("\u001B[B")
        }
        for (i in 0 until count - 1) w.write("\u001B[A")
        w.write("\r")
        w.flush()
    }

    private fun restore(w: java.io.Writer, attrs: org.jline.terminal.Attributes, terminal: Terminal) {
        terminal.setAttributes(attrs)
        w.write("\u001B[?25h") // show cursor
        (w as? java.io.Flushable)?.flush()
    }
}
