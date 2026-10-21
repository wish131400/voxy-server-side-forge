package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Isolated synthetic costs, not a live FPS or game-thread profile. */
class PredictionEditedTerrainBenchmark {
    @TempDir Path directory;
    private static volatile long consumed;
    private static final com.sun.management.ThreadMXBean METRICS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    @BeforeAll static void bootstrap() { DensityMemoBenchTest.bootstrap(); }

    @Test void measureCaptureSnapshotPersistenceAndCachedAreaReads() throws Exception {
        var results = new LinkedHashMap<String, Object>();
        results.put("java", System.getProperty("java.version"));
        var data = PredictionEditedTerrainTest.craterData();
        try (var sampler = DensityMemoBenchTest.sampler(LithostitchedNativeTest.document(), 42L)) {
            var old = EditedTerrainLegacyCaptureExtractor.extractAll(1, 1, data, sampler);
            var current = ClientCaptureExtractor.extractAll(1, 1, data, sampler);
            assertArrayEquals(old, current, "snapshot content, including biome/occupancy, must match");
            results.put("legacyExtract", measure(8, 20, () -> consumed +=
                    EditedTerrainLegacyCaptureExtractor.extractAll(1, 1, data, sampler)[136].surfaceY()));
            results.put("newExtract", measure(8, 20, () -> consumed +=
                    ClientCaptureExtractor.extractAll(1, 1, data, sampler)[136].surfaceY()));
        }

        var sections = new LevelChunkSection[12];
        for (int i = 0; i < sections.length; i++) {
            sections[i] = ClientCaptureExtractorTest.section();
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++)
                sections[i].setBlockState(x, y, z, ((x + z + y) % 7 == 0 ? Blocks.GRAVEL : Blocks.STONE).defaultBlockState());
        }
        var chunk = org.mockito.Mockito.mock(LevelChunk.class);
        org.mockito.Mockito.when(chunk.getSections()).thenReturn(sections);
        org.mockito.Mockito.when(chunk.getMinSection()).thenReturn(-4);
        results.put("clientSnapshot12SectionsIncludingMockAccess", measure(50, 100, () ->
                consumed += PredictionLocalCaptures.snapshot(chunk).sections().length));
        try (var disk = new PredictionDiskCache(directory, 77)) {
            var store = new PredictionCapturedTerrain(disk);
            var samples = PredictionEditedTerrainTest.craterSamples();
            var index = new java.util.concurrent.atomic.AtomicInteger();
            results.put("newCaptureAnd12MipWrites", measure(4, 16, () ->
                    assertTrue(store.put(index.getAndIncrement(), 0, samples))));
            results.put("identicalCaptureNoWrites", measure(50, 100, () -> assertFalse(store.put(0, 0, samples))));
            var base = PredictionEditedTerrainTest.sample(64);
            results.put("cachedCoarseGrid66x66", measure(20, 100, () -> {
                long sum = 0;
                for (int z = 0; z < 66; z++) for (int x = 0; x < 66; x++)
                    sum += store.sample((x - 1) * 32, (z - 1) * 32, 32, base).surfaceY();
                consumed += sum;
            }));
            results.put("decodedCacheBytes", store.bytes());
            try (var paths = Files.walk(directory)) {
                results.put("diskBytesAfter20SyntheticChunks", paths.filter(Files::isRegularFile)
                        .mapToLong(path -> { try { return Files.size(path); } catch (Exception e) { throw new RuntimeException(e); } }).sum());
            }
        }
        var output = Path.of(System.getProperty("vss.editedTerrainResults"));
        Files.createDirectories(output.getParent());
        Files.writeString(output, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(results));
        System.out.println("EDITED_TERRAIN_BENCH " + new com.google.gson.Gson().toJson(results));
    }
    private static Map<String, Object> measure(int warmup, int rounds, Runnable action) {
        for (int i = 0; i < warmup; i++) action.run();
        double[] wall = new double[rounds], cpu = new double[rounds], allocated = new double[rounds];
        for (int i = 0; i < rounds; i++) {
            long beforeCpu = METRICS.getCurrentThreadCpuTime();
            long beforeAllocation = METRICS.getThreadAllocatedBytes(Thread.currentThread().getId());
            long started = System.nanoTime(); action.run();
            wall[i] = (System.nanoTime() - started) / 1e6;
            cpu[i] = (METRICS.getCurrentThreadCpuTime() - beforeCpu) / 1e6;
            allocated[i] = METRICS.getThreadAllocatedBytes(Thread.currentThread().getId()) - beforeAllocation;
        }
        double cpuMean = Arrays.stream(cpu).sum() / rounds;
        Arrays.sort(wall); Arrays.sort(cpu); Arrays.sort(allocated);
        return Map.of("rounds", rounds, "wallMedianMs", wall[rounds / 2], "wallP95Ms", wall[(int) Math.ceil(rounds * .95) - 1],
                "cpuMeanMs", cpuMean, "allocatedMedianBytes", allocated[rounds / 2]);
    }
}
