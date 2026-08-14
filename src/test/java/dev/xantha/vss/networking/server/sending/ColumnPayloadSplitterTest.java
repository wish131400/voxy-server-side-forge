package dev.xantha.vss.networking.server.sending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import io.netty.buffer.Unpooled;
import java.util.Arrays;
import net.minecraft.network.FriendlyByteBuf;

import org.junit.jupiter.api.Test;

class ColumnPayloadSplitterTest {
    @Test
    void fittingEncodedColumnDoesNotDecompressOrParseTheFrame() {
        byte[] deliberatelyInvalidZstdFrame = new byte[] {1, 2, 3, 4};
        EncodedColumnData encoded = new EncodedColumnData(
                1, 2, LodByteCompression.METHOD_ZSTD, 4096, deliberatelyInvalidZstdFrame,
                5L, EncodedColumnData.SCHEMA_VERSION, true, new int[] {-1, 0},
                EncodedColumnData.crc32c(deliberatelyInvalidZstdFrame));
        VoxelColumnS2CPayload payload = new VoxelColumnS2CPayload(7, null, encoded)
                .withTransferMetadata(1L, 0, 1, encoded.sectionYs());
        payload.setAllowZstdEncoding(true);

        var split = ColumnPayloadSplitter.splitForBandwidth(null, payload, 1_000_000L, true);

        assertEquals(1, split.size());
        assertArrayEquals(new int[] {-1, 0}, split.get(0).replacementSectionYs());
    }

    @Test
    void oversizedColumnUsesWireScannerWithoutConstructingChunkSections() {
        byte[] raw = fiveSectionWireColumn();
        VoxelColumnS2CPayload payload = new VoxelColumnS2CPayload(
                9, 2, 3, null, 6L, raw, true, 2L, 0, 1, new int[] {0, 1, 2, 3, 4});

        var split = ColumnPayloadSplitter.splitForBandwidth(null, payload, 64L * 1024L, false);

        assertTrue(split.size() > 1);
        assertArrayEquals(new int[] {0, 1, 2, 3, 4}, split.get(split.size() - 1).replacementSectionYs());
    }

    private static byte[] fiveSectionWireColumn() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(5);
            byte[] light = new byte[2048];
            Arrays.fill(light, (byte) 1);
            for (int sectionY = 0; sectionY < 5; sectionY++) {
                buf.writeByte(sectionY);
                buf.writeShort(1);
                writeSingleValueContainer(buf);
                writeSingleValueContainer(buf);
                buf.writeBoolean(true);
                buf.writeBytes(light);
                buf.writeBoolean(true);
                buf.writeBytes(light);
            }
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static void writeSingleValueContainer(FriendlyByteBuf buf) {
        buf.writeByte(0);
        buf.writeVarInt(0);
        buf.writeVarInt(0);
    }

    @Test
    void targetWireBytesShrinksBelowOldTwentyFourKilobyteFloorAtLowBandwidth() {
        int target = ColumnPayloadSplitter.targetWireBytes(64L * 1024L);

        assertTrue(target < 24 * 1024);
        assertEquals(16 * 1024, target);
    }

    @Test
    void targetWireBytesKeepsSmallFloorForVeryLowBandwidth() {
        assertEquals(8 * 1024, ColumnPayloadSplitter.targetWireBytes(4L * 1024L));
    }

    @Test
    void targetWireBytesStillCapsLargeBandwidthPayloads() {
        assertEquals(256 * 1024, ColumnPayloadSplitter.targetWireBytes(4L * 1024L * 1024L));
    }
}
