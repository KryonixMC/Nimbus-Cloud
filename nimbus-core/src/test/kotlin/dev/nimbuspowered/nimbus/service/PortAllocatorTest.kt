package dev.nimbuspowered.nimbus.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortAllocatorTest {

    private lateinit var allocator: PortAllocator

    @BeforeEach
    fun setUp() {
        allocator = PortAllocator()
    }

    @Test
    fun `allocateProxyPort returns configured proxy port`() {
        val port = allocator.allocateProxyPort()
        assertEquals(25565, port)
    }

    @Test
    fun `allocateProxyPort is idempotent`() {
        val port1 = allocator.allocateProxyPort()
        val port2 = allocator.allocateProxyPort()
        assertEquals(port1, port2)
        assertEquals(25565, port1)
    }

    @Test
    fun `allocateBackendPort returns port at or above backendBasePort`() {
        // Use a high, unlikely-to-be-in-use base port so the test is stable
        // across dev environments where 30000 may already be bound.
        val highAllocator = PortAllocator(backendBasePort = 52000)
        val port = highAllocator.allocateBackendPort()
        assertTrue(port >= 52000, "Allocated port $port should be >= 52000")
    }

    @Test
    fun `multiple allocateBackendPort calls return sequential available ports`() {
        val port1 = allocator.allocateBackendPort()
        val port2 = allocator.allocateBackendPort()
        val port3 = allocator.allocateBackendPort()

        // Ports should be sequential (assuming all are available on the host)
        assertEquals(port1 + 1, port2)
        assertEquals(port2 + 1, port3)
    }

    @Test
    fun `release frees port for reallocation`() {
        val port1 = allocator.allocateBackendPort()
        val port2 = allocator.allocateBackendPort()

        // Release the first port
        allocator.release(port1)

        // Next allocation should return the released port (it's lower and now free)
        val port3 = allocator.allocateBackendPort()
        assertEquals(port1, port3)
    }

    @Test
    fun `port exhaustion throws IllegalStateException`() {
        // Use a tiny range: base 50000, max = 50000 + 9999 = 59999
        // But isPortAvailable uses ServerSocket, so we can't exhaust all ports easily.
        // Use a range where we can trigger the exception by constraining the allocator.
        val smallAllocator = PortAllocator(proxyPort = 50000, backendBasePort = 50001)

        assertThrows<IllegalStateException> {
            // Allocate more than the 9999 port range allows
            // The range is 50001-60000, so 10000 ports max.
            // Some ports may be unavailable on the system, making this fail sooner.
            for (i in 0..10000) {
                smallAllocator.allocateBackendPort()
            }
        }
    }

    @Test
    fun `concurrent allocation returns all unique ports`() = runBlocking {
        val ports = java.util.Collections.synchronizedList(mutableListOf<Int>())

        val jobs = (1..20).map {
            launch {
                val port = allocator.allocateBackendPort()
                ports.add(port)
            }
        }

        jobs.forEach { it.join() }

        assertEquals(20, ports.size)
        assertEquals(20, ports.toSet().size, "All allocated ports must be unique")
    }

    @Test
    fun `custom proxy port is returned by allocateProxyPort`() {
        val customAllocator = PortAllocator(proxyPort = 19132)
        assertEquals(19132, customAllocator.allocateProxyPort())
    }

    // ── New edge-case tests ───────────────────────────────────────────────────

    @Test
    fun `allocateBedrockPort starts at bedrockBasePort when bedrockEnabled`() {
        val bedrockAllocator = PortAllocator(bedrockEnabled = true, bedrockBasePort = 29132)
        val port = bedrockAllocator.allocateBedrockPort()
        assertTrue(port >= 29132, "First Bedrock port $port should be >= 29132")
    }

    @Test
    fun `allocateBedrockPort throws when bedrockEnabled is false`() {
        val noBedrockAllocator = PortAllocator(bedrockEnabled = false)
        assertThrows<IllegalStateException> {
            noBedrockAllocator.allocateBedrockPort()
        }
    }

    @Test
    fun `reserveIfAvailable returns true for unoccupied port and false for already-allocated port`() {
        val highAllocator = PortAllocator(backendBasePort = 53000)
        val port = 53500

        val first = highAllocator.reserveIfAvailable(port)
        assertTrue(first, "reserveIfAvailable should return true for a free port")

        val second = highAllocator.reserveIfAvailable(port)
        assertFalse(second, "reserveIfAvailable should return false for an already-allocated port")
    }

    @Test
    fun `release allows the same port to be reallocated immediately`() {
        val highAllocator = PortAllocator(backendBasePort = 54000)
        val port = highAllocator.allocateBackendPort()

        highAllocator.release(port)

        val reallocated = highAllocator.allocateBackendPort()
        assertEquals(port, reallocated, "Released port should be the next allocated port")
    }

    @Test
    fun `releaseBedrockPort allows the same bedrock port to be reallocated`() {
        val bedrockAllocator = PortAllocator(bedrockEnabled = true, bedrockBasePort = 29200)
        val port = bedrockAllocator.allocateBedrockPort()

        bedrockAllocator.releaseBedrockPort(port)

        val reallocated = bedrockAllocator.allocateBedrockPort()
        assertEquals(port, reallocated, "Released Bedrock port should be the next allocated Bedrock port")
    }

    @Test
    fun `reserve marks port as used so subsequent allocateBackendPort skips it`() {
        val highAllocator = PortAllocator(backendBasePort = 55000)
        // Pre-reserve the first port in range
        highAllocator.reserve(55000)

        val allocated = highAllocator.allocateBackendPort()
        assertTrue(allocated > 55000, "allocateBackendPort should skip the reserved port 55000, got $allocated")
    }

    @Test
    fun `port exhaustion throws IllegalStateException after filling the range`() {
        // Use a base port where 10 sequential ports are very unlikely to be in use.
        // We allocate all 10000 allowed ports; the 10001st must throw.
        val smallBase = 56000
        val smallAllocator = PortAllocator(backendBasePort = smallBase)

        assertThrows<IllegalStateException> {
            for (i in 0..10000) {
                smallAllocator.allocateBackendPort()
            }
        }
    }

    @Test
    fun `concurrent allocateBackendPort 200 calls all return unique ports`() = runTest {
        val highAllocator = PortAllocator(backendBasePort = 57000)
        val ports = withContext(Dispatchers.Default) {
            (1..200).map { async { highAllocator.allocateBackendPort() } }.awaitAll()
        }
        assertEquals(200, ports.size)
        assertEquals(200, ports.toSet().size, "All 200 concurrently allocated ports must be unique")
    }

    @Test
    fun `invalidateExternalCache does not crash and allocation still works`() {
        val highAllocator = PortAllocator(backendBasePort = 58000)
        // Allocate once to populate internal state
        highAllocator.allocateBackendPort()
        // Invalidate cache — must not throw
        highAllocator.invalidateExternalCache()
        // Allocation after cache clear must still succeed
        val port = highAllocator.allocateBackendPort()
        assertTrue(port >= 58000, "Port $port after cache invalidation should still be in range")
    }
}
