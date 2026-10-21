package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45C.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class PredictionCommandDirectionGpuTest {
    @Test void compareConservativeCommandDirectionsWithOriginalCurrentFrameHiZ() throws Exception {
        var csv=new ArrayList<String>();csv.add("scenario,round,mode,gpu_ns,cpu_ns,submitted_quads");
        try(var fixture=new CompletionGpuFixture();var old=new CompletionLegacyOcclusionCuller();var next=new PredictionOcclusionCuller()) {
            for(int scenario=0;scenario<4;scenario++) {
                fixture.configure(scenario);long[][] times=new long[2][61],cpu=new long[2][61];
                byte[][] pixels=new byte[2][];float[][] depths=new float[2][];long[] submitted=new long[2];
                for(int round=-20;round<61;round++)for(int order=0;order<2;order++) {
                    int mode=Math.floorMod(round+order,2);fixture.clear(false);glFinish();
                    long start=System.nanoTime();glBeginQuery(GL_TIME_ELAPSED,fixture.timer);
                    if(mode==0){assertTrue(old.begin(fixture.depth,fixture.size,fixture.size,fixture.transform));old.rebuild();
                        old.setCameraOffset(0,0,0);fixture.draw(old.filter(fixture.boxes,fixture.input,fixture.output,fixture.commands));old.end();}
                    else {assertTrue(next.begin(fixture.depth,fixture.size,fixture.size,fixture.transform));next.rebuild();
                        next.setCameraOffset(0,0,0);fixture.draw(next.filter(fixture.boxes,fixture.input,fixture.output,fixture.commands));next.end();}
                    glEndQuery(GL_TIME_ELAPSED);long elapsedCpu=System.nanoTime()-start;
                    long time=glGetQueryObjectui64(fixture.timer,GL_QUERY_RESULT);
                    if(round>=0){times[mode][round]=time;cpu[mode][round]=elapsedCpu;}
                    if(round==0){pixels[mode]=fixture.pixels();depths[mode]=fixture.depths();
                        int[] commands=new int[fixture.commands*5];glGetNamedBufferSubData(fixture.output,0,commands);
                        for(int c=0;c<fixture.commands;c++)submitted[mode]+=commands[c*5]/6;}
                    if(round>=0)csv.add(scenario+","+round+","+mode+","+time+","+elapsedCpu+","+submitted[mode]);
                }
                assertArrayEquals(pixels[0],pixels[1]);assertArrayEquals(depths[0],depths[1]);
                assertEquals(fixture.quads,submitted[0]);assertEquals(scenario==0?0:scenario==2?fixture.quads/2:fixture.quads,submitted[1]);
                System.out.printf(Locale.ROOT,"COMPLETION_DIRECTIONS scenario=%d quads=%d->%d GPU_ms=%.6f->%.6f CPU_ms=%.6f->%.6f%n",
                        scenario,submitted[0],submitted[1],CompletionGpuFixture.median(times[0])/1e6,CompletionGpuFixture.median(times[1])/1e6,
                        CompletionGpuFixture.median(cpu[0])/1e6,CompletionGpuFixture.median(cpu[1])/1e6);
            }
        }
        CompletionGpuFixture.save("command-direction.csv",csv);
    }
}
