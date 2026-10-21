package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ResidentCoverageIntegrationTest {
    @Test void stableNormalizedCandidatesStillFollowVoxyWithdrawalRestorationAndVerticalRange() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        int y=32768+64*4;
        int[] words={33|34<<16,33|34<<16,33|33<<16,34|34<<16,y|y<<16,y|y<<16,0,0xffffff,0,0xffffff,0xffffff,0xffffff};
        var mesh=PredictionPackedMesh.terrainRecords(words,64);
        var draw=PredictionPreparedPassLocalTest.draw(0,0,mesh);
        var source=List.of(draw);var input=new PredictionGpuSelectionInput();
        var camera=new Vec3(4,68,4);var index=new PredictionExactCoverageIndex();
        var coverage=PredictionPreparedPassLocalTest.coverage();var pass=new PredictionPreparedPassLocalTest.Pass(false);
        try {
            for(int z=0;z<7;z++)for(int x=0;x<7;x++)index.confirm(Level.OVERWORLD,x,z,0);
            coverage.update(index.snapshot(Level.OVERWORLD,0,0,64,ExactCoverageGate.SETTLE_NANOS),camera,480);
            input.update(source,camera,4096);var candidates=input.candidates();var normalized=candidates.get(0);
            pass.prepare(candidates,1,camera);assertNull(pass.ranges(normalized));
            index.remove(Level.OVERWORLD,2,2);
            coverage.update(index.snapshot(Level.OVERWORLD,0,0,64,ExactCoverageGate.SETTLE_NANOS),camera,480);
            input.update(source,camera,4096);assertSame(candidates,input.candidates());
            pass.prepare(input.candidates(),1,camera);assertEquals(1,pass.ranges(normalized).quads);
            index.confirm(Level.OVERWORLD,2,2,0);
            coverage.update(index.snapshot(Level.OVERWORLD,0,0,64,ExactCoverageGate.SETTLE_NANOS),camera,480);
            input.update(source,camera,4096);assertSame(candidates,input.candidates());
            pass.prepare(input.candidates(),1,camera);assertNull(pass.ranges(normalized));
            var high=new Vec3(4,1000,4);
            coverage.update(index.snapshot(Level.OVERWORLD,0,0,64,ExactCoverageGate.SETTLE_NANOS),high,480);
            input.update(source,high,4096);pass.prepare(input.candidates(),1,high);
            assertEquals(1,pass.ranges(input.candidates().get(0)).quads,"3D ownership range must restore the original prediction");
        } finally {coverage.clear();pass.clear.invoke(pass.value);input.clear();}
    }
}
