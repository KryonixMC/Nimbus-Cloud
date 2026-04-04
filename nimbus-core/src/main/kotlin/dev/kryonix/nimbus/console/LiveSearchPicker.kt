package dev.kryonix.nimbus.console

import dev.kryonix.nimbus.console.ConsoleFormatter.BOLD
import dev.kryonix.nimbus.console.ConsoleFormatter.CYAN
import dev.kryonix.nimbus.console.ConsoleFormatter.DIM
import dev.kryonix.nimbus.console.ConsoleFormatter.GREEN
import dev.kryonix.nimbus.console.ConsoleFormatter.RESET
import dev.kryonix.nimbus.console.ConsoleFormatter.YELLOW
import kotlinx.coroutines.*
import org.jline.terminal.Terminal

/**
 * Interactive live search picker for the terminal.
 *
 * Displays a text input field where the user types a query. Results are
 * fetched via a debounced callback and rendered below the input.
 *
 * Supports both single-select (ENTER) and multi-select (SPACE to toggle, ENTER to confirm).
 */
object LiveSearchPicker {

    /** One line of rendered search result. */
    data class SearchLine(
        val sourceTag: String,
        val name: String,
        val author: String,
        val downloads: String,
        val description: String
    )

    private const val MAX_RESULTS = 5
    private const val DEBOUNCE_MS = 300L
    private const val POLL_MS = 50L

    private val SPINNER = charArrayOf('⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏')

    /**
     * Opens an interactive live search UI with multi-select support.
     *
     * - Type to search, results appear live
     * - UP/DOWN to navigate
     * - SPACE to toggle selection on the current item
     * - ENTER to confirm all selected items (or the cursor item if none toggled)
     * - ESC to cancel
     *
     * @param terminal JLine terminal for raw I/O
     * @param title Header text (e.g. "Search plugins (1.21.4)")
     * @param initialQuery Pre-filled query string
     * @param identify Returns a unique key for each item (for tracking selections across searches)
     * @param search Callback that performs the actual search (debounced)
     * @param render Converts a result item to display strings
     * @return List of selected items, or null if cancelled (ESC)
     */
    suspend fun <T> liveSearchMulti(
        terminal: Terminal,
        title: String,
        initialQuery: String = "",
        identify: (T) -> String,
        search: suspend (query: String) -> List<T>,
        render: (T) -> SearchLine
    ): List<T>? = coroutineScope {
        val query = StringBuilder(initialQuery)
        var cursor = 0
        var results: List<T> = emptyList()
        val selectedKeys = mutableSetOf<String>()
        val selectedItems = mutableMapOf<String, T>() // key → item, preserves across searches
        var loading = false
        var spinnerIdx = 0
        var searchJob: Job? = null
        var renderedLines = 0

        val w = terminal.writer()
        val originalAttrs = terminal.enterRawMode()
        val reader = terminal.reader()

        try {
            w.print("\u001B[?25l") // hide cursor
            w.flush()

            fun redraw() {
                if (renderedLines > 0) clearLines(w, renderedLines)
                renderedLines = drawMulti(w, title, query.toString(), results, cursor, selectedKeys, loading, spinnerIdx, identify, render)
            }

            fun triggerDebouncedSearch() {
                searchJob?.cancel()
                searchJob = launch {
                    loading = true; redraw()
                    delay(DEBOUNCE_MS)
                    try {
                        results = search(query.toString())
                        cursor = 0
                    } catch (_: Exception) {}
                    loading = false
                    redraw()
                }
            }

            redraw()

            // If initial query provided, search immediately
            if (query.isNotEmpty()) {
                loading = true
                redraw()
                searchJob = launch {
                    try { results = search(query.toString()); cursor = 0 } catch (_: Exception) {}
                    loading = false; redraw()
                }
            }

            while (isActive) {
                val c = withContext(Dispatchers.IO) { reader.read(POLL_MS.toLong()) }

                if (c == -2) {
                    if (loading) { spinnerIdx = (spinnerIdx + 1) % SPINNER.size; redraw() }
                    continue
                }
                if (c == -1) break

                when (c) {
                    // ENTER — confirm selection
                    13, 10 -> {
                        val finalSelection = if (selectedKeys.isNotEmpty()) {
                            // Return all toggled items (order of selection)
                            selectedItems.values.toList()
                        } else if (results.isNotEmpty() && cursor in results.indices) {
                            // Nothing toggled — return cursor item as single selection
                            listOf(results[cursor])
                        } else {
                            continue
                        }
                        clearLines(w, renderedLines)
                        restore(w, originalAttrs, terminal)
                        return@coroutineScope finalSelection
                    }

                    // SPACE — toggle selection
                    32 -> {
                        if (results.isNotEmpty() && cursor in results.indices) {
                            val item = results[cursor]
                            val key = identify(item)
                            if (key in selectedKeys) {
                                selectedKeys.remove(key)
                                selectedItems.remove(key)
                            } else {
                                selectedKeys.add(key)
                                selectedItems[key] = item
                            }
                            // Move cursor down after toggle for quick multi-select
                            if (cursor < results.size - 1) cursor++
                            redraw()
                        }
                    }

                    // Ctrl+C
                    3 -> {
                        clearLines(w, renderedLines)
                        restore(w, originalAttrs, terminal)
                        return@coroutineScope null
                    }

                    // ESC / Arrow keys
                    27 -> {
                        val next = withContext(Dispatchers.IO) { reader.peek(50) }
                        if (next == -2 || next == -1) {
                            clearLines(w, renderedLines)
                            restore(w, originalAttrs, terminal)
                            return@coroutineScope null
                        }
                        withContext(Dispatchers.IO) { reader.read() }
                        val arrow = withContext(Dispatchers.IO) { reader.read() }
                        when (arrow) {
                            65 -> if (results.isNotEmpty()) { cursor = (cursor - 1 + results.size) % results.size; redraw() }
                            66 -> if (results.isNotEmpty()) { cursor = (cursor + 1) % results.size; redraw() }
                        }
                    }

                    // Backspace
                    127, 8 -> {
                        if (query.isNotEmpty()) {
                            query.deleteCharAt(query.length - 1)
                            triggerDebouncedSearch()
                            redraw()
                        }
                    }

                    // Printable characters
                    in 33..126 -> {
                        query.append(c.toChar())
                        triggerDebouncedSearch()
                        redraw()
                    }
                }
            }

            clearLines(w, renderedLines)
            restore(w, originalAttrs, terminal)
            null
        } catch (e: Exception) {
            if (renderedLines > 0) clearLines(w, renderedLines)
            restore(w, originalAttrs, terminal)
            throw e
        }
    }

