package dev.nimbuspowered.nimbus.resourcepacks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The SHA-1 hash passed to {@code Player#setResourcePack} is a 20-byte digest encoded as
 * 40 hex chars in the controller's REST response. If this decoder mis-decodes, every
 * resource pack on the network silently fails hash negotiation and clients reject it.
 */
class HexToBytesTest {

    @Test
    void decodesLowercaseHex() {
        byte[] out = NimbusResourcePacksPlugin.hexToBytes("deadbeef");
        assertArrayEquals(new byte[]{(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef}, out);
    }

    @Test
    void decodesUppercaseHex() {
        byte[] out = NimbusResourcePacksPlugin.hexToBytes("DEADBEEF");
        assertArrayEquals(new byte[]{(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef}, out);
    }

    @Test
    void decodesMixedCaseHex() {
        byte[] out = NimbusResourcePacksPlugin.hexToBytes("DeAdBeEf");
        assertArrayEquals(new byte[]{(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef}, out);
    }

    @Test
    void emptyStringProducesEmptyArray() {
        assertArrayEquals(new byte[0], NimbusResourcePacksPlugin.hexToBytes(""));
    }

    @Test
    void fullSha1ProducesTwentyBytes() {
        String sha1 = "2fd4e1c67a2d28fced849ee1bb76e7391b93eb12"; // SHA-1("The quick brown fox jumps over the lazy dog")
        byte[] out = NimbusResourcePacksPlugin.hexToBytes(sha1);
        assertEquals(20, out.length);
        assertEquals((byte) 0x2f, out[0]);
        assertEquals((byte) 0x12, out[19]);
    }

    @Test
    void zeroHexProducesZeroBytes() {
        byte[] out = NimbusResourcePacksPlugin.hexToBytes("0000");
        assertArrayEquals(new byte[]{0, 0}, out);
    }
}
