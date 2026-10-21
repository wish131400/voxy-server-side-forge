package dev.xantha.vss.client.prediction;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Replays previously captured geometry; never attaches to or changes a client. */
public final class PredictionOwnershipMemoryReplay {
    public static void main(String[] args) throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var input = new DataInputStream(new BufferedInputStream(System.in, 1 << 20));
        long oldBytes = 0, hotBytes = 0, coldBytes = 0, runsTotal = 0, quads = 0, meshes = 0;
        long oldNanos = 0, newNanos = 0, filterOldNanos = 0, filterNewNanos = 0;
        byte[] columns = new byte[130 * 130]; Arrays.fill(columns, (byte) 255);
        for (int z = 0; z < 130; z++) for (int x = 0; x < 130; x++) if ((x + z) % 5 == 0) columns[z * 130 + x] = 0;
        var index = new PredictionExactCoverageMask.Snapshot(-2, -2, 130, columns);
        while (true) {
            int length; try { length = input.readInt(); } catch (EOFException end) { break; }
            int palette = input.readInt(), count = input.readInt(); int[] compact = new int[length];
            for (int i = 0; i < length; i++) compact[i] = input.readInt();
            int[] words = PredictionGpuEncoding.decode(compact, palette, count);
            long begin = System.nanoTime(); var legacy = new PredictionOwnershipLegacy(words); oldNanos += System.nanoTime() - begin;
            begin = System.nanoTime(); var current = new LegacyOwnershipRuns(words); newNanos += System.nanoTime() - begin;
            if (legacy.ends.length != current.size()) throw new AssertionError("run count " + meshes);
            for (int run = 0; run < current.size(); run++) {
                if (legacy.ends[run] != current.end(run)) throw new AssertionError("ends " + meshes);
                for (int axis = 0; axis < 6; axis++) if (Float.floatToRawIntBits(legacy.boxes[run * 6 + axis])
                        != Float.floatToRawIntBits(current.coordinate(run, axis))) throw new AssertionError("coordinate " + meshes);
            }
            var plan = new PredictionDrawRanges(new int[]{0}, new int[]{count});
            begin = System.nanoTime(); var oldFiltered = legacy.filter(plan, index, 0, 0, 30, 90, 45, 400,
                    Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY); filterOldNanos += System.nanoTime() - begin;
            begin = System.nanoTime(); var filtered = current.filter(plan, index, 0, 0, 30, 90, 45, 400,
                    Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY); filterNewNanos += System.nanoTime() - begin;
            if (!Arrays.equals(oldFiltered.first, filtered.first) || !Arrays.equals(oldFiltered.count, filtered.count))
                throw new AssertionError("filter " + meshes);
            long before = current.retainedHeapBytes();
            var cache = new LegacyMetadataCache(64L << 20, 16, Runnable::run, () -> Long.MAX_VALUE);
            current.cool(cache);
            oldBytes += 128L + legacy.ends.length * 29L; hotBytes += before; coldBytes += current.retainedHeapBytes();
            runsTotal += current.size(); quads += count; meshes++;
        }
        String json = String.format(Locale.ROOT,
                "{\"meshes\":%d,\"quads\":%d,\"runs\":%d,\"oldBytes\":%d,\"hotBytes\":%d,\"coldBytes\":%d,"
                + "\"legacyBuildMs\":%.3f,\"compactBuildMs\":%.3f,\"legacyFilterMs\":%.3f,\"compactFilterMs\":%.3f}",
                meshes, quads, runsTotal, oldBytes, hotBytes, coldBytes, oldNanos / 1e6, newNanos / 1e6,
                filterOldNanos / 1e6, filterNewNanos / 1e6);
        Files.writeString(Path.of(args[0]), json + "\n"); System.out.println(json);
    }
}
