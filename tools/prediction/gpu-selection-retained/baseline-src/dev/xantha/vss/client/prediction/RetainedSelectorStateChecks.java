package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL45C.*;
import static org.lwjgl.opengl.ARBIndirectParameters.*;
import java.util.Arrays;
import org.joml.Matrix4f;

/** Checks a partially occupied workgroup, counter reuse and every state binding touched by selection. */
final class RetainedSelectorStateChecks {
    static void run() {
        int[] buffers = new int[12];
        for (int i=0;i<buffers.length;i++) { buffers[i]=glCreateBuffers(); glNamedBufferData(buffers[i],4096,GL_STREAM_DRAW); }
        int pyramid=glCreateTextures(GL_TEXTURE_2D), sentinel=glCreateTextures(GL_TEXTURE_2D), sampler=glGenSamplers();
        glTextureStorage2D(pyramid,1,GL_R32F,128,128); glTextureStorage2D(sentinel,1,GL_R32F,128,128);
        glTextureParameteri(pyramid,GL_TEXTURE_MIN_FILTER,GL_NEAREST); glTextureParameteri(pyramid,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        float[] depth=new float[128*128]; glTextureSubImage2D(pyramid,0,0,0,128,128,GL_RED,GL_FLOAT,depth);
        int source=buffers[4], boxes=buffers[5], output=buffers[6], counter=buffers[7];
        int[] commands=new int[65*5]; float[] bounds=new float[65*8];
        for(int q=0;q<65;q++) {
            commands[q*5]=6; commands[q*5+1]=1; commands[q*5+4]=q;
            bounds[q*8]=-1; bounds[q*8+1]=-1; bounds[q*8+2]=-11;
            bounds[q*8+4]=1; bounds[q*8+5]=1; bounds[q*8+6]=-9;
        }
        bounds[64*8]=1000; bounds[64*8+4]=1002;
        glNamedBufferData(source,commands,GL_STATIC_DRAW); glNamedBufferData(boxes,bounds,GL_STATIC_DRAW);
        int alignment=glGetInteger(GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
        for(int i=0;i<4;i++) glBindBufferRange(GL_SHADER_STORAGE_BUFFER,i,buffers[i],alignment,128);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,buffers[8]);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER,buffers[9]);
        glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER,buffers[10]);
        glBindBuffer(GL_PARAMETER_BUFFER_ARB,buffers[11]);
        glBindImageTexture(0,sentinel,0,false,0,GL_READ_ONLY,GL_R32F);
        glBindTextureUnit(8,sentinel); glBindSampler(8,sampler); glActiveTexture(GL_TEXTURE3); glUseProgram(0);
        var transform=VssLodProjection.of(new Matrix4f().perspective((float)Math.toRadians(70),1,.05f,4096)).matrix();
        try(var selector=new PredictionGpuCommandSelector()) {
            for(int round=0;round<2;round++) {
                glNamedBufferSubData(counter,0,new int[]{999});
                selector.begin(transform,0,0,0,512,512,pyramid,1);
                selector.dispatch(boxes,source,output,65,counter); selector.end();
                int[] count=new int[1]; glGetNamedBufferSubData(counter,0,count);
                assertEquals(round==0?64:0,count[0],"counter must reset after the previous dispatch");
                if(round==0) {
                    int[] filtered=new int[64*5]; glGetNamedBufferSubData(output,0,filtered);
                    int[] ids=new int[64];
                    for(int i=0;i<64;i++) { assertEquals(6,filtered[i*5]); assertEquals(1,filtered[i*5+1]); ids[i]=filtered[i*5+4]; }
                    Arrays.sort(ids); for(int i=0;i<64;i++) assertEquals(i,ids[i],"every retained source command appears exactly once");
                }
                assertEquals(0,glGetInteger(GL_CURRENT_PROGRAM)); assertEquals(GL_TEXTURE3,glGetInteger(GL_ACTIVE_TEXTURE));
                assertEquals(sentinel,glGetIntegeri(GL_TEXTURE_BINDING_2D,8)); assertEquals(sampler,glGetIntegeri(GL_SAMPLER_BINDING,8));
                assertEquals(sentinel,glGetIntegeri(GL_IMAGE_BINDING_NAME,0)); assertEquals(GL_READ_ONLY,glGetIntegeri(GL_IMAGE_BINDING_ACCESS,0));
                assertEquals(buffers[8],glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING));
                assertEquals(buffers[9],glGetInteger(GL_DRAW_INDIRECT_BUFFER_BINDING));
                assertEquals(buffers[10],glGetInteger(GL_DISPATCH_INDIRECT_BUFFER_BINDING));
                assertEquals(buffers[11],glGetInteger(GL_PARAMETER_BUFFER_BINDING_ARB));
                for(int i=0;i<4;i++) { assertEquals(buffers[i],glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING,i));
                    assertEquals(alignment,glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START,i)); assertEquals(128,glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE,i)); }
                assertEquals(GL_NO_ERROR,glGetError());
                Arrays.fill(depth,1); glTextureSubImage2D(pyramid,0,0,0,128,128,GL_RED,GL_FLOAT,depth);
            }
        } finally {
            glBindBuffer(GL_PARAMETER_BUFFER_ARB,0); glBindBuffer(GL_DRAW_INDIRECT_BUFFER,0); glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER,0);
            for(int i=0;i<4;i++) glBindBufferBase(GL_SHADER_STORAGE_BUFFER,i,0);
            glBindImageTexture(0,0,0,false,0,GL_READ_ONLY,GL_R32F); glBindSampler(8,0);
            glDeleteSamplers(sampler); glDeleteTextures(pyramid); glDeleteTextures(sentinel);
            for(int buffer:buffers) glDeleteBuffers(buffer);
        }
    }
}
