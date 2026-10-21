from pathlib import Path

root = Path(__file__).resolve().parents[3]
baseline = root / 'tools/prediction/cluster-render-experiments/baseline/PredictionOcclusionCuller.java.txt'
target = root / 'tools/prediction/cluster-render-experiments/src/dev/xantha/vss/client/prediction'
source = baseline.read_text(encoding='utf-8-sig')
source = source.replace('PredictionOcclusionCuller', 'ClusterCandidateOcclusionCuller')
source = source.replace('uniform bool Hierarchical;', '''uniform bool Hierarchical;
            uniform bool Spatial;
            uniform bool Fast;
            uniform bool Deep;
            uniform bool Compact;
            layout(std430,binding=5) readonly buffer Order { uint order[]; };
            layout(std430,binding=6) buffer Counts { uint counts[]; };
            shared uint masks[2];''')
source = source.replace('parentHidden=Hierarchical && occluded(parents[gl_WorkGroupID.x]);', '''uint groups=(Count+63u)/64u;
                    parentHidden=Deep && occluded(parents[groups+gl_WorkGroupID.x/16u]);
                    parentHidden=parentHidden || (Hierarchical && occluded(parents[gl_WorkGroupID.x]));
                    masks[0]=0u; masks[1]=0u;''')
start = source.index('                uint id=gl_GlobalInvocationID.x;')
end = source.index('                barrier();\n                if(Sample && lane==0u)', start)
source = source[:start] + '''                uint ordinal=gl_GlobalInvocationID.x;
                uint id=ordinal<Count && Spatial ? order[ordinal] : ordinal;
                bool keep=false;
                if(Fast && parentHidden && !Sample) {
                    if(ordinal<Count) result[id*5u]=0u;
                    return;
                }
                if(ordinal<Count) {
                    uint start=id*5u;
                    bool hide=parentHidden || backFacing(boxes[id]) || occluded(boxes[id]);
                    keep=!hide && source[start]!=0u;
                    if(!Compact) {
                        result[start]=hide?0u:source[start];
                        if(!Fast || !hide) for(uint i=1u;i<5u;i++) result[start+i]=source[start+i];
                    }
                    if(keep && Compact) atomicOr(masks[lane/32u],1u<<(lane%32u));
                    if(Sample) {
                        atomicAdd(testedCount,1u); atomicAdd(testedQuads,source[start]/6u);
                        if(hide) { atomicAdd(hiddenCount,1u); atomicAdd(rejectedQuads,source[start]/6u); }
                    }
                }
                barrier();
                if(Compact) {
                    uint total=bitCount(masks[0])+bitCount(masks[1]);
                    if(lane==0u) counts[gl_WorkGroupID.x+1u]=total;
                    if(keep) {
                        uint prefix=uint(bitCount(masks[lane/32u]&((1u<<(lane%32u))-1u)));
                        if(lane>=32u) prefix+=uint(bitCount(masks[0]));
                        uint dest=(gl_WorkGroupID.x*64u+prefix)*5u;
                        for(uint i=0u;i<5u;i++) result[dest+i]=source[id*5u+i];
                    }
                }
''' + source[end:]
source = source.replace('    private int reduce, filter, pyramid, width, height, levels;', '''    private static final String PACK = """
            #version 430
            layout(local_size_x=64) in;
            layout(std430,binding=0) readonly buffer Input { uint source[]; };
            layout(std430,binding=1) writeonly buffer Output { uint result[]; };
            layout(std430,binding=2) buffer Counts { uint counts[]; };
            uniform uint Groups;
            shared uint offset;
            void main() {
                uint group=gl_WorkGroupID.x, lane=gl_LocalInvocationID.x;
                if(lane==0u) {
                    uint prefix=0u;
                    for(uint i=0u;i<group;i++) prefix+=counts[i+1u];
                    offset=prefix;
                    if(group+1u==Groups) counts[0]=prefix+counts[group+1u];
                }
                barrier();
                if(lane<counts[group+1u]) {
                    uint src=(group*64u+lane)*5u, dst=(offset+lane)*5u;
                    for(uint i=0u;i<5u;i++) result[dst+i]=source[src+i];
                }
            }
            """;
    private int pack, groupsUniform;
    private boolean fast;
    void fast(boolean enabled) { fast=enabled; }
    private int spatialUniform, fastUniform, deepUniform, compactUniform;
    private int reduce, filter, pyramid, width, height, levels;''')
