package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46C.*;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** Actual water pass plus whole-plan MDI replay. Control forces the previous
 * per-movement revision invalidation after the identical exact stable sort. */
@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class ImplementationWaterGpuTest {
    private static final int SIZE=256;
    @Test void unchangedTransparentOrderReusesProductionSubmissionAndKeepsPixels() throws Exception {
        assertTrue(glfwInit());glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        long window=glfwCreateWindow(SIZE,SIZE,"water order replay",0,0);assertNotEquals(0,window);
        var gpus=new ArrayList<PredictionGpuTile>();var textures=new ArrayList<Integer>();TextureTarget target=null;
        var rows=new ArrayList<String>();rows.add("scenario,round,mode,cpu_ns,encoded_items,replay_frames,revision_changes");
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();if(!RenderSystem.isOnRenderThread())RenderSystem.initRenderThread();AlgorithmBootstrap.bootstrapMinecraft();
            int vao=glGenVertexArrays(),ebo=glGenBuffers();glBindVertexArray(vao);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);
            var mesh=PredictionMeshCodecTest.fixture();int count=mesh.gpuPayload().quadCount(),axis=mesh.cellAxis();
            int[] indices=new int[count*6],corners={0,1,2,0,2,3};for(int q=0;q<count;q++)for(int c=0;c<6;c++)indices[q*6+c]=q*4+corners[c];
            glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STATIC_DRAW);
            var draws=new ArrayList<PredictionRenderer.Draw>();
            for(int i=0;i<96;i++) {
                var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,i%12-6,i/12-4,0);
                var tile=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],mesh,new PredictionDepthBound(60,80),0,i+1,axis,1);
                var gpu=new PredictionGpuTile(key);gpus.add(gpu);assertTrue(gpu.ensureMesh(tile));boolean[] allowed=new boolean[axis*axis];Arrays.fill(allowed,true);gpu.updateCoverage(allowed);
                draws.add(new PredictionRenderer.Draw(tile,gpu,allowed,false,VssLodFaceGroup.ALL,0,new AABB(tile.baseBlockX(),60,tile.baseBlockZ(),tile.baseBlockX()+axis,80,tile.baseBlockZ()+axis)));
            }
            PredictionVegetationRenderGpuTest.setupTextures(textures);
            float[] clearDepth=new float[SIZE*SIZE];Arrays.fill(clearDepth,1);
            int depth=PredictionVegetationRenderGpuTest.texture(textures,7,SIZE,SIZE,GL_R32F,GL_RED,clearDepth);
            PredictionVegetationRenderGpuTest.texture(textures,5,SIZE,SIZE,GL_R32F,GL_RED,new float[SIZE*SIZE]);
            PredictionVegetationRenderGpuTest.texture(textures,8,1,1,GL_R8,GL_RED,new float[]{0});
            target=new TextureTarget(SIZE,SIZE,true,false);
            try(var shader=PredictionTerrainProgram.createBatch();var a=new PredictionIndirectBatch();var b=new PredictionIndirectBatch()) {
                var camera=new Vec3(0,260,400);var vanilla=new Matrix4f().perspective(1.2f,1,.05f,8192);
                PredictionVegetationRenderGpuTest.setup(shader,vanilla,camera,false,false,depth);
                shader.setRealRenderDistance(0);shader.setDetailDistance(0);
                shader.setOpaqueAlpha(0);shader.setCamera(new Matrix4f().lookAlong(0,-200,-400,0,1,0),VssLodProjection.of(vanilla).matrix());
                var passes=new PredictionPreparedPassLocalTest.Pass[]{new PredictionPreparedPassLocalTest.Pass(true),new PredictionPreparedPassLocalTest.Pass(true)};
                var batches=new PredictionIndirectBatch[]{a,b};
                var revision=passes[0].type.getDeclaredField("revision");revision.setAccessible(true);
                java.lang.reflect.Method drawMethod=null,rangesMethod=null;
                for(var pass:passes)pass.prepare(draws,1,camera);
                Object entry=passes[0].entries().get(0);drawMethod=entry.getClass().getDeclaredMethod("draw");drawMethod.setAccessible(true);
                rangesMethod=entry.getClass().getDeclaredMethod("ranges");rangesMethod.setAccessible(true);
                for(String scenario:List.of("slow_move","walk","jump"))for(int round=-8;round<31;round++) {
                    long[] encoded={0,0},replays={0,0},revisions={0,0};
                    for(int order=0;order<2;order++) {
                        int mode=Math.floorMod(round+order,2);var pass=passes[mode];var batch=batches[mode];
                        long encodedBefore=batch.encodedItems();
                        long start=System.nanoTime();
                        for(int frame=0;frame<64;frame++) {
                            double x=scenario.equals("slow_move")?(round*64+frame)*.001:scenario.equals("walk")?(round*64+frame)*.2:(frame%2==0?-200:200);
                            var moved=new Vec3(x,260,400+x*.37);long before=pass.counter("revision");pass.prepare(draws,1,moved);
                            if(mode==0&&pass.counter("revision")==before)revision.setLong(pass.value,before+1);
                            if(pass.counter("revision")!=before)revisions[mode]++;
                            target.bindWrite(true);glViewport(0,0,SIZE,SIZE);glDisable(GL_CULL_FACE);glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);
                            glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GEQUAL);glDepthMask(true);glClearDepth(0);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
                            batch.begin(shader,false);long replay=batch.replayPass(pass.value,pass.counter("revision"),false,moved,Long.MAX_VALUE);
                            if(replay<0) {
                                var entries=pass.entries();
                                for(int i=entries.size()-1;i>=0;i--) {var item=entries.get(i);assertTrue(batch.add((PredictionRenderer.Draw)drawMethod.invoke(item),(PredictionDrawRanges)rangesMethod.invoke(item),moved,true,false,Long.MAX_VALUE));}
                            } else replays[mode]++;
                            batch.end();
                        }
                        encoded[mode]=batch.encodedItems()-encodedBefore;
                        long elapsed=System.nanoTime()-start;
                        if(round>=0)rows.add(scenario+","+round+","+mode+","+elapsed+","+encoded[mode]+","+replays[mode]+","+revisions[mode]);
                    }
                }
                // Same final order and camera, independently rendered through full rebuild vs replay.
                var pixelFrames=new ArrayList<byte[]>();
                for(int mode=0;mode<2;mode++) {
                    var pass=passes[mode];pass.prepare(draws,1,camera);var batch=batches[mode];
                    target.bindWrite(true);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);batch.begin(shader,false);
                    if(mode==0)revision.setLong(pass.value,pass.counter("revision")+1);
                    long replay=batch.replayPass(pass.value,pass.counter("revision"),false,camera,Long.MAX_VALUE);
                    if(replay<0) {var entries=pass.entries();for(int i=entries.size()-1;i>=0;i--) {var item=entries.get(i);assertTrue(batch.add((PredictionRenderer.Draw)drawMethod.invoke(item),(PredictionDrawRanges)rangesMethod.invoke(item),camera,true,false,Long.MAX_VALUE));}}
                    batch.end();var bytes=BufferUtils.createByteBuffer(SIZE*SIZE*4);glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,bytes);
                    byte[] image=new byte[bytes.remaining()];bytes.get(image);pixelFrames.add(image);
                }
                assertArrayEquals(pixelFrames.get(0),pixelFrames.get(1));assertEquals(GL_NO_ERROR,glGetError());
                int visible=0;for(int p=3;p<pixelFrames.get(0).length;p+=4)if(pixelFrames.get(0)[p]!=0)visible++;
                assertTrue(visible>10,"transparent comparison must contain actual water pixels");
                for(var pass:passes)pass.clear.invoke(pass.value);
            }
            glDeleteBuffers(ebo);glDeleteVertexArrays(vao);AlgorithmSupport.save("water-production-replay.csv",rows);
        } finally {
            if(target!=null)target.destroyBuffers();gpus.forEach(PredictionGpuTile::close);glFinish();PredictionTerrainArena.SHARED.close();PredictionQuadBufferPool.SHARED.close();
            for(int t:textures)glDeleteTextures(t);glfwDestroyWindow(window);glfwTerminate();
        }
    }
}
