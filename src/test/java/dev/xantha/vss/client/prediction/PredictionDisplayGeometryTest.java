package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PredictionDisplayGeometryTest {
    @Test void leafAndFacadeLayersReduceGeometryKeepFootprintAndRestoreSource() {
        for (int flags : new int[]{PredictionPackedMesh.FLAG_CUTOUT, 0}) {
            int[] words = grid(flags), original = words.clone();
            int[] first = {0, 0, 0, 0, 0}, count = {1024, 0, 0, 0, 0};
            int[][] retained = new int[4][5];
            for (int[] level : retained) level[0] = 1024;
            PredictionSpatialOrder.arrange(words, first, count, new boolean[256]);
            original = words.clone();
            var display = PredictionDisplayGeometry.build(words, first, count, retained, 64);
            assertNotNull(display.ranges());
            assertArrayEquals(original, Arrays.copyOf(display.words(), original.length));
            for (int tier = 1; tier <= 3; tier++) {
                var range = display.ranges()[tier][1];
                System.out.println("DISPLAY_LOD kind=" + flags + " tier=" + tier + " quads=1024->" + range.quads + " runs=" + range.first.length);
                assertTrue(range.quads <= 410, "small coplanar leaves/facades must coalesce: " + flags + "/" + tier + "=" + range.quads);
                double area = 0;
                for (int i = 0; i < range.first.length; i++) for (int q = range.first[i]; q < range.first[i] + range.count[i]; q++) {
                    int p = q * 12;
                    int[] x = new int[4], z = new int[4];
                    for (int c = 0; c < 4; c++) {
                        x[c] = display.words()[p + c / 2] >>> ((c & 1) * 16) & 65535;
                        z[c] = display.words()[p + 2 + c / 2] >>> ((c & 1) * 16) & 65535;
                    }
                    for (int c = 0; c < 4; c++) area += (x[c] * z[(c + 1) % 4] - x[(c + 1) % 4] * z[c]) / 2.0;
                    assertEquals(flags, display.words()[p + 6]);
                }
                assertEquals(1024, area, "surface outline and holes must remain exact");
            }
        }
    }

    @Test void customModelsAndFluidsStayExactWhileLodTextureAndWallFlagsSurviveMerging() {
        for (int flags : new int[]{PredictionPackedMesh.FLAG_MODEL_UV, PredictionPackedMesh.FLAG_UNSHADED,
                1 << PredictionPackedMesh.FLAGS_FLUID_SHIFT}) {
            var result = build(grid(flags)); assertNull(result.ranges());
        }
        var lod = build(grid(PredictionPackedMesh.FLAG_LOD_TEXTURE_SCALE));
        assertNotNull(lod.ranges());
        for (int p = 6; p < lod.words().length; p += 12) assertEquals(PredictionPackedMesh.FLAG_LOD_TEXTURE_SCALE, lod.words()[p]);
        int[] walls = grid(0);
        for (int q = 0; q < 1024; q++) walls[q * 12 + 9] |= 1 << 27;
        var merged = build(walls);
        assertNotNull(merged.ranges());
        for (int p = 9; p < merged.words().length; p += 12) assertNotEquals(0, merged.words()[p] & (1 << 27));
    }

    @Test void independentPlantTierCompactsKnownVegetationIntoTheSubmittedRange() {
        int[] words = grid(0);
        boolean[] vegetation = new boolean[256];
        vegetation[1] = true;
        for (int q = 0; q < 1024; q++) {
            int p = q * 12;
            words[p + 6] = 1 | PredictionPackedMesh.FLAG_UNSHADED;
            words[p + 10] = PredictionPackedMesh.FEATURE;
        }
        int[] original = words.clone();
        var result = PredictionVegetationRanges.arrange(words, new int[5], new int[]{1024, 0, 0, 0, 0},
                vegetation, new boolean[256]);
        assertEquals(original.length, words.length, "plant LOD allocates no geometry variants");
        assertEquals(1024, result.range(VssLodFaceGroup.ALL, 0, 0).quads);
        for (int q = 0; q < 1024; q++) {
            int id = words[q * 12 + 8];
            int oldQ = id % 64 + id / 64 * 32;
            assertArrayEquals(Arrays.copyOfRange(original, oldQ * 12, (oldQ + 1) * 12),
                    Arrays.copyOfRange(words, q * 12, (q + 1) * 12), "canonical records stay exact");
        }
        var far = result.range(VssLodFaceGroup.ALL, 1, 0);
        assertNotNull(far);
        assertTrue(far.quads < 1024, "far display range must omit vegetation records");
        assertTrue(far.quads <= 512, "tier one should retain at most one plant column in two");
        assertEquals(far.quads, result.range(VssLodFaceGroup.ALL, 1, 3).quads,
                "the aquatic tier must not further thin land plants");
    }

    @Test void displayOrderingKeepsSolidSharedRowsModelsFluidsAndCanopyFaces() {
        int[] words = new int[6 * 12];
        int[] flags = {1 | PredictionPackedMesh.FLAG_UNSHADED, 1,
                1 | PredictionPackedMesh.FLAG_MODEL_UV | PredictionPackedMesh.FLAG_UNSHADED,
                1 | PredictionPackedMesh.FLAG_UNSHADED | 1 << PredictionPackedMesh.FLAGS_FLUID_SHIFT,
                1 | PredictionPackedMesh.FLAG_CUTOUT, 1 | PredictionPackedMesh.FLAG_UNSHADED};
        for (int q = 0; q < flags.length; q++) {
            PredictionSpatialOrderTest.quad(words, q, 7, 7, 80, flags[q]);
            words[q * 12 + 10] = q == 5 ? 0 : PredictionPackedMesh.FEATURE;
        }
        boolean[] vegetation = new boolean[256]; vegetation[1] = true;
        var kept = PredictionVegetationRanges.arrange(words, new int[]{0}, new int[]{6}, vegetation, new boolean[256]);
        assertEquals(6, kept.range(1, 0, 0).quads);
        for (int tier = 1; tier <= 3; tier++) {
            var range = kept.range(1, tier, 0);
            assertEquals(5, range.quads);
            for (int r = 0; r < range.first.length; r++)
                for (int q = range.first[r]; q < range.first[r] + range.count[r]; q++)
                    assertNotEquals(0, words[q * 12 + 8], "only the first crossed feature may be omitted");
        }
    }

    @Test void wholeStalksKeepTheirVerticalPartsAndBothFacesWhileWaterAndLandChooseIndependently() {
        int[] words = new int[128 * 12];
        boolean[] vegetation = new boolean[256], aquatic = new boolean[256];
        vegetation[1] = vegetation[2] = aquatic[2] = true;
        for (int q = 0; q < 128; q++) {
            int column = q / 4, face = q % 2, height = q / 2 % 2;
            PredictionCrossEncodingTest.cross(words, q, column % 8 + face, column / 8,
                    33088 + height * 16, column % 8 + 1 - face, column / 8 + 1,
                    33104 + height * 16, q % 4 + 1);
            int row = column < 16 ? 1 : 2;
            words[q * 12 + 6] = words[q * 12 + 6] & ~65535 | row;
            words[q * 12 + 8] = column;
        }
        var plan = PredictionVegetationRanges.arrange(words, new int[]{0}, new int[]{128}, vegetation, aquatic);
        for (int landTier = 0; landTier < 4; landTier++) for (int waterTier = 0; waterTier < 4; waterTier++) {
            int[] parts = new int[32]; var ranges = plan.range(1, landTier, waterTier);
            for (int r = 0; r < ranges.first.length; r++) for (int q = ranges.first[r]; q < ranges.first[r] + ranges.count[r]; q++)
                parts[words[q * 12 + 8]]++;
            for (int n : parts) assertTrue(n == 0 || n == 4, "a stalk must retain both faces and both vertical pieces");
        }
        boolean[] shared = new boolean[256];
        var exact = PredictionVegetationRanges.arrange(words, new int[]{0}, new int[]{128}, shared, aquatic);
        assertEquals(128, exact.range(1, 3, 3).quads, "a non-vegetation shared material must override an aquatic sprite name");
    }

    @Test void publishingCompressionAndCacheRebuildDoNotDuplicateFarLayers() {
        int[] words = grid(PredictionPackedMesh.FLAG_CUTOUT);
        var mesh = PredictionPackedMesh.terrainRecords(words, 64);
        int[] original = mesh.quads().clone(), source = mesh.cacheWords();
        assertEquals(1024 * 12, source.length);
        assertTrue(original.length > source.length);
        mesh.prepareGpuStorage(); mesh.prepareStorage();
        assertArrayEquals(original, mesh.restoreWords());
        int[] first = new int[5], count = {1024, 0, 0, 0, 0};
        var restored = new PredictionPackedMesh(mesh.cacheWords(), 64, 1024, first, count,
                new int[5], new int[5], false, 0);
        assertArrayEquals(original, restored.quads());
        for (int tier = 0; tier <= 3; tier++) {
            var a = mesh.drawRanges(false, VssLodFaceGroup.ALL, tier);
            var b = restored.drawRanges(false, VssLodFaceGroup.ALL, tier);
            assertArrayEquals(a.first, b.first); assertArrayEquals(a.count, b.count);
        }
    }

    @Test void finishedDiskRecordRestoresFarLayersAndKeepsCanonicalPrefix() throws Exception {
        PredictionMeshCodecTest.bootstrap();
        var payload = PredictionPackedMesh.terrainRecords(grid(PredictionPackedMesh.FLAG_CUTOUT), 64);
        var fixture = PredictionMeshCodecTest.fixture(64);
        var mesh = PredictionMesh.restored(1024 * 6, 0, payload, fixture.seamMesh());
        byte[] signature = new byte[32];
        byte[] bytes = PredictionMeshCodec.encode(mesh, signature);
        var restored = PredictionMeshCodec.decode(bytes, signature, 64);
        assertNotNull(restored);
        assertArrayEquals(payload.quads(), restored.gpuPayload().quads());
        assertEquals(1024 * 12, restored.gpuPayload().cacheWords().length);
        assertTrue(restored.gpuPayload().hasDisplayLod());
    }

    private static PredictionDisplayGeometry.Result build(int[] words) {
        int[] first = new int[5], count = {1024, 0, 0, 0, 0};
        int[][] retained = new int[4][5]; for (int[] level : retained) level[0] = 1024;
        return PredictionDisplayGeometry.build(words, first, count, retained, 64);
    }

    static int[] grid(int flags) {
        int[] words = new int[1024 * 12];
        for (int q = 0; q < 1024; q++) {
            PredictionSpatialOrderTest.quad(words, q, q % 32, q / 32, 80, flags);
            for (int offset : new int[]{7, 9, 10, 11}) words[q * 12 + offset] = 0x556633;
            words[q * 12 + 8] = q % 32 + q / 32 * 64;
        }
        return words;
    }
}
