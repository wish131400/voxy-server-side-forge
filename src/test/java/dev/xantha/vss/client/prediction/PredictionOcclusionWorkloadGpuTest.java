package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45C.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class PredictionOcclusionWorkloadGpuTest {
    @Test void remoteUpdatesNoLongerPreventMeasuredSelectionOnRealGpu() throws Exception {
        var csv=new ArrayList<String>();csv.add("hidden,frame,mode,gpu_ns,culling");
        try(var fixture=new CompletionGpuFixture()) {
            fixture.configure(1);
            for(boolean hidden:new boolean[]{false,true})try(var culler=new PredictionOcclusionCuller()) {
                var legacy=new PredictionOcclusionPolicy();var local=new PredictionOcclusionPolicy();
                long[][] times=new long[2][61];byte[][] pixels=new byte[2][];float[][] depths=new float[2][];
                for(int frame=1;frame<=170;frame++)for(int order=0;order<2;order++) {
                    int mode=(frame+order)%2;var policy=mode==0?legacy:local;
                    boolean culling=policy.choose(fixture.transform,0,0,0,fixture.size,fixture.size,mode==0?frame:1,1);
                    fixture.clear(hidden);glFinish();glBeginQuery(GL_TIME_ELAPSED,fixture.timer);
                    int command=fixture.input;
                    if(culling){assertTrue(culler.begin(fixture.depth,fixture.size,fixture.size,fixture.transform));
                        culler.setCameraOffset(0,0,0);}
                    // Eight ordered distance buckets, with current-frame depth rebuilt between them.
                    for(int bucket=0;bucket<8;bucket++) {
                        if(culling){culler.rebuild();command=culler.filter(fixture.boxes,fixture.input,fixture.output,fixture.commands);}
                        fixture.draw(command);
                    }
                    if(culling)culler.end();glEndQuery(GL_TIME_ELAPSED);
                    long nanos=glGetQueryObjectui64(fixture.timer,GL_QUERY_RESULT);
                    if(policy.sample())policy.accept(policy.epoch(),culling,nanos);
                    if(frame>=110)times[mode][frame-110]=nanos;
                    csv.add(hidden+","+frame+","+mode+","+nanos+","+culling);
                    if(frame==170){pixels[mode]=fixture.pixels();depths[mode]=fixture.depths();}
                }
                assertArrayEquals(pixels[0],pixels[1]);assertArrayEquals(depths[0],depths[1]);
                assertTrue(legacy.diagnostics().contains("measuring-hiz"));assertTrue(local.diagnostics().contains("occlusionComparisons=1"));
                assertTrue(local.diagnostics().contains("selected-"));
                System.out.printf(Locale.ROOT,"COMPLETION_POLICY hidden=%s buckets=8 quads=2097152 GPU_ms=%.6f->%.6f old=%s new=%s%n",hidden,
                        CompletionGpuFixture.median(times[0])/1e6,CompletionGpuFixture.median(times[1])/1e6,legacy.diagnostics(),local.diagnostics());
            }
        }
        CompletionGpuFixture.save("visible-workload-policy.csv",csv);
    }
}
