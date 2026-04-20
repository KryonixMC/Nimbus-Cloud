package dev.nimbuspowered.nimbus.loadbalancer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class ProxyProtocolV2Test {

    private val signature = byteArrayOf(
        0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D,
        0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
    )

    @Test
    fun `encodes IPv4 with correct signature and length`() {
        val addr = InetSocketAddress(InetAddress.getByName("10.0.0.5"), 54321)
        val bytes = ProxyProtocolV2.encode(addr)

        // signature first 12 bytes
        assertArrayEquals(signature, bytes.copyOfRange(0, 12))
        // version+command
        assertEquals(0x21.toByte(), bytes[12])
        // family+transport for AF_INET + STREAM
        assertEquals(0x11.toByte(), bytes[13])
        // address length (16-bit big-endian) — 12 for IPv4
        val len = ByteBuffer.wrap(bytes, 14, 2).short.toInt() and 0xFFFF
        assertEquals(12, len)
        // source address bytes
        assertArrayEquals(byteArrayOf(10, 0, 0, 5), bytes.copyOfRange(16, 20))
        // destination zeroed
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), bytes.copyOfRange(20, 24))
        // source port
        val srcPort = ByteBuffer.wrap(bytes, 24, 2).short.toInt() and 0xFFFF
        assertEquals(54321, srcPort)
        assertEquals(28, bytes.size) // 12 sig + 1 ver + 1 fam + 2 len + 12 addrs
    }

    @Test
    fun `encodes IPv6 with correct family byte and length`() {
        val addr = InetSocketAddress(InetAddress.getByName("::1"), 25565)
        val bytes = ProxyProtocolV2.encode(addr)

        assertArrayEquals(signature, bytes.copyOfRange(0, 12))
        assertEquals(0x21.toByte(), bytes[12])
        assertEquals(0x21.toByte(), bytes[13]) // AF_INET6 + STREAM
        val len = ByteBuffer.wrap(bytes, 14, 2).short.toInt() and 0xFFFF
        assertEquals(36, len)
        assertEquals(16 + 36, bytes.size)
    }
}
