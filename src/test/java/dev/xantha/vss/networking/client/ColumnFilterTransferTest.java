package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.common.processing.ColumnCoverage;
import dev.xantha.vss.common.processing.EncodedColumnData;
import dev.xantha.vss.common.processing.LodByteCompression;
import dev.xantha.vss.networking.payloads.ServerIdentityS2CPayload;
import dev.xantha.vss.networking.payloads.VoxelColumnS2CPayload;
import dev.xantha.vss.networking.server.sending.ColumnPayloadSplitter;
import dev.xantha.vss.testutil.ColumnFilterFixtures;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ColumnFilterTransferTest {
    private static final ColumnCoverage POLICY = new ColumnCoverage("a".repeat(64));
    @BeforeAll static void bootstrap() { ColumnFilterFixtures.biomes(); }
    @AfterEach void disconnect() { ClientConnectionIdentity.endSession(); }

    private static VoxelColumnS2CPayload payload(byte[] bytes, long transfer, int part, int count, int[] ys, ColumnCoverage coverage) {
        return new VoxelColumnS2CPayload(7, 0, 7, Level.OVERWORLD, 123L, bytes, false, transfer, part, count, ys, coverage);
    }

    private static VoxelColumnS2CPayload roundTrip(VoxelColumnS2CPayload payload) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try { VoxelColumnS2CPayload.encode(payload, buffer); return VoxelColumnS2CPayload.decode(buffer); }
        finally { buffer.release(); }
    }

    @Test void splitAndOutOfOrderWireTransferPreserveScopeAndNeverClearOmittedSections() {
        var raw = ColumnFilterFixtures.source(0);
        var encoded = new EncodedColumnData(0, 7, LodByteCompression.METHOD_NONE, raw.sizeBytes(), raw.sectionBytes(), 123L,
                EncodedColumnData.SCHEMA_VERSION, false, raw.sectionYs(), raw.sectionLengths(), EncodedColumnData.crc32c(raw.sectionBytes()), POLICY);
        var source = new VoxelColumnS2CPayload(7, Level.OVERWORLD, encoded).withTransferMetadata(42L, 0, 1, raw.sectionYs());
        List<VoxelColumnS2CPayload> parts = ColumnPayloadSplitter.splitForBandwidth(null, source, 4L * 1024L, false);
        assertTrue(parts.size() > 1);
        var assembler = new ClientColumnTransferAssembler(4, 1_000_000L);
        ClientColumnTransferAssembler.OfferResult completed = null;
        for (int i = parts.size() - 1; i >= 0; i--) {
            var part = roundTrip(parts.get(i)); assertEquals(POLICY, part.coverage()); assertFalse(part.completeColumn());
            completed = assembler.offer(part, false, true, 0, 0L, 10L + parts.size() - i);
        }
        assertEquals(ClientColumnTransferAssembler.OfferStatus.COMPLETED, completed.status());
        assertFalse(completed.column().replaceMissingSections());
        var decoded = ClientColumnProcessor.decodeColumn(completed.column(), ColumnFilterFixtures.biomes());
        assertFalse(decoded.authoritative()); assertFalse(decoded.replaceMissingSections()); assertEquals(POLICY, decoded.coverage());
        assertEquals(3, decoded.sections().length); assertEquals(0L, assembler.activeBytes());
    }

    @Test void allUntouchedCompletesAsASuccessfulNoOpWithoutAnAirReplacement() {
        var assembler = new ClientColumnTransferAssembler(4, 1_000_000L);
        var completed = assembler.offer(roundTrip(payload(new byte[] {0}, 43L, 0, 1, new int[0], POLICY)), false, true, 0, 0L, 1L);
        assertEquals(ClientColumnTransferAssembler.OfferStatus.COMPLETED, completed.status());
        var decoded = ClientColumnProcessor.decodeColumn(completed.column(), ColumnFilterFixtures.biomes());
        assertEquals(0, decoded.sections().length); assertFalse(decoded.replaceMissingSections()); assertFalse(decoded.authoritative());
        assertTrue(decoded.completesRequest());
    }

    @Test void mixedPoliciesRejectAndReleaseTheTransfer() {
        var assembler = new ClientColumnTransferAssembler(4, 1_000_000L);
        assembler.offer(payload(new byte[] {0}, 44L, 0, 2, new int[0], POLICY), false, false, 0, 0L, 1L);
        var rejected = assembler.offer(payload(new byte[] {0}, 44L, 1, 2, new int[0], new ColumnCoverage("b".repeat(64))), false, false, 0, 0L, 2L);
        assertEquals(ClientColumnTransferAssembler.OfferStatus.REJECTED, rejected.status());
        assertEquals(0, assembler.activeLogicalColumns()); assertEquals(0L, assembler.activeBytes());
    }

    @Test void normalizingScopedMetadataPreservesNegotiatedCompressionWithoutDecodingTheBody() {
        // The body must remain opaque when it already fits the negotiated wire
        // format. A lost capability flag would needlessly decompress this frame.
        byte[] frame = new byte[] {1, 2, 3, 4};
        var encoded = new EncodedColumnData(0, 7, LodByteCompression.METHOD_ZSTD, 4096, frame, 123L,
                EncodedColumnData.SCHEMA_VERSION, false, new int[] {0}, new int[0], EncodedColumnData.crc32c(frame), POLICY);
        var source = new VoxelColumnS2CPayload(7, Level.OVERWORLD, encoded).withTransferMetadata(47L, 0, 1, new int[] {0});
        source.setAllowZstdEncoding(true);
        var parts = ColumnPayloadSplitter.splitForBandwidth(null, source, 1_000_000L, true);
        assertEquals(1, parts.size()); assertEquals(POLICY, parts.get(0).coverage());
    }

    @Test void incompleteDataWithoutFilterProvenanceIsStillRejected() {
        var assembler = new ClientColumnTransferAssembler(4, 1_000_000L);
        assertEquals(ClientColumnTransferAssembler.OfferStatus.REJECTED,
                assembler.offer(payload(new byte[] {0}, 45L, 0, 1, new int[0], ColumnCoverage.FULL), false, false, 0, 0L, 1L).status());
    }

    @Test void malformedManifestCannotBecomeASuccessfulScopedUpdate() {
        var assembler = new ClientColumnTransferAssembler(4, 1_000_000L);
        var completed = assembler.offer(payload(new byte[] {0}, 46L, 0, 1, new int[] {0}, POLICY), false, false, 0, 0L, 1L);
        assertThrows(IllegalArgumentException.class, () -> ClientColumnProcessor.decodeColumn(completed.column(), ColumnFilterFixtures.biomes()));
    }

    @Test void handshakeRoundTripCarriesThePolicyAndDisconnectClearsIt() {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var original = new ServerIdentityS2CPayload("7K4M9PXA", false, "node-a", POLICY.filterKey());
            ServerIdentityS2CPayload.encode(original, buffer);
            ClientConnectionIdentity.acceptServerIdentity(ServerIdentityS2CPayload.decode(buffer));
            assertEquals(POLICY.filterKey(), ClientConnectionIdentity.columnFilterKey());
            ClientConnectionIdentity.beginSession("test.example", false);
            assertEquals(POLICY.filterKey(), ClientConnectionIdentity.columnFilterKey(), "login/config identity survives play start");
            assertFalse(VSSClientNetworking.shouldApplyStrictLodOrder(), "scoped updates cannot satisfy a full-column barrier");
            ClientConnectionIdentity.endSession(); assertEquals("", ClientConnectionIdentity.columnFilterKey());
        } finally { buffer.release(); }
    }
}
