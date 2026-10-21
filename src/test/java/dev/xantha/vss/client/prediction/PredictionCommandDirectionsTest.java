package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.Test;

class PredictionCommandDirectionsTest {
    @Test void verticalWaterMotionKeepsSortVersionWhileHorizontalOrderChangesImmediately() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var mesh=PredictionMeshCodecTest.fixture().gpuPayload();
        var a=PredictionPreparedPassLocalTest.draw(0,0,mesh);var b=PredictionPreparedPassLocalTest.draw(10,0,mesh);
        var draws=List.of(a,b);var pass=new PredictionPreparedPassLocalTest.Pass(true);
        pass.prepare(draws,1,Vec3.ZERO);long revision=pass.counter("revision");
        pass.prepare(draws,1,new Vec3(0,1000,0));assertEquals(revision,pass.counter("revision"));
        var method=pass.entries().get(0).getClass().getDeclaredMethod("draw");method.setAccessible(true);
        assertSame(a,method.invoke(pass.entries().get(0)));
        pass.prepare(draws,1,new Vec3(1000,1000,0));assertTrue(pass.counter("revision")>revision);
        assertSame(b,method.invoke(pass.entries().get(0)));
    }
    @Test void partialRangesKeepOriginalOrderUnknownSlopesAndConservativeMixedMeshlets() {
        int[] words=new int[1024*12];
        for(int q=0;q<1024;q++) {
            int flags=q<256?PredictionPackedMesh.FLAG_BANK:q<512?0:q<768?PredictionPackedMesh.FLAG_UNSHADED:PredictionPackedMesh.FLAG_BANK;
            PredictionSpatialOrderTest.quad(words,q,q%64,q/64,80,flags);
        }
        // One sloping face makes the last whole meshlet unknown.
        words[900*12+5]+=1;
        var bounds=new PredictionMeshletBounds(words);
        var ranges=new PredictionDrawRanges(new int[]{17,300,800},new int[]{223,400,200});
        var result=PredictionCommandDirections.filter(bounds,ranges,64|8);
        assertArrayEquals(new int[]{300,800},result.first);assertArrayEquals(new int[]{400,200},result.count);
        assertSame(ranges,PredictionCommandDirections.filter(bounds,ranges,127));
        assertSame(ranges,PredictionCommandDirections.filter(bounds,ranges,64|4|8));
        assertEquals(0,PredictionCommandDirections.filter(new PredictionMeshletBounds(Arrays.copyOf(words,256*12)),
                new PredictionDrawRanges(new int[]{0},new int[]{256}),64|8).quads);
    }
    @Test void cameraRegionsMatchMasksAcrossWorldLimitNearPlaneAndMorphUpdates() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var draw=PredictionPreparedPassLocalTest.draw(0,0,PredictionPackedMesh.seamRecords(new int[0],64));
        var region=new PredictionCommandDirections.Region();var camera=new Vec3(32,160,32);
        region.begin(camera);region.include(draw,camera);int mask=PredictionCommandDirections.mask(draw,camera);
        for(double x:new double[]{-3,0,16,67})for(double y:new double[]{105,120,500}) {
            var next=new Vec3(x,y,32);
            if(region.contains(next))assertEquals(mask,PredictionCommandDirections.mask(draw,next));
        }
        assertFalse(region.contains(new Vec3(-5,160,32)));assertFalse(region.contains(new Vec3(32,99,32)));
        draw.gpu().packed().morph(new float[1],0,100);assertEquals(127,PredictionCommandDirections.mask(draw,camera));
        draw.gpu().packed().morph(null,0,0);
        assertEquals(127,PredictionCommandDirections.mask(draw,new Vec3(Double.NaN,0,0)));
        var b=new AABB(29999936,80,-30000000,30000000,82,-29999936);
        var edge=new PredictionRenderer.Draw(draw.tile(),draw.gpu(),draw.allowed(),false,31,0,b);
        assertTrue((PredictionCommandDirections.mask(edge,new Vec3(29999999,85.9,-29999990))&4)!=0,"four-block guard keeps eye-height faces");
    }
    @Test void uploadedTallFeaturesExtendDirectionsBeyondSampledTileBounds() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        int[] words=new int[256*12];
        for(int q=0;q<256;q++)PredictionSpatialOrderTest.quad(words,q,q%64,q/64,200,PredictionPackedMesh.FLAG_BANK);
        var packed=PredictionPackedMesh.seamRecords(words,64);
        var draw=PredictionPreparedPassLocalTest.draw(0,0,packed);
        var camera=new Vec3(32,160,32);assertEquals(200,packed.occlusionBounds.extent[4]);
        assertTrue((PredictionCommandDirections.mask(draw,camera)&4)!=0,"downward tall model above the sampled box must stay visible");
        assertEquals(256,PredictionCommandDirections.filter(packed.occlusionBounds,packed.drawRanges(false,31),PredictionCommandDirections.mask(draw,camera)).quads);
    }
    @Test void cachedPassRestoresDownwardRangesWhenEyeCrossesAndNeverInvalidatesOwnership() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        int[] words=new int[512*12];
        for(int q=0;q<512;q++)PredictionSpatialOrderTest.quad(words,q,q%64,q/64,80,q<256?PredictionPackedMesh.FLAG_BANK
                :PredictionPackedMesh.FLAG_UNSHADED|(2<<24));
        var packed=PredictionPackedMesh.seamRecords(words,64);var draw=PredictionPreparedPassLocalTest.draw(0,0,packed);
        var list=List.of(draw);var pass=new PredictionPreparedPassLocalTest.Pass(false);
        pass.prepare(list,1,new Vec3(32,160,32));var top=pass.ranges(draw);assertTrue(top.quads<512);
        long builds=pass.counter("rangeBuilds"),revision=pass.counter("revision");
        pass.prepare(list,1,new Vec3(32.2,160.1,32.2));assertSame(top,pass.ranges(draw));assertEquals(revision,pass.counter("revision"));
        pass.prepare(list,1,new Vec3(32,-10,32));assertEquals(builds,pass.counter("rangeBuilds"));
        assertNotSame(top,pass.ranges(draw));
        packed.morph(new float[1],0,100);pass.prepare(list,1,new Vec3(32,160,32));assertEquals(512,pass.ranges(draw).quads);

    }
}
