package dev.xantha.vss.client.prediction;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL45C.*;

/** Bounded GPU selection of resident opaque commands; no visibility readback. */
final class PredictionGpuCommandSelector implements AutoCloseable {
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
            layout(std430,binding=5) readonly buffer Choices { uint choices[]; };
            layout(binding=8) uniform sampler2D Pyramid;
            uniform mat4 Transform;
            uniform vec3 CameraOffset;
            uniform ivec2 ViewSize;
            uniform int Levels;
            uniform uint Count;
            uniform bool UseHiz;
            uniform bool UseLod;
            uniform float Horizon;
            uniform int PassStage;
            bool selectedLod(Box box) {
                uint node=floatBitsToUint(box.hi.w);
                if(!UseLod || node==0u) return true;
                uint choice=choices[node],flags=floatBitsToUint(box.lo.w),mask=(flags>>8u)&15u;
                if((flags&(1u<<14u))!=0u && ((choice>>3u)&7u)>((flags>>15u)&3u)) return false;
                return (choice&1u)!=0u && (mask==0u || (mask&(1u<<((choice>>1u)&3u)))!=0u);
            }
            bool withinHorizon(Box box) {
                vec3 lo=box.lo.xyz+CameraOffset,hi=box.hi.xyz+CameraOffset;
                vec2 delta=max(max(lo.xz,-hi.xz),vec2(0));
                return length(delta)<=Horizon;
            }
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
    private static final String VISIBILITY = PredictionOcclusionCuller.visibilityShader();
    private static final String SELECT = HEADER + VISIBILITY + """
            shared uint prefix[64];
            shared uint outputBase;
            void main() {
                uint lane=gl_LocalInvocationID.x;
                uint id=gl_GlobalInvocationID.x;
                bool keep=false;
                if(id<Count) keep=source[id*5u]!=0u && (PassStage<0 || int((floatBitsToUint(boxes[id].lo.w)>>12u)&3u)==PassStage)
                        && selectedLod(boxes[id]) && withinHorizon(boxes[id])
                        && !outside(boxes[id]) && !backFacing(boxes[id])
                        && (!UseHiz || !occluded(boxes[id]));
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
    private int selector;
    private int nodeSelector;
    private Uniforms uniforms;
    private static final int[] ZERO={0};
    private final float[] matrix = new float[16];
    private int previousProgram, previousStorage;
    private int texture, sampler;
    private final int[] buffers = new int[6];
    private final long[] starts = new long[6], sizes = new long[6];
    private boolean active;
    private float cameraX, cameraY, cameraZ;
    private int viewWidth, viewHeight, pyramid, levels;
    private int choices;
    private float horizon = Float.MAX_VALUE;
    private int passStage = -1;
    void stage(int stage) { passStage = stage; }
    void begin(Matrix4f transform, float x, float y, float z, int width, int height, int pyramid, int levels) {
        if (active) throw new IllegalStateException("Prediction selection already active");
        if (selector == 0) {selector=compile(SELECT);uniforms=new Uniforms(selector);}
        cameraX=x; cameraY=y; cameraZ=z;
        transform.get(matrix);
        viewWidth=width; viewHeight=height; this.pyramid=pyramid; this.levels=levels;
        previousProgram=glGetInteger(GL_CURRENT_PROGRAM);
        previousStorage=glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);
        texture=glGetIntegeri(GL_TEXTURE_BINDING_2D,8); sampler=glGetIntegeri(GL_SAMPLER_BINDING,8);
        choices=0;horizon=Float.MAX_VALUE;passStage=-1;
        for(int i=0;i<buffers.length;i++) {
            buffers[i]=glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING,i);
            starts[i]=glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START,i);
            sizes[i]=glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE,i);
        }
        active=true;
    }
    void lod(int nodes, int choices, int count, float pixelError, float fullDistance, float horizon, boolean prepare) {
        this.choices=choices;this.horizon=horizon;
        if(choices==0) return;
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,5,choices);
        if(!prepare) return;
        if(nodeSelector==0) nodeSelector=compile(PredictionGpuLodGraph.SELECT);
        int query=PredictionRenderTimings.gpuStart(PredictionRenderTimings.Stage.GPU_LOD_SELECT);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,4,nodes);
        glUseProgram(nodeSelector);
        glUniformMatrix4fv(glGetUniformLocation(nodeSelector,"Transform"),false,matrix);
        glUniform3f(glGetUniformLocation(nodeSelector,"CameraOffset"),cameraX,cameraY,cameraZ);
        glUniform2i(glGetUniformLocation(nodeSelector,"ViewSize"),viewWidth,viewHeight);
        glUniform1ui(glGetUniformLocation(nodeSelector,"NodeCount"),count);
        glUniform1f(glGetUniformLocation(nodeSelector,"PixelError"),pixelError);
        glUniform1f(glGetUniformLocation(nodeSelector,"FullDistance"),fullDistance);
        glDispatchCompute((count+63)/64,1,1);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        PredictionRenderTimings.gpuEnd(query);
    }
    void dispatch(int boxes, int source, int result, int count, int drawCount) {
        if (!active || count <= 0) throw new IllegalStateException("Prediction selection needs an active nonempty batch");
        // A previous frame can still consume this count. Order the buffer update
        // before shader atomics, then publish all pages with one final barrier.
        glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
        glClearNamedBufferData(drawCount,GL_R32UI,GL_RED_INTEGER,GL_UNSIGNED_INT,ZERO);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,boxes);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,source);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,result);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,3,drawCount);
        glBindSampler(8,0);
        glBindTextureUnit(8,pyramid);
        glUseProgram(selector);
        glUniformMatrix4fv(uniforms.transform,false,matrix);
        glUniform3f(uniforms.camera,cameraX,cameraY,cameraZ);
        glUniform2i(uniforms.view,viewWidth,viewHeight);
        glUniform1i(uniforms.levels,levels);
        glUniform1ui(uniforms.count,count);
        glUniform1i(uniforms.hiz,pyramid > 0 && levels > 0 ? 1 : 0);
        glUniform1i(uniforms.lod,choices!=0?1:0);
        glUniform1f(uniforms.horizon,horizon);
        glUniform1i(uniforms.stage,passStage);
        glDispatchCompute((count+63)/64,1,1);
    }
    private static final class Uniforms {
        final int transform,camera,view,levels,count,hiz,lod,horizon,stage;
        Uniforms(int program) {
            transform=glGetUniformLocation(program,"Transform");camera=glGetUniformLocation(program,"CameraOffset");
            view=glGetUniformLocation(program,"ViewSize");levels=glGetUniformLocation(program,"Levels");
            count=glGetUniformLocation(program,"Count");hiz=glGetUniformLocation(program,"UseHiz");
            lod=glGetUniformLocation(program,"UseLod");horizon=glGetUniformLocation(program,"Horizon");
            stage=glGetUniformLocation(program,"PassStage");
        }
    }
    void end() {
        if(!active)return;
        glMemoryBarrier(GL_COMMAND_BARRIER_BIT|GL_SHADER_STORAGE_BARRIER_BIT);
        glUseProgram(previousProgram);
        for(int i=0;i<buffers.length;i++) if(buffers[i]!=0 && sizes[i]>0)
            glBindBufferRange(GL_SHADER_STORAGE_BUFFER,i,buffers[i],starts[i],sizes[i]);
        else glBindBufferBase(GL_SHADER_STORAGE_BUFFER,i,buffers[i]);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER,previousStorage);
        int previousActive=glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE8); glBindTexture(GL_TEXTURE_2D,texture); glActiveTexture(previousActive);
        glBindSampler(8,sampler);
        active=false;
    }
    static void draw(int countBuffer,int max) {
        int previous=glGetInteger(org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_BINDING_ARB);
        glBindBuffer(org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB,countBuffer);
        try {
        if(GL.getCapabilities().OpenGL46)
            org.lwjgl.opengl.GL46C.glMultiDrawElementsIndirectCount(GL_TRIANGLES,GL_UNSIGNED_INT,0L,0L,max,20);
        else org.lwjgl.opengl.ARBIndirectParameters.glMultiDrawElementsIndirectCountARB(GL_TRIANGLES,GL_UNSIGNED_INT,0L,0L,max,20);
        } finally { glBindBuffer(org.lwjgl.opengl.ARBIndirectParameters.GL_PARAMETER_BUFFER_ARB,previous); }
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
    public void close(){end();if(selector!=0)glDeleteProgram(selector);if(nodeSelector!=0)glDeleteProgram(nodeSelector);
        selector=nodeSelector=0;uniforms=null;}
}
