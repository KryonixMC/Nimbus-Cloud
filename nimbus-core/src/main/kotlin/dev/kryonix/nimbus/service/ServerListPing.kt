package dev.kryonix.nimbus.service

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

object ServerListPing {
    private val logger = LoggerFactory.getLogger(ServerListPing::class.java)

    private val json = Json { ignoreUnknownKeys = true }

    data class PingResult(
        val onlinePlayers: Int,
        val maxPlayers: Int,
        val playerNames: List<String>,
        val motd: String,
        val version: String
    )

    @Serializable
    private data class StatusResponse(
        val version: VersionInfo = VersionInfo(),
        val players: PlayersInfo = PlayersInfo(),
        val description: Description? = null
    )

    @Serializable
    private data class VersionInfo(
        val name: String = "Unknown",
        val protocol: Int = 0
    )

    @Serializable
    private data class PlayersInfo(
        val max: Int = 0,
        val online: Int = 0,
        val sample: List<PlayerSample> = emptyList()
    )

    @Serializable
    private data class PlayerSample(
        val name: String = "",
        val id: String = ""
    )

    @Serializable
    private data class Description(
        val text: String = ""
    )

    /**
     * Pings a Minecraft server and returns player info.
     * @param host the host to connect to (usually "127.0.0.1")
     * @param port the port to connect to
     * @param timeout connection/read timeout in milliseconds
     * @return PingResult or null if the ping failed
     */
    fun ping(host: String = "127.0.0.1", port: Int, timeout: Int = 3000): PingResult? {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.soTimeout = timeout
            socket.connect(java.net.InetSocketAddress(host, port), timeout)

            val output = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())

            // Build handshake packet
            val handshake = buildPacket(0x00) {
                writeVarInt(this, 767) // Protocol version (1.21.4)
                writeString(this, host)
                write(port shr 8 and 0xFF)
                write(port and 0xFF)
                writeVarInt(this, 1) // Next state: Status
            }

            // Send handshake
            writeVarInt(output, handshake.size)
            output.write(handshake)

            // Send status request (packet ID 0x00, no fields)
            val statusRequest = buildPacket(0x00) {}
            writeVarInt(output, statusRequest.size)
            output.write(statusRequest)
            output.flush()

            // Read status response
            val responseLength = readVarInt(input)
            if (responseLength <= 0) {
                logger.debug("Received empty response from {}:{}", host, port)
                return null
            }

            val packetId = readVarInt(input)
            if (packetId != 0x00) {
                logger.debug("Unexpected packet ID {} from {}:{}", packetId, host, port)
                return null
            }

            val jsonString = readString(input)
            val response = json.decodeFromString<StatusResponse>(jsonString)

            // Extract MOTD - handle both string and object description formats
            val motd = response.description?.text ?: ""

            return PingResult(
                onlinePlayers = response.players.online,
                maxPlayers = response.players.max,
                playerNames = response.players.sample.map { it.name },
                motd = motd,
                version = response.version.name
            )
        } catch (e: Exception) {
            logger.debug("Failed to ping {}:{}: {}", host, port, e.message)
            return null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    // -- VarInt encoding/decoding --

    private fun writeVarInt(output: DataOutputStream, value: Int) {
        var remaining = value
        while (true) {
            if (remaining and 0x7F.inv() == 0) {
                output.writeByte(remaining)
                return
            }
            output.writeByte((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
    }

    private fun writeVarInt(output: ByteArrayOutputStream, value: Int) {
        var remaining = value
        while (true) {
            if (remaining and 0x7F.inv() == 0) {
                output.write(remaining)
                return
            }
            output.write((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
    }

    private fun readVarInt(input: DataInputStream): Int {
        var value = 0
        var position = 0
        while (true) {
            val byte = input.readByte().toInt()
            value = value or ((byte and 0x7F) shl position)
            if (byte and 0x80 == 0) return value
            position += 7
            if (position >= 32) throw RuntimeException("VarInt too large")
        }
    }

    // -- String helpers --

    private fun writeString(output: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarInt(output, bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = readVarInt(input)
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    // -- Packet builder --

    private inline fun buildPacket(packetId: Int, block: ByteArrayOutputStream.() -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        writeVarInt(buffer, packetId)
        buffer.block()
        return buffer.toByteArray()
    }
}
