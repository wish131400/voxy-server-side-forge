package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Real GL allocation/retirement, using small fixture meshes; not a world VRAM forecast. */
@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class PredictionSeamResidencyGpuTest {
    @SuppressWarnings("unchecked")
    @Test void offscreenSeamsReleaseAnActualPageAndTurnRestoresExactPayloadWithParentCoverage() throws Exception {
        assertTrue(glfwInit());glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        long window=glfwCreateWindow(32,32,"VSS seam residency",0,0);assertNotEquals(0,window);
        String oldIndirect=System.getProperty("vss.disableIndirect");
        var gpuTiles=new HashMap<PredictionTileKey,PredictionGpuTile>();
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();RenderSystem.initRenderThread();
            ClientTerrainSamplerTest.bootstrapMinecraft();
            var parent=move(PredictionFeatureHandoffTest.tile(2,16,true,true,100),8,8);
            var fineMesh=PredictionFeatureHandoffTest.tile(0,1,true,true,1);
            var all=new HashMap<PredictionTileKey,PredictionTile>();all.put(parent.key(),parent);
            for(int z=32;z<34;z++)for(int x=32;x<36;x++) {
                var tile=move(fineMesh,x,z);all.put(tile.key(),tile);
            }
            var source=new PredictionTileManager.RenderSnapshot(Level.OVERWORLD,VssLodLayout.of(4096,6,true,false),all,Map.of());
            var render=new PredictionRenderResidency();render.retain(source);
            var clock=new AtomicLong();
            var old=new PredictionGpuResidency<PredictionTileKey>(1,clock::get);
            var next=new PredictionGpuResidency<PredictionTileKey>(1,clock::get);
            var seam=new PredictionRenderer.SeamBatch();
            var field=PredictionRenderer.SeamBatch.class.getDeclaredField("draws");field.setAccessible(true);
            var seams=(Map<PredictionTileKey,PredictionRenderer.Draw>)field.get(seam);
            var frustum=new Frustum(new Matrix4f(),new Matrix4f().perspective(1.2F,1,.1F,8192));frustum.prepare(0,100,0);
            // Keep the mandatory coarse parent in its own buffer so this test can
            // verify the fine arena page is physically deleted when completely empty.
            System.setProperty("vss.disableIndirect","true");
            var coarseGpu=new PredictionGpuTile(parent.key());gpuTiles.put(parent.key(),coarseGpu);
            assertTrue(coarseGpu.ensureMesh(parent));render.uploaded(parent);
            System.clearProperty("vss.disableIndirect");
            int page=0;long live=0;
            for(var tile:all.values())if(tile!=parent) {
                var gpu=new PredictionGpuTile(tile.key());gpuTiles.put(tile.key(),gpu);
                assertTrue(gpu.ensureMesh(tile));render.uploaded(tile);
                assertNotNull(gpu.arenaSlice());page=gpu.arenaSlice().page.buffer;
                var box=new AABB(tile.baseBlockX(),64,tile.baseBlockZ(),tile.baseBlockX()+64,100,tile.baseBlockZ()+64);
                seams.put(tile.key(),new PredictionRenderer.Draw(tile,gpu,new boolean[tile.mesh().cellCount()],true,
                        VssLodFaceGroup.ALL,0,box));
                assertFalse(seam.requiresTerrain(tile.key(),frustum));assertTrue(render.hasEvictionFallback(tile));
                long bytes=gpu.residentBytes();live+=bytes;
                old.uploaded(tile.key(),tile.revision(),bytes);next.uploaded(tile.key(),tile.revision(),bytes);
                old.observe(tile.key(),bytes,false,true,true,false);
                next.observe(tile.key(),bytes,false,seam.requiresTerrain(tile.key(),frustum),true,false);
            }
            long allocated=PredictionTerrainArena.SHARED.allocatedBytes();assertTrue(glIsBuffer(page));
            clock.set(PredictionGpuResidency.OUTSIDE_GRACE_NANOS-1);
            assertTrue(next.retirements(allocated).isEmpty(),"brief turns cannot retire geometry");
            clock.incrementAndGet();assertTrue(old.retirements(allocated).isEmpty(),"old CPU seam pin prevents retirement");
            var victims=next.retirements(allocated);assertEquals(8,victims.size());
            for(var victim:victims) {
                assertTrue(render.hasEvictionFallback(victim.key()));render.evict(victim.key());next.evicted(victim);
                gpuTiles.remove(victim.key()).close();
                var cold=all.get(victim.key());
                assertFalse(next.allowsUpload(victim.key(),cold.revision()+1,false,false,false));
                var frame=render.snapshot(source);
                assertSame(parent,frame.coveringTileAtDetail(cold.baseBlockX()/16,cold.baseBlockZ()/16,0));
                assertSame(parent,frame.coveringFeatureTileAtDetail(cold.baseBlockX()/16,cold.baseBlockZ()/16,0));
            }
            glFinish();PredictionTerrainArena.SHARED.reap();
            assertEquals(0,next.residentBytes());assertEquals(0,PredictionTerrainArena.SHARED.allocatedBytes());
            assertFalse(glIsBuffer(page));assertTrue(coarseGpu.hasMesh(parent));
            long start=System.nanoTime();
            for(var victim:victims) {
                var tile=all.get(victim.key());assertTrue(next.allowsUpload(tile.key(),tile.revision(),true,false,false));
                var gpu=new PredictionGpuTile(tile.key());gpuTiles.put(tile.key(),gpu);
                assertTrue(gpu.ensureMesh(tile));render.uploaded(tile);next.uploaded(tile.key(),tile.revision(),gpu.residentBytes());
            }
            long restoreNs=System.nanoTime()-start;
            for(var victim:victims) {
                var tile=all.get(victim.key());var gpu=gpuTiles.get(tile.key());
                assertSame(tile,render.snapshot(source).coveringFeatureTileAtDetail(tile.baseBlockX()/16,tile.baseBlockZ()/16,0));
                int[] expected=tile.mesh().gpuPayload().opaqueUploadWords();
                var actual=MemoryUtil.memAllocInt(expected.length);
                try {glGetNamedBufferSubData(gpu.arenaSlice().page.buffer,gpu.arenaSlice().offset,actual);
                    int[] words=new int[expected.length];actual.get(words);assertArrayEquals(expected,words);}
                finally {MemoryUtil.memFree(actual);}
            }
            System.out.printf(Locale.ROOT,"FOLLOWUP_SEAM_RESIDENCY tiles=8 payloadBytes=%d->%d arenaAllocatedBytes=%d->0 restore8Cpu_ms=%.6f coldReupload=false parentCoverage=true exactPayload=true%n",
                    live,0,allocated,restoreNs/1e6);
            assertEquals(GL_NO_ERROR,glGetError());
        } finally {
            if(oldIndirect==null)System.clearProperty("vss.disableIndirect");else System.setProperty("vss.disableIndirect",oldIndirect);
            for(var gpu:gpuTiles.values())gpu.close();glFinish();PredictionTerrainArena.SHARED.reap();
            PredictionTerrainArena.SHARED.close();PredictionQuadBufferPool.SHARED.close();
            glfwDestroyWindow(window);glfwTerminate();
        }
    }
    private static PredictionTile move(PredictionTile tile,int x,int z) {
        return new PredictionTile(new PredictionTileKey(Level.OVERWORLD,x,z,tile.key().lod()),tile.heights(),tile.groundHeights(),
                tile.samples(),tile.mesh(),tile.depthBound(),0,tile.revision(),tile.cellAxis(),tile.spacingBlocks());
    }
}
