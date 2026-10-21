package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46C.*;
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
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** Real captured geometry and the unmodified LIVE terrain shaders/record encoder.
 * The 6x6 arrangement is a replay workload, not a capture of one complete world.
 * History selects phase zero only; current depth ALWAYS validates phase one.
 * Fixed command slots preserve input order and exercise the persistent-scene design. */
class RasterBoxesForestGpuTest {
    private static final String[] MODES={"direct", "hiz", "two_pass_hiz", "two_pass_boxes", "two_pass_boxes_ordered"};
    private static final Set<Integer> LEAVES=Set.of(31,40,41,42,88,91,94,167);
    private static final int SLOT_LIMIT=16384, TILES=36;
    private static boolean compact;
    private static int compactProgram,compactOutput,compactCount;
    private record Image(byte[] color,float[] depth) { }
    private record Camera(Vec3 position,Matrix4f model,VssLodProjection.MatrixData projection,Matrix4f transform) { }
    private record Asset(AlgorithmSupport.Forest forest,PredictionPackedMesh mesh,PredictionGpuTile gpu,
                         PredictionTileManager.PredictionTile tile) { }
    private static final String SELECT="""
        #version 430
        layout(local_size_x=64)in;
        struct Box{vec4 lo;vec4 hi;};
        layout(std430,binding=0)readonly buffer Boxes{Box boxes[];};
        layout(std430,binding=1)readonly buffer Input{uint inputCommands[];};
        layout(std430,binding=2)writeonly buffer Output{uint commands[];};
        layout(std430,binding=3)buffer Seen{uint state[];};
        uniform mat4 Transform;uniform vec3 CameraOffset;uniform uint Count;uniform int Phase;uniform bool Ordered;
        // Same conservative direction predicate as the production Hi-Z culler.
        bool backFacing(Box b){
            uint directions=floatBitsToUint(b.lo.w)&127u;
            if(directions==0u||(directions&64u)!=0u)return false;
            vec3 lo=b.lo.xyz+CameraOffset,hi=b.hi.xyz+CameraOffset;
            if(any(isnan(lo))||any(isnan(hi))||any(isinf(lo))||any(isinf(hi)))return false;
            uint face=(hi.x> -4.?1u:0u)|(lo.x<4.?2u:0u)|(hi.y> -4.?4u:0u)
                |(lo.y<4.?8u:0u)|(hi.z> -4.?16u:0u)|(lo.z<4.?32u:0u);
            return (directions&face)==0u;
        }
        bool visible(Box b,out bool unsafe){
            bool l=true,r=true,t=true,d=true;unsafe=false;
            vec3 lo=b.lo.xyz+CameraOffset,hi=b.hi.xyz+CameraOffset;
            if(any(isnan(lo))||any(isnan(hi))||any(isinf(lo))||any(isinf(hi))){unsafe=true;return true;}
            unsafe=all(lessThanEqual(lo,vec3(0)))&&all(greaterThanEqual(hi,vec3(0)));
            for(int i=0;i<8;i++){
                vec3 p=mix(lo,hi,vec3(float(i&1),float((i>>1)&1),float((i>>2)&1)));
                vec4 c=Transform*vec4(p,1.);
                unsafe=unsafe||c.w<=0.||c.z>=c.w||c.z<=-c.w;
                l=l&&c.x< -c.w;r=r&&c.x>c.w;t=t&&c.y>c.w;d=d&&c.y< -c.w;
            }
            return unsafe||!(l||r||t||d);
        }
        void main(){uint id=gl_GlobalInvocationID.x;if(id>=Count)return;
            uint old=state[id];bool draw=false;
            if(Phase==0){bool unsafe;bool view=visible(boxes[id],unsafe)&&!backFacing(boxes[id])&&inputCommands[id*5u]>0u;
                draw=view&&((old&1u)!=0u||unsafe);
                state[id]=(view?8u:0u)|(view&&unsafe?2u:0u)|(draw?4u:0u);
            }else{draw=(Ordered ? (old&6u)!=0u : (old&2u)!=0u&&(old&4u)==0u)&&inputCommands[id*5u]>0u;
                state[id]=(old&8u)|((old&2u)!=0u?1u:0u);}
            for(uint k=0u;k<5u;k++)commands[id*5u+k]=(k==0u&&!draw)?0u:inputCommands[id*5u+k];
        }
        """;
    private static final String HIZ_MARK="""
        #version 430
        layout(local_size_x=64)in;
        layout(std430,binding=2)readonly buffer Output{uint commands[];};
        layout(std430,binding=3)buffer Seen{uint state[];};uniform uint Count;
        void main(){uint id=gl_GlobalInvocationID.x;if(id>=Count)return;
            if(commands[id*5u]>0u&&(state[id]&8u)!=0u)state[id]|=2u;}
        """;
    private static final String BOX_VERTEX="""
        #version 430
        struct Box{vec4 lo;vec4 hi;};layout(std430,binding=0)readonly buffer Boxes{Box boxes[];};
        layout(std430,binding=3)readonly buffer Seen{uint state[];};
        uniform mat4 Transform;uniform vec3 CameraOffset;flat out uint Id;
        const int corner[6]=int[6](0,1,3,0,3,2);
        void main(){Id=uint(gl_InstanceID);
            if((state[Id]&8u)==0u||(state[Id]&2u)!=0u){gl_Position=vec4(2,2,2,1);return;}
            Box b=boxes[Id];int face=gl_VertexID/6,n=face/2,side=face%2,u=(n+1)%3,v=(n+2)%3;
            int c=corner[gl_VertexID%6];vec3 t=vec3(0);t[n]=float(side);
            t[u]=float((c&1)^(1-side));t[v]=float((c>>1)&1);
            vec3 p=mix(b.lo.xyz,b.hi.xyz,t)+CameraOffset;gl_Position=Transform*vec4(p,1.);
        }
        """;
    private static final String BOX_FRAGMENT="""
        #version 430
        layout(early_fragment_tests)in;
        layout(std430,binding=3)buffer Seen{uint state[];};flat in uint Id;
        // Match the important Meridian fast path: fragments of an already
        // confirmed box do not keep contending on the same global atomic.
        void main(){if((state[Id]&2u)==0u)atomicOr(state[Id],2u);}
        """;
    /** Stable GPU compaction: contiguous lane spans plus an inclusive scan.
     * Keeping order avoids introducing additional coplanar material changes.
     * This cost is inside the same GPU query as every culling and draw stage. */
    private static final String COMPACT="""
        #version 430
        layout(local_size_x=256)in;
        layout(std430,binding=1)readonly buffer Input{uint source[];};
        layout(std430,binding=2)writeonly buffer Output{uint target[];};
        layout(std430,binding=4)writeonly buffer Counts{uint drawCount;};
        uniform uint Count;shared uint prefix[256];
        void main(){uint lane=gl_LocalInvocationID.x,span=(Count+255u)/256u;
            uint first=min(Count,lane*span),last=min(Count,first+span),n=0u;
            for(uint id=first;id<last;id++)if(source[id*5u]>0u)n++;
            prefix[lane]=n;barrier();
            for(uint step=1u;step<256u;step*=2u){uint add=lane>=step?prefix[lane-step]:0u;barrier();prefix[lane]+=add;barrier();}
            uint base=lane==0u?0u:prefix[lane-1u];
            for(uint id=first;id<last;id++)if(source[id*5u]>0u){for(uint k=0u;k<5u;k++)target[base*5u+k]=source[id*5u+k];base++;}
            if(lane==255u)drawCount=prefix[lane];
        }
        """;

