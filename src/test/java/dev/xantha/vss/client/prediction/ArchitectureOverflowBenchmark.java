package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL43C.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

/** Fills the real main arena, then compares the two production upload/submission paths. */
final class ArchitectureOverflowBenchmark {
    static void run(PredictionTerrainProgram program, List<PredictionRenderer.Draw> fixtures, Vec3 camera) throws Exception {
        String destination = System.getProperty("vss.architectureResults");
        if (destination == null) return;
        String prior = System.getProperty("vss.disableIndirectOverflow");
        String indirect = System.getProperty("vss.disableIndirect");
        System.clearProperty("vss.disableIndirect");
        var fills = new ArrayList<PredictionTerrainArena.Slice>();
        var tiles = new ArrayList<PredictionGpuTile>();
        var modes = List.of(new ArrayList<PredictionRenderer.Draw>(), new ArrayList<PredictionRenderer.Draw>());
        var source = fixtures.stream().filter(d -> d.gpu().arenaSlice() != null).findFirst().orElseThrow();
        int query = glGenQueries();
        var csv = new ArrayList<String>();
        csv.add("round,mode,cpu_ns,gpu_ns,draw_calls,committed_bytes");
        try {
            ByteBuffer dummy = MemoryUtil.memAlloc(PredictionTerrainArena.PAGE_BYTES / 2);
            try {
                for (int bytes : new int[]{dummy.capacity(), 1 << 20, 4096}) {
                    dummy.clear().limit(bytes);
                    PredictionTerrainArena.Slice slice;
                    while ((slice = PredictionTerrainArena.SHARED.upload(dummy, 0)) != null) fills.add(slice);
                }
            } finally { MemoryUtil.memFree(dummy); }
            long mainBytes = PredictionTerrainArena.SHARED.allocatedBytes();
            long[] committed = new long[2];
            for (int mode = 0; mode < 2; mode++) {
                System.setProperty("vss.disableIndirectOverflow", mode == 0 ? "true" : "false");
                long before = PredictionTerrainArena.OVERFLOW.allocatedBytes();
                long bytes = 0;
                for (int index = 0; index < 384; index++) {
                    var mesh = source.tile().mesh();
                    var key = new PredictionTileManager.PredictionTileKey(source.tile().key().dimension(), index % 24, index / 24, 0);
                    var tile = new PredictionTileManager.PredictionTile(key, new int[0], new int[0], new ClientColumnSample[0],
                            mesh, new PredictionDepthBound(60, 80), 0, index + 1, mesh.cellAxis(), 1);
                    var gpu = new PredictionGpuTile(key); tiles.add(gpu); assertTrue(gpu.ensureMesh(tile));
                    if (mode == 0) assertNull(gpu.arenaSlice());
                    else assertSame(PredictionTerrainArena.OVERFLOW, gpu.arenaSlice().arena);
                    boolean[] allowed = new boolean[mesh.cellCount()]; Arrays.fill(allowed, true); gpu.updateCoverage(allowed);
                    modes.get(mode).add(new PredictionRenderer.Draw(tile, gpu, allowed, false, VssLodFaceGroup.ALL, 0,
                            new AABB(tile.baseBlockX(), 60, tile.baseBlockZ(), tile.baseBlockX() + tile.spanBlocks(), 80,
                                    tile.baseBlockZ() + tile.spanBlocks())));
                    bytes += gpu.standaloneBytes();
                }
                committed[mode] = bytes + PredictionTerrainArena.OVERFLOW.allocatedBytes() - before;
            }
            var target = new com.mojang.blaze3d.pipeline.TextureTarget(128, 128, true, false);
            try (var old = new PredictionIndirectBatch(); var candidate = new PredictionIndirectBatch()) {
                for (boolean culled : new boolean[]{false, true}) for (int mask : new int[]{0, 1, 2, 0}) {
                    for (var draws : modes) for (var draw : draws) {
                        var allowed = draw.allowed();
                        for (int i = 0; i < allowed.length; i++) allowed[i] = mask == 0 || mask == 2 && i % 7 != 0;
                        draw.gpu().updateCoverage(allowed);
                    }
                    assertArrayEquals(PredictionProductionBatchGpuTest.groupedFallbackFrame(program, old, modes.get(0), camera, target, false, culled),
                            PredictionProductionBatchGpuTest.groupedFallbackFrame(program, candidate, modes.get(1), camera, target, false, culled),
                            "full arena overflow color/depth mask=" + mask + " culled=" + culled);
                }
                glEnable(GL_RASTERIZER_DISCARD);
                long[][] cpu = new long[2][41], gpu = new long[2][41];
                var groups = List.of(new PredictionOpaqueBatches(), new PredictionOpaqueBatches());
                long[] calls = new long[2];
                for (int round = -24; round < 41; round++) for (int order = 0; order < 2; order++) {
                    int mode = Math.floorMod(round + order, 2);
                    var batch = mode == 0 ? old : candidate;
                    var draws = modes.get(mode);
                    glFinish(); glBeginQuery(GL_TIME_ELAPSED, query);
                    long start = System.nanoTime();
                    program.use(); program.setOpaqueAlpha(1); batch.begin(program, true, 11, 19);
                    long direct = 0;
                    if (batch.replayPass(draws, 1, true, camera, Long.MAX_VALUE) < 0) {
                        int count = groups.get(mode).prepare(draws, 11);
                        for (int b = 0; b < count; b++) {
                            batch.nextOcclusionBucket(); var bucket = groups.get(mode).bucket(b);
                            for (var page : bucket.pages) for (var draw : page.draws)
                                assertTrue(batch.add(draw, draw.gpu().packed().drawRanges(false, draw.faces()), camera, false, true, Long.MAX_VALUE));
                            if (!bucket.fallback.isEmpty()) batch.flush();
                            for (var draw : bucket.fallback) {
                                var packed = draw.gpu().packed(); var ranges = packed.drawRanges(false, draw.faces());
                                batch.fallback(draw, ranges, camera); program.batch(false);
                                draw.gpu().bindTerrain(program); draw.gpu().bindYield(3);
                                program.setTile((float)(draw.tile().baseBlockX() - camera.x), (float)-camera.y,
                                        (float)(draw.tile().baseBlockZ() - camera.z), draw.tile().spacingBlocks(), packed.cellAxis(), true);
                                program.setMorph(packed, 0); program.setBoundaryReplacement(true);
                                PredictionRenderer.submitRanges(ranges, packed, false); direct++;
                            }
                        }
                    }
                    calls[mode] = direct + batch.end();
                    long elapsed = System.nanoTime() - start; glEndQuery(GL_TIME_ELAPSED);
                    long device = glGetQueryObjectui64(query, GL_QUERY_RESULT);
                    if (round >= 0) {
                        cpu[mode][round] = elapsed; gpu[mode][round] = device;
                        csv.add(round + "," + mode + "," + elapsed + "," + device + "," + calls[mode] + "," + committed[mode]);
                    }
                }
                for (long[] values : cpu) Arrays.sort(values);
                for (long[] values : gpu) Arrays.sort(values);
                assertEquals(384, calls[0]); assertTrue(calls[1] < 16);
                assertEquals(mainBytes, PredictionTerrainArena.SHARED.allocatedBytes(), "overflow cannot enlarge the main arena");
                System.out.printf(Locale.ROOT, "ARCHITECTURE_OVERFLOW tiles=384 CPU_ms=%.6f->%.6f GPU_ms=%.6f->%.6f calls=%d->%d bytes=%d->%d%n",
                        cpu[0][20] / 1e6, cpu[1][20] / 1e6, gpu[0][20] / 1e6, gpu[1][20] / 1e6, calls[0], calls[1], committed[0], committed[1]);
            } finally { glDisable(GL_RASTERIZER_DISCARD); target.destroyBuffers(); }
        } finally {
            if (prior == null) System.clearProperty("vss.disableIndirectOverflow"); else System.setProperty("vss.disableIndirectOverflow", prior);
            if (indirect == null) System.clearProperty("vss.disableIndirect"); else System.setProperty("vss.disableIndirect", indirect);
            for (var tile : tiles) tile.close();
            for (var slice : fills) PredictionTerrainArena.SHARED.retire(slice);
            glFinish(); for (int i = 0; i <= (fills.size() + tiles.size()) / 16 + 1; i++) PredictionTerrainArena.SHARED.reap();
            assertEquals(0, PredictionTerrainArena.OVERFLOW.allocatedBytes(), "all unused overflow pages must retire");
            glDeleteQueries(query);
            Path directory = Path.of(destination); Files.createDirectories(directory);
            Files.write(directory.resolve("overflow-submission.csv"), csv);
        }
        assertEquals(GL_NO_ERROR, glGetError());
    }
}
