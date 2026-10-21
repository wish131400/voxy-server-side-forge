package dev.xantha.vss.client.prediction;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Offline real source meshes. No client, no FPS or live ownership assumptions. */
public class ProgressiveCorpusReplay {
    public static void main(String[] args) throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        Path output = Path.of(args[1]); Files.createDirectories(output);
        try (var in = new DataInputStream(new GZIPInputStream(Files.newInputStream(Path.of(args[0])), 1 << 20));
             var out = Files.newBufferedWriter(output.resolve("progressive-detail-corpus.csv"))) {
            int magic = in.readInt(); if (magic != 0x56535350) throw new IOException("capture magic");
            boolean[] approximate = new boolean[256], plants = new boolean[256], aquatic = new boolean[256];
            for (int i = 0; i < 256; i++) {
                approximate[i] = in.readBoolean(); plants[i] = in.readBoolean(); aquatic[i] = in.readBoolean();
            }
            int size = in.readInt(), gained = 0, unchanged = 0; long totalGround = 0, totalFeatures = 0;
            long[] before = new long[4], after = new long[4]; long oldBytes = 0, newBytes = 0, oldTime = 0, newTime = 0;
            long[] mixedBefore = new long[4], mixedAfter = new long[4];
            out.write("mesh,source,ground,features,shared,old_variants,old4096,new4096,old8192,new8192,old_bytes,new_bytes,old_build_ns,new_build_ns,mixed_old4096,mixed_new4096,mixed_old8192,mixed_new8192\n");
            for (int index = 0; index < size; index++) {
                int id = in.readInt(), axis = in.readInt(), spacing = in.readInt(), length = in.readInt(), terrain = in.readInt();
                int[] source = new int[length]; for (int w = 0; w < length; w++) source[w] = in.readInt();
                boolean crossed = true;
                for (int q = 1; q < terrain; q++) if (group(source[q * 12 + 6], true) < group(source[(q - 1) * 12 + 6], true)) { crossed = false; break; }
                int[] first = new int[5], count = new int[5];
                for (int q = 0; q < terrain; q++) count[group(source[q * 12 + 6], crossed)]++;
                int at = 0; for (int g = 0; g < 5; g++) { first[g] = at; at += count[g]; }
                var merged = PredictionScanMerge.merge(source, first, count, axis, spacing, true, 16);
                boolean[] thin = plants.clone(); for (int i = 0; i < 256; i++) thin[i] |= aquatic[i];
                var retained = PredictionSpatialOrder.arrange(merged.words(), merged.first(), merged.count(), thin);
                var sourceVegetation = PredictionVegetationRanges.arrange(merged.words(), merged.first(), merged.count(), plants, aquatic);
                long start = System.nanoTime();
                var old = ProgressiveBaselineDisplayGeometry.build(merged.words(), merged.first(), merged.count(), retained, axis, approximate);
                long oldNanos = System.nanoTime() - start; start = System.nanoTime();
                var next = PredictionDisplayGeometry.build(merged.words(), merged.first(), merged.count(), retained, axis, approximate, sourceVegetation);
                long newNanos = System.nanoTime() - start;
                if (!Arrays.equals(merged.words(), Arrays.copyOf(next.words(), merged.words().length))) throw new AssertionError("source changed " + id);
                if (old.ranges() != null && !Arrays.equals(old.words(), next.words())) throw new AssertionError("existing variant changed " + id);
                int n = Arrays.stream(merged.count()).sum(), features = 0;
                for (int q = 0; q < n; q++) if ((merged.words()[q * 12 + 10] & PredictionPackedMesh.FEATURE) != 0) features++;
                int ground = n - features; totalGround += ground; totalFeatures += features;
                if (next.sharedFeatures() != null) gained++; else unchanged++;
                long[] aq = new long[4], bq = new long[4], mq = new long[4]; int[] distances = {1024, 2048, 4096, 8192};
                for (int d = 0; d < distances.length; d++) {
                    var a = new PredictionDisplayLod(); var b = new PredictionDisplayLod();
                    a.update(old.errors(), distances[d], 1475, 512, false, 2); b.update(next.errors(), distances[d], 1475, 512, false, 2);
                    aq[d] = a.tier() == 0 ? n : old.range(a.tier(), 0, 31, 0).quads;
                    bq[d] = b.tier() == 0 ? n : next.range(b.tier(), 0, 31, 0).quads;
                    if (bq[d] > aq[d]) throw new AssertionError("geometry regression " + id);
                    before[d] += aq[d]; after[d] += bq[d];
                    var split = b.tier() == 0 || next.sharedFeatures() == null ? null : next.sharedFeatures().range(b.tier(), 0, 31, 0);
                    mq[d] = split == null ? n : split.quads;
                    if (mq[d] > n) throw new AssertionError("mixed-ownership regression " + id);
                    mixedBefore[d] += n; mixedAfter[d] += mq[d];
                }
                oldBytes += old.words().length * 4L; newBytes += next.words().length * 4L; oldTime += oldNanos; newTime += newNanos;
                out.write(id + "," + n + "," + ground + "," + features + "," + (next.sharedFeatures() != null) + "," + (old.ranges() != null)
                        + "," + aq[2] + "," + bq[2] + "," + aq[3] + "," + bq[3] + "," + old.words().length * 4L + "," + next.words().length * 4L
                        + "," + oldNanos + "," + newNanos + "," + n + "," + mq[2] + "," + n + "," + mq[3] + "\n");
                if ((index + 1) % 250 == 0) { out.flush(); System.out.println("PROGRESSIVE_CORPUS meshes=" + (index + 1) + "/" + size + " shared=" + gained); }
            }
            System.out.printf(Locale.ROOT,"PROGRESSIVE_CORPUS_FINAL meshes=%d shared=%d unchanged=%d ground=%d features=%d before=%s after=%s bytes=%d->%d worker_ns=%d->%d%n",
                    size, gained, unchanged, totalGround, totalFeatures, Arrays.toString(before), Arrays.toString(after), oldBytes, newBytes, oldTime, newTime);
            System.out.printf("PROGRESSIVE_CORPUS_MIXED before=%s after=%s assumption=all-terrain-owned-and-feature-handoff-not-live-share%n", Arrays.toString(mixedBefore), Arrays.toString(mixedAfter));
        }
    }
    private static int group(int flags, boolean crossed) {
        if (crossed && (flags & PredictionPackedMesh.FLAG_UNSHADED) != 0 && (flags & PredictionPackedMesh.FLAG_MODEL_UV) == 0) return 0;
        int axis = flags >>> 24 & 3; boolean positive = (flags & 1 << 30) != 0;
        return axis == 1 ? (positive ? 4 : 3) : axis == 2 ? (positive ? 2 : 1) : 0;
    }
}
