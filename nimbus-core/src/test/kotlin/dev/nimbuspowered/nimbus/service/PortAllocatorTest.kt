package dev.nimbuspowered.nimbus.service

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
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
    fun `allocateBackendPort returns port starting from backendBasePort`() {
        val port = allocator.allocateBackendPort()
        assertEquals(30000, port)
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
}
