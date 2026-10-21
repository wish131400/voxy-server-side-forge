package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class BenefitPreparedPassTest {
    private static volatile long sink;
    @Test void compareCurrentBaselinePreparationDuringMovementAndUnchangedFrames() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var rows=new ArrayList<String>(); rows.add("water,path,round,mode,cpu_ns");
        var draws=new ArrayList<PredictionRenderer.Draw>();
        var payload=PredictionPackedMesh.seamRecords(BenefitDirectionGpuTest.fixture(2),128);
        for(boolean water:new boolean[]{false,true})for(String path:List.of("stationary","vertical","horizontal","crossing")) {
            draws.clear();var active=water?PredictionMeshCodecTest.fixture().gpuPayload():payload;
            for(int i=0;i<1024;i++)draws.add(PredictionPreparedPassLocalTest.draw(i%32-16,i/32-16,active));
            PredictionPreparedPassLocalTest.coverage().clear();BenefitBaselinePass.synchronizeRenderer();
            var old=new BenefitBaselinePass.Pass(water);var next=new PredictionPreparedPassLocalTest.Pass(water);
            var method=BenefitBaselinePass.Pass.class.getDeclaredMethod("prepare",List.class,long.class,Vec3.class);method.setAccessible(true);
            long[][] times=new long[2][301];
            for(int round=-400;round<301;round++) {
                double t=round+401;
                var camera=new Vec3(path.equals("horizontal")?t*.12:path.equals("crossing")?t*1.5:32,
                        path.equals("vertical")?160+t*.1:path.equals("crossing")?70+t*.04:160,32);
                for(int order=0;order<2;order++) {
                    int mode=Math.floorMod(round+order,2);long start=System.nanoTime();
                    if(mode==0)method.invoke(old,draws,1L,camera);else next.prepare(draws,1,camera);
                    sink+=mode==0?1:2;
                    if(round>=0){long elapsed=System.nanoTime()-start;times[mode][round]=elapsed;rows.add(water+","+path+","+round+","+mode+","+elapsed);}
                }
            }
            System.out.printf(Locale.ROOT,"BENEFIT_PREPARE water=%s path=%s draws=1024 CPU_ms=%.6f->%.6f builds=%d%n",
                    water,path,CompletionGpuFixture.median(times[0])/1e6,CompletionGpuFixture.median(times[1])/1e6,next.counter("rangeBuilds"));
        }
        PredictionPreparedPassLocalTest.coverage().clear();CompletionGpuFixture.save("benefit-prepare.csv",rows);
    }
}