source = source.replace('hierarchicalUniform=glGetUniformLocation(filter,"Hierarchical");', '''hierarchicalUniform=glGetUniformLocation(filter,"Hierarchical");
                spatialUniform=glGetUniformLocation(filter,"Spatial");
                fastUniform=glGetUniformLocation(filter,"Fast");
                deepUniform=glGetUniformLocation(filter,"Deep");
                compactUniform=glGetUniformLocation(filter,"Compact");
                pack=compile(PACK); groupsUniform=glGetUniformLocation(pack,"Groups");''')
needle = '        if (!filtering) return input;\n        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,bounds);'
source = source.replace(needle, '''        return dispatch(bounds,input,output,commands,parents,0,false,0);
    }
    int dispatch(int bounds, int input, int output, int commands, int parents, int indices, boolean deep, int counts) {
        if (!filtering) return input;
        glUseProgram(filter);
        glUniform1i(spatialUniform,indices!=0?1:0);
        glUniform1i(fastUniform,fast?1:0);
        glUniform1i(deepUniform,deep?1:0);
        glUniform1i(compactUniform,counts!=0?1:0);
        if(indices!=0)glBindBufferBase(GL_SHADER_STORAGE_BUFFER,5,indices);
        if(counts!=0)glBindBufferBase(GL_SHADER_STORAGE_BUFFER,6,counts);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,bounds);''')
source = source.replace('    void endFiltering() {', '''    void pack(int input, int output, int counts, int commands) {
        glUseProgram(pack);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,0,input);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,1,output);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER,2,counts);
        int groups=(commands+63)/64;
        glUniform1ui(groupsUniform,groups);glDispatchCompute(groups,1,1);
    }

    void endFiltering() {''')
source = source.replace('if(filter!=0)glDeleteProgram(filter);', 'if(filter!=0)glDeleteProgram(filter); if(pack!=0)glDeleteProgram(pack);')
source = source.replace('new int[5]', 'new int[7]').replace('new long[5]', 'new long[7]').replace('i<5;i++', 'i<7;i++')
(target / 'ClusterCandidateOcclusionCuller.java').write_text(source, encoding='utf-8')

skip = baseline.read_text(encoding='utf-8-sig')
skip = skip.replace('PredictionOcclusionCuller', 'ClusterBatchSkipOcclusionCuller')
skip = skip.replace('uniform bool Hierarchical;', '''uniform bool Hierarchical;
            uniform bool SkipEmpty;
            layout(std430,binding=5) buffer DrawCount { uint drawCount; };
            shared uint anyVisible;''')
skip = skip.replace('testedCount=0u; hiddenCount=0u; testedQuads=0u; rejectedQuads=0u;',
                    'testedCount=0u; hiddenCount=0u; testedQuads=0u; rejectedQuads=0u; anyVisible=0u;')
skip = skip.replace('bool hide=parentHidden || backFacing(boxes[id]) || occluded(boxes[id]);', '''bool hide=parentHidden || backFacing(boxes[id]) || occluded(boxes[id]);
                    if(SkipEmpty && !hide && source[start]!=0u) atomicOr(anyVisible,1u);''')
skip = skip.replace('if(Sample && lane==0u) {', 'if(SkipEmpty && lane==0u && anyVisible!=0u) atomicExchange(drawCount,Count);\n                if(Sample && lane==0u) {')
skip = skip.replace('    private int reduce, filter, pyramid, width, height, levels;', '''    private int skipEmptyUniform, counter;
    void counter(int buffer) { counter=buffer; }
    private int reduce, filter, pyramid, width, height, levels;''')
skip = skip.replace('hierarchicalUniform=glGetUniformLocation(filter,"Hierarchical");',
                    'hierarchicalUniform=glGetUniformLocation(filter,"Hierarchical"); skipEmptyUniform=glGetUniformLocation(filter,"SkipEmpty");')
skip = skip.replace('        glUniform1ui(countUniform,commands);', '''        glUniform1i(skipEmptyUniform,counter!=0?1:0);
        if(counter!=0) {
            glClearNamedBufferData(counter,GL_R32UI,GL_RED_INTEGER,GL_UNSIGNED_INT,new int[]{0});
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER,5,counter);
        }
        glUniform1ui(countUniform,commands);''')
skip = skip.replace('GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT',
                    'GL_COMMAND_BARRIER_BIT | GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT')
skip = skip.replace('new int[5]', 'new int[6]').replace('new long[5]', 'new long[6]').replace('i<5;i++', 'i<6;i++')
(target / 'ClusterBatchSkipOcclusionCuller.java').write_text(skip, encoding='utf-8')
