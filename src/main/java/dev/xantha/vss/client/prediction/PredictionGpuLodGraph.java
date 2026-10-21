package dev.xantha.vss.client.prediction;

import java.util.*;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

/** Stable GPU node identities. A coarse alternative is armed only by a complete, source-matched family. */
final class PredictionGpuLodGraph {
    static final String SELECT = """
            #version 430
            layout(local_size_x=64) in;
            struct Node { vec4 lo; vec4 hi; uvec4 state; vec4 errors; };
            layout(std430,binding=4) readonly buffer Nodes { Node nodes[]; };
            layout(std430,binding=5) buffer Choices { uint choices[]; };
            uniform mat4 Transform;
            uniform vec3 CameraOffset;
            uniform ivec2 ViewSize;
            uniform uint NodeCount;
            uniform float PixelError;
            uniform float FullDistance;
            float projectedSize(Node n,float displacement) {
                if(isnan(displacement) || isinf(displacement)) return 1e30;
                vec3 lo=n.lo.xyz+CameraOffset,hi=n.hi.xyz+CameraOffset;
                vec3 delta=max(max(lo,-hi),vec3(0));
                if(length(delta.xz)<=FullDistance) return 1e30;
                float minW=1e30;vec2 maxClip=vec2(0);
                for(int i=0;i<8;i++) {
                    vec3 p=vec3((i&1)==0?lo.x:hi.x,(i&2)==0?lo.y:hi.y,(i&4)==0?lo.z:hi.z);
                    vec4 c=Transform*vec4(p,1);
                    if(any(isnan(c))||any(isinf(c))||c.w<=0.0) return 1e30;
                    minW=min(minW,c.w);maxClip=max(maxClip,abs(c.xy/c.w));
                }
                float rw=length(vec3(Transform[0].w,Transform[1].w,Transform[2].w));
                vec2 row=vec2(length(vec3(Transform[0].x,Transform[1].x,Transform[2].x)),
                              length(vec3(Transform[0].y,Transform[1].y,Transform[2].y)));
                float denominator=minW-displacement*rw;
                if(denominator<=0.0) return 1e30;
                vec2 error=displacement*(row+maxClip*rw)/denominator*vec2(ViewSize)*0.5;
                return max(error.x,error.y);
            }
            bool acceptable(Node n,float displacement) { return projectedSize(n,displacement)<=PixelError*0.8; }
            uint groundCover(Node n,uint previous) {
                if((n.state.w&8u)==0u) return 0u;
                float size=projectedSize(n,2.0);
                uint result=0u;
                for(uint tier=1u;tier<=4u;tier++) {
                    float threshold=16.0/float(1u<<(tier-1u));
                    if(size<=threshold*(tier<=previous?1.0:0.8)) result=tier;
                }
                return result;
            }
            bool coarse(Node n) { return n.state.x!=0u && acceptable(n,n.lo.w); }
            uint representation(Node n) {
                if((n.state.w&1u)==0u) return 0u;
                uint result=0u;
                vec3 lo=n.lo.xyz+CameraOffset,hi=n.hi.xyz+CameraOffset;
                float horizontal=length(max(max(lo.xz,-hi.xz),vec2(0)));
                float pixels=max(length(vec3(Transform[0].x,Transform[1].x,Transform[2].x))*float(ViewSize.x),
                                 length(vec3(Transform[0].y,Transform[1].y,Transform[2].y))*float(ViewSize.y))*0.5;
                float radius=max(256.0,max(FullDistance,min(4096.0,pixels*0.5)));
                for(uint tier=1u;tier<=3u;tier++) {
                    float error=n.errors[int(tier-1u)];
                    if((n.state.w&2u)!=0u) error=max(error,float(1u<<tier));
                    if((n.state.w&4u)!=0u && horizontal<=radius*float(1u<<(tier-1u))+max(32.0,radius/16.0)) continue;
                    if(acceptable(n,error)) result=tier;
                }
                return result;
            }
            void main() {
                uint id=gl_GlobalInvocationID.x;
                if(id>=NodeCount) return;
                Node n=nodes[id];
                bool keep=n.state.z!=0u && (n.state.y==0u || n.state.x!=0u) && !coarse(n);
                if(n.state.x!=0u) keep=coarse(n);
                uint parent=floatBitsToUint(n.hi.w);
                for(int depth=0;parent!=0u && depth<24;depth++) {
                    if(parent>=NodeCount) { keep=false;break; }
                    Node ancestor=nodes[parent];
                    if(coarse(ancestor)) { keep=false;break; }
                    parent=floatBitsToUint(ancestor.hi.w);
                }
                uint history=floatBitsToUint(n.errors.w);
                uint previous=(choices[id]>>6u)==history?((choices[id]>>3u)&7u):0u;
                choices[id]=(history<<6u)|(keep?(1u|(representation(n)<<1u)|(groundCover(n,previous)<<3u)):0u);
            }
            """;
    record Node(PredictionRenderer.Draw draw, int index, int parent, boolean children, boolean alternative,
                float error, int representations, float error1, float error2, float error3) { }
    private final IdentityHashMap<PredictionGpuTile, Integer> slots = new IdentityHashMap<>();
    private final BitSet allocated = new BitSet();
    private List<Node> nodes = List.of();
    private final IdentityHashMap<PredictionGpuTile, Node> byGpu = new IdentityHashMap<>();
    private List<PredictionRenderer.Draw> source;
    private Object ranges;
    private long gpuEpoch = -1;
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet members = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

