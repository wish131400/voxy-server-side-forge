package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.Executor;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;

class PredictionOwnershipMemoryTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }

    @Test void exactBoundariesSurviveDictionaryWideHeightsAndFloatFallback() {
        for (int mode = 0; mode < 5; mode++) {
            int count = 4096; int[] ends = new int[count]; byte[] kinds = new byte[count]; float[] boxes = new float[count * 6];
            var random = new Random(93 + mode);
            for (int run = 0; run < count; run++) {
                ends[run] = run * 3 + 3; kinds[run] = (byte) (run & 3);
                int b = run * 6, cell = mode == 0 ? run & 7 : run;
                boxes[b] = -32 + (cell % 128) * 16; boxes[b + 2] = -16 + (cell / 128) * 16;
                boxes[b + 3] = boxes[b] + 32; boxes[b + 5] = boxes[b + 2] + 16;
                boxes[b + 1] = (random.nextInt(mode == 2 ? 160000 : 4000) - 16000) / 16f;
                boxes[b + 4] = boxes[b + 1] + random.nextInt(10000) / 16f;
                if (mode == 3) boxes[b + 1] += 1f / 32;
                if (mode == 4) {
                    boxes[b] = -0f;
                    boxes[b + 1] = run % 2 == 0 ? Float.intBitsToFloat(0x7fc00123) : -Float.MAX_VALUE;
                    boxes[b + 4] = Float.MAX_VALUE;
                }
            }
            var data = new LegacyOwnershipData(ends, kinds, boxes);
            var restored = LegacyOwnershipData.decode(data.encode());
            assertEquals(count, restored.size());
            for (int run = 0; run < count; run++) {
                assertEquals(ends[run], restored.end(run)); assertEquals(kinds[run], restored.kind(run));
                for (int axis = 0; axis < 6; axis++) assertEquals(Float.floatToRawIntBits(boxes[run * 6 + axis]),
                        Float.floatToRawIntBits(restored.coordinate(run, axis)), "mode=" + mode + " run=" + run + " axis=" + axis);
                assertEquals(run, restored.firstRun(ends[run] - 1)); assertEquals(run + 1, restored.firstRun(ends[run]));
            }
            if (mode == 0) assertTrue(data.retainedBytes() < count * 14L, "repeated footprints must reduce hot memory");
            if (mode == 1) assertTrue(data.retainedBytes() < count * 18L, "unique footprints still pack exactly");
        }
    }

    @Test void malformedColdMetadataIsRejectedBeforePublication() {
        var data = new LegacyOwnershipData(new int[]{3}, new byte[]{3}, new float[]{-16, -64.0625f, 0, 16, 4096, 32});
        int[] encoded = data.encode(); encoded[2] = 123;
        assertThrows(IllegalStateException.class, () -> LegacyOwnershipData.decode(encoded));
        assertThrows(IllegalStateException.class, () -> LegacyOwnershipData.decode(new int[0]));
    }

    @Test void coldMetadataRestoresExactSubmissionAndRevocationWithoutBlocking() {
        var mesh = mesh(); var runs = LegacyOwnershipRuns.of(mesh);
        var input = mesh.drawRanges(false, VssLodFaceGroup.ALL);
        byte[] settled = new byte[64 * 64]; Arrays.fill(settled, (byte) 255);
        var index = new PredictionExactCoverageMask.Snapshot(-1, -1, 64, settled);
        var state = new LegacyResidentCoverage(PredictionCoarseHandoffTest.ready()); state.update(index, new Vec3(4, 68, 4), 4096, Level.OVERWORLD);
        var before = state.filter(mesh, input, 0, 0, false, VssLodFaceGroup.ALL);
        var queue = new ArrayDeque<Runnable>(); var cache = cache(queue);
        long hot = runs.retainedHeapBytes(); runs.cool(cache); assertEquals(1, queue.size());
        queue.remove().run(); assertFalse(runs.ready()); assertTrue(runs.retainedHeapBytes() < hot);
        assertTrue(mesh.uploadReady(), "obsolete ownership metadata must not delay a ready GPU payload");
        assertSame(input, state.filter(mesh, input, 0, 0, false, VssLodFaceGroup.ALL), "cold data cannot certify deletion");
        runs.requestRestore(cache); if (!queue.isEmpty()) queue.remove().run();
        // The frozen reference filter may have requested its own background restore.
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!runs.ready() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertTrue(runs.ready());
        var restored = state.filter(mesh, input, 0, 0, false, VssLodFaceGroup.ALL);
        assertArrayEquals(before.first, restored.first); assertArrayEquals(before.count, restored.count);
        state.update(new PredictionExactCoverageMask.Snapshot(-1, -1, 64, new byte[64 * 64]),
                new Vec3(4, 68, 4), 4096, Level.OVERWORLD);
        assertSame(input, state.filter(mesh, input, 0, 0, false, VssLodFaceGroup.ALL), "coverage withdrawal restores every quad");
    }

    @Test void uploadedPinCancelsAnAlreadyQueuedFreezeAndReleasePermitsCooling() {
        var runs = LegacyOwnershipRuns.of(mesh()); var queue = new ArrayDeque<Runnable>(); var cache = cache(queue);
        runs.cool(cache); assertTrue(runs.pin()); queue.remove().run();
        assertTrue(runs.ready(), "uploaded payload cannot lose hot proofs during a freeze race");
        runs.cool(cache); assertTrue(queue.isEmpty()); runs.unpin(); runs.cool(cache); queue.remove().run();
        assertFalse(runs.ready()); assertFalse(runs.pin()); runs.requestRestore(cache); queue.remove().run();
        assertTrue(runs.pin()); runs.unpin();
    }

    @Test void boundedQueueAndResetReleasePromisesWithoutPublishingStaleMetadata() {
        var queue = new ArrayDeque<Runnable>(); var cache = new LegacyMetadataCache(64L << 20, 1, queue::add, () -> Long.MAX_VALUE);
        var one = LegacyOwnershipRuns.of(mesh()); var two = LegacyOwnershipRuns.of(mesh());
        one.cool(cache); long promised = cache.pendingBytes(); two.cool(cache);
        assertEquals(1, cache.pendingJobs()); assertEquals(promised, cache.pendingBytes()); assertEquals(1, queue.size());
        cache.clear(); queue.remove().run(); assertTrue(one.ready()); assertEquals(0, cache.pendingBytes());
        one.cool(cache); queue.remove().run(); assertFalse(one.ready());
        one.requestRestore(cache); cache.clear(); queue.remove().run(); assertFalse(one.ready());
        one.requestRestore(cache); queue.remove().run(); assertTrue(one.ready()); assertEquals(0, cache.pendingJobs());
        var noHeadroom = new LegacyMetadataCache(64L << 20, 16, queue::add, () -> 0);
        two.cool(noHeadroom); assertTrue(queue.isEmpty());
    }

    private static LegacyMetadataCache cache(ArrayDeque<Runnable> queue) {
        return new LegacyMetadataCache(64L << 20, 16, queue::add, () -> Long.MAX_VALUE);
    }
    static PredictionPackedMesh mesh() {
        int[] words = new int[12000 * PredictionPackedMesh.STRIDE_INTS];
        for (int q = 0; q < 12000; q++) {
            int x = q % 2 == 0 ? 1 : 65;
            int[] quad = PredictionPreviewHandoffTest.quad(x, 1, 64 + q % 16, x + 1, 2, q % 64, false);
            if (q % 3 != 0) quad[10] &= ~PredictionPackedMesh.FEATURE;
            System.arraycopy(quad, 0, words, q * 12, 12);
        }
        return PredictionPackedMesh.seamRecords(words, 64);
    }
}
