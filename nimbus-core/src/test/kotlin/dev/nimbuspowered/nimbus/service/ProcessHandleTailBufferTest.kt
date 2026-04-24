package dev.nimbuspowered.nimbus.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Tests for the rolling tail buffer in [ProcessHandle].
 *
 * `tailBuffer` is private with no injection hook, so lines are fed by starting
 * real short-lived shell processes. The only exception is the "empty before any
 * process is started" case, which needs no process at all.
 *
 * TAIL_CAPACITY = 50 (private companion constant in ProcessHandle).
 *
 * Important: `runTest` uses a virtual clock, so `delay()` is skipped instantly.
 * We therefore wait for the child process to exit via `handle.awaitExit()`, which
 * suspends on Dispatchers.IO and completes only after real I/O has finished.
 * For the concurrent-read test the readers use `withContext(Dispatchers.IO) {
 * Thread.sleep(...) }` to introduce real-time spread.
 */
class ProcessHandleTailBufferTest {

    private val handle = ProcessHandle()

    @AfterEach
    fun tearDown() {
        handle.destroy()
    }

    // -------------------------------------------------------------------------
    // 1. snapshotTail() returns empty list when no lines have been emitted
    // -------------------------------------------------------------------------

    @Test
    fun `snapshotTail returns empty list before any process is started`() {
        val snapshot = handle.snapshotTail()
        assertTrue(snapshot.isEmpty(), "Expected empty tail buffer but got: $snapshot")
    }

    // -------------------------------------------------------------------------
    // 2. snapshotTail() returns correct lines after N < TAIL_CAPACITY lines
    // -------------------------------------------------------------------------

    @Test
    fun `snapshotTail returns all lines when fewer than TAIL_CAPACITY lines emitted`(@TempDir tempDir: Path) =
        runTest(timeout = kotlin.time.Duration.parse("15s")) {
            val lineCount = 20
            handle.start(
                workDir = tempDir,
                command = listOf("bash", "-c", "for i in \$(seq 1 $lineCount); do echo \"line \$i\"; done")
            )
            // awaitExit() suspends on Dispatchers.IO until the child process finishes.
            // After it returns the stdout reader coroutine has also completed (EOF).
            // We add a tiny real-time pause to let the IO coroutine drain the last lines.
            handle.awaitExit()
            withContext(Dispatchers.IO) { Thread.sleep(200) }

            val snapshot = handle.snapshotTail()
            assertEquals(lineCount, snapshot.size, "Expected $lineCount lines in tail but got ${snapshot.size}")
            assertTrue(snapshot.first().contains("1"), "First line should contain '1', got: ${snapshot.first()}")
            assertTrue(snapshot.last().contains("$lineCount"), "Last line should contain '$lineCount', got: ${snapshot.last()}")
        }

    // -------------------------------------------------------------------------
    // 3. snapshotTail() returns exactly TAIL_CAPACITY lines after capacity + 10
    // -------------------------------------------------------------------------

    @Test
    fun `snapshotTail returns exactly TAIL_CAPACITY lines after overflow`(@TempDir tempDir: Path) =
        runTest(timeout = kotlin.time.Duration.parse("15s")) {
            val totalLines = 60          // 50 + 10 overflow
            val tailCapacity = 50
            handle.start(
                workDir = tempDir,
                command = listOf("bash", "-c", "for i in \$(seq 1 $totalLines); do echo \"line \$i\"; done")
            )
            handle.awaitExit()
            withContext(Dispatchers.IO) { Thread.sleep(200) }

            val snapshot = handle.snapshotTail()
            assertEquals(
                tailCapacity, snapshot.size,
                "Expected exactly $tailCapacity lines (TAIL_CAPACITY) after $totalLines emitted, got ${snapshot.size}"
            )
        }

    // -------------------------------------------------------------------------
    // 4. snapshotTail() newest line is last (correct order preserved)
    // -------------------------------------------------------------------------

    @Test
    fun `snapshotTail newest line is last in correct order`(@TempDir tempDir: Path) =
        runTest(timeout = kotlin.time.Duration.parse("15s")) {
            val totalLines = 60
            handle.start(
                workDir = tempDir,
                command = listOf("bash", "-c", "for i in \$(seq 1 $totalLines); do echo \"line \$i\"; done")
            )
            handle.awaitExit()
            withContext(Dispatchers.IO) { Thread.sleep(200) }

            val snapshot = handle.snapshotTail()
            // With 60 lines emitted and TAIL_CAPACITY=50, kept lines are 11..60.
            assertTrue(
                snapshot.last().contains("$totalLines"),
                "Expected last snapshot entry to contain '$totalLines' (newest line), got: ${snapshot.last()}"
            )
            val expectedOldest = totalLines - 50 + 1   // = 11
            assertTrue(
                snapshot.first().contains("$expectedOldest"),
                "Expected first snapshot entry to contain '$expectedOldest' (oldest kept line), got: ${snapshot.first()}"
            )
        }

    // -------------------------------------------------------------------------
    // 5. Concurrent writes: many coroutines write simultaneously
    //    → no exception, snapshotTail().size <= TAIL_CAPACITY
    // -------------------------------------------------------------------------

    @Test
    fun `concurrent stdout ingestion does not corrupt buffer and respects TAIL_CAPACITY`(
        @TempDir tempDir: Path
    ) = runTest(timeout = kotlin.time.Duration.parse("20s")) {
        val tailCapacity = 50
        val totalLines = 200

        // Emit 200 lines as fast as possible to stress the synchronized tail buffer.
        handle.start(
            workDir = tempDir,
            command = listOf(
                "bash", "-c",
                "for i in \$(seq 1 $totalLines); do echo \"concurrent line \$i\"; done"
            )
        )

        // Launch concurrent readers that use real-time sleeps (withContext(IO) + Thread.sleep)
        // so they actually spread across the process lifetime rather than all firing at t=0.
        val concurrentReads = (1..20).map { i ->
            async(Dispatchers.IO) {
                Thread.sleep((i * 10).toLong())
                handle.snapshotTail()
            }
        }.awaitAll()

        // Every concurrent snapshot must stay within capacity and contain no nulls.
        for (snapshot in concurrentReads) {
            assertTrue(
                snapshot.size <= tailCapacity,
                "Concurrent snapshot size ${snapshot.size} exceeded TAIL_CAPACITY $tailCapacity"
            )
            snapshot.forEach { line ->
                assertNotNull(line, "Snapshot contained a null entry")
            }
        }

        // Wait for the process to finish, then verify the final buffer is full.
        handle.awaitExit()
        withContext(Dispatchers.IO) { Thread.sleep(200) }

        val finalSnapshot = handle.snapshotTail()
        assertTrue(
            finalSnapshot.size <= tailCapacity,
            "Final snapshot size ${finalSnapshot.size} exceeded TAIL_CAPACITY $tailCapacity"
        )
        assertEquals(tailCapacity, finalSnapshot.size, "Expected buffer full after $totalLines lines")
    }
}