    void update(List<PredictionRenderer.Draw> draws,
                IdentityHashMap<PredictionRenderer.Draw, PredictionDrawRanges> selected) {
        long nextGpu = PredictionGpuTile.submissionEpoch();
        // Topology, source proofs and representation ranges are immutable.
        // Mutable metadata is handled by scene record versions; only an actual
        // member residency change can invalidate this graph behind the same input.
        if (source == draws && ranges == selected
                && (gpuEpoch == nextGpu || !PredictionSubmissionChanges.GPU.affects(gpuEpoch, nextGpu, members))) {
            gpuEpoch = nextGpu; return;
        }
        var live = new IdentityHashMap<PredictionGpuTile, PredictionRenderer.Draw>();
        var byKey = new HashMap<PredictionTileKey, PredictionRenderer.Draw>();
        for (var draw : draws) if (draw.gpu().arenaSlice() != null && selected.get(draw) != null) {
            live.put(draw.gpu(), draw);
            if (!draw.seam()) byKey.put(draw.tile().key(), draw);
        }
        for (var it = slots.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            if (!live.containsKey(entry.getKey())) { allocated.clear(entry.getValue()); it.remove(); }
        }
        for (var gpu : live.keySet()) if (!slots.containsKey(gpu)) {
            int slot = allocated.nextClearBit(1); allocated.set(slot); slots.put(gpu, slot);
        }
        members.clear(); for (var gpu : live.keySet()) members.add(gpu.submissionId());
        var parents = new IdentityHashMap<PredictionGpuTile, Integer>();
        var armed = Collections.newSetFromMap(new IdentityHashMap<PredictionGpuTile, Boolean>());
        var ordered = new ArrayList<>(live.values());
        ordered.sort(Comparator.comparingInt(draw -> draw.tile().key().lod()));
        for (var draw : ordered) {
            var proof = draw.tile().mesh().parentLod;
            if (!draw.coarseProxy() || proof == null || !proof.sealedEdges()
                    || !Float.isFinite((float) proof.error()) || !safe(draw, selected)) continue;
            var children = new PredictionTileManager.PredictionTile[4];
            var childDraws = new PredictionRenderer.Draw[4];
            boolean complete = true;
            for (int i = 0; i < 4; i++) {
                var child = byKey.get(proof.child(i).key()); childDraws[i] = child;
                if (child == null || !safe(child, selected) || child.coarseProxy() && !armed.contains(child.gpu())) {
                    complete = false; break;
                }
                children[i] = child.tile();
            }
            if (!complete || !proof.describes(children)) continue;
            armed.add(draw.gpu());
            for (var child : childDraws) parents.put(child.gpu(), slots.get(draw.gpu()));
        }
        var result = new ArrayList<Node>(live.size()); byGpu.clear();
        for (var draw : ordered) {
            var proof = draw.tile().mesh().parentLod;
            var packed = draw.gpu().packed();
            boolean variants = !draw.seam() && !draw.coarseProxy() && draw.completeOwnership()
                    && !draw.gpu().morphActive(System.nanoTime())
                    && contains(selected.get(draw), packed.gpuRepresentations().union);
            int flags = variants ? 1 | (packed.hasVegetationLod() ? 2 : 0) | (packed.hasAquaticLod() ? 4 : 0) | (packed.hasGroundCoverLod() ? 8 : 0) : 0;
            float[] errors = packed.displayErrors();
            var node = new Node(draw, slots.get(draw.gpu()), parents.getOrDefault(draw.gpu(), 0),
                    armed.contains(draw.gpu()), draw.coarseProxy(), proof == null ? Float.POSITIVE_INFINITY : (float) proof.error(),
                    flags, error(packed, errors, 1), error(packed, errors, 2), error(packed, errors, 3));
            result.add(node); byGpu.put(draw.gpu(), node);
        }
        if (!result.equals(nodes)) nodes = List.copyOf(result);
        source = draws; ranges = selected; gpuEpoch = nextGpu;
    }
    private static boolean safe(PredictionRenderer.Draw draw,
                                IdentityHashMap<PredictionRenderer.Draw, PredictionDrawRanges> selected) {
        var packed = draw.gpu().packed();
        var ranges = selected.get(draw);
        return !draw.seam() && (draw.completeOwnership() || draw.coarseProxy()) && !draw.tile().scopeOnly()
                && packed != null && packed.waterQuadCount() == 0 && !packed.hasFeatures
                && !draw.gpu().morphActive(System.nanoTime()) && ranges != null
                && contains(ranges, packed.drawRanges(false, VssLodFaceGroup.ALL));
    }
    private static float error(PredictionPackedMesh packed, float[] errors, int tier) {
        // An unavailable terrain variant falls back to the exact source ranges.
        if (!packed.hasTerrainDisplayLod()) return 0;
        return errors == null || tier >= errors.length || Float.isNaN(errors[tier]) ? Float.POSITIVE_INFINITY : errors[tier];
    }
    private static boolean contains(PredictionDrawRanges outer, PredictionDrawRanges inner) {
        int r = 0;
        for (int i = 0; i < inner.first.length; i++) {
            int at = inner.first[i], stop = at + inner.count[i];
            while (r < outer.first.length && outer.first[r] + outer.count[r] <= at) r++;
            for (int k = r; at < stop && k < outer.first.length && outer.first[k] <= at; k++)
                at = Math.max(at, outer.first[k] + outer.count[k]);
            if (at < stop) return false;
        }
        return true;
    }
    List<Node> nodes() { return nodes; }
    Node node(PredictionGpuTile gpu) { return byGpu.get(gpu); }
    int capacity() { return allocated.length(); }
    void clear() { slots.clear(); allocated.clear(); nodes = List.of(); byGpu.clear(); members.clear(); source = null; ranges = null; gpuEpoch = -1; }
}
