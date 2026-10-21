package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL46C.*;
import java.nio.*;
import java.util.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

/** Alternating full-pass A/B, including current-frame depth reduction and command packing. */
@EnabledIfSystemProperty(named="vss.gpuTests",matches="true")
class PredictionClusterArchitectureGpuTest {
    @Test void spatialHierarchyAndPackingAreConservativeAndMeasured() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        assertTrue(glfwInit());glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        long window=glfwCreateWindow(256,256,"VSS cluster A/B",0,0);assertNotEquals(0,window);
        var rows=new ArrayList<String>(); rows.add("commands,scenario,round,mode,gpu_ns,cpu_ns,visible_commands");
        try {
            glfwMakeContextCurrent(window);GL.createCapabilities();
            if(!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread())com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
            System.out.println("CLUSTER_GPU "+glGetString(GL_RENDERER)+" "+glGetString(GL_VERSION)
                    +" indirect_count="+(GL.getCapabilities().OpenGL46||GL.getCapabilities().GL_ARB_indirect_parameters)
                    +" nv_mesh_shader="+GL.getCapabilities().GL_NV_mesh_shader);
            if(GL.getCapabilities().GL_NV_mesh_shader) System.out.println("CLUSTER_MESH_LIMITS vertices="
                    +glGetInteger(org.lwjgl.opengl.NVMeshShader.GL_MAX_MESH_OUTPUT_VERTICES_NV)+" primitives="
                    +glGetInteger(org.lwjgl.opengl.NVMeshShader.GL_MAX_MESH_OUTPUT_PRIMITIVES_NV));
            for(int count:new int[]{2048,8192})try(var f=new Fixture(count)) {
                for(int scenario=0;scenario<4;scenario++) {
                    f.configure(scenario);
                    byte[] expected=null;float[] depths=null;
                    for(int mode=0;mode<10;mode++) {
                        if(mode==5)continue;
                        f.render(mode);
                        if(mode==0){expected=f.pixels();depths=f.depths();}
                        else {assertArrayEquals(expected,f.pixels(),"color scenario="+scenario+" mode="+mode);
                            assertArrayEquals(depths,f.depths(),"depth scenario="+scenario+" mode="+mode);}
                        if(mode==3||mode==4)assertEquals(f.fixedVisible(),f.compactVisible(),"GPU count must match nonzero source commands");
                    }
                    long[][] gpu=new long[10][81],cpu=new long[10][81];
                    int[] measured=Boolean.getBoolean("vss.clusterAllModes")?new int[]{0,1,2,3,4,5,6,7,8,9}:new int[]{0,9,5};
                    for(int round=-24;round<81;round++)for(int order=0;order<measured.length;order++) {
                        int mode=measured[Math.floorMod(round+order,measured.length)]; f.clear();glFinish();
                        long start=System.nanoTime();glBeginQuery(GL_TIME_ELAPSED,f.query);
                        f.submit(mode);glEndQuery(GL_TIME_ELAPSED);long cpuTime=System.nanoTime()-start;
                        long elapsed=glGetQueryObjectui64(f.query,GL_QUERY_RESULT);
                        if(round>=0){gpu[mode][round]=elapsed;cpu[mode][round]=cpuTime;
                            rows.add(count+","+scenario+","+round+","+mode+","+elapsed+","+cpuTime+","+(mode==3||mode==4||mode==6||mode==9?f.compactVisible():-1));}
                    }
                    for(int mode:measured)System.out.printf(Locale.ROOT,
                            "CLUSTER_AB commands=%d scenario=%s mode=%s GPU_ms=%.6f CPU_ms=%.6f%n",count,
                            new String[]{"hidden","half","open","holes"}[scenario],
                            new String[]{"legacy","spatial","deep","pack-spatial","pack-deep","direct","pack-ordered","fast-spatial","fast-ordered","skip-empty-batch"}[mode],
                            CompletionGpuFixture.median(gpu[mode])/1e6,CompletionGpuFixture.median(cpu[mode])/1e6);
                }
            }
            assertEquals(GL_NO_ERROR,glGetError());
        } finally {glfwDestroyWindow(window);glfwTerminate();}
        CompletionGpuFixture.save("cluster-experiment.csv",rows);
    }

    private static final class Fixture implements AutoCloseable {
        final int commands,size=256,depth,color,fbo,vao,ebo,boxes,input,fixed,temp,packed,counts,loose,parents,indices,query;
        final Matrix4f transform=VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(70),1,.05f,4096)).matrix();
        final ClusterLegacyOcclusionCuller legacy=new ClusterLegacyOcclusionCuller();
        final ClusterCandidateOcclusionCuller candidate=new ClusterCandidateOcclusionCuller();
        final ClusterBatchSkipOcclusionCuller skip=new ClusterBatchSkipOcclusionCuller();
        final GlProgram shader;
        Fixture(int commands) {
            this.commands=commands;
            depth=glCreateTextures(GL_TEXTURE_2D);color=glCreateTextures(GL_TEXTURE_2D);fbo=glCreateFramebuffers();
            glTextureStorage2D(depth,1,GL_DEPTH_COMPONENT32F,size,size);glTextureStorage2D(color,1,GL_RGBA8,size,size);
            glTextureParameteri(depth,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTextureParameteri(depth,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
            glNamedFramebufferTexture(fbo,GL_DEPTH_ATTACHMENT,depth,0);glNamedFramebufferTexture(fbo,GL_COLOR_ATTACHMENT0,color,0);
            assertEquals(GL_FRAMEBUFFER_COMPLETE,glCheckNamedFramebufferStatus(fbo,GL_FRAMEBUFFER));
            vao=glCreateVertexArrays();ebo=glCreateBuffers();glBindVertexArray(vao);
            int[] elements=new int[commands*256*6],corners={0,1,2,0,2,3};
            for(int q=0;q<commands*256;q++)for(int c=0;c<6;c++)elements[q*6+c]=q*4+corners[c];
            glNamedBufferData(ebo,elements,GL_STATIC_DRAW);glVertexArrayElementBuffer(vao,ebo);
            int[] cmd=new int[commands*5];
            var b=BufferUtils.createByteBuffer(commands*32);
            for(int c=0;c<commands;c++) {
                cmd[c*5]=256*6;cmd[c*5+1]=1;cmd[c*5+2]=c*256*6;cmd[c*5+4]=c;
                int slot=(c*977)&(commands-1);
                float x=(slot%64)/64f*26-13,y=(slot/64)/(commands/64f)*26-13;
                b.putFloat(x-2).putFloat(y-2).putFloat(-22).putInt(64);
                b.putFloat(x+26/64f+2).putFloat(y+26/(commands/64f)+2).putFloat(-18).putFloat(0);
            }
            b.flip();var layout=new ClusterCommandLayout(b);
            boxes=buffer(b);input=buffer(cmd);fixed=empty(commands*20L);temp=empty(commands*20L);packed=empty(commands*20L);
            counts=empty(((commands+63)/64+1)*4L);parents=buffer(layout.parents);indices=buffer(layout.indices);
            float[] old=new float[(commands+63)/64*8];
            for(int p=0;p<old.length;p+=8)for(int a=0;a<3;a++){old[p+a]=Float.POSITIVE_INFINITY;old[p+4+a]=Float.NEGATIVE_INFINITY;}
            for(int c=0;c<commands;c++)for(int a=0;a<3;a++) {
                int p=c/64*8;old[p+a]=Math.min(old[p+a],b.getFloat(c*32+a*4));old[p+4+a]=Math.max(old[p+4+a],b.getFloat(c*32+16+a*4));
            }
            loose=buffer(old);query=glGenQueries();
            shader=GlProgram.link("cluster-fixture", """
                    #version 460 core
                    uniform mat4 Transform;
                    uniform int Commands;
                    flat out vec3 Tint;
                    void main() {
                        int slot=(gl_BaseInstance*977)&(Commands-1),corner=gl_VertexID%4;
                        vec2 p=(vec2(slot%64,slot/64)+vec2(corner==1||corner==2?1:0,corner>=2?1:0))
                            /vec2(64,Commands/64)*26.0-13.0;
                        Tint=vec3(float((gl_BaseInstance%251)+1)/255.0,.5,1);
                        gl_Position=Transform*vec4(p,-20,1);
                    }
                    """, """
                    #version 460 core
                    flat in vec3 Tint;
                    out vec4 Color;
                    void main() { Color=vec4(Tint,1); }
                    """);
            shader.use();glUniformMatrix4fv(shader.uniform("Transform"),false,transform.get(new float[16]));
            glUniform1i(shader.uniform("Commands"),commands);
        }
        void configure(int scenario) {
            float[] data=new float[size*size];
            for(int y=0;y<size;y++)for(int x=0;x<size;x++)data[y*size+x]=switch(scenario){
                case 0->.1f;case 1->x<size/2?.1f:0;case 2->0;default->(x/32+y/32)%2==0?.1f:0;};
            glTextureSubImage2D(depth,0,0,0,size,size,GL_DEPTH_COMPONENT,GL_FLOAT,data);
        }
        void clear() {
            glBindFramebuffer(GL_FRAMEBUFFER,fbo);glBindVertexArray(vao);glViewport(0,0,size,size);
            glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GEQUAL);glDepthMask(false);glDisable(GL_CULL_FACE);glDisable(GL_BLEND);
            glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
        }
        void render(int mode) {clear();submit(mode);}
        void submit(int mode) {
            int result=input;
            if(mode==0) {
                assertTrue(legacy.begin(depth,size,size,transform));legacy.rebuild();legacy.beginFiltering();
                result=legacy.dispatch(boxes,input,fixed,commands,loose);legacy.endFiltering();
            } else if(mode==9) {
                skip.counter(counts);assertTrue(skip.begin(depth,size,size,transform));skip.rebuild();skip.beginFiltering();
                result=skip.dispatch(boxes,input,fixed,commands,loose);skip.endFiltering();
            } else if(mode!=5) {
                boolean compact=mode==3||mode==4||mode==6;
                boolean spatial=mode!=6&&mode!=8;
                candidate.fast(mode==7||mode==8);
                assertTrue(candidate.begin(depth,size,size,transform));candidate.rebuild();candidate.beginFiltering();
                result=candidate.dispatch(boxes,input,compact?temp:fixed,commands,spatial?parents:loose,spatial?indices:0,mode==2||mode==4,compact?counts:0);
                if(compact){glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);candidate.pack(temp,packed,counts,commands);result=packed;}
                candidate.endFiltering();
            }
            shader.use();glBindBuffer(GL_DRAW_INDIRECT_BUFFER,result);
            if(mode==3||mode==4||mode==6||mode==9){glBindBuffer(GL_PARAMETER_BUFFER,counts);glMultiDrawElementsIndirectCount(GL_TRIANGLES,GL_UNSIGNED_INT,0L,0L,commands,20);glBindBuffer(GL_PARAMETER_BUFFER,0);}
            else glMultiDrawElementsIndirect(GL_TRIANGLES,GL_UNSIGNED_INT,0L,commands,20);
            if(mode==0)legacy.end();else if(mode==9)skip.end();else if(mode!=5)candidate.end();
        }
        int compactVisible(){int[] n={0};glGetNamedBufferSubData(counts,0,n);return n[0];}
        int fixedVisible(){int[] n=new int[commands*5];glGetNamedBufferSubData(fixed,0,n);int total=0;for(int c=0;c<commands;c++)if(n[c*5]!=0)total++;return total;}
        byte[] pixels(){var b=BufferUtils.createByteBuffer(size*size*4);glReadPixels(0,0,size,size,GL_RGBA,GL_UNSIGNED_BYTE,b);var a=new byte[b.remaining()];b.get(a);return a;}
        float[] depths(){var b=BufferUtils.createFloatBuffer(size*size);glReadPixels(0,0,size,size,GL_DEPTH_COMPONENT,GL_FLOAT,b);var a=new float[b.remaining()];b.get(a);return a;}
        static int buffer(int[] data){int id=glCreateBuffers();glNamedBufferData(id,data,GL_STATIC_DRAW);return id;}
        static int buffer(float[] data){int id=glCreateBuffers();glNamedBufferData(id,data,GL_STATIC_DRAW);return id;}
        static int buffer(ByteBuffer data){int id=glCreateBuffers();glNamedBufferData(id,data,GL_STATIC_DRAW);return id;}
        static int empty(long bytes){int id=glCreateBuffers();glNamedBufferData(id,bytes,GL_DYNAMIC_DRAW);return id;}
        @Override public void close(){legacy.close();candidate.close();skip.close();shader.close();glBindFramebuffer(GL_FRAMEBUFFER,0);glBindVertexArray(0);
            glBindBuffer(GL_DRAW_INDIRECT_BUFFER,0);glBindBuffer(GL_PARAMETER_BUFFER,0);for(int i=0;i<7;i++)glBindBufferBase(GL_SHADER_STORAGE_BUFFER,i,0);
            for(int b:new int[]{ebo,boxes,input,fixed,temp,packed,counts,loose,parents,indices})glDeleteBuffers(b);
            glDeleteQueries(query);glDeleteVertexArrays(vao);glDeleteFramebuffers(fbo);glDeleteTextures(depth);glDeleteTextures(color);}
    }
}
