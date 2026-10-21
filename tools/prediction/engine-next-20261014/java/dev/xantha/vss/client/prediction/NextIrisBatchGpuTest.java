package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45C.*;
import java.util.*;
import java.lang.reflect.*;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.Test;

/** Uses the production Iris submit method and the Voxy adapter's 5/10+/9+ layout. */
class NextIrisBatchGpuTest {
    private static final String UBO="layout(std140,binding=5) uniform ShaderUniformBindings { vec4 PackFrame; };\n";
    private static final String TAA=UBO+"vec2 vssTaaShift(){return PackFrame.xy;}\n";
    private static final String PATCH="""
            #define BUFFER_BINDING_INDEX_BASE 10
            layout(std430,binding=BUFFER_BINDING_INDEX_BASE+0) readonly buffer PackData { vec4 PackValue[]; };
            #define BASE_SAMPLER_BINDING_INDEX 9
            layout(binding=BASE_SAMPLER_BINDING_INDEX+0) uniform sampler2D PackTexture;
            layout(location=0) out vec4 TestColor;
            layout(location=1) out vec4 TestAux;
            void voxy_emitFragment(VoxyFragmentParameters p) {
                TestColor=p.sampledColour * PackValue[0] * PackFrame.z * texture(PackTexture,vec2(.5));
                TestAux=vec4(p.lightMap, float(p.face)/7.0, 1.0);
            }
            """;
    private static final Field PROGRAM=field("program"),BATCH=field("indirectBatch");
    private static final Method SUBMIT=submit();
    private record Image(byte[] color,byte[] auxiliary,float[] depth){}