    // ── Rendering ──────────────────────────────────────────

    private fun <T> drawMulti(
        w: java.io.Writer,
        title: String,
        query: String,
        results: List<T>,
        cursor: Int,
        selectedKeys: Set<String>,
        loading: Boolean,
        spinnerIdx: Int,
        identify: (T) -> String,
        render: (T) -> SearchLine
    ): Int {
        var lines = 0

        // Title + selection count
        val selCount = if (selectedKeys.isNotEmpty()) "  ${GREEN}${selectedKeys.size} selected${RESET}" else ""
        w.write("  ${BOLD}$title${RESET}$selCount\n")
        lines++

        // Search input
        val spinner = if (loading) " ${YELLOW}${SPINNER[spinnerIdx]}${RESET}" else ""
        w.write("  ${DIM}>${RESET} ${query}${DIM}_${RESET}$spinner\n")
        lines++

        w.write("\n")
        lines++

        if (results.isEmpty()) {
            if (query.isNotEmpty() && !loading) {
                w.write("  ${DIM}No plugins found.$RESET\n")
                lines++
            } else if (query.isEmpty()) {
                w.write("  ${DIM}Type to search...$RESET\n")
                lines++
            }
        } else {
            val visible = results.take(MAX_RESULTS)
            for ((i, item) in visible.withIndex()) {
                val line = render(item)
                val key = identify(item)
                val isCursor = i == cursor
                val isSelected = key in selectedKeys

                val pointer = if (isCursor) "${CYAN}>${RESET} " else "  "
                val checkbox = if (isSelected) "${GREEN}✓${RESET}" else "${DIM}○${RESET}"
                val nameColor = if (isCursor) CYAN else if (isSelected) GREEN else ""
                val nameReset = if (isCursor || isSelected) RESET else ""

                val tag = "${DIM}${line.sourceTag}${RESET}"
                val dl = "${DIM}${line.downloads}${RESET}"
                val author = "${DIM}${line.author}${RESET}"
                w.write("  $pointer$checkbox $tag  $nameColor${line.name}$nameReset  $author  $dl\n")
                lines++

                val descIndent = if (isCursor) "        " else "      "
                w.write("  $descIndent${DIM}${line.description}${RESET}\n")
                lines++
            }

            if (results.size > MAX_RESULTS) {
                w.write("  ${DIM}  ... and ${results.size - MAX_RESULTS} more${RESET}\n")
                lines++
            }
        }

        w.write("\n")
        lines++
        w.write("  ${ConsoleFormatter.hint("↑↓ navigate  ·  space select  ·  enter confirm  ·  esc cancel")}\n")
        lines++

        w.flush()
        return lines
    }

    // ── Helpers ─────────────────────────────────────────────

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
