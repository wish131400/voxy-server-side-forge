package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.junit.jupiter.api.Test;

/** Captured geometry, real vanilla alpha textures and the production MDI shader. */
class EngineForestGpuTest {
    private static final int SIZE=512;
    private static final Set<Integer> LEAVES=Set.of(31,40,41,42,88,91,94,167);
    private record Fixture(int id,int span,int spacing,float minY,float maxY,int[][] words) { }
    private record Upload(PredictionGpuTile gpu,PredictionRenderer.Draw draw,PredictionPackedMesh packed) { }
    private record Image(byte[] rgba,float[] depth) { }

    @Test void compareWholeForestSubmissionAndCanopyCoverage() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft(); assertTrue(glfwInit());
        glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        long window=glfwCreateWindow(SIZE,SIZE,"Engine forest comparison",0,0);assertNotEquals(0,window);
        var textures=new ArrayList<Integer>();var timing=new ArrayList<String>();var quality=new ArrayList<String>();
        timing.add("mesh,distance,hiz,round,mode,cpu_ns,gpu_ns,mdi_quads,mdi_commands,proxy_eligible");
        quality.add("mesh,distance,yaw,mode,source_pixels,proxy_pixels,iou,added_fraction,lost_fraction,rgb_mae,proxy_eligible");
        Path inputs=Path.of(System.getProperty("vss.engineInputs")),output=Path.of(System.getProperty("vss.followupResults"));Files.createDirectories(output);
        String force=System.getProperty("vss.forcePredictionOcclusion"),disable=System.getProperty("vss.disablePredictionOcclusion");
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();if(!RenderSystem.isOnRenderThread())RenderSystem.initRenderThread();
            System.out.println("ENGINE_FOREST_DEVICE "+glGetString(GL_RENDERER)+" "+glGetString(GL_VERSION));
            var fixtures=read(inputs);int max=fixtures.stream().flatMap(f->Arrays.stream(f.words)).mapToInt(w->w.length/12).max().orElseThrow();
            int vao=glGenVertexArrays(),ebo=glGenBuffers();glBindVertexArray(vao);glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);
            int[] indices=new int[max*6],corners={0,1,2,0,2,3};for(int q=0;q<max;q++)for(int c=0;c<6;c++)indices[q*6+c]=q*4+corners[c];
            glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STATIC_DRAW);setupTextures(inputs,textures);
            float[] depth=new float[SIZE*SIZE];Arrays.fill(depth,1);int mainDepth=texture(textures,7,SIZE,SIZE,GL_R32F,GL_RED,depth);
            int exact=texture(textures,8,1,1,GL_R8,GL_RED,new float[]{0});
            glActiveTexture(GL_TEXTURE6);int volume=glGenTextures();textures.add(volume);glBindTexture(GL_TEXTURE_3D,volume);
            glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_3D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glTexImage3D(GL_TEXTURE_3D,0,GL_R8,1,1,1,0,GL_RED,GL_FLOAT,new float[]{0});
            var target=new TextureTarget(SIZE,SIZE,true,false);int query=glGenQueries();
            try(var shader=PredictionTerrainProgram.createBatch()) {
                for(var fixture:fixtures) {
                    var whole=new ArrayList<Upload>();var canopy=new ArrayList<Upload>();
                    var batches=new ArrayList<PredictionIndirectBatch>();
                    try {
                        for(int mode=0;mode<3;mode++) {
                            whole.add(upload(fixture,fixture.words[mode],mode));canopy.add(upload(fixture,leafOnly(fixture.words[mode]),mode+3));
                            batches.add(new PredictionIndirectBatch());
                        }
                        // LOD approximation is assessed even when it is too close
                        // to enable; timing records explicitly carry eligibility.
                        for(int distance:new int[]{256,1024,4096})for(int angle:new int[]{0,45}) {
                            var view=view(fixture,distance,angle);configure(shader,view,mainDepth,exact);
                            Image reference=null;
                            for(int mode=0;mode<3;mode++) {
                                clear(target,vao);render(shader,canopy.get(mode),canopy.get(mode).packed.drawRanges(false,31),view,batches.get(mode),target,false);
                                var direct=capture();clear(target,vao);direct(shader,canopy.get(mode),canopy.get(mode).packed.drawRanges(false,31),view);
                                var expected=capture();assertArrayEquals(expected.rgba,direct.rgba,"direct/MDI colors "+fixture.id+"/"+mode);
                                assertArrayEquals(expected.depth,direct.depth,"direct/MDI depths "+fixture.id+"/"+mode);
                                if(mode==0)reference=direct;
                                quality.add(quality(fixture.id,distance,angle,mode,reference,direct,eligible(distance,mode,true,false,false,false)));
                                save(output.resolve("forest-"+fixture.id+"-"+distance+"-"+angle+"-"+mode+".png"),direct.rgba);
                            }
                            assertTrue(count(reference.rgba)>0,"captured canopy must be visible "+fixture.id+" distance="+distance);
                        }
                        for(int distance:new int[]{1024,4096})for(boolean hiz:new boolean[]{false,true}) {
                            var view=view(fixture,distance,0);configure(shader,view,mainDepth,exact);
                            if(hiz){System.setProperty("vss.forcePredictionOcclusion","true");System.clearProperty("vss.disablePredictionOcclusion");}
                            else{System.clearProperty("vss.forcePredictionOcclusion");System.setProperty("vss.disablePredictionOcclusion","true");}
                            var ranges=new ArrayList<PredictionDrawRanges>();
                            for(var item:whole) {
                                var lod=new PredictionDisplayLod();lod.update(item.packed.displayErrors(),distance,SIZE/(2*Math.tan(Math.toRadians(35))),512,false,2);
                                ranges.add(item.packed.drawRanges(false,31,0,lod.tier(),0));
                            }
                            long[][] gpu=new long[3][81],cpu=new long[3][81];
                            for(int round=-24;round<81;round++)for(int order=0;order<3;order++) {
                                int mode=Math.floorMod(round+order,3);clear(target,vao);shader.use();glFinish();
                                long start=System.nanoTime();glBeginQuery(GL_TIME_ELAPSED,query);
                                render(shader,whole.get(mode),ranges.get(mode),view,batches.get(mode),target,hiz);
                                glEndQuery(GL_TIME_ELAPSED);long elapsed=System.nanoTime()-start,device=glGetQueryObjectui64(query,GL_QUERY_RESULT);
                                if(round>=0) {
                                    gpu[mode][round]=device;cpu[mode][round]=elapsed;
                                    timing.add(fixture.id+","+distance+","+hiz+","+round+","+mode+","+elapsed+","+device+","+
                                            counter(batches.get(mode),"lastMdiQuads")+","+counter(batches.get(mode),"lastMdiCommands")+","+eligible(distance,mode,true,false,false,false));
                                }
                            }
                            System.out.printf(Locale.ROOT,"ENGINE_FOREST mesh=%d distance=%d hiz=%s quads=%d/%d/%d GPU_ms=%.6f/%.6f/%.6f CPU_ms=%.6f/%.6f/%.6f%n",
                                    fixture.id,distance,hiz,ranges.get(0).quads,ranges.get(1).quads,ranges.get(2).quads,
                                    CompletionGpuFixture.median(gpu[0])/1e6,CompletionGpuFixture.median(gpu[1])/1e6,CompletionGpuFixture.median(gpu[2])/1e6,
                                    CompletionGpuFixture.median(cpu[0])/1e6,CompletionGpuFixture.median(cpu[1])/1e6,CompletionGpuFixture.median(cpu[2])/1e6);
                            assertEquals(GL_NO_ERROR,glGetError());
                        }
                    } finally {
                        batches.forEach(PredictionIndirectBatch::close);whole.forEach(u->u.gpu.close());canopy.forEach(u->u.gpu.close());
                        glFinish();PredictionTerrainArena.SHARED.reap();
                    }
                }
            } finally {glDeleteQueries(query);target.destroyBuffers();glDeleteBuffers(ebo);glDeleteVertexArrays(vao);}
            CompletionGpuFixture.save("engine-forest.csv",timing);CompletionGpuFixture.save("engine-forest-quality.csv",quality);
            assertFalse(eligible(256,1,true,false,false,false));assertFalse(eligible(4096,1,false,false,false,false));
            assertFalse(eligible(4096,1,true,true,false,false));assertFalse(eligible(4096,1,true,false,true,false));assertFalse(eligible(4096,1,true,false,false,true));
        } finally {
            if(force==null)System.clearProperty("vss.forcePredictionOcclusion");else System.setProperty("vss.forcePredictionOcclusion",force);
            if(disable==null)System.clearProperty("vss.disablePredictionOcclusion");else System.setProperty("vss.disablePredictionOcclusion",disable);
            glFinish();PredictionTerrainArena.SHARED.close();PredictionTerrainArena.WATER.close();PredictionQuadBufferPool.SHARED.close();
            for(int texture:textures)glDeleteTextures(texture);glfwDestroyWindow(window);glfwTerminate();
        }
    }

    private record View(Vec3 camera,Matrix4f model,VssLodProjection.MatrixData projection,Matrix4f transform) { }
    private static View view(Fixture f,int distance,int yaw) {
        double angle=Math.toRadians(yaw),center=f.span*.5,y=(f.minY+f.maxY)*.5;
        var camera=new Vec3(center+Math.sin(angle)*distance,y+f.span*.12,center+Math.cos(angle)*distance);
        var direction=new Vec3(center,y,center).subtract(camera).normalize();
        var model=new Matrix4f().lookAlong((float)direction.x,(float)direction.y,(float)direction.z,0,1,0);
        var projection=VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(70),1,.05f,16384));
        return new View(camera,model,projection,new Matrix4f(projection.matrix()).mul(model));
    }
    private static boolean eligible(double distance,int mode,boolean owned,boolean scoped,boolean seam,boolean morph) {
        if(mode==0)return true;if(!owned||scoped||seam||morph)return false;
        double error=(mode==1?2:4)*Math.sqrt(3);return distance>512&&error*(SIZE/(2*Math.tan(Math.toRadians(35))))/(distance-error)<=2;
    }
    private static Upload upload(Fixture f,int[] words,int revision) {
        var packed=PredictionPackedMesh.terrainRecords(words.clone(),f.span/f.spacing);packed.prepareGpuStorage();
        var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,0);
        var mesh=PredictionMesh.restored(words.length/12*6,0,packed,null);
        var tile=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],mesh,
                new PredictionDepthBound((int)f.minY,(int)Math.ceil(f.maxY)),0,revision+1,f.span/f.spacing,f.spacing);
        var gpu=new PredictionGpuTile(key);assertTrue(gpu.ensureMesh(tile));assertNotNull(gpu.arenaSlice());
        boolean[] all=new boolean[(f.span/f.spacing)*(f.span/f.spacing)];Arrays.fill(all,true);gpu.updateCoverage(all);
        byte[] boundary=new byte[all.length];Arrays.fill(boundary,(byte)192);gpu.updateBoundaryCoverage(boundary);
        var draw=new PredictionRenderer.Draw(tile,gpu,all,false,31,0,new AABB(0,f.minY,0,f.span,f.maxY,f.span));
        return new Upload(gpu,draw,packed);
    }
    private static void render(PredictionTerrainProgram shader,Upload item,PredictionDrawRanges ranges,View view,
                               PredictionIndirectBatch batch,TextureTarget target,boolean hiz) {
        if(hiz){batch.workload(ranges.quads);batch.begin(shader,target.getDepthTextureId(),SIZE,SIZE,view.transform,view.camera,1,1,1);}
        else batch.begin(shader);
        long replay=batch.replayPass(ranges,1,false,view.camera,Long.MAX_VALUE);
        if(replay<0)assertTrue(batch.add(item.draw,ranges,view.camera,false,false,Long.MAX_VALUE));
        batch.end();
    }
    private static void direct(PredictionTerrainProgram shader,Upload item,PredictionDrawRanges ranges,View view) {
        shader.use();shader.batch(false);item.gpu.bindTerrain(shader);item.gpu.bindYield(3);
        shader.setTile((float)-view.camera.x,(float)-view.camera.y,(float)-view.camera.z,item.draw.tile().spacingBlocks(),item.draw.tile().cellAxis(),false);
        shader.setMorph(item.packed,0);shader.setBoundaryReplacement(true);
        PredictionRenderer.submitRanges(ranges,item.packed,false);
    }
    private static void configure(PredictionTerrainProgram shader,View view,int mainDepth,int exact) {
        shader.use();shader.setSamplers(0,1,2,3,4);shader.setFrame(new float[]{0,0,0,0},1e7f,2e7f,0,1e7f,false,1);
        shader.setCamera(view.model,view.projection.matrix());shader.bindMainDepth(mainDepth,view.projection);shader.setOpaqueAlpha(1);
        shader.setRealCoverage(0,0,0,0);shader.setRealRenderDistance(0);shader.setVoxyOwnershipDistance(0);shader.setHorizon(131072);shader.setDetailDistance(0);shader.setMorph(null,0);
        int id=glGetInteger(GL_CURRENT_PROGRAM);glUniform1fv(glGetUniformLocation(id,"DirectionalTint[0]"),new float[]{1,1,1,1,1,1,1});
        glUniform1i(glGetUniformLocation(id,"ExactCoverage"),8);glUniform3f(glGetUniformLocation(id,"ExactCoverageGrid"),0,0,1);
        glUniform1i(glGetUniformLocation(id,"VanillaMask"),6);glUniform3i(glGetUniformLocation(id,"VanillaMaskSize"),1,1,1);
        glUniform3f(glGetUniformLocation(id,"VanillaMaskOrigin"),1e6f,1e6f,1e6f);glActiveTexture(GL_TEXTURE8);glBindTexture(GL_TEXTURE_2D,exact);
    }
    private static void clear(TextureTarget target,int vao) {
        target.bindWrite(true);glBindVertexArray(vao);glViewport(0,0,SIZE,SIZE);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GEQUAL);glDepthMask(true);glClearDepth(0);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
    }
    private static Image capture() {
        var color=BufferUtils.createByteBuffer(SIZE*SIZE*4);glReadPixels(0,0,SIZE,SIZE,GL_RGBA,GL_UNSIGNED_BYTE,color);
        var depth=BufferUtils.createFloatBuffer(SIZE*SIZE);glReadPixels(0,0,SIZE,SIZE,GL_DEPTH_COMPONENT,GL_FLOAT,depth);
        byte[] c=new byte[color.remaining()];color.get(c);float[] d=new float[depth.remaining()];depth.get(d);return new Image(c,d);
    }
    private static long count(byte[] image) {long count=0;for(int p=3;p<image.length;p+=4)if(image[p]!=0)count++;return count;}
    private static String quality(int mesh,int distance,int yaw,int mode,Image source,Image candidate,boolean eligible) {
        long intersection=0,union=0,added=0,lost=0;double error=0;
        for(int p=0;p<source.rgba.length;p+=4) {
            boolean a=source.rgba[p+3]!=0,b=candidate.rgba[p+3]!=0;
            if(a&&b)intersection++;if(a||b){union++;for(int c=0;c<3;c++)error+=Math.abs(Byte.toUnsignedInt(source.rgba[p+c])-Byte.toUnsignedInt(candidate.rgba[p+c]))/255.;}
            if(!a&&b)added++;if(a&&!b)lost++;
        }
        long pixels=count(source.rgba);return mesh+","+distance+","+yaw+","+mode+","+pixels+","+count(candidate.rgba)+","+
                (union==0?1:(double)intersection/union)+","+(double)added/Math.max(1,pixels)+","+(double)lost/Math.max(1,pixels)+","+error/Math.max(1,union*3)+","+eligible;
    }
    private static void save(Path path,byte[] rgba) throws Exception {
        var image=new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_ARGB);int[] pixels=new int[SIZE*SIZE];
        for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++){int p=((SIZE-1-y)*SIZE+x)*4;pixels[y*SIZE+x]=(Byte.toUnsignedInt(rgba[p+3])<<24)|(Byte.toUnsignedInt(rgba[p])<<16)|(Byte.toUnsignedInt(rgba[p+1])<<8)|Byte.toUnsignedInt(rgba[p+2]);}
        image.setRGB(0,0,SIZE,SIZE,pixels,0,SIZE);ImageIO.write(image,"png",path.toFile());
    }
    private static long counter(PredictionIndirectBatch batch,String name) throws Exception {var f=PredictionIndirectBatch.class.getDeclaredField(name);f.setAccessible(true);return f.getLong(batch);}
    private static int[] leafOnly(int[] words) {
        int count=0;for(int p=0;p<words.length;p+=12)if(LEAVES.contains(words[p+6]&65535))count++;
        int[] result=new int[count*12];int at=0;for(int p=0;p<words.length;p+=12)if(LEAVES.contains(words[p+6]&65535)){System.arraycopy(words,p,result,at,12);at+=12;}return result;
    }
    private static List<Fixture> read(Path inputs) throws Exception {
        var result=new ArrayList<Fixture>();try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(inputs.resolve("forest-fixtures.bin"))))) {
            int count=in.readInt();for(int i=0;i<count;i++){int id=in.readInt(),span=in.readInt(),spacing=in.readInt();float lo=in.readFloat(),hi=in.readFloat();int[][] words=new int[3][];
                for(int mode=0;mode<3;mode++){words[mode]=new int[in.readInt()];for(int p=0;p<words[mode].length;p++)words[mode][p]=in.readInt();}result.add(new Fixture(id,span,spacing,lo,hi,words));}
        }return result;
    }
    private static int texture(List<Integer> textures,int unit,int width,int height,int format,int type,float[] data) {
        glActiveTexture(GL_TEXTURE0+unit);int id=glGenTextures();textures.add(id);glBindTexture(GL_TEXTURE_2D,id);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glTexImage2D(GL_TEXTURE_2D,0,format,width,height,0,type,GL_FLOAT,data);return id;
    }
    private static void setupTextures(Path inputs,List<Integer> textures) throws Exception {
        glActiveTexture(GL_TEXTURE0);int atlas=glGenTextures();textures.add(atlas);glBindTexture(GL_TEXTURE_2D,atlas);
        byte[] bytes=Files.readAllBytes(inputs.resolve("atlas.rgba"));var data=BufferUtils.createByteBuffer(bytes.length);data.put(bytes).flip();
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA8,256,256,0,GL_RGBA,GL_UNSIGNED_BYTE,data);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAX_LEVEL,4);glGenerateMipmap(GL_TEXTURE_2D);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST_MIPMAP_LINEAR);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        texture(textures,1,1,1,GL_RGBA8,GL_RGBA,new float[]{1,1,1,1});
        var table=ByteBuffer.wrap(Files.readAllBytes(inputs.resolve("sprite-table.bin"))).order(ByteOrder.BIG_ENDIAN);float[] values=new float[table.remaining()/4];for(int i=0;i<values.length;i++)values[i]=table.getFloat();
        texture(textures,2,256,2,GL_RGBA32F,GL_RGBA,values);
    }
}
