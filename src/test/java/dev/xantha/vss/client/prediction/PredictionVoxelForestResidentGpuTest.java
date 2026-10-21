package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.TextureTarget;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

/** The admission comparison includes static command fragmentation, GPU choice,
 * compaction, barriers and real atlas rasterization on captured forest inputs. */
@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class PredictionVoxelForestResidentGpuTest {
    private static final Set<Integer> LEAVES=Set.of(31,40,41,42,88,91,94,167);
    @Test void capturedForestsThroughResidentSceneAndCommandSelection() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();VssLodSpriteTable.close();
        assertTrue(glfwInit());glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        long window=glfwCreateWindow(512,512,"VSS resident forest comparison",0,0);assertNotEquals(0,window);
        var textures=new ArrayList<Integer>();TextureTarget target=null;int vao=0,ebo=0,query=0;
        var csv=new ArrayList<String>();csv.add("mesh,mode,round,gpu_ns,source_commands,selected_commands,selected_quads");
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();RenderSystem.initRenderThread();
            PredictionVoxelForestGpuTest.setupTextures(textures);
            float[] depths=new float[512*512];Arrays.fill(depths,1);
            int depth=PredictionVegetationRenderGpuTest.texture(textures,7,512,512,GL_R32F,GL_RED,depths);
            target=new TextureTarget(512,512,true,false);query=glGenQueries();
            vao=glGenVertexArrays();ebo=glGenBuffers();glBindVertexArray(vao);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);
            int[] indices=new int[200000*6],corners={0,1,2,0,2,3};
            for(int q=0;q<200000;q++)for(int c=0;c<6;c++)indices[q*6+c]=q*4+corners[c];
            glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STATIC_DRAW);
            try(var shader=PredictionTerrainProgram.createBatch();var selector=new PredictionGpuCommandSelector()) {
                int[] leafMask=new int[8];for(int row:LEAVES)leafMask[row>>>5]|=1<<(row&31);
                shader.setLeaves(false,leafMask);
                for(var forest:AlgorithmSupport.forests()) {
                    int[] words=forest.words()[0].clone();
                    for(int p=6;p<words.length;p+=12)if(LEAVES.contains(words[p]&65535))words[p]|=PredictionPackedMesh.FLAG_CUTOUT;
                    var states=new State[2];
                    try {
                        for(int mode=0;mode<2;mode++) {
                            if(mode==0)System.setProperty("vss.disablePredictionVoxelLod","true");else System.clearProperty("vss.disablePredictionVoxelLod");
                            states[mode]=new State(forest,words);
                        }
                        System.clearProperty("vss.disablePredictionVoxelLod");
                        int distance=4096,yaw=45;var camera=PredictionVoxelForestGpuTest.camera(forest,distance,yaw);
                        PredictionVoxelForestGpuTest.configure(shader,forest,distance,yaw,depth);
                        shader.setBatchCameraOffset((float)-camera.x,(float)-camera.y,(float)-camera.z);
                        double y=(forest.minY()+forest.maxY())*.5;
                        var direction=new net.minecraft.world.phys.Vec3(forest.span()*.5,y,forest.span()*.5).subtract(camera).normalize();
                        var view=new Matrix4f().lookAlong((float)direction.x,(float)direction.y,(float)direction.z,0,1,0);
                        var transform=new Matrix4f(VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(70),1,.05f,65536)).matrix()).mul(view);
                        long[][] ns=new long[2][31];long[] quads=new long[2];int[] commands=new int[2];
                        for(int round=-16;round<31;round++)for(int order=0;order<2;order++) {
                            int mode=Math.floorMod(round+order,2);var s=states[mode];
                            target.bindWrite(true);glViewport(0,0,512,512);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);
                            glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GEQUAL);glDepthMask(true);glClearDepth(0);glClearColor(0,0,0,0);
                            glClear(GL_DEPTH_BUFFER_BIT|GL_COLOR_BUFFER_BIT);glFinish();
                            glBeginQuery(GL_TIME_ELAPSED,query);
                            selector.begin(transform,(float)-camera.x,(float)-camera.y,(float)-camera.z,512,512,0,0);
                            try {
                                selector.lod(s.scene.nodeBuffer(),s.scene.choiceBuffer(),s.scene.nodeCount(),1.5f,256,65536,true);
                                selector.dispatch(s.entry.chunk.slot.bounds,s.entry.chunk.slot.indirect,s.result,s.entry.chunk.commandCount,s.count);
                            } finally {selector.end();}
                            shader.use();shader.batch(true);s.gpu.bindYield(3);
                            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,s.entry.chunk.slot.metadata);
                            glActiveTexture(GL_TEXTURE4);glBindTexture(GL_TEXTURE_BUFFER,s.entry.chunk.page().texture);
                            glBindBuffer(GL_DRAW_INDIRECT_BUFFER,s.result);
                            PredictionGpuCommandSelector.draw(s.count,s.entry.chunk.commandCount);shader.batch(false);
                            glEndQuery(GL_TIME_ELAPSED);long elapsed=glGetQueryObjectui64(query,GL_QUERY_RESULT);
                            int[] n=new int[1];glGetNamedBufferSubData(s.count,0,n);commands[mode]=n[0];
                            int[] output=new int[n[0]*5];glGetNamedBufferSubData(s.result,0,output);long q=0;
                            for(int i=0;i<n[0];i++)q+=output[i*5]/6;quads[mode]=q;
                            if(round>=0){ns[mode][round]=elapsed;csv.add(forest.id()+","+mode+","+round+","+elapsed+","+s.entry.chunk.commandCount+","+n[0]+","+q);}
                        }
                        System.out.printf(Locale.ROOT,"VOXEL_RESIDENT_AB tiles=16 id=%d commands=%d->%d selected=%d->%d quads=%d->%d GPU_ms=%.6f->%.6f%n",
                                forest.id(),states[0].entry.chunk.commandCount,states[1].entry.chunk.commandCount,
                                commands[0],commands[1],quads[0],quads[1],CompletionGpuFixture.median(ns[0])/1e6,CompletionGpuFixture.median(ns[1])/1e6);
                        assertTrue(quads[1]<=quads[0],"actual GPU choice must preserve existing terrain reductions");
                    } finally {System.clearProperty("vss.disablePredictionVoxelLod");for(var s:states)if(s!=null)s.close();}
                }
            }
            CompletionGpuFixture.save("voxel-forest-resident.csv",csv);assertEquals(GL_NO_ERROR,glGetError());
        } finally {
            glFinish();PredictionTerrainArena.SHARED.close();PredictionTerrainArena.WATER.close();PredictionQuadBufferPool.SHARED.close();
            if(target!=null)target.destroyBuffers();if(query!=0)glDeleteQueries(query);
            if(ebo!=0)glDeleteBuffers(ebo);if(vao!=0)glDeleteVertexArrays(vao);
            for(int t:textures)glDeleteTextures(t);glfwDestroyWindow(window);glfwTerminate();VssLodSpriteTable.close();
        }
    }
    private static final class State implements AutoCloseable {
        final PredictionGpuTile gpu;final PredictionGpuScene scene=new PredictionGpuScene();
        final ArrayList<PredictionGpuTile> tiles=new ArrayList<>();
        final PredictionGpuScene.Entry entry;final int result,count;
        State(AlgorithmSupport.Forest f,int[] words) {
            var packed=PredictionPackedMesh.terrainRecords(words,f.span()/f.spacing());packed.prepareGpuStorage();
            var ranges=new IdentityHashMap<PredictionRenderer.Draw,PredictionDrawRanges>();
            var draws=new ArrayList<PredictionRenderer.Draw>();
            for(int i=0;i<16;i++) {
                var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,i%4,i/4,1);
                var tile=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],
                        PredictionMesh.restored(words.length/2,0,packed,null),new PredictionDepthBound((int)f.minY(),(int)Math.ceil(f.maxY())),0,1,f.span()/f.spacing(),f.spacing());
                var uploaded=new PredictionGpuTile(key);tiles.add(uploaded);assertTrue(uploaded.ensureMesh(tile));
                boolean[] all=new boolean[tile.cellAxis()*tile.cellAxis()];Arrays.fill(all,true);uploaded.updateCoverage(all);
                byte[] boundary=new byte[all.length];Arrays.fill(boundary,(byte)192);uploaded.updateBoundaryCoverage(boundary);
                var draw=new PredictionRenderer.Draw(tile,uploaded,all,false,31,0,new AABB(tile.baseBlockX(),f.minY(),tile.baseBlockZ(),tile.baseBlockX()+f.span(),f.maxY(),tile.baseBlockZ()+f.span()));
                draws.add(draw);ranges.put(draw,packed.gpuRepresentations().union);
            }
            gpu=tiles.get(0);boolean ready=false;for(int i=0;i<100&&!ready;i++)ready=scene.update(draws,ranges,false);assertTrue(ready);
            entry=scene.entry(gpu);assertNotNull(entry);result=glCreateBuffers();count=glCreateBuffers();
            for(var uploaded:tiles)assertSame(entry.chunk,scene.entry(uploaded).chunk,"benchmark submits a complete single region/page batch");
            glNamedBufferData(result,entry.chunk.commandCount*20L,GL_DYNAMIC_DRAW);glNamedBufferData(count,4,GL_DYNAMIC_DRAW);
        }
        public void close(){glDeleteBuffers(result);glDeleteBuffers(count);scene.close();for(var uploaded:tiles)uploaded.close();}
    }
}
