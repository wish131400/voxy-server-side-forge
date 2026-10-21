package dev.xantha.vss.client.prediction;

import static org.lwjgl.opengl.GL46C.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.*;
import org.lwjgl.opengl.NVMeshShader;

/** Test-only NV backend using the production decoder and fragment shader. No game integration. */
final class ClusterMeshProgram {
    static final int QUADS = 32;
    static PredictionTerrainProgram create() throws Exception {
        String vertex=source("TERRAIN_VERTEX"),fragment=PredictionTerrainProgram.batchFragment(source("TERRAIN_FRAGMENT"));
        vertex=PredictionTerrainProgram.batchVertex(vertex);
        var outputs=new LinkedHashMap<String,String>();
        var matcher=Pattern.compile("(?m)^\\s*(flat\\s+)?out\\s+(\\w+)\\s+(\\w+)\\s*;").matcher(vertex);
        while(matcher.find())outputs.put(matcher.group(3),(matcher.group(1)==null?"":matcher.group(1))+"out "+matcher.group(2));
        var primitive=new HashSet<String>();
        outputs.forEach((name,declaration)->{if(declaration.startsWith("flat"))primitive.add(name);});
        for(var output:outputs.entrySet()) {
            String name=output.getKey();
            vertex=vertex.replaceAll("\\b"+name+"\\b","meshLocal_"+name);
        }
        matcher=Pattern.compile("(?m)^\\s*(flat\\s+)?out\\s+(\\w+)\\s+meshLocal_(\\w+)\\s*;").matcher(vertex);
        StringBuffer declarations=new StringBuffer();
        while(matcher.find())matcher.appendReplacement(declarations,Matcher.quoteReplacement("\n"+matcher.group(2)+" meshLocal_"+matcher.group(3)+";\n"
                +(primitive.contains(matcher.group(3))?"perprimitiveNV ":"")+outputs.get(matcher.group(3))+" "+matcher.group(3)+"[];"));
        matcher.appendTail(declarations);vertex=declarations.toString();
        vertex=vertex.replace("#version 460 core","#version 460 core\n#extension GL_NV_mesh_shader : require\n"
                +"layout(local_size_x=32) in;\nlayout(triangles,max_vertices=128,max_primitives=64) out;\n"
                +"uniform int MeshFirstQuad;\nuniform int MeshQuadCount;\nint MeshVertex;\nint MeshRecord;\nvec4 MeshPosition;");
        vertex=vertex.replace("gl_VertexID","MeshVertex").replace("gl_BaseInstance","MeshRecord").replace("gl_Position","MeshPosition");
        vertex=vertex.replace("void main() {","void decodeVertex() {");
        var main=new StringBuilder("\nvoid main() {\nuint tasks=(uint(MeshQuadCount)+31u)/32u;\n"
                +"MeshRecord=int(gl_WorkGroupID.x/tasks);\nuint first=(gl_WorkGroupID.x%tasks)*32u;\n"
                +"uint quads=min(32u,uint(MeshQuadCount)-first);\n"
                +"for(uint q=gl_LocalInvocationID.x;q<quads;q+=32u)for(uint c=0u;c<4u;c++) {\nuint v=q*4u+c;\n"
                +"MeshVertex=(MeshFirstQuad+int(first))*4+int(v);\ndecodeVertex();\n"
                +"gl_MeshVerticesNV[v].gl_Position=MeshPosition;\n");
        for(String name:outputs.keySet()) {
            if(primitive.contains(name))main.append("if(c>=2u)").append(name).append("[q*2u+c-2u]=meshLocal_").append(name).append(";\n");
            else main.append(name).append("[v]=meshLocal_").append(name).append(";\n");
        }
        main.append("}\nfor(uint q=gl_LocalInvocationID.x;q<quads;q+=32u) {\n"
                +"uint v=q*4u,p=q*6u;\ngl_PrimitiveIndicesNV[p]=v;\ngl_PrimitiveIndicesNV[p+1u]=v+1u;\n"
                +"gl_PrimitiveIndicesNV[p+2u]=v+2u;\ngl_PrimitiveIndicesNV[p+3u]=v;\n"
                +"gl_PrimitiveIndicesNV[p+4u]=v+2u;\ngl_PrimitiveIndicesNV[p+5u]=v+3u;\n}\n"
                +"if(gl_LocalInvocationID.x==0u)gl_PrimitiveCountNV=quads*2u;\n}\n");
        vertex+=main;
        int mesh=compile(NVMeshShader.GL_MESH_SHADER_NV,vertex),frag=compile(GL_FRAGMENT_SHADER,fragment),id=glCreateProgram();
        glAttachShader(id,mesh);glAttachShader(id,frag);glLinkProgram(id);glDeleteShader(mesh);glDeleteShader(frag);
        if(glGetProgrami(id,GL_LINK_STATUS)==0){String log=glGetProgramInfoLog(id);glDeleteProgram(id);throw new IllegalStateException(log);}
        var glConstructor=GlProgram.class.getDeclaredConstructor(int.class);glConstructor.setAccessible(true);
        GlProgram replacement=glConstructor.newInstance(id);
        var program=PredictionTerrainProgram.createBatch();
        var handle=PredictionTerrainProgram.class.getDeclaredField("program");handle.setAccessible(true);
        GlProgram previous=(GlProgram)handle.get(program);
        var map=GlProgram.class.getDeclaredField("uniforms");map.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,Integer> names=(Map<String,Integer>)map.get(previous);
        var locations=new HashMap<Integer,String>();names.forEach((name,location)->{if(location>=0)locations.put(location,name);});
        for(var field:PredictionTerrainProgram.class.getDeclaredFields())if(field.getType()==int.class&&Modifier.isFinal(field.getModifiers())) {
            field.setAccessible(true);String name=locations.get(field.getInt(program));
            if(name!=null)field.setInt(program,replacement.uniform(name));
        }
        handle.set(program,replacement);previous.close();return program;
    }
    private static String source(String name)throws Exception {var f=PredictionTerrainProgram.class.getDeclaredField(name);f.setAccessible(true);return (String)f.get(null);}
    private static int compile(int type,String source){int shader=glCreateShader(type);glShaderSource(shader,source);glCompileShader(shader);
        if(glGetShaderi(shader,GL_COMPILE_STATUS)==0){String log=glGetShaderInfoLog(shader);glDeleteShader(shader);throw new IllegalStateException(log);}return shader;}
}
