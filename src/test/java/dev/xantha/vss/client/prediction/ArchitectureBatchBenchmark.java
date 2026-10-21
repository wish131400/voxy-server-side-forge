package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL43C.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Exact pre-change submission loop against the production regional cache. */
final class ArchitectureBatchBenchmark {
    static void run(PredictionTerrainProgram program, List<PredictionRenderer.Draw> fixtures, Vec3 camera) throws Exception {
        String destination = System.getProperty("vss.architectureResults");
        if (destination == null) return;
        var arena = fixtures.stream().filter(d -> d.gpu().arenaSlice() != null).toList();
        var standalone = fixtures.stream().filter(d -> d.gpu().arenaSlice() == null).findFirst().orElseThrow();
        var scene = new ArrayList<PredictionRenderer.Draw>();
        for (int i = 0; i < 3072; i++) {
            var source = arena.get(i % arena.size());
            var mesh = source.tile().mesh();
            var key = new PredictionTileManager.PredictionTileKey(source.tile().key().dimension(), i % 64, i / 64, 0);
            var tile = new PredictionTileManager.PredictionTile(key, new int[0], new int[0], new ClientColumnSample[0],
                    mesh, new PredictionDepthBound(60, 80), 0, i + 1, mesh.cellAxis(), 1);
            scene.add(new PredictionRenderer.Draw(tile, source.gpu(), source.allowed(), false, VssLodFaceGroup.ALL, 0,
                    new AABB(tile.baseBlockX(), 60, tile.baseBlockZ(), tile.baseBlockX() + tile.spanBlocks(), 80,
                            tile.baseBlockZ() + tile.spanBlocks())));
        }
        var csv = new ArrayList<String>();
        csv.add("layout,round,mode,cpu_ns,gpu_ns,uploaded_batches,uploaded_bytes,encoded_items");
        int query = glGenQueries();
        try {
            glEnable(GL_RASTERIZER_DISCARD);
            for (String layout : List.of("stable", "local-change", "mixed-local-change")) {
                try (var old = new ArchitectureLegacyIndirectBatch(); var candidate = new PredictionIndirectBatch()) {
                    long[][] cpu = new long[2][41], gpu = new long[2][41];
                    for (int round = -24; round < 41; round++) {
                        if (!layout.equals("stable")) {
                            var draw = scene.get(0);
                            int faces = (round & 1) == 0 ? VssLodFaceGroup.ALL : 15;
                            scene.set(0, new PredictionRenderer.Draw(draw.tile(), draw.gpu(), draw.allowed(), false,
                                    faces, 0, draw.bounds()));
                        }
                        for (int order = 0; order < 2; order++) {
                            int mode = Math.floorMod(round + order, 2);
                            glFinish();
                            glBeginQuery(GL_TIME_ELAPSED, query);
                            long start = System.nanoTime();
                            program.use();
                            program.setOpaqueAlpha(1);
                            if (mode == 0) old.begin(program, true, round + 100, 19);
                            else candidate.begin(program, true, round + 100, 19);
                            for (int index = 0; index < scene.size(); index++) {
                                var draw = scene.get(index);
                                var ranges = draw.gpu().packed().drawRanges(false, draw.faces());
                                if (mode == 0) assertTrue(old.add(draw, ranges, camera, false, true, Long.MAX_VALUE));
                                else assertTrue(candidate.add(draw, ranges, camera, false, true, Long.MAX_VALUE));
                                if (layout.startsWith("mixed") && index % 256 == 255) {
                                    if (mode == 0) { old.flush(); old.fallback(standalone, ranges, camera); }
                                    else { candidate.flush(); candidate.fallback(standalone, ranges, camera); }
                                    program.batch(false);
                                    standalone.gpu().bindTerrain(program);
                                    standalone.gpu().bindYield(3);
                                    program.setTile((float)(standalone.tile().baseBlockX() - camera.x), (float)-camera.y,
                                            (float)(standalone.tile().baseBlockZ() - camera.z), 1,
                                            standalone.gpu().packed().cellAxis(), true);
                                    PredictionRenderer.submitRanges(standalone.gpu().packed().drawRanges(false, VssLodFaceGroup.ALL),
                                            standalone.gpu().packed(), false);
                                }
                            }
                            if (mode == 0) old.end(); else candidate.end();
                            long elapsed = System.nanoTime() - start;
                            glEndQuery(GL_TIME_ELAPSED);
                            long device = glGetQueryObjectui64(query, GL_QUERY_RESULT);
                            if (round >= 0) {
                                cpu[mode][round] = elapsed; gpu[mode][round] = device;
                                csv.add(layout + "," + round + "," + mode + "," + elapsed + "," + device + ","
                                        + (mode == 0 ? old.uploadedBatches() : candidate.uploadedBatches()) + ","
                                        + (mode == 0 ? old.uploadedBytes() : candidate.uploadedBytes()) + ","
                                        + (mode == 0 ? old.encodedItems() : candidate.encodedItems()));
                            }
                        }
                    }
                    for (long[] values : cpu) Arrays.sort(values);
                    for (long[] values : gpu) Arrays.sort(values);
                    System.out.printf(Locale.ROOT, "ARCHITECTURE_BATCH layout=%s CPU_ms=%.6f->%.6f GPU_ms=%.6f->%.6f %s%n",
                            layout, cpu[0][20] / 1e6, cpu[1][20] / 1e6, gpu[0][20] / 1e6, gpu[1][20] / 1e6,
                            candidate.diagnostics());
                    if (!layout.equals("stable")) assertTrue(candidate.encodedItems() < old.encodedItems(),
                            "a local change must preserve later resident command buffers without re-encoding them");
                }
            }
        } finally {
            glDisable(GL_RASTERIZER_DISCARD); glDeleteQueries(query);
            Path directory = Path.of(destination); Files.createDirectories(directory);
            Files.write(directory.resolve("regional-submission.csv"), csv);
        }
        assertEquals(GL_NO_ERROR, glGetError());
    }
}
