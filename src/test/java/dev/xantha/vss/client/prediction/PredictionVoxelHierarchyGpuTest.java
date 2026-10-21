package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** Exercises the production resident graph, command selector and terrain shader. */
@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class PredictionVoxelHierarchyGpuTest {
    private static final int SIZE=256;

    @Test void gpuSelectsVoxelLayersAndRestoresExactGeometryWithoutUploads() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();VssLodSpriteTable.close();
        assertTrue(glfwInit());glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        long window=glfwCreateWindow(SIZE,SIZE,"VSS voxel hierarchy verification",0,0);assertNotEquals(0,window);
        var textures=new ArrayList<Integer>();int vao=0,ebo=0,result=0,count=0,query=0;TextureTarget target=null;
        var csv=new ArrayList<String>();csv.add("scenario,round,mode,gpu_ns,cpu_ns,quads,commands");
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();RenderSystem.initRenderThread();
            System.out.println("VOXEL_HIERARCHY_GPU "+glGetString(GL_RENDERER)+" "+glGetString(GL_VERSION));
            try(var contents=PredictionVegetationMaterialTest.contents("block/vss_ground_gpu")) {
                int row=PredictionVegetationMaterialTest.restore(new PredictionVegetationMaterialTest.Sprite(contents),Blocks.OAK_LEAVES);
                int[] plants=PredictionVoxelHierarchyTest.building();
                for(int p=6;p<plants.length;p+=12)plants[p]|=row|PredictionPackedMesh.FLAG_CUTOUT;
                int[] words=plants;
                var packed=PredictionPackedMesh.terrainRecords(words,128);packed.prepareGpuStorage();
                int stored=packed.quadCount();assertTrue(packed.hasFeatureVoxelLod());
                var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,1);
                var tile=new PredictionTileManager.PredictionTile(key,new int[129*129],new int[129*129],
                        new ClientColumnSample[129*129],PredictionMesh.restored(words.length/2,0,packed,null),
                        new PredictionDepthBound(-32,90),0,1,128,1);
                vao=glGenVertexArrays();ebo=glGenBuffers();glBindVertexArray(vao);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);
                int[] indices=new int[stored*6],corners={0,1,2,0,2,3};
                for(int q=0;q<stored;q++)for(int c=0;c<6;c++)indices[q*6+c]=q*4+corners[c];
                glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STATIC_DRAW);
                PredictionVegetationRenderGpuTest.setupTextures(textures);
                glClearTexImage(textures.get(3),0,GL_RED,GL_FLOAT,new float[]{0});
                float[] depths=new float[SIZE*SIZE];Arrays.fill(depths,1);
                int depth=PredictionVegetationRenderGpuTest.texture(textures,7,SIZE,SIZE,GL_R32F,GL_RED,depths);
                target=new TextureTarget(SIZE,SIZE,true,false);query=glGenQueries();
                try(var gpu=new PredictionGpuTile(key);var shader=PredictionTerrainProgram.createBatch();
                    var scene=new PredictionGpuScene();var selector=new PredictionGpuCommandSelector();
                    var reference=new PredictionIndirectBatch()) {
                    assertTrue(gpu.ensureMesh(tile));
                    boolean[] all=new boolean[128*128];Arrays.fill(all,true);gpu.updateCoverage(all);
                    byte[] boundary=new byte[all.length];Arrays.fill(boundary,(byte)192);gpu.updateBoundaryCoverage(boundary);
                    var draw=new PredictionRenderer.Draw(tile,gpu,all,false,31,0,new AABB(0,-32,0,128,90,128));
                    var draws=List.of(draw);var selected=new IdentityHashMap<PredictionRenderer.Draw,PredictionDrawRanges>();
                    selected.put(draw,packed.gpuRepresentations().union);
                    boolean ready=false;for(int i=0;i<100&&!ready;i++)ready=scene.update(draws,selected,false);assertTrue(ready);
                    var entry=scene.entry(gpu);assertNotNull(entry);
                    assertNotEquals(0,entry.chunk.phases&2,"leaf surfaces retain the vegetation pass timing lane");
                    long encoded=scene.encodedItems(),uploaded=scene.uploadedBytes();
                    result=glCreateBuffers();count=glCreateBuffers();
                    glNamedBufferData(result,entry.chunk.commandCount*20L,GL_DYNAMIC_DRAW);glNamedBufferData(count,4,GL_DYNAMIC_DRAW);
                    int node=ints(entry.chunk.slot.bounds,entry.first*32L+28,1)[0];assertTrue(node>0);
                    int flags=ints(scene.nodeBuffer(),node*64L+44,1)[0];assertNotEquals(0,flags&1);
                    int[] expectedGround={0,0,0,0,0};
                    for(int test=0;test<5;test++) {
                        Vec3 camera=new Vec3(64.125,130.25,(test==0||test==4?170:3000)+.375);
                        var projection=new Matrix4f().perspective(test==2?.003f:1.2f,1,.05f,65536);
                        var view=view(camera);configure(shader,projection,view,camera,depth);
                        select(selector,scene,entry,result,count,new Matrix4f(VssLodProjection.of(projection).matrix()).mul(view),camera);
                        int choice=ints(scene.choiceBuffer(),node*4L,1)[0];assertEquals(1,choice&1);
                        int terrain=(choice>>1)&3,cover=(choice>>3)&7;assertEquals(expectedGround[test],cover,"near/far/zoom/return case="+test);
                        var ranges=packed.drawRanges(false,31,terrain,terrain,terrain,cover,false);
                        int commands=ints(count,0,1)[0];int[] output=ints(result,0,commands*5);
                        long quads=0;for(int i=0;i<commands;i++)quads+=output[i*5]/6;
                        if(test==1 || test==3)assertEquals(ranges.quads,quads,
                                "unmagnified far view contains every selected source meshlet, case="+test);
                        else assertTrue(quads<=ranges.quads,
                                "near and magnified frusta may reject offscreen source meshlets, case="+test);
                        if(test==1 || test==3) { assertTrue(terrain>0); assertTrue(quads<4096,"voxel layers replace dense source geometry"); }
                        else assertEquals(0,terrain,"near views and zoom restore exact source");
                        // Opaque workgroups compact in unspecified order. Normalize
                        // only this readback oracle so equal-depth cross intersections
                        // use the same primitive order as the CPU reference. The
                        // timed production path below uses native GPU output.
                        var indicesInSource=new BitSet();
                        for(int r=0;r<ranges.first.length;r++) {
                            int first=packed.gpuFirst(ranges.first[r],ranges.count[r],false);
                            indicesInSource.set(first,first+ranges.count[r]);
                        }
                        var actualIndices=new BitSet();
                        Integer[] order=new Integer[commands];for(int i=0;i<commands;i++)order[i]=i;
                        Arrays.sort(order,Comparator.comparingInt(i->output[i*5+2]));
                        int[] ordered=new int[output.length];
                        for(int i=0;i<commands;i++) {
                            int at=order[i]*5,first=output[at+2]/6,n=output[at]/6;
                            actualIndices.set(first,first+n);System.arraycopy(output,at,ordered,i*5,5);
                        }
                        assertEquals(quads,actualIndices.cardinality(),"no duplicate primitives");
                        var unexpected=(BitSet)actualIndices.clone();unexpected.andNot(indicesInSource);
                        assertTrue(unexpected.isEmpty(),"every emitted primitive belongs to the selected representation");
                        glNamedBufferSubData(result,0,ordered);
                        clear(target);drawResident(shader,gpu,entry,result,count,1);byte[] actual=pixels();
                        clear(target);reference.begin(shader);assertTrue(reference.add(draw,ranges,camera,false,false,Long.MAX_VALUE));reference.end();
                        assertArrayEquals(pixels(),actual,"selected commands and ordinary production draw must agree, case="+test);
                        assertTrue(scene.update(draws,selected,false));
                        assertEquals(encoded,scene.encodedItems(),"projection changes cannot reencode static commands");
                        assertEquals(uploaded,scene.uploadedBytes(),"projection changes cannot reupload static records");
                        assertEquals(stored,packed.quadCount());
                    }

                    // Alternate exact and voxel representations of the same resident scene.
                    // Both modes include compute selection, barriers and MDI submission.
                    Vec3 camera=new Vec3(64.125,130.25,3000.375);var projection=new Matrix4f().perspective(1.2f,1,.05f,65536);
                    var view=view(camera);configure(shader,projection,view,camera,depth);
                    var transform=new Matrix4f(VssLodProjection.of(projection).matrix()).mul(view);
                    for(int scenario=0;scenario<2;scenario++) {
                        if(scenario==0)glEnable(GL_RASTERIZER_DISCARD);else glDisable(GL_RASTERIZER_DISCARD);
                        long[][] gpuNs=new long[2][41],cpuNs=new long[2][41];long[] quads=new long[2],commands=new long[2];
                        for(int round=-16;round<41;round++)for(int order=0;order<2;order++) {
                            int mode=Math.floorMod(round+order,2);clear(target);glFinish();
                            long start=System.nanoTime();glBeginQuery(GL_TIME_ELAPSED,query);
                            glNamedBufferSubData(scene.nodeBuffer(),node*64L+44,new int[]{mode==0?flags&~1:flags});
                            select(selector,scene,entry,result,count,transform,camera);
                            drawResident(shader,gpu,entry,result,count,64);
                            glEndQuery(GL_TIME_ELAPSED);long cpu=System.nanoTime()-start;
                            long ns=glGetQueryObjectui64(query,GL_QUERY_RESULT);
                            int n=ints(count,0,1)[0];int[] output=ints(result,0,n*5);long q=0;
                            for(int i=0;i<n;i++)q+=output[i*5]/6;
                            quads[mode]=q*64;commands[mode]=n*64L;
                            if(round>=0){gpuNs[mode][round]=ns;cpuNs[mode][round]=cpu;
                                csv.add(scenario+","+round+","+mode+","+ns+","+cpu+","+quads[mode]+","+commands[mode]);}
                        }
                        assertTrue(quads[1]<quads[0]);assertTrue(quads[1]<quads[0]/4,"coarse region materially reduces submitted work");
                        System.out.printf(Locale.ROOT,"VOXEL_HIERARCHY_AB scenario=%s quads=%d->%d commands=%d->%d GPU_ms=%.6f->%.6f CPU_ms=%.6f->%.6f%n",
                                scenario==0?"vertex":"raster",quads[0],quads[1],commands[0],commands[1],
                                CompletionGpuFixture.median(gpuNs[0])/1e6,CompletionGpuFixture.median(gpuNs[1])/1e6,
                                CompletionGpuFixture.median(cpuNs[0])/1e6,CompletionGpuFixture.median(cpuNs[1])/1e6);
                    }
                    glDisable(GL_RASTERIZER_DISCARD);assertEquals(GL_NO_ERROR,glGetError());
                }
            }
            CompletionGpuFixture.save("voxel-hierarchy.csv",csv);
        } finally {
            glDisable(GL_RASTERIZER_DISCARD);glFinish();PredictionTerrainArena.SHARED.close();PredictionTerrainArena.WATER.close();PredictionQuadBufferPool.SHARED.close();
            if(query!=0)glDeleteQueries(query);if(result!=0)glDeleteBuffers(result);if(count!=0)glDeleteBuffers(count);
            if(target!=null)target.destroyBuffers();if(ebo!=0)glDeleteBuffers(ebo);if(vao!=0)glDeleteVertexArrays(vao);
            for(int t:textures)glDeleteTextures(t);VssLodSpriteTable.close();glfwDestroyWindow(window);glfwTerminate();
        }
    }

    private static Matrix4f view(Vec3 camera) {
        return new Matrix4f().lookAlong((float)(64-camera.x),(float)(81-camera.y),(float)(64-camera.z),0,1,0);
    }
    private static void configure(PredictionTerrainProgram shader,Matrix4f projection,Matrix4f view,Vec3 camera,int depth) {
        PredictionVegetationRenderGpuTest.setup(shader,projection,camera,false,false,depth);
        shader.setCamera(view,VssLodProjection.of(projection).matrix());
        shader.setBatchCameraOffset((float)-camera.x,(float)-camera.y,(float)-camera.z);
    }
    private static void select(PredictionGpuCommandSelector selector,PredictionGpuScene scene,PredictionGpuScene.Entry entry,
                               int result,int count,Matrix4f transform,Vec3 camera) {
        selector.begin(transform,(float)-camera.x,(float)-camera.y,(float)-camera.z,SIZE,SIZE,0,0);
        try {selector.lod(scene.nodeBuffer(),scene.choiceBuffer(),scene.nodeCount(),1.5f,256,65536,true);
            selector.dispatch(entry.chunk.slot.bounds,entry.chunk.slot.indirect,result,entry.chunk.commandCount,count);
        } finally {selector.end();}
    }
    private static void drawResident(PredictionTerrainProgram shader,PredictionGpuTile gpu,PredictionGpuScene.Entry entry,
                                     int result,int count,int repeats) {
        shader.use();shader.batch(true);gpu.bindYield(3);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,entry.chunk.slot.metadata);
        glActiveTexture(GL_TEXTURE4);glBindTexture(GL_TEXTURE_BUFFER,entry.chunk.page().texture);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER,result);
        for(int r=0;r<repeats;r++)PredictionGpuCommandSelector.draw(count,entry.chunk.commandCount);
        shader.batch(false);
    }
    private static void clear(TextureTarget target) {
        target.bindWrite(true);glViewport(0,0,SIZE,SIZE);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GEQUAL);glDepthMask(true);glClearDepth(0);glClearColor(0,0,0,0);
        glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
    }
    private static int[] ints(int buffer,long offset,int count) {int[] values=new int[count];glGetNamedBufferSubData(buffer,offset,values);return values;}
    private static byte[] pixels() {
        var data=BufferUtils.createByteBuffer(SIZE*SIZE*4);glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,data);
        byte[] values=new byte[data.remaining()];data.get(values);return values;
    }
}
