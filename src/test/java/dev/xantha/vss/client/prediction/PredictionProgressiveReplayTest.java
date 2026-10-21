package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PredictionProgressiveReplayTest {
    @Test void historicalMeshesKeepExistingVariantsAndGainSharedGroundRanges() throws Exception {
        var fixtures = new FarTerrainFixture("/progressive-detail/shared-source.bin.gz"); var rows = new ArrayList<String>();
        rows.add("mesh,source,ground,features,before_variants,shared,distance,before_quads,after_quads,extra_quads,commands");
        int improved = 0; long before4096 = 0, after4096 = 0;
        for (var mesh : fixtures.meshes) {
            int[] words = mesh.words().clone();
            var sourceVegetation = PredictionVegetationRanges.arrange(words, mesh.first(), mesh.count(), fixtures.plants, fixtures.aquatic);
            var old = ProgressiveBaselineDisplayGeometry.build(words, mesh.first(), mesh.count(), mesh.retained(), mesh.axis(), fixtures.approximate);
            var next = PredictionDisplayGeometry.build(words, mesh.first(), mesh.count(), mesh.retained(), mesh.axis(), fixtures.approximate, sourceVegetation);
            assertArrayEquals(words, Arrays.copyOf(next.words(), words.length));
            int total = Arrays.stream(mesh.count()).sum(), features = 0;
            for (int q = 0; q < total; q++) if ((words[q * 12 + 10] & PredictionPackedMesh.FEATURE) != 0) features++;
            assertTrue(next.words().length - words.length <= (total - (next.vegetation() == null && next.sharedFeatures() != null ? features : 0)) * 12 / 2);
            if (old.ranges() != null) { assertArrayEquals(old.words(), next.words()); assertArrayEquals(old.errors(), next.errors()); }
            if (old.ranges() == null && next.ranges() != null) improved++;
            if (next.sharedFeatures() != null) for (int tier = 1; tier < 4; tier++) if (Float.isFinite(next.errors()[tier]))
                for (int mask = 0; mask < 32; mask++) {
                    var shared = next.sharedFeatures().range(tier, 0, mask, 0);
                    var sourceRange = sourceVegetation.range(mask, 0, 0);
                    assertTrue(shared.quads <= sourceRange.quads);
                    BitSet expected = sourceFeatures(words, sourceRange, words.length / 12);
                    BitSet actual = sourceFeatures(next.words(), shared, words.length / 12);
                    assertEquals(expected, actual, "feature source indices must stay exact across every face mask");
                }
            for (int distance : new int[]{256, 1024, 2048, 4096, 8192, 16384}) {
                var a = new PredictionDisplayLod(); var b = new PredictionDisplayLod();
                a.update(old.errors(), distance, 900, 512, false, 2); b.update(next.errors(), distance, 900, 512, false, 2);
                var ar = a.tier() == 0 ? sourceVegetation.range(31, 0, 0) : old.range(a.tier(), 0, 31, 0);
                var br = b.tier() == 0 ? sourceVegetation.range(31, 0, 0) : next.range(b.tier(), 0, 31, 0);
                assertTrue(br.quads <= ar.quads, "historical mesh " + mesh.id());
                for (int r = 0; r < br.first.length; r++) assertTrue(br.first[r] >= 0 && br.first[r] + br.count[r] <= next.words().length / 12);
                if (distance == 4096) { before4096 += ar.quads; after4096 += br.quads; }
                rows.add(mesh.id() + "," + total + "," + (total - features) + "," + features + "," + (old.ranges() != null) + "," + (next.sharedFeatures() != null)
                        + "," + distance + "," + ar.quads + "," + br.quads + "," + (next.words().length - words.length) / 12 + "," + br.first.length);
            }
        }
        CompletionGpuFixture.save("progressive-detail-historical.csv", rows);
        System.out.println("PROGRESSIVE_REPLAY improved=" + improved + " meshes=" + fixtures.meshes.size() + " quads4096=" + before4096 + "->" + after4096);
        // Report the real corpus honestly; a synthetic regression covers admission
        // cases absent from this historical capture rather than fabricating a gain.
        assertTrue(after4096 <= before4096);
    }
    private static BitSet sourceFeatures(int[] words, PredictionDrawRanges ranges, int sourceQuads) {
        var features = new BitSet();
        for (int r = 0; r < ranges.first.length; r++) for (int q = ranges.first[r]; q < ranges.first[r] + ranges.count[r]; q++)
            if ((words[q * 12 + 10] & PredictionPackedMesh.FEATURE) != 0) {
                assertTrue(q < sourceQuads, "a split handoff cannot draw a displaced feature copy"); features.set(q);
            }
        return features;
    }
}