    @Test void adapterMrtTaaMasksMorphFallbackAndCompleteSubmissionBenefit() throws Exception {
        try(var fixture=new CompletionGpuFixture()) {
            var textures=new ArrayList<Integer>();var tiles=new ArrayList<PredictionGpuTile>();
            int ubo=glCreateBuffers(),ssbo=glCreateBuffers(),external=glCreateBuffers(),generic=glCreateBuffers(),indirect=glCreateBuffers();
            glNamedBufferData(ubo,new float[]{.001f,-.0015f,1,0},GL_STATIC_DRAW);glBindBufferBase(GL_UNIFORM_BUFFER,5,ubo);
            glNamedBufferData(ssbo,new float[]{1,.875f,.75f,1},GL_STATIC_DRAW);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,10,ssbo);
            glNamedBufferData(external,1024,GL_STATIC_DRAW);glNamedBufferData(generic,1024,GL_STATIC_DRAW);glNamedBufferData(indirect,1024,GL_STATIC_DRAW);
            PredictionVegetationRenderGpuTest.setupTextures(textures);
            int main=PredictionVegetationRenderGpuTest.texture(textures,7,256,256,GL_R32F,GL_RED,new float[256*256]);
            PredictionVegetationRenderGpuTest.texture(textures,9,1,1,GL_RGBA8,GL_RGBA,new float[]{1,1,1,1});
            int auxiliary=glCreateTextures(GL_TEXTURE_2D);glTextureStorage2D(auxiliary,1,GL_RGBA8,256,256);
            glNamedFramebufferTexture(fixture.fbo,GL_COLOR_ATTACHMENT1,auxiliary,0);
            glNamedFramebufferDrawBuffers(fixture.fbo,new int[]{GL_COLOR_ATTACHMENT0,GL_COLOR_ATTACHMENT1});
            var rows=new ArrayList<String>();rows.add("tiles,case,round,mode,cpu_ns,gpu_ns,calls,encoded,uploaded_bytes");
            var throughput=new ArrayList<String>();throughput.add("tiles,case,round,mode,frames,elapsed_ns");
            try(var direct=PredictionTerrainProgram.createIris(TAA,s->s+UBO+PATCH);
                var next=PredictionTerrainProgram.createIrisBatch(TAA,s->s+UBO+PATCH);
                var batch=new PredictionIndirectBatch()) {
                assertTrue(next.supportsBatch());BATCH.set(null,batch);
                verifyTransitions(fixture,direct,next,main,tiles);
                verifyLayoutBenefit(fixture,direct,next,main,tiles);
                for(int count:new int[]{16,128,256,512,2048}) {
                    var draws=new ArrayList<PredictionRenderer.Draw>();
                    for(int i=0;i<count;i++)draws.add(upload(i,count,tiles));
                    List<PredictionRenderer.Draw> source=List.copyOf(draws);
                    var passes=List.of(new PredictionPreparedPassLocalTest.Pass(false),new PredictionPreparedPassLocalTest.Pass(false));
                    int side=(int)Math.ceil(Math.sqrt(count));var camera=new Vec3(side*8,105,side*16+80);
                    var model=new Matrix4f().lookAlong(0,-.35f,-1,0,1,0);
                    for(boolean reverse:new boolean[]{false,true}) {
                        glClipControl(GL_LOWER_LEFT,reverse?GL_ZERO_TO_ONE:GL_NEGATIVE_ONE_TO_ONE);
                        var projection=reverse?new Matrix4f().perspective(1.2f,1,4096,.05f,true):new Matrix4f().perspective(1.2f,1,.05f,4096);
                        for(var shader:List.of(direct,next))configure(shader,model,projection,camera,main,reverse);
                        for(int mask:new int[]{192,64,0,192}) {
                            for(var draw:draws){byte[] value=new byte[16];Arrays.fill(value,(byte)mask);draw.gpu().updateBoundaryCoverage(value);}
                            Image expected=null;
                            for(int mode=0;mode<2;mode++) {
                                var shader=mode==0?direct:next;var pass=passes.get(mode);pass.prepare(source,1,camera);
                                clear(fixture,reverse);shader.use();PROGRAM.set(null,shader);
                                long calls=((Number)SUBMIT.invoke(null,pass.value,camera,false,false,Long.MAX_VALUE)).longValue();
                                assertTrue(calls>0);Image result=capture(fixture);
                                if(mode==0)expected=result;else assertImage(expected,result,"tiles="+count+",reverse="+reverse+",mask="+mask);
                                if(mode==0 && mask==192 && count==128)assertTrue(colored(result.color)>500,"fixture must draw actual cutout geometry");
                            }
                        }
                    }
                    // Final conventional frame; record cold and sparse rebuild cost too.
                    glClipControl(GL_LOWER_LEFT,GL_NEGATIVE_ONE_TO_ONE);
                    var projection=new Matrix4f().perspective(1.2f,1,.05f,4096);
                    for(var shader:List.of(direct,next))configure(shader,model,projection,camera,main,false);
                    for(String scenario:List.of("stable","turn","sparse")) {
                        for(int round=-24;round<61;round++) {
                            if(scenario.equals("sparse")) {
                                int at=Math.floorMod(round,count);var draw=draws.get(at);boolean[] same=draw.allowed().clone();
                                draws.set(at,new PredictionRenderer.Draw(draw.tile(),draw.gpu(),same,draw.features(),draw.seam(),draw.faces(),0,draw.bounds(),true));
                                source=List.copyOf(draws);
                            }
                            if(scenario.equals("turn")) {model.identity().lookAlong((float)Math.sin(round*.019),-.35f,(float)-Math.cos(round*.019),0,1,0);
                                for(var shader:List.of(direct,next)){shader.use();shader.setCamera(model,projection);}}
                            for(int order=0;order<2;order++) {
                                int mode=Math.floorMod(round+order,2);var shader=mode==0?direct:next;var pass=passes.get(mode);
                                clear(fixture,false);shader.use();PROGRAM.set(null,shader);glFinish();
                                glBindBufferRange(GL_SHADER_STORAGE_BUFFER,7,external,256,512);glBindBuffer(GL_SHADER_STORAGE_BUFFER,generic);
                                glBindBuffer(GL_DRAW_INDIRECT_BUFFER,indirect);
                                long encoded=batch.encodedItems(),start=System.nanoTime();glBeginQuery(GL_TIME_ELAPSED,fixture.timer);
                                pass.prepare(source,1,camera);
                                long calls=((Number)SUBMIT.invoke(null,pass.value,camera,false,false,Long.MAX_VALUE)).longValue();
                                glEndQuery(GL_TIME_ELAPSED);long cpu=System.nanoTime()-start,gpu=glGetQueryObjectui64(fixture.timer,GL_QUERY_RESULT);
                                if(round>=0)rows.add(count+","+scenario+","+round+","+mode+","+cpu+","+gpu+","+calls+","+(batch.encodedItems()-encoded)+","+(mode==0?0:batch.uploadedBytes()));
                                assertEquals(external,glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING,7));
                                assertEquals(256,glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START,7));assertEquals(512,glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE,7));
                                assertEquals(generic,glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING));assertEquals(indirect,glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING));
                                assertEquals(ssbo,glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING,10));assertEquals(ubo,glGetIntegeri(GL_UNIFORM_BUFFER_BINDING,5));
                                assertEquals(GL_NO_ERROR,glGetError());
                            }
                        }
                        for(int round=-2;round<9;round++)for(int order=0;order<2;order++) {
                            int mode=Math.floorMod(round+order,2);var shader=mode==0?direct:next;var pass=passes.get(mode);PROGRAM.set(null,shader);shader.use();glFinish();
                            long start=System.nanoTime();for(int frame=0;frame<24;frame++){
                                if(scenario.equals("sparse")){int at=(frame+round*24+count*10)%count;draws.set(at,ResidentRecordIntegrationTest.replacement(draws.get(at)));source=List.copyOf(draws);}
                                if(scenario.equals("turn")){shader.use();shader.setCamera(new Matrix4f().lookAlong((float)Math.sin(frame*.019),-.35f,(float)-Math.cos(frame*.019),0,1,0),projection);}
                                clear(fixture,false);shader.use();pass.prepare(source,1,camera);SUBMIT.invoke(null,pass.value,camera,false,false,Long.MAX_VALUE);
                            }
                            glFinish();if(round>=0)throughput.add(count+","+scenario+","+round+","+mode+",24,"+(System.nanoTime()-start));
                        }
                    }
                    for(var pass:passes)pass.clear.invoke(pass.value);
                    tiles.forEach(PredictionGpuTile::close);tiles.clear();glFinish();PredictionTerrainArena.SHARED.reap();
                }
            } finally {
                PROGRAM.set(null,null);BATCH.set(null,null);tiles.forEach(PredictionGpuTile::close);
                glClipControl(GL_LOWER_LEFT,GL_NEGATIVE_ONE_TO_ONE);
                for(int binding:new int[]{7,10})glBindBufferBase(GL_SHADER_STORAGE_BUFFER,binding,0);glBindBufferBase(GL_UNIFORM_BUFFER,5,0);
                glBindBuffer(GL_SHADER_STORAGE_BUFFER,0);glBindBuffer(GL_DRAW_INDIRECT_BUFFER,0);
                for(int buffer:new int[]{ubo,ssbo,external,generic,indirect})glDeleteBuffers(buffer);
                for(int texture:textures)glDeleteTextures(texture);glDeleteTextures(auxiliary);
                glFinish();PredictionTerrainArena.SHARED.close();PredictionTerrainArena.WATER.close();PredictionQuadBufferPool.SHARED.close();
            }
            CompletionGpuFixture.save("next-iris.csv",rows);CompletionGpuFixture.save("next-iris-throughput.csv",throughput);
        }
    }

    static PredictionRenderer.Draw upload(int i,int count,List<PredictionGpuTile> tiles) {
        return upload(i,count,tiles,false,false);
    }
    private static void verifyLayoutBenefit(CompletionGpuFixture fixture,PredictionTerrainProgram direct,PredictionTerrainProgram next,int main,List<PredictionGpuTile> tiles)throws Exception {
        var rows=new ArrayList<String>();rows.add("tiles,case,round,mode,frames,elapsed_ns");
        var ctor=PredictionTerrainArena.class.getDeclaredConstructor(int.class,String.class,int.class);ctor.setAccessible(true);
        var sliceField=PredictionGpuTile.class.getDeclaredField("arenaSlice");sliceField.setAccessible(true);
        for(String layout:List.of("page-run-1","page-run-2","page-run-4","page-run-8","page-run-64","standalone-2","standalone-8")){
            var a=ctor.newInstance(1,"layout-a",8*1024*1024);var b=ctor.newInstance(1,"layout-b",8*1024*1024);
            var draws=new ArrayList<PredictionRenderer.Draw>();
            var passes=List.of(new PredictionPreparedPassLocalTest.Pass(false),new PredictionPreparedPassLocalTest.Pass(false));
            try{
                int group=Integer.parseInt(layout.substring(layout.lastIndexOf('-')+1));
                for(int i=0;i<512;i++){
                    var draw=upload(i,512,tiles,layout.startsWith("standalone")&&i%group==0,false);draws.add(draw);
                    if(layout.startsWith("page")){
                        var gpu=draw.gpu();var old=gpu.arenaSlice();int[] mask=new int[16];glGetNamedBufferSubData(old.page.buffer,old.maskOffset,mask);
                        int[] words=gpu.packed().opaqueUploadWords();var bytes=BufferUtils.createByteBuffer(words.length*4);bytes.asIntBuffer().put(words);
                        var arena=(i/group)%2==0?a:b;var moved=arena.upload(bytes,16);assertNotNull(moved);arena.mask(moved,mask);
                        sliceField.set(gpu,moved);PredictionTerrainArena.SHARED.retire(old);NextInvalidationTest.changed(gpu);
                    }
                }
                List<PredictionRenderer.Draw> source=List.copyOf(draws);var camera=new Vec3(184,105,448);
                var model=new Matrix4f().lookAlong(0,-.35f,-1,0,1,0);var projection=new Matrix4f().perspective(1.2f,1,.05f,4096);
                glClipControl(GL_LOWER_LEFT,GL_NEGATIVE_ONE_TO_ONE);Image reference=null;
                for(int mode=0;mode<2;mode++){
                    var shader=mode==0?direct:next;configure(shader,model,projection,camera,main,false);PROGRAM.set(null,shader);passes.get(mode).prepare(source,1,camera);clear(fixture,false);SUBMIT.invoke(null,passes.get(mode).value,camera,false,false,Long.MAX_VALUE);
                    Image result=capture(fixture);if(reference==null)reference=result;else assertImage(reference,result,layout);
                }
                for(String scenario:List.of("stable","sparse"))for(int round=-3;round<11;round++)for(int order=0;order<2;order++){
                    int mode=Math.floorMod(round+order,2);var shader=mode==0?direct:next;configure(shader,model,projection,camera,main,false);PROGRAM.set(null,shader);glFinish();long start=System.nanoTime();
                    for(int frame=0;frame<24;frame++){
                        if(scenario.equals("sparse")){int at=Math.floorMod(round*24+frame,draws.size());draws.set(at,ResidentRecordIntegrationTest.replacement(draws.get(at)));source=List.copyOf(draws);}
                        clear(fixture,false);shader.use();passes.get(mode).prepare(source,1,camera);SUBMIT.invoke(null,passes.get(mode).value,camera,false,false,Long.MAX_VALUE);
                    }
                    glFinish();if(round>=0)rows.add("512,"+layout+"-"+scenario+","+round+","+mode+",24,"+(System.nanoTime()-start));
                }
                assertEquals(GL_NO_ERROR,glGetError());
            }finally{
                for(var pass:passes)pass.clear.invoke(pass.value);tiles.forEach(PredictionGpuTile::close);tiles.clear();glFinish();a.close();b.close();PredictionTerrainArena.SHARED.reap();
            }
        }
        CompletionGpuFixture.save("next-iris-layout-throughput.csv",rows);
    }
    private static PredictionRenderer.Draw upload(int i,int count,List<PredictionGpuTile> tiles,boolean standalone,boolean morph) {
        int side=(int)Math.ceil(Math.sqrt(count));var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,i%side,i/side,0);
        int[] words=PredictionVegetationRenderGpuTest.fixture();
        var packed=PredictionPackedMesh.terrainRecords(words,4);
        if(morph){float[] heights=new float[25];Arrays.fill(heights,2);packed.morph(heights,64,82);}
        packed.prepareGpuStorage();
        var mesh=PredictionMesh.restored(words.length/12*6,0,packed,null);
        var tile=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],mesh,new PredictionDepthBound(64,80),0,i+1,4,4);
        var gpu=new PredictionGpuTile(key);tiles.add(gpu);
        String previous=System.getProperty("vss.disableIndirect");try{
            if(standalone)System.setProperty("vss.disableIndirect","true");else System.clearProperty("vss.disableIndirect");
            assertTrue(gpu.ensureMesh(tile));
        }finally{if(previous==null)System.clearProperty("vss.disableIndirect");else System.setProperty("vss.disableIndirect",previous);}
        assertEquals(standalone,gpu.arenaSlice()==null);
        boolean[] allowed=new boolean[16];Arrays.fill(allowed,true);gpu.updateCoverage(allowed);
        byte[] boundary=new byte[16];Arrays.fill(boundary,(byte)192);gpu.updateBoundaryCoverage(boundary);
        return new PredictionRenderer.Draw(tile,gpu,allowed,allowed,false,31,morph?1:0,new AABB(tile.baseBlockX(),64,tile.baseBlockZ(),tile.baseBlockX()+16,82,tile.baseBlockZ()+16),true);
    }
    private static void verifyTransitions(CompletionGpuFixture fixture,PredictionTerrainProgram direct,PredictionTerrainProgram next,int main,List<PredictionGpuTile> tiles)throws Exception {
        var draws=new ArrayList<PredictionRenderer.Draw>();for(int i=0;i<320;i++)draws.add(upload(i,320,tiles,i%16==1,i%3==0));
        var passes=List.of(new PredictionPreparedPassLocalTest.Pass(false),new PredictionPreparedPassLocalTest.Pass(false));
        var camera=new Vec3(144,100,370);var model=new Matrix4f().lookAlong(0,-.35f,-1,0,1,0);
        long uploaded=System.nanoTime();var at=PredictionGpuTile.class.getDeclaredField("uploadedAt");at.setAccessible(true);for(var tile:tiles)at.setLong(tile,uploaded);
        try{
            for(boolean reverse:new boolean[]{false,true}){
                glClipControl(GL_LOWER_LEFT,reverse?GL_ZERO_TO_ONE:GL_NEGATIVE_ONE_TO_ONE);
                var projection=reverse?new Matrix4f().perspective(1.2f,1,4096,.05f,true):new Matrix4f().perspective(1.2f,1,.05f,4096);
                for(var shader:List.of(direct,next))configure(shader,model,projection,camera,main,reverse);
                for(int phase=0;phase<8;phase++){
                    int mask=phase==2?0:phase==3?64:192;
                    for(var tile:tiles){byte[] value=new byte[16];Arrays.fill(value,(byte)mask);tile.updateBoundaryCoverage(value);}
                    PredictionVegetationRenderGpuTest.setDepth(main,projection,phase==4?180:0,reverse);
                    if(phase==6)Collections.reverse(draws);
                    if(phase==7)draws.remove(0);
                    List<PredictionRenderer.Draw> source=List.copyOf(draws);
                    long time=uploaded+(phase==0?0:phase==1?175_000_000L:400_000_000L);
                    Image reference=null;for(int mode=0;mode<2;mode++){
                        var shader=mode==0?direct:next;var pass=passes.get(mode);pass.prepare(source,phase+1,camera);
                        clear(fixture,reverse);if(phase==5)glViewport(17,19,128,192);
                        shader.use();PROGRAM.set(null,shader);SUBMIT.invoke(null,pass.value,camera,false,false,time);
                        Image image=capture(fixture);if(reference==null)reference=image;else assertImageNear(reference,image,"mixed standalone/morph/retraction phase="+phase+" reverse="+reverse);
                    }
                }
            }
            // A fresh shader token must rebuild records even if the pass itself is unchanged.
            try(var reloaded=PredictionTerrainProgram.createIrisBatch(TAA,s->s+UBO+PATCH)){
                glClipControl(GL_LOWER_LEFT,GL_NEGATIVE_ONE_TO_ONE);var projection=new Matrix4f().perspective(1.2f,1,.05f,4096);
                Image reference=null;for(var shader:List.of(direct,reloaded)){
                    configure(shader,model,projection,camera,main,false);var pass=passes.get(shader==direct?0:1);pass.prepare(List.copyOf(draws),99,camera);
                    clear(fixture,false);shader.use();PROGRAM.set(null,shader);SUBMIT.invoke(null,pass.value,camera,false,false,uploaded+500_000_000L);
                    Image image=capture(fixture);if(reference==null)reference=image;else assertImage(reference,image,"shader reload");
                }
            }
            assertEquals(GL_NO_ERROR,glGetError());
        }finally{for(var pass:passes)pass.clear.invoke(pass.value);tiles.forEach(PredictionGpuTile::close);tiles.clear();glFinish();PredictionTerrainArena.SHARED.reap();}
    }
    private static void assertImageNear(Image a,Image b,String context){
        assertEquals(a.color.length,b.color.length);for(int i=0;i<a.color.length;i++)assertTrue(Math.abs(Byte.toUnsignedInt(a.color[i])-Byte.toUnsignedInt(b.color[i]))<=1,context+" color "+i);
        assertArrayEquals(a.auxiliary,b.auxiliary,context);assertArrayEquals(a.depth,b.depth,1e-6f,context);
    }
    private static void configure(PredictionTerrainProgram shader,Matrix4f model,Matrix4f projection,Vec3 camera,int main,boolean reverse) {
        shader.use();shader.setSamplers(0,1,2,3,4);shader.setCamera(model,projection);shader.setIrisFrame(new Matrix4f(projection).invert(),256,256,reverse,new int[256],reverse?0:1);
        shader.setFrame(new float[]{0,0,0,0},1e7f,2e7f,0,1e7f,false,1);shader.setRealCoverage(0,0,0,0);shader.setHorizon(131072);shader.setDetailDistance(1024);
        shader.setRealRenderDistance(0);shader.setVoxyOwnershipDistance(0);shader.setMorph(null,0);shader.setOpaqueAlpha(1);
        PredictionVegetationRenderGpuTest.setDepth(main,projection,0,reverse);
        int program=glGetInteger(GL_CURRENT_PROGRAM);glUniform1i(glGetUniformLocation(program,"MainDepth"),7);
        glUniform1fv(glGetUniformLocation(program,"DirectionalTint[0]"),new float[]{1,1,1,1,1,1,1});
        glUniform1i(glGetUniformLocation(program,"VanillaMask"),6);glUniform3i(glGetUniformLocation(program,"VanillaMaskSize"),2,8,2);
        glUniform3f(glGetUniformLocation(program,"VanillaMaskOrigin"),1e6f,1e6f,1e6f);
        glUniform1i(glGetUniformLocation(program,"ExactCoverage"),8);glUniform3f(glGetUniformLocation(program,"ExactCoverageGrid"),1e6f,1e6f,1);
    }
    private static void clear(CompletionGpuFixture f,boolean reverse) {
        glBindFramebuffer(GL_FRAMEBUFFER,f.fbo);glBindVertexArray(f.vao);glViewport(0,0,256,256);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);
        glEnable(GL_DEPTH_TEST);glDepthMask(true);glDepthFunc(reverse?GL_GREATER:GL_LESS);glClearDepth(reverse?0:1);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
    }
    private static Image capture(CompletionGpuFixture f) {
        byte[][] outputs=new byte[2][];for(int i=0;i<2;i++){glReadBuffer(GL_COLOR_ATTACHMENT0+i);outputs[i]=f.pixels();}return new Image(outputs[0],outputs[1],f.depths());
    }
    private static void assertImage(Image a,Image b,String context){assertArrayEquals(a.color,b.color,context);assertArrayEquals(a.auxiliary,b.auxiliary,context);assertArrayEquals(a.depth,b.depth,context);}
    private static int colored(byte[] image){int result=0;for(int at=3;at<image.length;at+=4)if(image[at]!=0)result++;return result;}
    private static Field field(String name){try{var f=PredictionRenderer.class.getDeclaredField(name);f.setAccessible(true);return f;}catch(Exception e){throw new AssertionError(e);}}
    private static Method submit(){try{var type=Class.forName(PredictionRenderer.class.getName()+"$PreparedPass");var m=PredictionRenderer.class.getDeclaredMethod("submitIris",type,Vec3.class,boolean.class,boolean.class,long.class);m.setAccessible(true);return m;}catch(Exception e){throw new AssertionError(e);}}
}
