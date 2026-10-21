package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Method;
import java.util.*;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.client.prediction.PredictionTileManager.*;

/** Production old/new masks, including an unrelated retained telescope patch. */
class ZoomReturnCoverageCpuTest {
    private static volatile long consumed;
    @Test void retainedScopeDoesNotExpandOrdinaryCoverageWorkAcrossTheWorld() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var current = PredictionRenderer.class.getDeclaredMethod("resolveCoverage", PredictionTile.class,
                int.class, int.class, double.class, RenderSnapshot.class, VssLodFocus.class);
        current.setAccessible(true);
        var baseline = ZoomReturnBaselineCoverage.class.getDeclaredMethod("resolveCoverage", PredictionTile.class,
                int.class, int.class, double.class, RenderSnapshot.class, VssLodFocus.class);
        baseline.setAccessible(true);
        var layout = VssLodLayout.of(32768, 6, true, false);
        var rows = new ArrayList<String>();
        rows.add("scenario,round,mode,cpu_ns,rendered_cells");
        for (String scenario : List.of("ordinary", "unrelated-scope", "overlapping-scope")) {
            var values = new HashMap<PredictionTileKey, PredictionTile>();
            var draws = new ArrayList<PredictionTile>();
            for (int x = -4; x < 4; x++) for (int z = -4; z < 4; z++) {
                var tile = PredictionCoverageWorkTest.tile(x, z, 4, layout);
                draws.add(tile); values.put(tile.key(), tile);
            }
            if (!scenario.equals("ordinary")) {
                var base = PredictionCoverageWorkTest.tile(scenario.equals("unrelated-scope") ? 1024 : -1,
                        scenario.equals("unrelated-scope") ? 1024 : -1, 0, layout);
                var scoped = new PredictionTile(base.key(), base.heights(), base.groundHeights(), base.samples(),
                        base.mesh(), base.depthBound(), 0, 2, 64, 1, true);
                values.put(scoped.key(), scoped); draws.add(scoped);
            }
            var snapshot = new RenderSnapshot(Level.OVERWORLD, layout, values, Map.of());
            for (int frame = 0; frame < 3; frame++) for (var tile : draws) {
                var a = resolve(baseline, tile, snapshot, frame);
                var b = resolve(current, tile, snapshot, frame);
                assertArrayEquals(a.allowed(), b.allowed(), scenario + " ground");
                assertArrayEquals(a.features(), b.features(), scenario + " decoration");
                assertEquals(a.coverageSkipped(), b.coverageSkipped());
            }
            long[][] times = new long[2][31];
            var methods = new Method[]{baseline, current};
            for (int round = -8; round < 31; round++) for (int order = 0; order < 2; order++) {
                int mode = Math.floorMod(round + order, 2);
                long start = System.nanoTime(), sum = 0;
                for (var tile : draws) sum += resolve(methods[mode], tile, snapshot, round).rendered();
                long elapsed = System.nanoTime() - start;
                consumed = sum;
                if (round >= 0) {
                    times[mode][round] = elapsed;
                    rows.add(scenario + "," + round + "," + mode + "," + elapsed + "," + sum);
                }
            }
            System.out.printf(Locale.ROOT, "ZOOM_RETURN_COVERAGE scenario=%s tiles=%d CPU_ms=%.6f->%.6f masks=identical%n",
                    scenario, draws.size(), CompletionGpuFixture.median(times[0]) / 1e6,
                    CompletionGpuFixture.median(times[1]) / 1e6);
        }
        CompletionGpuFixture.save("zoom-return-coverage.csv", rows);
    }
    private static PredictionRenderer.TileCoverage resolve(Method method, PredictionTile tile,
                                                            RenderSnapshot snapshot, int frame) throws Exception {
        return (PredictionRenderer.TileCoverage)method.invoke(null, tile, frame, 0, 1024D, snapshot, null);
    }
}
