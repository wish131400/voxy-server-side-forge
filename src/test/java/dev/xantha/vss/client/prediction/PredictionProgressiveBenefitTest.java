package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.Test;

class PredictionProgressiveBenefitTest {
    private static volatile long sink;
    @Test void offscreenUpdatesSelectionLookupDistanceAndMemoryAccountingHaveSeparateMeasurements() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var modern = new PredictionPreparedPassLocalTest.Pass(false);
        Class<?> oldType = Class.forName(ProgressiveBaselineRenderer.class.getName() + "$PreparedPass");
        var constructor = oldType.getDeclaredConstructor(boolean.class); constructor.setAccessible(true);
        Object oldPass = constructor.newInstance(false);
        var oldPrepare = oldType.getDeclaredMethod("prepare", List.class, long.class, Vec3.class); oldPrepare.setAccessible(true);
        var draws = new ArrayList<PredictionRenderer.Draw>(); var oldDraws = new ArrayList<PredictionRenderer.Draw>();
        var mesh = PredictionPackedMesh.seamRecords(PredictionDisplayGeometryTest.grid(0), 64);
        for (int i = 0; i < 1024; i++) {
            var draw = PredictionPreparedPassLocalTest.draw(i % 32, i / 32, mesh); draws.add(draw);
            oldDraws.add(draw);
        }
        var camera = new Vec3(32, 80, 32); modern.prepare(draws, 1, camera); oldPrepare.invoke(oldPass, oldDraws, 1L, camera);
        var remote = PredictionPackedMesh.seamRecords(new int[12], 1); remote.trackSubmission();
        var beans = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean(); long thread = Thread.currentThread().getId();
        var rows = new ArrayList<String>(); rows.add("operation,round,mode,ns,allocated_bytes");
        long[][] times = new long[2][41], allocation = new long[2][41];
        for (int round = -80; round < 41; round++) for (int order = 0; order < 2; order++) {
            int mode = Math.floorMod(round + order, 2); remote.morph(null, 0, 0);
            long bytes = beans.getThreadAllocatedBytes(thread), start = System.nanoTime();
            if (mode == 0) oldPrepare.invoke(oldPass, oldDraws, (long)round + 100, camera);
            else modern.prepare(draws, round + 100, camera);
            long ns = System.nanoTime() - start; bytes = beans.getThreadAllocatedBytes(thread) - bytes;
            if (round >= 0) { times[mode][round] = ns; allocation[mode][round] = bytes; rows.add("offscreen_updates," + round + "," + mode + "," + ns + "," + bytes); }
        }
        System.out.printf(Locale.ROOT,"PROGRESSIVE_BENEFIT operation=offscreen_updates draws=1024 CPU_ns=%.0f->%.0f allocated_bytes=%.0f->%.0f%n",
                median(times[0]), median(times[1]), median(allocation[0]), median(allocation[1]));
        assertTrue(median(times[1]) < median(times[0]), "retain the prepared-pass optimization only with a measured benefit");
        var ranges = modern.ranges(draws.get(0)); assertNotNull(ranges);
        // Old accounting is reproduced from the pre-change fields. Its result
        // matches the production counter in PredictionProgressiveDetailTest.
        int[] words = PredictionProgressiveDetailTest.decorated(8192);
        var plan = PredictionVegetationRanges.arrange(words, new int[5], new int[]{words.length / 12,0,0,0,0}, new boolean[256], new boolean[256]);
        for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++) for (int m = 0; m < 32; m++) plan.range(m, a, b);
        var field = PredictionVegetationRanges.class.getDeclaredField("selected"); field.setAccessible(true);
        var slots = (PredictionDrawRanges[][][])field.get(plan); long base = 620;
        measure(rows, "retained_bytes", () -> {
            long bytes = base;
            for (var tiers : slots) for (var selected : tiers) if (selected != null) {
                bytes += 16L + selected.length * 4L;
                for (var r : selected) if (r != null) bytes += 64L + r.first.length * 8L;
            }
            return bytes;
        }, plan::retainedHeapBytes, 1000);
        var coverage = new LegacyResidentCoverage(PredictionCoarseHandoffTest.ready());
        coverage.update(PredictionExactCoverageMask.Snapshot.around(0, 0, 64), camera, 4096);
        var selected = new LegacyResidentCoverage.Selection();
        assertEquals(coverage.selectionVersion(mesh, 0, 0, false), coverage.selectionVersion(mesh, 0, 0, false, selected));
        measure(rows, "ownership_version", () -> coverage.selectionVersion(mesh, 0, 0, false),
                () -> coverage.selectionVersion(mesh, 0, 0, false, selected), 10000);
        double[] cx = new double[4096], cz = new double[4096]; var random = new Random(619);
        for (int i = 0; i < cx.length; i++) { cx[i] = random.nextDouble(-30000, 30000); cz[i] = random.nextDouble(-30000, 30000); }
        measure(rows, "world_distance", () -> {
            double sum = 0; for (int i = 0; i < cx.length; i++) sum += Math.hypot(Math.max(0, Math.max(-cx[i], cx[i] - 64)), Math.max(0, Math.max(-cz[i], cz[i] - 64)));
            return Double.doubleToLongBits(sum);
        }, () -> {
            double sum = 0; for (int i = 0; i < cx.length; i++) sum += PredictionAquaticLod.distance(cx[i], cz[i], 0, 0, 64);
            return Double.doubleToLongBits(sum);
        }, 10);
        CompletionGpuFixture.save("progressive-detail-cpu-benefit.csv", rows);

    }
    private static void measure(List<String> rows, String name, java.util.function.LongSupplier old,
                                java.util.function.LongSupplier next, int repeats) {
        long[][] times = new long[2][41];
        for (int round = -80; round < 41; round++) for (int order = 0; order < 2; order++) {
            int mode = Math.floorMod(round + order, 2); long sum = 0, start = System.nanoTime();
            for (int i = 0; i < repeats; i++) sum += (mode == 0 ? old : next).getAsLong();
            long ns = System.nanoTime() - start; sink = sum;
            if (round >= 0) { times[mode][round] = ns; rows.add(name + "," + round + "," + mode + "," + ns + ",0"); }
        }
        System.out.printf(Locale.ROOT,"PROGRESSIVE_BENEFIT operation=%s repeats=%d CPU_ns=%.0f->%.0f%n", name, repeats, median(times[0]), median(times[1]));
        assertTrue(median(times[1]) < median(times[0]), "retain " + name + " only when the isolated component improves");
    }
    private static double median(long[] values) { long[] copy = values.clone(); Arrays.sort(copy); return copy[copy.length / 2]; }
}
