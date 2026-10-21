package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43C.*;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

@EnabledIfSystemProperty(named="vss.gpuTests", matches="true")
class PredictionAuthoritativeCoverageViewGpuTest {
    @Test void realSceneKeepsFallbackCommandsAndAllocationAcrossResidentCoverageChanges() throws Exception {
        assertTrue(glfwInit()); glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        long window=glfwCreateWindow(64,64,"scene ownership regression",0,0); assertNotEquals(0,window);
        var coverage=new LegacyResidentCoverage(PredictionCoarseHandoffTest.ready());
        var scene=new PredictionRenderer.Scene(Runnable::run);
        var field=PredictionRenderer.class.getDeclaredField("gpuTiles"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var gpu=(Map<PredictionTileKey,PredictionGpuTile>)field.get(null);
        var previous=new HashMap<>(gpu); gpu.clear();
        PredictionGpuTile resident=null;
        try {
            glfwMakeContextCurrent(window); GL.createCapabilities();
            if(!RenderSystem.isOnRenderThread()) RenderSystem.initRenderThread();
            ClientTerrainSamplerTest.bootstrapMinecraft();
            var tile=PredictionLodSeamsTest.tile(0,0,1,64);
            resident=new PredictionGpuTile(tile.key()); assertTrue(resident.ensureMesh(tile)); gpu.put(tile.key(),resident);
            var layout=VssLodLayout.of(4096,6,true,true);
            var snapshot=new PredictionTileManager.RenderSnapshot(Level.OVERWORLD,layout,Map.of(tile.key(),tile),Map.of());
            var view=new PredictionRenderer.CoverageView(0,0,1300,null);
            var camera=new Vec3(32,80,32); var matrix=new Matrix4f();
            coverage.update(null,camera,480,Level.OVERWORLD);
            var first=scene.prepare(snapshot,snapshot.tiles().keySet(),view,camera,4096,32,matrix,matrix,null);
            var original=first.stream().filter(draw -> !draw.seam()).findFirst().orElseThrow();
            var slice=resident.arenaSlice(); var payload=resident.packed();
            assertTrue(coverage.fullyOwned(Level.OVERWORLD,payload,0,0)==false);
            coverage.update(PredictionAuthoritativeCoverageViewTest.mask(),camera,480,Level.OVERWORLD);
            assertTrue(coverage.fullyOwned(Level.OVERWORLD,payload,0,0));
            var hidden=scene.prepare(snapshot,Set.of(),view,camera,4096,32,matrix,matrix,null);
            assertTrue(hidden.stream().anyMatch(draw -> draw==original),"an unselected Voxy resident must not delete prediction");
            assertTrue(scene.residentDraws().stream().anyMatch(draw -> draw==original),"GPU input retains the same fallback");
            assertSame(payload,resident.packed()); assertSame(slice,resident.arenaSlice());
            coverage.update(null,camera,480,Level.OVERWORLD);
            var restored=scene.prepare(snapshot,Set.of(),view,camera,4096,32,matrix,matrix,null);
            assertTrue(restored.stream().anyMatch(draw -> draw==original),"withdrawal restores without reupload or camera movement");
            assertTrue(scene.residentDraws().stream().anyMatch(draw -> draw==original));
            assertSame(slice,resident.arenaSlice());
            coverage.update(PredictionAuthoritativeCoverageViewTest.mask(),camera,480,Level.OVERWORLD);
            scene.prepare(snapshot,Set.of(),view,camera,4096,32,matrix,matrix,null);
            payload.morph(new float[]{0},-64,1000);
            var morph=scene.prepare(snapshot,Set.of(),view,camera,4096,32,matrix,matrix,null);
            assertTrue(morph.stream().anyMatch(draw -> draw==original),"mesh epoch restores an expanded morph bound");
            assertEquals(GL_NO_ERROR,glGetError());
        } finally {
            scene.clear(); if(resident!=null)resident.close(); gpu.clear();gpu.putAll(previous);
            var drain=PredictionRenderer.class.getDeclaredMethod("drainRetiredTiles"); drain.setAccessible(true);
            for(int i=0;i<8;i++)drain.invoke(null);
            glFinish(); PredictionTerrainArena.SHARED.reap();PredictionTerrainArena.SHARED.close();
            PredictionTerrainArena.WATER.reap();PredictionTerrainArena.WATER.close();
            glfwDestroyWindow(window);glfwTerminate();
        }
    }
}
