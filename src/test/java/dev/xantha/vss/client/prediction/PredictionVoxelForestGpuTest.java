package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.awt.image.BufferedImage;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** Captured immutable prediction surfaces, production packing/shaders, original atlas alpha. */
@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class PredictionVoxelForestGpuTest {
    private static final int SIZE=512;
    private static final Set<Integer> LEAVES=Set.of(31,40,41,42,88,91,94,167);
    @Test void compareCapturedForestGeometryAndVisualCoverage() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();VssLodSpriteTable.close();
        assertTrue(glfwInit());glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        long window=glfwCreateWindow(SIZE,SIZE,"VSS forest voxel comparison",0,0);assertNotEquals(0,window);
        var textures=new ArrayList<Integer>();int vao=0,ebo=0,query=0;TextureTarget target=null;
        var csv=new ArrayList<String>();csv.add("mesh,distance,yaw,tier,source_quads,voxel_quads,pixels_before,pixels_after,iou,added,lost,rgb_error");
        var timing=new ArrayList<String>();timing.add("mesh,mode,round,gpu_ns,quads");
        var selectedIou=new ArrayList<Double>();var selectedAdded=new ArrayList<Double>();
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();RenderSystem.initRenderThread();
            System.out.println("VOXEL_FOREST_GPU "+glGetString(GL_RENDERER)+" "+glGetString(GL_VERSION));
            setupTextures(textures);
            float[] depths=new float[SIZE*SIZE];Arrays.fill(depths,1);
            int depth=texture(textures,7,SIZE,SIZE,GL_R32F,GL_RED,depths);
            target=new TextureTarget(SIZE,SIZE,true,false);query=glGenQueries();
            vao=glGenVertexArrays();ebo=glGenBuffers();glBindVertexArray(vao);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);
            int[] indices=new int[200000*6],corners={0,1,2,0,2,3};
            for(int q=0;q<200000;q++)for(int c=0;c<6;c++)indices[q*6+c]=q*4+corners[c];
            glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STATIC_DRAW);
            try(var shader=PredictionTerrainProgram.createBatch()) {
                int[] leafMask=new int[8];for(int row:LEAVES)leafMask[row>>>5]|=1<<(row&31);
                shader.setLeaves(false,leafMask);
                for(var forest:AlgorithmSupport.forests()) {
                    int[] source=forest.words()[0].clone();
                    for(int p=6;p<source.length;p+=12)if(LEAVES.contains(source[p]&65535))source[p]|=PredictionPackedMesh.FLAG_CUTOUT;
                    var packed=PredictionPackedMesh.terrainRecords(source,forest.span()/forest.spacing());packed.prepareGpuStorage();
                    var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,1);
                    var tile=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],
                            PredictionMesh.restored(source.length/2,0,packed,null),new PredictionDepthBound((int)forest.minY(),(int)Math.ceil(forest.maxY())),
                            0,1,forest.span()/forest.spacing(),forest.spacing());
                    try(var gpu=new PredictionGpuTile(key)) {
                        assertTrue(gpu.ensureMesh(tile));boolean[] all=new boolean[tile.cellAxis()*tile.cellAxis()];Arrays.fill(all,true);gpu.updateCoverage(all);
                        byte[] boundary=new byte[all.length];Arrays.fill(boundary,(byte)192);gpu.updateBoundaryCoverage(boundary);
                        var exact=packed.drawRanges(false,31,0,0,0,0,false);
                        for(int distance:new int[]{1024,2048,4096})for(int yaw:new int[]{0,45,90,135}) {
                            configure(shader,forest,distance,yaw,depth);
                            var lod=new PredictionDisplayLod();
                            lod.update(packed.displayErrors(),distance-forest.span(),SIZE/(2*Math.tan(Math.toRadians(35))),256,false);
                            var coarse=packed.drawRanges(false,31,0,lod.tier(),0,0,false);
                            clear(target);draw(shader,gpu,tile,exact,forest,distance,yaw);byte[] before=pixels();
                            clear(target);draw(shader,gpu,tile,coarse,forest,distance,yaw);byte[] after=pixels();
                            String row=quality(forest.id(),distance,yaw,lod.tier(),exact.quads,coarse.quads,before,after);
                            csv.add(row);
                            if(lod.tier()>0){String[] values=row.split(",");selectedIou.add(Double.parseDouble(values[8]));selectedAdded.add(Double.parseDouble(values[9]));}
                            if(lod.tier()==0)assertArrayEquals(before,after,"protected view uses identical source");
                            if(forest.id()==1511&&yaw==45) {
                                save("forest-"+distance+"-source.png",before);
                                save("forest-"+distance+"-voxel.png",after);
                            }
                        }
                        int distance=4096,yaw=45;configure(shader,forest,distance,yaw,depth);
                        var lod=new PredictionDisplayLod();lod.update(packed.displayErrors(),distance-forest.span(),SIZE/(2*Math.tan(Math.toRadians(35))),256,false);
                        // Include the terrain display LOD that production already had.
                        // The comparison must not credit that existing reduction again.
                        var hierarchy=packed.cachePlan().display().voxelFeatures();
                        var baseline=packed.drawRanges(false,31,0,lod.tier(),0,0,false);
                        if(hierarchy!=null) {
                            var previous=new PredictionDisplayLod();
                            previous.update(hierarchy.base.errors(),distance-forest.span(),SIZE/(2*Math.tan(Math.toRadians(35))),256,false);
                            var existing=hierarchy.base.range(previous.tier(),0,31,0,0);
                            if(existing!=null)baseline=existing;
                        }
                        var ranges=List.of(baseline,packed.drawRanges(false,31,0,lod.tier(),0,0,false));
                        long[][] ns=new long[2][31];
                        for(int round=-16;round<31;round++)for(int order=0;order<2;order++) {
                            int mode=Math.floorMod(round+order,2);clear(target);glFinish();glBeginQuery(GL_TIME_ELAPSED,query);
                            draw(shader,gpu,tile,ranges.get(mode),forest,distance,yaw);
                            glEndQuery(GL_TIME_ELAPSED);long elapsed=glGetQueryObjectui64(query,GL_QUERY_RESULT);
                            if(round>=0){ns[mode][round]=elapsed;timing.add(forest.id()+","+mode+","+round+","+elapsed+","+ranges.get(mode).quads);}
                        }
                        System.out.printf(Locale.ROOT,"VOXEL_CAPTURE_AB id=%d tier=%d quads=%d->%d GPU_ms=%.6f->%.6f%n",
                                forest.id(),lod.tier(),ranges.get(0).quads,ranges.get(1).quads,CompletionGpuFixture.median(ns[0])/1e6,CompletionGpuFixture.median(ns[1])/1e6);
                        assertTrue(ranges.get(1).quads<=ranges.get(0).quads,"existing terrain LOD savings must be preserved");
                    }
                }
            }
            CompletionGpuFixture.save("voxel-forest-quality.csv",csv);CompletionGpuFixture.save("voxel-forest-timing.csv",timing);
            Collections.sort(selectedIou);Collections.sort(selectedAdded);
            assertFalse(selectedIou.isEmpty());
            double iou=selectedIou.get(selectedIou.size()/2),added=selectedAdded.get(selectedAdded.size()/2);
            System.out.printf(Locale.ROOT,"VOXEL_VISUAL views=%d median_iou=%.6f median_added=%.6f%n",selectedIou.size(),iou,added);
            assertTrue(iou>=.90,"coarse alpha geometry must preserve the visible silhouette");
            assertTrue(added<=.12,"coarse foliage must not repeat the dense canopy regression");
            assertEquals(GL_NO_ERROR,glGetError());
        } finally {
            glFinish();PredictionTerrainArena.SHARED.close();PredictionTerrainArena.WATER.close();PredictionQuadBufferPool.SHARED.close();
            if(target!=null)target.destroyBuffers();if(query!=0)glDeleteQueries(query);
            if(ebo!=0)glDeleteBuffers(ebo);if(vao!=0)glDeleteVertexArrays(vao);
            for(int t:textures)glDeleteTextures(t);glfwDestroyWindow(window);glfwTerminate();VssLodSpriteTable.close();
        }
    }
    static Vec3 camera(AlgorithmSupport.Forest f,int distance,int yaw) {
        double a=Math.toRadians(yaw),center=f.span()*.5;
        return new Vec3(center+Math.sin(a)*distance,(f.minY()+f.maxY())*.5+f.span()*.12,center+Math.cos(a)*distance);
    }
    static void configure(PredictionTerrainProgram shader,AlgorithmSupport.Forest f,int distance,int yaw,int depth) {
        var camera=camera(f,distance,yaw);double y=(f.minY()+f.maxY())*.5;
        var direction=new Vec3(f.span()*.5,y,f.span()*.5).subtract(camera).normalize();
        var view=new Matrix4f().lookAlong((float)direction.x,(float)direction.y,(float)direction.z,0,1,0);
        var vanilla=new Matrix4f().perspective((float)Math.toRadians(70),1,.05f,65536);var projection=VssLodProjection.of(vanilla);
        PredictionVegetationRenderGpuTest.setup(shader,vanilla,camera,false,false,depth);
        shader.setCamera(view,projection.matrix());shader.setHorizon(131072);shader.setDetailDistance(0);
        shader.setRealRenderDistance(0);shader.setBoundaryReplacement(true);shader.setMorph(null,0);
    }
    private static void draw(PredictionTerrainProgram shader,PredictionGpuTile gpu,PredictionTileManager.PredictionTile tile,
                             PredictionDrawRanges ranges,AlgorithmSupport.Forest forest,int distance,int yaw) {
        var camera=camera(forest,distance,yaw);shader.use();shader.batch(false);gpu.bindTerrain(shader);gpu.bindYield(3);
        shader.setTile((float)-camera.x,(float)-camera.y,(float)-camera.z,tile.spacingBlocks(),tile.cellAxis(),false);
        PredictionRenderer.submitRanges(ranges,gpu.packed(),false);
    }
    private static void clear(TextureTarget target) {
        target.bindWrite(true);glViewport(0,0,SIZE,SIZE);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GEQUAL);glDepthMask(true);glClearDepth(0);glClearColor(0,0,0,0);
        glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
    }
    private static byte[] pixels() {
        var bytes=BufferUtils.createByteBuffer(SIZE*SIZE*4);glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,bytes);
        byte[] result=new byte[bytes.remaining()];bytes.get(result);return result;
    }
    private static String quality(int id,int distance,int yaw,int tier,int q0,int q1,byte[] a,byte[] b) {
        int intersection=0,union=0,pixels0=0,pixels1=0,added=0,lost=0;double rgb=0;
        for(int p=0;p<a.length;p+=4){boolean x=a[p+3]!=0,y=b[p+3]!=0;
            if(x)pixels0++;if(y)pixels1++;if(x&&y)intersection++;if(x||y){union++;for(int c=0;c<3;c++)rgb+=Math.abs(Byte.toUnsignedInt(a[p+c])-Byte.toUnsignedInt(b[p+c]))/255.;}
            if(x&&!y)lost++;if(y&&!x)added++;
        }
        assertTrue(pixels0>0,"source forest is visible");
        return id+","+distance+","+yaw+","+tier+","+q0+","+q1+","+pixels0+","+pixels1+","+(double)intersection/Math.max(1,union)+","+(double)added/Math.max(1,pixels0)+","+(double)lost/Math.max(1,pixels0)+","+rgb/Math.max(1,union*3);
    }
    private static void save(String name,byte[] rgba) throws Exception {
        var image=new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++) {int p=((SIZE-y-1)*SIZE+x)*4;
            image.setRGB(x,y,(Byte.toUnsignedInt(rgba[p+3])<<24)|(Byte.toUnsignedInt(rgba[p])<<16)|(Byte.toUnsignedInt(rgba[p+1])<<8)|Byte.toUnsignedInt(rgba[p+2]));}
        ImageIO.write(image,"png",AlgorithmSupport.output().resolve(name).toFile());
    }
    private static int texture(List<Integer> owner,int unit,int w,int h,int format,int type,float[] values) {
        int id=glCreateTextures(GL_TEXTURE_2D);owner.add(id);glTextureStorage2D(id,1,format,w,h);
        glTextureParameteri(id,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTextureParameteri(id,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glTextureSubImage2D(id,0,0,0,w,h,type,GL_FLOAT,values);glBindTextureUnit(unit,id);return id;
    }
    static void setupTextures(List<Integer> owner) throws Exception {
        PredictionVegetationRenderGpuTest.setupTextures((ArrayList<Integer>)owner);
        int atlas=glCreateTextures(GL_TEXTURE_2D);owner.add(atlas);glTextureStorage2D(atlas,5,GL_RGBA8,256,256);
        byte[] rgba=Files.readAllBytes(AlgorithmSupport.inputs().resolve("atlas.rgba"));var bytes=BufferUtils.createByteBuffer(rgba.length);bytes.put(rgba).flip();
        glTextureSubImage2D(atlas,0,0,0,256,256,GL_RGBA,GL_UNSIGNED_BYTE,bytes);glGenerateTextureMipmap(atlas);
        glTextureParameteri(atlas,GL_TEXTURE_MIN_FILTER,GL_NEAREST_MIPMAP_LINEAR);glTextureParameteri(atlas,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glBindTextureUnit(0,atlas);
        var table=ByteBuffer.wrap(Files.readAllBytes(AlgorithmSupport.inputs().resolve("sprite-table.bin")));float[] values=new float[table.remaining()/4];table.asFloatBuffer().get(values);
        texture(owner,2,256,2,GL_RGBA32F,GL_RGBA,values);
        texture(owner,1,1,1,GL_RGBA8,GL_RGBA,new float[]{1,1,1,1});
    }
}
