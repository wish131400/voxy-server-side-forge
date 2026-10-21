package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL45C.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.BufferUtils;

/** Hidden OpenGL fixture. Synchronous query reads are test-only. */
final class CompletionGpuFixture implements AutoCloseable {
    final int size=256, commands=1024, quads=commands*256;
    final long window;
    final int depth,color,fbo,vao,ebo,boxes,input,output,timer;
    final Matrix4f transform=VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(70),1,.05f,4096)).matrix();
    final GlProgram shader;
    CompletionGpuFixture() {
        ClientTerrainSamplerTest.bootstrapMinecraft();assertTrue(glfwInit());glfwWindowHint(GLFW_VISIBLE,GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR,4);glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR,6);
        window=glfwCreateWindow(size,size,"VSS completion test",0,0);assertNotEquals(0,window);
        glfwMakeContextCurrent(window);GL.createCapabilities();com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
        System.out.println("COMPLETION_GPU "+glGetString(GL_RENDERER)+" "+glGetString(GL_VERSION));
        depth=glCreateTextures(GL_TEXTURE_2D);color=glCreateTextures(GL_TEXTURE_2D);fbo=glCreateFramebuffers();
        glTextureStorage2D(depth,1,GL_DEPTH_COMPONENT32F,size,size);glTextureStorage2D(color,1,GL_RGBA8,size,size);
        glTextureParameteri(depth,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTextureParameteri(depth,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glNamedFramebufferTexture(fbo,GL_DEPTH_ATTACHMENT,depth,0);glNamedFramebufferTexture(fbo,GL_COLOR_ATTACHMENT0,color,0);
        assertEquals(GL_FRAMEBUFFER_COMPLETE,glCheckNamedFramebufferStatus(fbo,GL_FRAMEBUFFER));
        vao=glCreateVertexArrays();ebo=glCreateBuffers();glBindVertexArray(vao);
        int[] indices=new int[quads*6],corners={0,1,2,0,2,3};
        for(int q=0;q<quads;q++)for(int c=0;c<6;c++)indices[q*6+c]=q*4+corners[c];
        glNamedBufferData(ebo,indices,GL_STATIC_DRAW);glVertexArrayElementBuffer(vao,ebo);
        int[] cmd=new int[commands*5];
        for(int c=0;c<commands;c++){cmd[c*5]=256*6;cmd[c*5+1]=1;cmd[c*5+2]=c*256*6;}
        input=glCreateBuffers();output=glCreateBuffers();boxes=glCreateBuffers();
        glNamedBufferData(input,cmd,GL_STATIC_DRAW);glNamedBufferData(output,cmd.length*4L,GL_STREAM_DRAW);
        glNamedBufferData(boxes,commands*32L,GL_STATIC_DRAW);timer=glGenQueries();
        shader=GlProgram.link("completion-fixture", """
                #version 430
                uniform mat4 Transform;
                uniform int Scenario;
                flat out int Visible;
                void main() {
                    int corner=gl_VertexID%4,q=gl_VertexID/4;
                    vec2 p=(vec2(q%512,q/512)+vec2(corner==1||corner==2?1:0,corner>=2?1:0))/512.0*2.0-1.0;
                    Visible=Scenario==0 || Scenario==2 && (q/256)%2==0 ? 0 : 1;
                    gl_Position=Transform*vec4(p,-20,1);
                }
                """, """
                #version 430
                flat in int Visible;
                out vec4 Color;
                void main() { if(Visible==0)discard;Color=vec4(0,1,0,1); }
                """);
        configure(1);
    }
    void configure(int scenario) {
        var data=BufferUtils.createByteBuffer(commands*32);
        for(int c=0;c<commands;c++) {
            int mask=scenario==0?16:scenario==2&&c%2==0?16:scenario==3?64:32;
            data.putFloat(-1).putFloat(-1).putFloat(-21).putInt(mask);
            data.putFloat(1).putFloat(1).putFloat(-20).putFloat(0);
        }
        data.flip();glNamedBufferSubData(boxes,0,data);
        shader.use();glUniformMatrix4fv(shader.uniform("Transform"),false,transform.get(new float[16]));
        glUniform1i(shader.uniform("Scenario"),scenario);
    }
    void clear(boolean hidden) {
        glBindFramebuffer(GL_FRAMEBUFFER,fbo);glBindVertexArray(vao);glViewport(0,0,size,size);
        glEnable(GL_DEPTH_TEST);glDepthFunc(GL_GEQUAL);glDepthMask(true);glDisable(GL_CULL_FACE);glDisable(GL_BLEND);
        glClearColor(0,0,0,1);glClearDepth(hidden?.1:0);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);
    }
    void draw(int buffer) { shader.use();glBindBuffer(GL_DRAW_INDIRECT_BUFFER,buffer);glMultiDrawElementsIndirect(GL_TRIANGLES,GL_UNSIGNED_INT,0L,commands,20); }
    byte[] pixels() { var bytes=BufferUtils.createByteBuffer(size*size*4);glReadPixels(0,0,size,size,GL_RGBA,GL_UNSIGNED_BYTE,bytes);
        var copy=new byte[bytes.remaining()];bytes.get(copy);return copy; }
    float[] depths() { var values=BufferUtils.createFloatBuffer(size*size);glReadPixels(0,0,size,size,GL_DEPTH_COMPONENT,GL_FLOAT,values);
        var copy=new float[values.remaining()];values.get(copy);return copy; }
    static long median(long[] values){var copy=values.clone();Arrays.sort(copy);return copy[copy.length/2];}
    static void save(String name,List<String> rows) throws Exception {
        var path=Path.of(System.getProperty("vss.followupResults", "build/prediction-completion-results"));Files.createDirectories(path);Files.write(path.resolve(name),rows);
    }
    @Override public void close() {
        shader.close();glBindFramebuffer(GL_FRAMEBUFFER,0);glBindVertexArray(0);glUseProgram(0);
        for(int i=0;i<5;i++)glBindBufferBase(GL_SHADER_STORAGE_BUFFER,i,0);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER,0);glDeleteBuffers(ebo);glDeleteBuffers(boxes);glDeleteBuffers(input);glDeleteBuffers(output);
        glDeleteQueries(timer);glDeleteVertexArrays(vao);glDeleteFramebuffers(fbo);glDeleteTextures(depth);glDeleteTextures(color);
        assertEquals(GL_NO_ERROR,glGetError());glfwDestroyWindow(window);glfwTerminate();
    }
}
