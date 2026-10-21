package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import org.junit.jupiter.api.Test;

class MergeBudgetCopyTest {
    @Test void realSourceDisplayAndEverySelectionStayByteIdenticalAcrossWorkspaceReuse() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft(); var fixture = new MergeBudgetFixture();
        for (int repeat = 0; repeat < 3; repeat++) for (var m : fixture.meshes) {
            int[] source = m.words().clone(); var a = fixture.before(m); var b = fixture.after(m);
            assertArrayEquals(source,m.words(),"worker compaction cannot modify the canonical source");
            assertArrayEquals(a.words(),b.words(),"all source, water and display records " + m.id());
            assertArrayEquals(a.errors(),b.errors());
            for (int tier = 1; tier <= 3; tier++) for (int mask = 0; mask < 32; mask++) {
                var old = a.range(tier,0,mask,0); var next = b.range(tier,0,mask,0);
                if (old == null) assertNull(next);
                else { assertArrayEquals(old.first,next.first); assertArrayEquals(old.count,next.count); }
            }
        }
    }
    @Test void paddedOpaqueScratchNeverPublishesItsStaleTailWithOrWithoutMerging() {
        for (boolean merge : new boolean[]{false,true}) {
            int[] source = PredictionScanMergeTest.slice(1,32,false);
            if (!merge) for (int p = 0; p < source.length; p += 12) source[p + 11]++;
            int[] padded = Arrays.copyOf(source,source.length * 2); Arrays.fill(padded,source.length,padded.length,0x6fffffff);
            var first = new int[5]; var count = new int[]{1024,0,0,0,0};
            var a = PredictionScanMerge.merge(source,first,count,32,0,false,16);
            var b = PredictionScanMerge.mergeDisplay(padded,first,count,32,16);
            int valid = Arrays.stream(b.count()).sum() * 12;
            assertArrayEquals(a.words(),Arrays.copyOf(b.words(),valid));
            assertArrayEquals(a.first(),b.first()); assertArrayEquals(a.count(),b.count());
        }
    }
    @Test void legacyJoinAndNoDisplayModesKeepTheirCanonicalBytes() {
        String previous = System.getProperty("vss.disableVoxyScanMerge");
        try {
            System.setProperty("vss.disableVoxyScanMerge","true");
            for (int flags : new int[]{0,PredictionPackedMesh.FLAG_CUTOUT,PredictionPackedMesh.FLAG_MODEL_UV}) {
                int[] words = PredictionDisplayGeometryTest.grid(flags), first = new int[5], count = {1024,0,0,0,0};
                int[][] retained = new int[4][5];
                var a = MergeBudgetLegacyDisplayGeometry.build(words,first,count,retained,64,new boolean[256]);
                var b = PredictionDisplayGeometry.build(words,first,count,retained,64,new boolean[256]);
                assertArrayEquals(a.words(),b.words()); assertArrayEquals(a.errors(),b.errors());
            }
        } finally { if (previous == null) System.clearProperty("vss.disableVoxyScanMerge"); else System.setProperty("vss.disableVoxyScanMerge",previous); }
    }
    @Test void benchmarkAlternatingWorkerBuildsAndAllocatedBytes() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft(); var fixture = new MergeBudgetFixture();
        var bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean(); long id = Thread.currentThread().getId();
        var rows = new ArrayList<String>(); rows.add("mesh,round,mode,worker_ns,allocated_bytes,stored_words");
        for (var m : fixture.meshes) {
            long[][] time = new long[2][41], bytes = new long[2][41];
            for (int round = -20; round < 41; round++) for (int order = 0; order < 2; order++) {
                int mode = Math.floorMod(round + order,2); long startBytes = bean.getThreadAllocatedBytes(id), start = System.nanoTime();
                int length = mode == 0 ? fixture.before(m).words().length : fixture.after(m).words().length;
                long elapsed = System.nanoTime() - start, allocated = bean.getThreadAllocatedBytes(id) - startBytes;
                if (round >= 0) { time[mode][round] = elapsed; bytes[mode][round] = allocated;
                    rows.add(m.id() + "," + round + "," + mode + "," + elapsed + "," + allocated + "," + length); }
            }
            for (var row : time) Arrays.sort(row); for (var row : bytes) Arrays.sort(row);
            System.out.printf(Locale.ROOT,"MERGE_BUDGET_COPY id=%d worker_ms=%.6f->%.6f allocated_KiB=%.3f->%.3f%n",m.id(),
                    time[0][20] / 1e6,time[1][20] / 1e6,bytes[0][20] / 1024.,bytes[1][20] / 1024.);
            assertTrue(bytes[1][20] < bytes[0][20],"real meshes must allocate less without changing records");
        }
        CompletionGpuFixture.save("merge-budget-worker.csv",rows);
    }
}
