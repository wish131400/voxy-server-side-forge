package dev.xantha.vss.client.prediction;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL45C.*;

/** Bounded GPU selection of resident opaque commands; no visibility readback. */
final class TraversalGpuCommandSelector implements AutoCloseable {
    static boolean enabled() {
        return Boolean.getBoolean("vss.gpuPredictionSelection") && supported();
    }
    static boolean supported() {
        var caps = GL.getCapabilities();
        return caps.OpenGL45 && (caps.OpenGL46 || caps.GL_ARB_indirect_parameters);
    }
    private static final String HEADER = """
            #version 430
            layout(local_size_x=64) in;
            struct Box { vec4 lo; vec4 hi; };
            layout(std430,binding=0) readonly buffer Bounds { Box boxes[]; };
            layout(std430,binding=1) readonly buffer InputCommands { uint source[]; };
            layout(std430,binding=2) writeonly buffer OutputCommands { uint result[]; };
            layout(std430,binding=3) buffer Counter { uint drawCount; };
            layout(std430,binding=4) readonly buffer Parents { Box parents[]; };
            layout(std430,binding=5) buffer Queue { uint groups; uint dy; uint dz; uint pad; uint queue[]; };
            layout(binding=8) uniform sampler2D Pyramid;
            uniform mat4 Transform;
            uniform vec3 CameraOffset;
            uniform ivec2 ViewSize;
            uniform int Levels;
            uniform uint Count;
            uniform bool UseHiz;
            uniform bool Hierarchical;
            uniform bool Queued;
            bool outside(Box box) {
                uint planes = 63u;
                for(int i=0;i<8;i++) {
                    vec3 p=vec3((i&1)==0?box.lo.x:box.hi.x,(i&2)==0?box.lo.y:box.hi.y,(i&4)==0?box.lo.z:box.hi.z)+CameraOffset;
                    vec4 c=Transform*vec4(p,1);
                    if(any(isnan(c))||any(isinf(c))) return false;
                    float slack=max(1e-4,abs(c.w)*1e-5);
                    planes &= (c.x < -c.w-slack?1u:0u)|(c.x > c.w+slack?2u:0u)
                            |(c.y < -c.w-slack?4u:0u)|(c.y > c.w+slack?8u:0u)
                            |(c.z < -c.w-slack?16u:0u)|(c.z > c.w+slack?32u:0u);
                }
                return planes != 0u;
            }
            """;
    // Share the exact conservative Hi-Z and direction predicates with the
    // existing path. Changing camera visibility must never reuse old depth.
    private static final String VISIBILITY = TraversalCandidateOcclusionCuller.visibilityShader();
    private static final String SELECT = HEADER + VISIBILITY + """
            shared uint prefix[64];
            shared uint outputBase;
            shared bool rejected;
            void main() {
                uint lane=gl_LocalInvocationID.x;
                uint group=Queued?queue[gl_WorkGroupID.x]:gl_WorkGroupID.x;
                if(lane==0u) rejected=!Queued && Hierarchical &&
                        (outside(parents[group]) || (UseHiz && occluded(parents[group])));
                barrier();
                if(rejected) return;
                uint id=group*64u+lane;
                bool keep=false;
                if(id<Count) keep=source[id*5u]!=0u && !outside(boxes[id]) && !backFacing(boxes[id])
                        && !(UseHiz && occluded(boxes[id]));
                prefix[lane]=keep?1u:0u;
                barrier();
                for(uint step=1u;step<64u;step*=2u) {
                    uint add=lane>=step?prefix[lane-step]:0u;
                    barrier();
                    prefix[lane]+=add;
                    barrier();
                }
                if(lane==0u) outputBase=atomicAdd(drawCount,prefix[63]);
                barrier();
                if(keep) for(uint i=0u;i<5u;i++) result[(outputBase+prefix[lane]-1u)*5u+i]=source[id*5u+i];
            }
            """;
    private static final String TRAVERSE = HEADER + VISIBILITY + """
            void main() {
                uint id=gl_GlobalInvocationID.x;
                if(id >= (Count+63u)/64u) return;
                if(outside(parents[id]) || (UseHiz && occluded(parents[id]))) return;
                uint slot=atomicAdd(groups,1u);
                queue[slot]=id;
            }
            """;
    private int selector, traverser;
    private Uniforms selectorUniforms, traverserUniforms;
    private static final int[] ZERO={0}, PRIME={0,1,1,0};
    private final float[] matrix = new float[16];
    private int previousProgram, previousStorage, previousDispatch, previousParameter;
    private int texture, sampler;
    private final int[] buffers = new int[6];
    private final long[] starts = new long[6], sizes = new long[6];
    private boolean active;
    private Matrix4f transform;
    private float cameraX, cameraY, cameraZ;
    private int viewWidth, viewHeight, pyramid, levels;
    void begin(Matrix4f transform, float x, float y, float z, int width, int height, int pyramid, int levels) {
        if (selector == 0) {selector=compile(SELECT);selectorUniforms=new Uniforms(selector);}
        this.transform=transform; cameraX=x; cameraY=y; cameraZ=z;
        transform.get(matrix);
        viewWidth=width; viewHeight=height; this.pyramid=pyramid; this.levels=levels;
        previousProgram=glGetInteger(GL_CURRENT_PROGRAM);
        previousStorage=glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);
        previousDispatch=glGetInteger(GL_DISPATCH_INDIRECT_BUFFER_BINDING);
        previousParameter=glGetInteger(org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_BINDING_ARB);
        texture=glGetIntegeri(GL_TEXTURE_BINDING_2D,8); sampler=glGetIntegeri(GL_SAMPLER_BINDING,8);
        for(int i=0;i<6;i++) {
            buffers[i]=glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING,i);
            starts[i]=glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START,i);
            sizes[i]=glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE,i);
        }
        active=true;
    }
    void dispatch(int boxes, int source, int result, int parents, int count, int drawCount, int queue, int mode) {
        // The previous draw/dispatch may still read these buffers. Clear through
        // the GL buffer-update path and publish it before shader atomics.
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        glClearNamedBufferData(drawCount,GL_R32UI,GL_RED_INTEGER,GL_UNSIGNED_INT,ZERO);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,boxes);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,source);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,result);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,3,drawCount);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,4,parents);
        glBindSampler(8,0);
        if(pyramid != 0) glBindTextureUnit(8,pyramid);
        if(mode==2) {
            if(traverser==0) {traverser=compile(TRAVERSE);traverserUniforms=new Uniforms(traverser);}
            glNamedBufferSubData(queue,0,PRIME);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,5,queue);
            uniforms(traverser,count,true,true);
            glDispatchCompute(((count+63)/64+63)/64,1,1);
            glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT|GL_COMMAND_BARRIER_BIT);
            uniforms(selector,count,true,true);
            glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER,queue);
            glDispatchComputeIndirect(0);
        } else {
            uniforms(selector,count,mode==1,false);
            glDispatchCompute((count+63)/64,1,1);
        }
    }
    private void uniforms(int program,int count,boolean hierarchical,boolean queued) {
        glUseProgram(program);
        var u=program==selector?selectorUniforms:traverserUniforms;
        glUniformMatrix4fv(u.transform,false,matrix);
        glUniform3f(u.camera,cameraX,cameraY,cameraZ);
        glUniform2i(u.view,viewWidth,viewHeight);
        glUniform1i(u.levels,levels);
        glUniform1i(u.hiz,pyramid!=0?1:0);
        glUniform1i(u.hierarchy,hierarchical?1:0);
        glUniform1i(u.queued,queued?1:0);
        glUniform1ui(u.count,count);
    }
    private static final class Uniforms {
        final int transform,camera,view,levels,hiz,hierarchy,queued,count;
        Uniforms(int program) {
            transform=glGetUniformLocation(program,"Transform");camera=glGetUniformLocation(program,"CameraOffset");
            view=glGetUniformLocation(program,"ViewSize");levels=glGetUniformLocation(program,"Levels");
            hiz=glGetUniformLocation(program,"UseHiz");hierarchy=glGetUniformLocation(program,"Hierarchical");
            queued=glGetUniformLocation(program,"Queued");count=glGetUniformLocation(program,"Count");
        }
    }
    void end() {
        if(!active)return;
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
        glUseProgram(previousProgram);
        for(int i=0;i<6;i++) if(buffers[i]!=0 && sizes[i]>0)
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER,i,buffers[i],starts[i],sizes[i]);
        else glBindBufferBase(GL_SHADER_STORAGE_BUFFER,i,buffers[i]);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,previousStorage);
        glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER,previousDispatch);
        glBindBuffer(org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB,previousParameter);
        int previousActive=glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE8); glBindTexture(GL_TEXTURE_2D,texture); glActiveTexture(previousActive);
        glBindSampler(8,sampler);
        active=false;
    }
    static void draw(int countBuffer,int max) {
        int previous=glGetInteger(org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_BINDING_ARB);
        glBindBuffer(org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB,countBuffer);
        if(GL.getCapabilities().OpenGL46)
            org.lwjgl.opengl.GL46C.glMultiDrawElementsIndirectCount(GL_TRIANGLES,GL_UNSIGNED_INT,0L,0L,max,20);
        else org.lwjgl.opengl.ARBIndirectParameters.glMultiDrawElementsIndirectCountARB(GL_TRIANGLES,GL_UNSIGNED_INT,0L,0L,max,20);
        glBindBuffer(org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB,previous);
    }
    private static int compile(String source) {
        int shader=glCreateShader(GL_COMPUTE_SHADER),program=0;
        try {
            glShaderSource(shader,source);glCompileShader(shader);
            if(glGetShaderi(shader,GL_COMPILE_STATUS)==GL_FALSE)throw new IllegalStateException(glGetShaderInfoLog(shader));
            program=glCreateProgram();glAttachShader(program,shader);glLinkProgram(program);
            if(glGetProgrami(program,GL_LINK_STATUS)==GL_FALSE)throw new IllegalStateException(glGetProgramInfoLog(program));
            return program;
        } catch(RuntimeException failure){if(program!=0)glDeleteProgram(program);throw failure;}
        finally{glDeleteShader(shader);}
    }
    public void close(){end();if(selector!=0)glDeleteProgram(selector);if(traverser!=0)glDeleteProgram(traverser);}
}