    @Test void fullForestTwoPassOcclusionHasConservativePixelsAndWholeIntervalTimings() throws Exception {
        assertTrue(glfwInit());glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        long window=glfwCreateWindow(1024,1024,"VSS rasterized box comparison",0,0);assertNotEquals(0,window);
        var textures=new ArrayList<Integer>();var buffers=new ArrayList<Integer>();var assets=new ArrayList<Asset>();
        var timing=new ArrayList<String>();var quality=new ArrayList<String>();var sceneRows=new ArrayList<String>();
        timing.add("size,scenario,frame,mode,cpu_ns,gpu_ns,candidate_commands,candidate_quads,phase0_commands,phase0_quads,phase1_commands,phase1_quads");
        quality.add("size,scenario,frame,mode,color_mismatches,depth_mismatches,alpha_mismatches,reference_pixels");
        sceneRows.add("slot,mesh,source_quads,render_quads,x,z");
        int comparisons=0;
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();if(!RenderSystem.isOnRenderThread())RenderSystem.initRenderThread();
            AlgorithmBootstrap.bootstrapMinecraft();
            Files.writeString(AlgorithmSupport.output().resolve("device.txt"),glGetString(GL_VENDOR)+"\n"+glGetString(GL_RENDERER)+"\n"+glGetString(GL_VERSION)+"\n");
            int vao=glCreateVertexArrays();glBindVertexArray(vao);
            for(var forest:AlgorithmSupport.forests())assets.add(upload(forest));
            int max=assets.stream().mapToInt(a->a.mesh.quadCount()).max().orElseThrow();
            int[] elements=new int[max*6],corners={0,1,2,0,2,3};
            for(int q=0;q<max;q++)for(int c=0;c<6;c++)elements[q*6+c]=q*4+corners[c];
            int ebo=buffer(buffers,elements);glVertexArrayElementBuffer(vao,ebo);
            setupTextures(textures);int mainDepth=texture(textures,7,1024,1024,GL_R32F,GL_RED,ones(1024*1024));
            texture(textures,8,1,1,GL_R8,GL_RED,new float[]{0});
            int volume=glCreateTextures(GL_TEXTURE_3D);textures.add(volume);glTextureStorage3D(volume,1,GL_R8,1,1,1);
            glTextureSubImage3D(volume,0,0,0,0,1,1,1,GL_RED,GL_FLOAT,new float[]{0});glBindTextureUnit(6,volume);
            var records=BufferUtils.createByteBuffer(TILES*64);
            var commands=BufferUtils.createByteBuffer(SLOT_LIMIT*20);var bounds=BufferUtils.createByteBuffer(SLOT_LIMIT*32);
            var page=assets.get(0).gpu.arenaSlice().page;
            for(int tile=0;tile<TILES;tile++){
                var asset=assets.get(tile%assets.size());var f=asset.forest;var original=asset.tile;
                int x=tile%6*128,z=tile/6*128;int span=f.span();
                var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,x/span,z/span,0);
                var shifted=new PredictionTileManager.PredictionTile(key,original.heights(),original.groundHeights(),original.samples(),original.mesh(),original.depthBound(),0,tile+1,span/f.spacing(),f.spacing());
                assertEquals(x,shifted.baseBlockX());assertEquals(z,shifted.baseBlockZ());
                float[] b=asset.mesh.occlusionBounds.extent;
                var draw=new PredictionRenderer.Draw(shifted,asset.gpu,new boolean[0],false,31,0,new AABB(x+b[0]-2,b[1]-2,z+b[2]-2,x+b[3]+2,b[4]+2,z+b[5]+2));
                var ranges=asset.mesh.drawRanges(false,31,0,0,0,0,false);
                assertSame(page,asset.gpu.arenaSlice().page,"One resident page avoids page-layout bias");
                PredictionSubmissionEncoding.write(draw,ranges,asset.gpu.arenaSlice(),false,false,true,tile,records,commands,bounds);
                sceneRows.add(tile+","+f.id()+","+f.words()[0].length/12+","+ranges.quads+","+x+","+z);
            }
            records.flip();commands.flip();bounds.flip();int count=commands.remaining()/20;
            assertTrue(count>1000&&count<SLOT_LIMIT,"Exercise a real meshlet workload: "+count);
            int metadata=buffer(buffers,records),input=buffer(buffers,commands),boxData=buffer(buffers,bounds);
            int[] canonical=new int[count*5];commands.asIntBuffer().get(canonical);
            int[] active=canonical.clone();long quads=0;for(int i=0;i<count;i++)quads+=canonical[i*5]/6;
            int[] states={0,0,buffer(buffers,new int[count]),buffer(buffers,new int[count]),buffer(buffers,new int[count])};
            int phase0=buffer(buffers,new int[count*5]),phase1=buffer(buffers,new int[count*5]),filtered=buffer(buffers,new int[count*5]);
            int selector=compute(SELECT),mark=compute(HIZ_MARK),query=glGenQueries();
            compact=Boolean.getBoolean("vss.rasterCompact");
            if(compact){compactProgram=compute(COMPACT);compactOutput=buffer(buffers,new int[count*5]);compactCount=buffer(buffers,new int[1]);}
            try(var terrain=PredictionTerrainProgram.createBatch();var boxProgram=GlProgram.link("raster-boxes",BOX_VERTEX,BOX_FRAGMENT);
                var culler=new PredictionOcclusionCuller()){
                int boxId=program(boxProgram);
                boolean quick=Boolean.getBoolean("vss.rasterQuick");int rounds=quick?3:61,warmup=quick?2:24;
                for(int size:quick?new int[]{512}:new int[]{512,1024}){
                    var target=new TextureTarget(size,size,true,false);
                    try{
                        for(String scenario:List.of("open","blocked75","blocked95","canopy","turning","jump90","near_plane","coverage_change")){
                            for(int state:states)if(state!=0)clearBuffer(state);
                            glNamedBufferSubData(input,0,canonical);
                            var samples=new long[MODES.length][rounds];
                            for(int frame=-warmup;frame<rounds;frame++){
                                var camera=camera(scenario,frame);configure(terrain,camera,mainDepth);
                                matrix(selector,camera);matrix(boxId,camera);
                                System.arraycopy(canonical,0,active,0,active.length);
                                if(scenario.equals("coverage_change")&&Math.floorMod(frame,16)>=8)
                                    for(int i=0;i<count;i++)if(active[i*5+4]%3!=0)active[i*5]=0;
                                glNamedBufferSubData(input,0,active);long candidateQuads=0;
                                for(int i=0;i<count;i++)candidateQuads+=active[i*5]/6;
                                boolean check=quick||frame==-warmup||frame>=0&&(frame<3||frame%8==0||frame%8==7||frame==rounds-1);
                                Image[] images=check?new Image[MODES.length]:null;
                                for(int order=0;order<MODES.length;order++){
                                    int mode=Math.floorMod(frame+order,MODES.length);
                                    clear(target,vao,size,scenario,frame);glFinish();
                                    long start=System.nanoTime();glBeginQuery(GL_TIME_ELAPSED,query);
                                    if(mode==0)draw(terrain,page.texture,metadata,input,count);
                                    else if(mode==1){
                                        assertTrue(culler.begin(target.getDepthTextureId(),size,size,camera.transform));
                                        culler.setCameraOffset((float)-camera.position.x,(float)-camera.position.y,(float)-camera.position.z);
                                        culler.rebuild();int output=culler.filter(boxData,input,filtered,count);
                                        draw(terrain,page.texture,metadata,output,count);culler.end();
                                    }else{
                                        glProgramUniform1i(selector,glGetUniformLocation(selector,"Ordered"),mode==4?1:0);
                                        select(selector,boxData,input,phase0,states[mode],count,0);
                                        if(mode==4)glColorMask(false,false,false,false);
                                        draw(terrain,page.texture,metadata,phase0,count);
                                        if(mode==2){
                                            assertTrue(culler.begin(target.getDepthTextureId(),size,size,camera.transform));
                                            culler.setCameraOffset((float)-camera.position.x,(float)-camera.position.y,(float)-camera.position.z);
                                            culler.rebuild();culler.filter(boxData,input,filtered,count);culler.end();
                                            glUseProgram(mark);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,filtered);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,3,states[mode]);
                                            glUniform1ui(glGetUniformLocation(mark,"Count"),count);glDispatchCompute((count+63)/64,1,1);glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
                                        }else{
                                            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,boxData);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,3,states[mode]);
                                            glColorMask(false,false,false,false);glDepthMask(false);glEnable(GL_CULL_FACE);glCullFace(GL_BACK);
                                            boxProgram.use();glDrawArraysInstanced(GL_TRIANGLES,0,36,count);
                                            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);glDisable(GL_CULL_FACE);glColorMask(true,true,true,true);glDepthMask(true);
                                        }
                                        select(selector,boxData,input,phase1,states[mode],count,1);
                                        draw(terrain,page.texture,metadata,phase1,count);
                                    }
                                    glEndQuery(GL_TIME_ELAPSED);long cpu=System.nanoTime()-start,gpu=glGetQueryObjectui64(query,GL_QUERY_RESULT);
                                    if(frame>=0){
                                        samples[mode][frame]=gpu;
                                        long[] first=mode>=2?stats(phase0,count):new long[]{0,0};
                                        long[] last=stats(mode==0?input:mode==1?filtered:phase1,count);
                                        timing.add(size+","+scenario+","+frame+","+MODES[mode]+","+cpu+","+gpu+","+count+","+candidateQuads+","+first[0]+","+first[1]+","+last[0]+","+last[1]);
                                    }
                                    if(check)images[mode]=capture(size);
                                    assertEquals(GL_NO_ERROR,glGetError(),scenario+"/"+MODES[mode]+"/"+frame);
                                }
                                if(check){
                                    long pixels=0;for(int p=3;p<images[0].color.length;p+=4)if(images[0].color[p]!=0)pixels++;
                                    if(frame==0&&scenario.equals("open")){assertTrue(pixels>100,"Forest cannot be empty");save(size+"-open.png",images[0],size);}
                                    for(int mode=1;mode<MODES.length;mode++){
                                        int c=0,d=0,a=0;for(int p=0;p<images[0].color.length;p++)if(images[0].color[p]!=images[mode].color[p]){c++;if(p%4==3)a++;}
                                        for(int p=0;p<images[0].depth.length;p++)if(Float.floatToRawIntBits(images[0].depth[p])!=Float.floatToRawIntBits(images[mode].depth[p]))d++;
                                        quality.add(size+","+scenario+","+frame+","+MODES[mode]+","+c+","+d+","+a+","+pixels);comparisons++;
                                        if(c!=0||d!=0){save(size+"-"+scenario+"-reference.png",images[0],size);save(size+"-"+scenario+"-"+MODES[mode]+".png",images[mode],size);}
                                        AlgorithmSupport.save("quality.csv",quality);AlgorithmSupport.save("timings.csv",timing);
                                        // Split color passes can change coplanar material tie winners.
                                        // Record every mismatch; only the ordered variant qualifies for
                                        // exact-output adoption. No RGB tolerance hides these differences.
                                        if(mode==1||mode==4)assertEquals(0,c,"Exact colors "+scenario+" frame="+frame+" mode="+MODES[mode]);
                                        assertEquals(0,a,"No visible pixel holes "+scenario+" frame="+frame+" mode="+MODES[mode]);
                                        assertEquals(0,d,"Exact depth "+scenario+" frame="+frame+" mode="+MODES[mode]);
                                    }
                                }
                            }
                            System.out.printf(Locale.ROOT,"RASTER_BOXES size=%d scene=%s commands=%d quads=%d gpu_ms=%.6f/%.6f/%.6f/%.6f/%.6f%n",
                                size,scenario,count,quads,AlgorithmSupport.median(samples[0])/1e6,AlgorithmSupport.median(samples[1])/1e6,AlgorithmSupport.median(samples[2])/1e6,AlgorithmSupport.median(samples[3])/1e6,AlgorithmSupport.median(samples[4])/1e6);
                        }
                    }finally{target.destroyBuffers();}
                }
            }finally{glDeleteProgram(selector);glDeleteProgram(mark);if(compactProgram!=0)glDeleteProgram(compactProgram);glDeleteQueries(query);glDeleteVertexArrays(vao);}
            AlgorithmSupport.save("timings.csv",timing);AlgorithmSupport.save("quality.csv",quality);AlgorithmSupport.save("scene.csv",sceneRows);
            System.out.println("RASTER_BOXES_COMPARISONS "+comparisons);
        }finally{
            assets.forEach(a->a.gpu.close());glFinish();PredictionTerrainArena.SHARED.close();PredictionQuadBufferPool.SHARED.close();
            for(int b:buffers)if(b!=0)glDeleteBuffers(b);for(int t:textures)glDeleteTextures(t);glfwDestroyWindow(window);glfwTerminate();
        }
    }

    private static Asset upload(AlgorithmSupport.Forest forest){
        int[] raw=forest.words()[0].clone();for(int p=6;p<raw.length;p+=12)if(LEAVES.contains(raw[p]&65535))raw[p]|=PredictionPackedMesh.FLAG_CUTOUT;
        var mesh=PredictionPackedMesh.terrainRecords(raw,forest.span()/forest.spacing());mesh.prepareGpuStorage();
        var key=new PredictionTileManager.PredictionTileKey(Level.OVERWORLD,0,0,0);
        var tile=new PredictionTileManager.PredictionTile(key,new int[0],new int[0],new ClientColumnSample[0],PredictionMesh.restored(mesh.quadCount()*4,0,mesh,null),new PredictionDepthBound((int)forest.minY(),(int)forest.maxY()),0,1,forest.span()/forest.spacing(),forest.spacing());
        var gpu=new PredictionGpuTile(key);assertTrue(gpu.ensureMesh(tile));assertNotNull(gpu.arenaSlice());
        boolean[] all=new boolean[tile.cellAxis()*tile.cellAxis()];Arrays.fill(all,true);gpu.updateCoverage(all);
        byte[] mask=new byte[all.length];Arrays.fill(mask,(byte)192);gpu.updateBoundaryCoverage(mask);
        return new Asset(forest,mesh,gpu,tile);
    }
    private static Camera camera(String scenario,int frame){
        var position=new Vec3(384,240,1152);double yaw=0;
        if(scenario.equals("turning"))yaw=Math.sin(frame*.13)*48;
        if(scenario.equals("jump90"))yaw=(frame&1)==0?0:90;
        if(scenario.equals("canopy"))position=new Vec3(384,150,900);
        if(scenario.equals("near_plane"))position=new Vec3(64+Math.sin(frame*.21)*10,145,128+Math.cos(frame*.21)*3);
        var direction=new Vec3(0,scenario.equals("near_plane")?-.03:-.12,-1).normalize();
        var model=new Matrix4f().lookAlong((float)direction.x,(float)direction.y,(float)direction.z,0,1,0).rotateY((float)Math.toRadians(yaw));
        var projection=VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(70),1,.05f,16384));
        return new Camera(position,model,projection,new Matrix4f(projection.matrix()).mul(model));
    }
    private static void configure(PredictionTerrainProgram shader,Camera camera,int depth){
        shader.use();shader.setSamplers(0,1,2,3,4);shader.setFrame(new float[]{0,0,0,0},1e7f,2e7f,0,1e7f,false,1);
        shader.setCamera(camera.model,camera.projection.matrix());shader.bindMainDepth(depth,camera.projection);shader.setOpaqueAlpha(1);
        shader.setRealCoverage(0,0,0,0);shader.setRealRenderDistance(0);shader.setHorizon(131072);shader.setDetailDistance(0);
        shader.setBatchCameraOffset((float)-camera.position.x,(float)-camera.position.y,(float)-camera.position.z);shader.setBatchTimeSeconds(100);shader.batch(true);
        int p=glGetInteger(GL_CURRENT_PROGRAM);glUniform1fv(glGetUniformLocation(p,"DirectionalTint[0]"),new float[]{1,1,1,1,1,1,1});
        glUniform1i(glGetUniformLocation(p,"ExactCoverage"),8);glUniform3f(glGetUniformLocation(p,"ExactCoverageGrid"),0,0,1);
        glUniform1i(glGetUniformLocation(p,"VanillaMask"),6);glUniform3i(glGetUniformLocation(p,"VanillaMaskSize"),1,1,1);glUniform3f(glGetUniformLocation(p,"VanillaMaskOrigin"),1e6f,1e6f,1e6f);
    }
    private static void draw(PredictionTerrainProgram terrain,int texture,int metadata,int commands,int count){
        if(compact){
            glUseProgram(compactProgram);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,commands);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,compactOutput);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,4,compactCount);
            glUniform1ui(glGetUniformLocation(compactProgram,"Count"),count);glDispatchCompute(1,1,1);
            glMemoryBarrier(GL_COMMAND_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
        }
        terrain.use();glBindTextureUnit(4,texture);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,7,metadata);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER,compact?compactOutput:commands);
        if(compact){glBindBuffer(GL_PARAMETER_BUFFER,compactCount);glMultiDrawElementsIndirectCount(GL_TRIANGLES,GL_UNSIGNED_INT,0,0,count,20);}
        else glMultiDrawElementsIndirect(GL_TRIANGLES,GL_UNSIGNED_INT,0,count,20);
    }
    private static void select(int program,int bounds,int input,int output,int state,int count,int phase){
        glUseProgram(program);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,bounds);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,input);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,output);glBindBufferBase(GL_SHADER_STORAGE_BUFFER,3,state);
        glUniform1ui(glGetUniformLocation(program,"Count"),count);glUniform1i(glGetUniformLocation(program,"Phase"),phase);
        glDispatchCompute((count+63)/64,1,1);glMemoryBarrier(GL_COMMAND_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
    }
    private static void clear(TextureTarget target,int vao,int size,String scenario,int frame){
        target.bindWrite(true);glBindVertexArray(vao);glViewport(0,0,size,size);glDisable(GL_BLEND);glDisable(GL_CULL_FACE);glDisable(GL_SCISSOR_TEST);
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GEQUAL);glDepthMask(true);glColorMask(true,true,true,true);glClearDepth(0);glClearColor(0,0,0,0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
        if(scenario.startsWith("blocked")||scenario.equals("coverage_change")&&Math.floorMod(frame,16)<8){
            int percent=scenario.equals("blocked95")?95:75;glEnable(GL_SCISSOR_TEST);glScissor(0,0,size*percent/100,size);
            glClearDepth(.05);glClear(GL_DEPTH_BUFFER_BIT);glClearDepth(0);glDisable(GL_SCISSOR_TEST);
        }
    }
    private static int buffer(List<Integer> owner,int[] values){int id=glCreateBuffers();owner.add(id);glNamedBufferData(id,values,GL_DYNAMIC_DRAW);return id;}
    private static int buffer(List<Integer> owner,ByteBuffer values){int id=glCreateBuffers();owner.add(id);glNamedBufferData(id,values,GL_STATIC_DRAW);return id;}
    private static void clearBuffer(int buffer){glClearNamedBufferData(buffer,GL_R32UI,GL_RED_INTEGER,GL_UNSIGNED_INT,new int[]{0});}
    private static int compute(String source){
        int shader=glCreateShader(GL_COMPUTE_SHADER);glShaderSource(shader,source);glCompileShader(shader);
        assertEquals(GL_TRUE,glGetShaderi(shader,GL_COMPILE_STATUS),glGetShaderInfoLog(shader));
        int program=glCreateProgram();glAttachShader(program,shader);glLinkProgram(program);glDeleteShader(shader);
        assertEquals(GL_TRUE,glGetProgrami(program,GL_LINK_STATUS),glGetProgramInfoLog(program));return program;
    }
    private static int program(GlProgram program){program.use();return glGetInteger(GL_CURRENT_PROGRAM);}
    private static void matrix(int program,Camera camera){glProgramUniformMatrix4fv(program,glGetUniformLocation(program,"Transform"),false,camera.transform.get(new float[16]));glProgramUniform3f(program,glGetUniformLocation(program,"CameraOffset"),(float)-camera.position.x,(float)-camera.position.y,(float)-camera.position.z);}
    private static long[] stats(int buffer,int count){glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);int[] data=new int[count*5];glGetNamedBufferSubData(buffer,0,data);long commands=0,quads=0;for(int i=0;i<count;i++)if(data[i*5]>0){commands++;quads+=data[i*5]/6;}return new long[]{commands,quads};}
    private static Image capture(int size){var bytes=BufferUtils.createByteBuffer(size*size*4);glReadPixels(0,0,size,size,GL_RGBA,GL_UNSIGNED_BYTE,bytes);byte[] color=new byte[bytes.remaining()];bytes.get(color);float[] depth=new float[size*size];glReadPixels(0,0,size,size,GL_DEPTH_COMPONENT,GL_FLOAT,depth);return new Image(color,depth);}
    private static void save(String name,Image frame,int size)throws Exception{
        var image=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<size;y++)for(int x=0;x<size;x++){int p=((size-1-y)*size+x)*4;image.setRGB(x,y,(Byte.toUnsignedInt(frame.color[p+3])<<24)|(Byte.toUnsignedInt(frame.color[p])<<16)|(Byte.toUnsignedInt(frame.color[p+1])<<8)|Byte.toUnsignedInt(frame.color[p+2]));}ImageIO.write(image,"png",AlgorithmSupport.output().resolve(name).toFile());
    }
    private static float[] ones(int size){float[] a=new float[size];Arrays.fill(a,1);return a;}
    private static int texture(List<Integer> owner,int unit,int width,int height,int format,int type,float[] values){int id=glCreateTextures(GL_TEXTURE_2D);owner.add(id);glTextureStorage2D(id,1,format,width,height);glTextureParameteri(id,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTextureParameteri(id,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glTextureSubImage2D(id,0,0,0,width,height,type,GL_FLOAT,values);glBindTextureUnit(unit,id);return id;}
    private static void setupTextures(List<Integer> owner)throws Exception{
        int atlas=glCreateTextures(GL_TEXTURE_2D);owner.add(atlas);glTextureStorage2D(atlas,5,GL_RGBA8,256,256);
        byte[] bytes=Files.readAllBytes(AlgorithmSupport.inputs().resolve("atlas.rgba"));var data=BufferUtils.createByteBuffer(bytes.length);data.put(bytes).flip();
        glTextureSubImage2D(atlas,0,0,0,256,256,GL_RGBA,GL_UNSIGNED_BYTE,data);glGenerateTextureMipmap(atlas);glTextureParameteri(atlas,GL_TEXTURE_MIN_FILTER,GL_NEAREST_MIPMAP_LINEAR);glTextureParameteri(atlas,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glBindTextureUnit(0,atlas);
        texture(owner,1,1,1,GL_RGBA8,GL_RGBA,new float[]{1,1,1,1});
        var table=ByteBuffer.wrap(Files.readAllBytes(AlgorithmSupport.inputs().resolve("sprite-table.bin"))).order(ByteOrder.BIG_ENDIAN);float[] values=new float[table.remaining()/4];table.asFloatBuffer().get(values);texture(owner,2,256,2,GL_RGBA32F,GL_RGBA,values);
    }
}
