package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PredictionPassStorageTest {
    @Test void compressedUploadedMeshesKeepDisplayPlansWithoutPinningCanonicalCoordinates() throws Exception {
        PredictionMeshCodecTest.bootstrap();
        PredictionMeshRestore.clear();
        for (boolean seam : new boolean[]{false, true}) {
            int[] source = new int[4096 * 12];
            for (int q = 0; q < 4096; q++) {
                PredictionSpatialOrderTest.quad(source, q, q % 64, q / 64, 80, PredictionPackedMesh.FLAG_CUTOUT);
                for (int offset : new int[]{7,9,10,11}) source[q * 12 + offset] = 0x556633;
            }
            var mesh = seam ? PredictionPackedMesh.seamRecords(source, 64) : PredictionPackedMesh.terrainRecords(source, 64);
            var field = PredictionPackedMesh.class.getDeclaredField("displayGeometry");
            field.setAccessible(true);
            var plan = (PredictionDisplayGeometry.Result)field.get(mesh);
            assertNull(plan.words(), "a retained display plan must not keep the worker's full coordinate array");
            if (!seam) assertTrue(mesh.hasDisplayLod());
            int[] canonical = mesh.quads().clone();
            var stats=mesh.geometryStats();
            assertEquals(4096, stats.sourceQuads(), "resident variants must not inflate source geometry counts");
            assertEquals(mesh.quadCount()-4096L, stats.displayVariantQuads());
            assertEquals(stats.sourceQuads(), stats.sourceTerrain()+stats.sourceFeatures()+stats.sourcePreviews()+stats.sourceWater());
            assertEquals(mesh.quadCount(), stats.sourceQuads()+stats.displayVariantQuads());
            var ranges = mesh.drawRanges(false, VssLodFaceGroup.ALL, 0, 1);
            float[] errors = mesh.displayErrors().clone();
            mesh.prepareGpuStorage();
            mesh.prepareStorage();
            assertTrue(mesh.compressed());
            mesh.uploaded();
            var coordinates = PredictionPackedMesh.class.getDeclaredField("quads");
            coordinates.setAccessible(true);
            assertNull(coordinates.get(mesh), "canonical storage is released after compression");
            assertNull(plan.words());
            assertSame(ranges, mesh.drawRanges(false, VssLodFaceGroup.ALL, 0, 1));
            assertArrayEquals(errors, mesh.displayErrors());
            assertArrayEquals(canonical, mesh.restoreWords(), "cold restoration retains original and display geometry");
            assertEquals(0, PredictionMeshRestore.readyBytes());
        }
    }

    @Test void rawAndCompactPassesPreserveOrderFlagsColorsAndAppendedGeometry() {
        for (boolean compact : new boolean[]{false, true}) {
            int[] words = new int[320 * 12];
            Random random = new Random(7);
            for (int q = 0; q < 320; q++) {
                int p = q * 12;
                for (int i = 0; i < 7; i++) words[p + i] = random.nextInt();
                words[p + 7] = q % 3; words[p + 8] = q;
                words[p + 9] = 0xf9000000 | q % 3; words[p + 10] = -1; words[p + 11] = 0x80000000;
            }
            var encoded = compact ? PredictionGpuEncoding.encode(words) : new PredictionGpuEncoding.Encoded(words, 0);
            if (compact) assertTrue(encoded.paletteBaseTexel() > 0);
            var opaque = PredictionGpuEncoding.selectPass(encoded.words(), encoded.paletteBaseTexel(), 320, 128, 96, false, compact);
            var water = PredictionGpuEncoding.selectPass(encoded.words(), encoded.paletteBaseTexel(), 320, 128, 96, true, compact);
            int[] expected = new int[(320 - 96) * 12];
            System.arraycopy(words, 0, expected, 0, 128 * 12);
            System.arraycopy(words, 224 * 12, expected, 128 * 12, 96 * 12);
            assertArrayEquals(expected, PredictionGpuEncoding.decode(opaque.words(), opaque.paletteBaseTexel(), 224));
            assertArrayEquals(Arrays.copyOfRange(words, 128 * 12, 224 * 12),
                    PredictionGpuEncoding.decode(water.words(), water.paletteBaseTexel(), 96));
            assertTrue(opaque.words().length + water.words().length < encoded.words().length + water.words().length);
        }
    }

    @Test void displayRangesAndColdCompressedRestoreUsePassLocalOffsets() throws Exception {
        PredictionMeshCodecTest.bootstrap();
        int[] terrain = PredictionDisplayGeometryTest.grid(PredictionPackedMesh.FLAG_CUTOUT);
        int[] words = Arrays.copyOf(terrain, terrain.length + 1024 * 12);
        for (int q = 1024; q < 2048; q++) {
            System.arraycopy(terrain, (q - 1024) * 12, words, q * 12, 12);
            words[q * 12 + 6] = 1 << PredictionPackedMesh.FLAGS_FLUID_SHIFT;
        }
        var mesh = new PredictionPackedMesh(words, 64, 1024, new int[5], new int[]{1024,0,0,0,0},
                new int[]{1024,2048,2048,2048,2048}, new int[]{1024,0,0,0,0}, false, 0);
        assertTrue(mesh.hasDisplayLod());
        int[] canonical = mesh.quads().clone();
        mesh.prepareGpuStorage();
        int[] opaque = mesh.opaqueUploadWords().clone(), water = mesh.waterUploadWords().clone();
        assertEquals(mesh.gpuStorageBytes(), (opaque.length + (long) water.length) * 4);
        assertEquals(opaque.length / 4, mesh.morphBaseTexel());
        int[] decoded = PredictionGpuEncoding.decode(opaque, mesh.opaquePaletteBaseTexel(), mesh.quadCount() - 1024);
        for (int tier = 0; tier <= 3; tier++) {
            var ranges = mesh.drawRanges(false, VssLodFaceGroup.ALL, tier);
            for (int i = 0; i < ranges.first.length; i++) {
                int first = mesh.gpuFirst(ranges.first[i], ranges.count[i], false);
                assertArrayEquals(Arrays.copyOfRange(canonical, ranges.first[i]*12, (ranges.first[i]+ranges.count[i])*12),
                        Arrays.copyOfRange(decoded, first*12, (first+ranges.count[i])*12));
            }
        }
        assertEquals(0, mesh.gpuFirst(1024, 1024, true));
        assertEquals(1024, mesh.gpuFirst(2048, 1, false));
        assertThrows(IllegalArgumentException.class, () -> mesh.gpuFirst(1023, 2, false));
        mesh.prepareStorage(); assertTrue(mesh.compressed()); mesh.uploaded();
        PredictionMeshRestore.clear();
        assertArrayEquals(canonical, mesh.restoreWords());
        PredictionMeshCompressionTest.awaitUpload(mesh);
        assertArrayEquals(opaque, mesh.opaqueUploadWords()); assertArrayEquals(water, mesh.waterUploadWords());
        mesh.uploaded(); assertEquals(0, PredictionMeshRestore.readyBytes());
    }

    @Test void emptyAndWaterOnlyMeshesHaveNoOpaqueAllocation() {
        var encoded = PredictionGpuEncoding.selectPass(new int[96*12], 0, 96, 0, 96, false, true);
        assertEquals(0, encoded.words().length);
        assertEquals(0, encoded.paletteBaseTexel());
        assertEquals(0, PredictionGpuEncoding.selectPass(new int[0],0,0,0,0,true,true).words().length);
    }

    @Test void rectangleCensusRejectsSlopesModelsAndCollapsedEdges() {
        int[] words = PredictionDisplayGeometryTest.grid(0);
        assertEquals(1024, PredictionGeometryStats.measure(words).rectangles());
        words[4]++; // one vertex of the horizontal face leaves its plane
        words[12 + 6] |= PredictionPackedMesh.FLAG_MODEL_UV;
        System.arraycopy(words, 24, words, 25, 1); // collapse/change the winding of a third face
        assertEquals(1021, PredictionGeometryStats.measure(words).rectangles());
        assertEquals(1021*12L, PredictionGeometryStats.measure(words).coordinateSavingUpperBound());
    }
}
