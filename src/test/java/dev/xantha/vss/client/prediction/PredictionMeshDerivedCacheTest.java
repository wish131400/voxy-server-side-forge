package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.*;

class PredictionMeshDerivedCacheTest {
    private static final byte[] ID = new byte[32];
    private static PredictionSeamMesh seams;
    @BeforeAll static void bootstrap() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        seams = PredictionMeshCodecTest.fixture(64).seamMesh();
    }

    static PredictionMesh fixture(int features, boolean water) {
        int[] words = features == 0 ? PredictionDisplayGeometryTest.grid(PredictionPackedMesh.FLAG_CUTOUT)
                : PredictionMeshFixture.decorated(features);
        // Exact custom geometry needs no atlas in a headless CPU regression.
        for (int p = 6; p < words.length; p += 12) words[p] &= ~65535;
        int terrain = words.length / 12;
        int[] wf = new int[5], wc = new int[5];
        if (water) {
            words = Arrays.copyOf(words, words.length + 12);
            PredictionSpatialOrderTest.quad(words, terrain, 12, 12, 100, 1 << PredictionPackedMesh.FLAGS_FLUID_SHIFT);
            wf[0] = terrain; wc[0] = 1;
        }
        var packed = PredictionPackedMesh.scanMerged(words, 64, terrain, new int[5], new int[]{terrain, 0, 0, 0, 0}, wf, wc, false, 0);
        return PredictionMesh.restored(terrain * 6, water ? 6 : 0, packed, seams);
    }

    @Test void completeStaticPlanRestoresWithoutAnyMergerSorterOrDisplayBuilder() throws Exception {
        for (int features : new int[]{0, 32, 8192}) {
            var mesh = fixture(features, true);
            assertTrue(mesh.gpuPayload().hasTerrainDisplayLod());
            if (features > 0) assertTrue(mesh.gpuPayload().hasTerrainOnlyDisplayLod());
            byte[] bytes = PredictionMeshCodec.encode(mesh, ID, ID, true, true);
            assertEquals(PredictionMeshCodec.PREPARED_RECORD_VERSION, ByteBuffer.wrap(bytes).getInt());
            PredictionMesh restored;
            try (var merger = mockStatic(PredictionScanMerge.class, call -> { throw new AssertionError("repeated scan merge"); });
                 var sorter = mockStatic(PredictionSpatialOrder.class, call -> { throw new AssertionError("repeated spatial order"); });
                 var plants = mockStatic(PredictionVegetationRanges.class, CALLS_REAL_METHODS);
                 var display = mockStatic(PredictionDisplayGeometry.class, call -> { throw new AssertionError("repeated display build"); })) {
                restored = PredictionMeshCodec.decode(bytes, ID, 64);
                plants.verifyNoInteractions();
            }
            assertEquivalent(mesh.gpuPayload(), restored.gpuPayload());
            for (int round = 0; round < 3; round++) {
                restored = PredictionMeshCodec.decode(PredictionMeshCodec.encode(restored, ID), ID, 64);
                assertArrayEquals(mesh.gpuPayload().quads(), restored.gpuPayload().quads(), "derived layers must never accumulate");
            }
        }
    }

    @Test void oldCanonicalVersionsStillRestoreAndMergedVersionsSkipScanMerge() throws Exception {
        var mesh = fixture(8192, true);
        for (int version : new int[]{8, 9, 11}) {
            byte[] bytes = PredictionMeshCacheTestRecords.legacy(mesh, ID, ID, true, true, version);
            if (version == 11) try (var merge = mockStatic(PredictionScanMerge.class, CALLS_REAL_METHODS)) {
                assertEquivalent(mesh.gpuPayload(), PredictionMeshCodec.decode(bytes, ID, 64).gpuPayload());
                merge.verify(() -> PredictionScanMerge.merge(any(), any(), any(), anyInt(), anyInt(), anyBoolean(), anyInt()), never());
            } else {
                var record = PredictionMeshCodec.decodeBaseRecord(bytes, ID, 64);
                assertEquivalent(mesh.gpuPayload(), record.mesh().gpuPayload());
                assertEquals(version != 8, record.surfaceCompleted());
            }
        }
    }

    @Test void policyChangeRebuildsFromCanonicalPrefixWithoutDuplicatingWater() throws Exception {
        var mesh = fixture(8192, true);
        byte[] bytes = PredictionMeshCodec.encode(mesh, ID);
        String key = "vss.disablePredictionDisplayLod", old = System.getProperty(key);
        try {
            System.setProperty(key, "true");
            var restored = PredictionMeshCodec.decode(bytes, ID, 64).gpuPayload();
            assertFalse(restored.hasTerrainDisplayLod());
            assertArrayEquals(mesh.gpuPayload().cacheWords(), restored.quads());
            assertArrayEquals(mesh.gpuPayload().drawRanges(true, 31).first, restored.drawRanges(true, 31).first);
            assertEquals(1, restored.drawRanges(true, 31).quads);
        } finally {
            if (old == null) System.clearProperty(key); else System.setProperty(key, old);
        }
    }

    @Test void materialRemappingReusesPlansAndExhaustedTableRestoresExactFallback() throws Exception {
        boolean[] vegetation = new boolean[256], aquatic = new boolean[256], land = new boolean[256], ground = new boolean[256];
        vegetation[1] = vegetation[7] = land[1] = land[7] = ground[1] = ground[7] = true;
        try (var table = mockStatic(VssLodSpriteTable.class, CALLS_REAL_METHODS)) {
            table.when(VssLodSpriteTable::vegetationSprites).thenAnswer(call -> vegetation.clone());
            table.when(VssLodSpriteTable::aquaticSprites).thenAnswer(call -> aquatic.clone());
            table.when(VssLodSpriteTable::landPlantSprites).thenAnswer(call -> land.clone());
            table.when(VssLodSpriteTable::groundCoverSprites).thenAnswer(call -> ground.clone());
            table.when(VssLodSpriteTable::displayApproximationSprites).thenAnswer(call -> new boolean[256]);
            table.when(() -> VssLodSpriteTable.writeMaterial(any(), anyInt())).thenAnswer(call -> {
                ((DataOutputStream) call.getArgument(0)).writeInt((int) call.getArgument(1)); return null;
            });
            table.when(() -> VssLodSpriteTable.readMaterial(any())).thenAnswer(call -> { ((DataInputStream) call.getArgument(0)).readInt(); return 7; });
            int[] words = PredictionDisplayGeometryTest.grid(1 | PredictionPackedMesh.FLAG_UNSHADED);
            for (int p = 10; p < words.length; p += 12) words[p] |= PredictionPackedMesh.FEATURE;
            var packed = PredictionPackedMesh.terrainRecords(words, 64);
            assertTrue(packed.hasGroundCoverLod());
            var mesh = PredictionMesh.restored(6144, 0, packed, seams);
            byte[] bytes = PredictionMeshCodec.encode(mesh, ID);
            var remapped = PredictionMeshCodec.decode(bytes, ID, 64).gpuPayload();
            assertTrue(remapped.hasGroundCoverLod());
            assertEquals(0, remapped.drawRanges(false, 31, 0, 0, 0, 4, false).quads);
            for (int p = 6; p < remapped.quads().length; p += 12) assertEquals(7, remapped.quads()[p] & 65535);
            table.when(() -> VssLodSpriteTable.readMaterial(any())).thenAnswer(call -> { ((DataInputStream) call.getArgument(0)).readInt(); return 255; });
            var fallback = PredictionMeshCodec.decode(bytes, ID, 64).gpuPayload();
            assertFalse(fallback.hasGroundCoverLod());
            assertEquals(1024, fallback.drawRanges(false, 31, 0, 0, 0, 4, false).quads, "unknown shared rows must remain exact");
        }
    }

    @Test void malformedPreparedRecordsAreRejectedBeforePublishing() throws Exception {
        var mesh = fixture(8192, true); byte[] bytes = PredictionMeshCodec.encode(mesh, ID);
        for (int remove : new int[]{1, 10, 256, bytes.length - 4})
            assertThrows(IOException.class, () -> PredictionMeshCodec.decode(Arrays.copyOf(bytes, bytes.length - remove), ID, 64));
        // This fixture uses row zero only, so the source length follows its quad words.
        assertEquals(0, ByteBuffer.wrap(bytes).getInt(98));
        int sourceOffset = 106 + ByteBuffer.wrap(bytes).getInt(102) * 4;
        for (int invalid : new int[]{-12, 1, Integer.MAX_VALUE}) {
            byte[] changed = bytes.clone(); ByteBuffer.wrap(changed).putInt(sourceOffset, invalid);
            assertThrows(IOException.class, () -> PredictionMeshCodec.decode(changed, ID, 64));
        }
        var metadata = new ByteArrayOutputStream();
        PredictionMeshDerivedCache.write(new DataOutputStream(metadata), mesh.gpuPayload().cachePlan(), mesh.gpuPayload().quads());
        int metadataOffset = bytes.length - metadata.size();
        byte[] missingMaterialKind = bytes.clone(); missingMaterialKind[metadataOffset + 8] = -1;
        assertThrows(IOException.class, () -> PredictionMeshCodec.decode(missingMaterialKind, ID, 64));
        for (int offset : new int[]{0, 8 + 256, 8 + 256 + 4 * 5 * 4}) {
            byte[] changed = bytes.clone(); ByteBuffer.wrap(changed).putInt(metadataOffset + offset, Integer.MAX_VALUE);
            assertThrows(IOException.class, () -> PredictionMeshCodec.decode(changed, ID, 64));
        }
    }

    private static void assertEquivalent(PredictionPackedMesh a, PredictionPackedMesh b) {
        assertArrayEquals(a.quads(), b.quads()); assertArrayEquals(a.cacheWords(), b.cacheWords());
        assertArrayEquals(a.displayErrors(), b.displayErrors());
        assertArrayEquals(a.occlusionBounds.values, b.occlusionBounds.values);
        assertArrayEquals(a.occlusionBounds.directions, b.occlusionBounds.directions);
        assertArrayEquals(a.occlusionBounds.renderEnds, b.occlusionBounds.renderEnds);
        assertEquals(a.hasGroundCoverLod(), b.hasGroundCoverLod());
        assertEquals(a.hasTerrainOnlyDisplayLod(), b.hasTerrainOnlyDisplayLod());
        for (int mask = 0; mask < 32; mask++) for (int display = 0; display < 4; display++)
            for (int plant = 0; plant < 4; plant++) for (int aquatic = 0; aquatic < 4; aquatic++)
                for (int ground : new int[]{0, 4}) for (boolean terrainOnly : new boolean[]{false, true}) {
                    var x = a.drawRanges(false, mask, aquatic, display, plant, ground, terrainOnly);
                    var y = b.drawRanges(false, mask, aquatic, display, plant, ground, terrainOnly);
                    assertArrayEquals(x.first, y.first); assertArrayEquals(x.count, y.count);
                }
        for (int mask = 0; mask < 32; mask++) {
            assertArrayEquals(a.drawRanges(true, mask).first, b.drawRanges(true, mask).first);
            assertArrayEquals(a.drawRanges(true, mask).count, b.drawRanges(true, mask).count);
        }
    }
}
