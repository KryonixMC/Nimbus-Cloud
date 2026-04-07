package dev.nimbuspowered.nimbus.loadbalancer

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress

object ProxyProtocolV2 {

    // PROXY protocol v2 signature (12 bytes)
    private val SIGNATURE = byteArrayOf(
        0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D,
        0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
    )

    /**
     * Encodes a PROXY protocol v2 header for a TCP connection.
     * version=2, command=PROXY, transport=STREAM
     */
    fun encode(clientAddr: InetSocketAddress): ByteArray {
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)

        // Signature
        out.write(SIGNATURE)

        // Version (2) and command (1=PROXY) in one byte: 0x21
        out.writeByte(0x21)

        val addr = clientAddr.address
        when (addr) {
            is Inet4Address -> {
                // AF_INET (0x1) + STREAM (0x1) = 0x11
                out.writeByte(0x11)
                // Address length: 4+4+2+2 = 12
                out.writeShort(12)
                // Source address
                out.write(addr.address)
                // Destination address (0.0.0.0 — we don't know the real dest here)
                out.write(byteArrayOf(0, 0, 0, 0))
                // Source port
                out.writeShort(clientAddr.port)
                // Destination port (0)
                out.writeShort(0)
            }
            is Inet6Address -> {
                // AF_INET6 (0x2) + STREAM (0x1) = 0x21
                out.writeByte(0x21)
                // Address length: 16+16+2+2 = 36
                out.writeShort(36)
                out.write(addr.address)
                out.write(ByteArray(16)) // Destination
                out.writeShort(clientAddr.port)
                out.writeShort(0)
            }
            else -> {
                // LOCAL (no address info)
                out.writeByte(0x00)
                out.writeShort(0)
            }
        }

        out.flush()
        return baos.toByteArray()
    }
}
