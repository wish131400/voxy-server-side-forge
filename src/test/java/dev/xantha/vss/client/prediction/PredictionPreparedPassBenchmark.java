package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Production range/cache paths with synthetic visible payloads; not an FPS estimate. */
class PredictionPreparedPassBenchmark {
    @Test void compareEntryCacheWithWholePassReassembly() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var csv=new ArrayList<String>();csv.add("scenario,round,mode,cpu_ns");
        for(String scenario:List.of("one-replacement","sixteen-replacements","new-list","moving","unrelated-residency","stationary")) {
            var draws=new ArrayList<PredictionRenderer.Draw>();
            var payloads=new ArrayList<PredictionPackedMesh>();
            var alternates=new ArrayList<PredictionPackedMesh>();
            for(int i=0;i<384;i++){var mesh=PredictionPreparedPassLocalTest.mesh();
                payloads.add(mesh);
                alternates.add(PredictionPreparedPassLocalTest.mesh());
                draws.add(PredictionPreparedPassLocalTest.draw(i%24-12,i/24-8,mesh));}
            var next=new PredictionPreparedPassLocalTest.Pass(false);var old=new CompletionLegacyPreparedPass(false);
            var oldPrepare=CompletionLegacyPreparedPass.class.getDeclaredMethod("prepare",List.class,long.class,Vec3.class);
            oldPrepare.setAccessible(true);
            CompletionLegacyPreparedPass.synchronizeRenderer();
            long[][] times=new long[2][151];
            for(int round=-300;round<151;round++) {
                long revision=scenario.equals("stationary")||scenario.equals("moving")?1:round+302L;
                if(scenario.endsWith("replacements")||scenario.equals("one-replacement")) {
                    int count=scenario.equals("one-replacement")?1:16;
                    for(int j=0;j<count;j++){int index=Math.floorMod(round*17+j,draws.size());
                        var a=draws.get(index).gpu().packed();var b=alternates.get(index);
                        PredictionPreparedPassLocalTest.payload(draws.get(index).gpu(),a==b?payloads.get(index):b);}
                }
                List<PredictionRenderer.Draw> input=scenario.equals("new-list")?new ArrayList<>(draws):draws;
                var camera=scenario.equals("moving")?new Vec3(round*.2,80,32):new Vec3(32,80,32);
                for(int order=0;order<2;order++) {
                    int mode=Math.floorMod(round+order,2);long start=System.nanoTime();
                    if(mode==0)oldPrepare.invoke(old,input,revision,camera);else next.prepare(input,revision,camera);
                    long elapsed=System.nanoTime()-start;
                    if(round>=0){times[mode][round]=elapsed;csv.add(scenario+","+round+","+mode+","+elapsed);}
                }
                for(var draw:draws){var a=old.ranges(draw);var b=next.ranges(draw);
                    assertEquals(a==null,b==null);if(a!=null){assertArrayEquals(a.first,b.first);assertArrayEquals(a.count,b.count);}}
            }
            System.out.printf(Locale.ROOT,"COMPLETION_PREPARED scenario=%s entries=%d CPU_ms=%.6f->%.6f builds=%d reuses=%d%n",
                    scenario,draws.size(),median(times[0])/1e6,median(times[1])/1e6,next.counter("rangeBuilds"),next.counter("rangeReuses"));
        }
        var output=Path.of(System.getProperty("vss.followupResults"));Files.createDirectories(output);
        Files.write(output.resolve("prepared-pass.csv"),csv);
    }
    private static volatile Object boundsSink;
    @Test void recordWorkerBoundsBuildCostWithoutMovingItToTheRenderThread() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var source=PredictionMeshCodecTest.fixture(32).gpuPayload().restoreWords();
        int quads=262144;int[] words=new int[quads*12];
        for(int q=0;q<quads;q++)System.arraycopy(source,q%(source.length/12)*12,words,q*12,12);
        long[][] times=new long[2][61];var rows=new ArrayList<String>();rows.add("round,mode,cpu_ns");
        for(int round=-30;round<61;round++)for(int order=0;order<2;order++) {
            int mode=Math.floorMod(round+order,2);long start=System.nanoTime();
            if(mode==0)boundsSink=new CompletionLegacyMeshletBounds(words);else boundsSink=new PredictionMeshletBounds(words);
            long elapsed=System.nanoTime()-start;if(round>=0){times[mode][round]=elapsed;rows.add(round+","+mode+","+elapsed);}
        }
        System.out.printf(Locale.ROOT,"COMPLETION_BOUNDS_BUILD quads=%d CPU_ms=%.6f->%.6f retained_extra_bytes=%d%n",
                quads,median(times[0])/1e6,median(times[1])/1e6,(quads+255)/256*4);
        var path=Path.of(System.getProperty("vss.followupResults"));Files.createDirectories(path);Files.write(path.resolve("direction-build.csv"),rows);
    }
    private static long median(long[] values){var copy=values.clone();Arrays.sort(copy);return copy[copy.length/2];}
}
