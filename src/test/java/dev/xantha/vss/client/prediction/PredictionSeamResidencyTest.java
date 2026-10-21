package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

class PredictionSeamResidencyTest {
    @Test void completedFeaturesCannotBeEvictedToAnUnfinishedParent() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var fine = PredictionFeatureHandoffTest.tile(0,1,true,true,1);
        var parent = PredictionFeatureHandoffTest.tile(1,4,false,false,2);
        var state = new PredictionRenderResidency();
        var source = PredictionFeatureHandoffTest.snapshot(fine,parent);
        state.retain(source); state.uploaded(parent); state.uploaded(fine);
        assertTrue(state.hasFallback(fine.key()));
        assertFalse(state.hasEvictionFallback(fine.key()), "ground-only fallback must preserve completed trees");
        assertFalse(state.hasEvictionFallback(fine));
        var completed = PredictionFeatureHandoffTest.tile(1,4,true,true,3);
        source = PredictionFeatureHandoffTest.snapshot(fine,completed);
        state.retain(source); state.uploaded(completed);
        assertTrue(state.hasEvictionFallback(fine));
        state.evict(fine.key());
        var drawable = state.snapshot(source);
        assertSame(completed,drawable.coveringTileAtDetail(0,0,0));
        assertSame(completed,drawable.coveringFeatureTileAtDetail(0,0,0));
        assertTrue(state.hasEvictionFallback(fine), "cold admission must validate the CPU tile too");
        state.uploaded(parent);
        assertFalse(state.hasEvictionFallback(fine), "a cold tile restores if its parent loses complete decoration");
    }

    @Test void waterAndTelescopeParentsKeepConservativeEvictionRules() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var wetMesh=PredictionMeshCodecTest.fixture();
        assertTrue(wetMesh.gpuPayload().drawRanges(true,VssLodFaceGroup.ALL).quads>0);
        wetMesh.surfaceReadyForRendering=false;
        var key=PredictionFeatureHandoffTest.tile(0,4,false,false,1).key();
        var wet=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],
                wetMesh,new PredictionDepthBound(60,80),0,1,wetMesh.cellAxis(),1);
        var unfinished=PredictionFeatureHandoffTest.tile(1,4,false,false,2);
        var state=new PredictionRenderResidency();state.retain(PredictionFeatureHandoffTest.snapshot(wet,unfinished));
        state.uploaded(unfinished);state.uploaded(wet);
        assertTrue(state.hasFallback(key));assertFalse(state.hasEvictionFallback(wet),"water requires a completed parent too");
        var ready=PredictionFeatureHandoffTest.tile(1,4,false,true,3);
        var scope=new PredictionTileManager.PredictionTile(ready.key(),ready.heights(),ready.groundHeights(),ready.samples(),
                ready.mesh(),ready.depthBound(),0,ready.revision(),ready.cellAxis(),ready.spacingBlocks(),true);
        state.uploaded(scope);assertFalse(state.hasEvictionFallback(wet),"telescope-only data cannot be the ordinary fallback");
        state.uploaded(ready);assertTrue(state.hasEvictionFallback(wet));
    }

    @SuppressWarnings("unchecked")
    @Test void offscreenSeamRecordsDoNotPinTerrainAndVisibleHighConnectorsStillDo() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var batch = new PredictionRenderer.SeamBatch();
        var field = PredictionRenderer.SeamBatch.class.getDeclaredField("draws"); field.setAccessible(true);
        var draws = (Map<PredictionTileKey,PredictionRenderer.Draw>)field.get(batch);
        var tile = PredictionFeatureHandoffTest.tile(0,1,false,true,1);
        var key = tile.key();
        var projection = new Matrix4f().perspective((float)Math.toRadians(70),1,.1F,4096);
        var frustum = new Frustum(new Matrix4f(),projection); frustum.prepare(0,400,0);
        var offscreen = new AABB(0,64,800,64,80,864);
        draws.put(key,new PredictionRenderer.Draw(tile,null,new boolean[1],true,VssLodFaceGroup.ALL,0,offscreen));
        assertFalse(batch.requiresTerrain(key,frustum), "retained CPU patch must not pin unseen terrain");
        assertTrue(batch.requiresTerrain(key,null), "unknown view must be conservative");
        var connector = new AABB(0,64,-864,64,512,-800);
        draws.put(key,new PredictionRenderer.Draw(tile,null,new boolean[1],true,VssLodFaceGroup.ALL,0,connector));
        assertTrue(batch.requiresTerrain(key,frustum), "tall connector remains visible above its terrain");

        var now = new AtomicLong(); var policy = new PredictionGpuResidency<String>(100,now::get);
        policy.uploaded("tile",1,200); policy.observe("tile",200,false,false,true,false);
        now.set(20_000_000_000L);
        var retirement = policy.retirements(200).get(0); policy.evicted(retirement);
        assertFalse(policy.allowsUpload("tile",2,false,false,false), "new CPU revision cannot undo cold retirement");
        assertTrue(policy.allowsUpload("tile",2,false,false,batch.requiresTerrain(key,frustum)),
                "a visible connector restores its owner even if the terrain bounds are outside the view");
    }
}
