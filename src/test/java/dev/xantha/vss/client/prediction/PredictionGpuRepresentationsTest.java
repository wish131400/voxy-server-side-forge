package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PredictionGpuRepresentationsTest {
    @Test void interleavedTrunksAndLeavesDoNotSplitCommandsAtEveryFace() {
        int[] words = PredictionDisplayGeometryTest.grid(PredictionPackedMesh.FLAG_MODEL_UV);
        int quads = words.length / PredictionPackedMesh.STRIDE_INTS;
        for (int q = 0; q < quads; q++) {
            int p = q * PredictionPackedMesh.STRIDE_INTS + 6;
            words[p] = (words[p] & ~65535) | (q & 1);
        }
        var bounds = new PredictionMeshletBounds(words, new boolean[]{false, true});
        assertEquals(1, bounds.renderEnds.length, "mixed forest meshlets share the vegetation timing lane");
        var full = range(0, quads);
        var plan = new PredictionGpuRepresentations(new PredictionDrawRanges[]{full, full, full, full});
        var segments = plan.clip(full, bounds, false);
        assertEquals((quads + 255) / 256, segments.masks().length,
                "alternating material classes must remain meshlet-sized indirect commands");
        assertEquals(bits(full), bits(segments.ranges()));
        for (int kind : segments.kinds()) assertEquals(1, kind);
        for (int mask : segments.masks()) assertEquals(15, mask);
    }

    @Test void overlappingCohortsRemainExactForEveryGpuChoiceAndCoverageWithdrawal() {
        var tiers = new PredictionDrawRanges[]{
                range(0,400,600,40), range(0,100,200,60,650,20),
                range(0,100,300,40,650,20), range(0,100,350,30,650,20)};
        var plan = new PredictionGpuRepresentations(tiers);
        var expected = new BitSet();
        for(var tier:tiers)expected.or(bits(tier));
        assertEquals(expected,bits(plan.union));
        int[] words=PredictionDisplayGeometryTest.grid(PredictionPackedMesh.FLAG_MODEL_UV);
        var bounds=new PredictionMeshletBounds(words);
        var selected=range(20,30,90,200,360,40,620,50);
        var segments=plan.clip(selected,bounds,false);
        for(int tier=0;tier<4;tier++) {
            var actual=new BitSet();
            for(int i=0;i<segments.masks().length;i++)if((segments.masks()[i]&1<<tier)!=0)
                actual.set(segments.ranges().first[i],segments.ranges().first[i]+segments.ranges().count[i]);
            var target=bits(tiers[tier]);target.and(bits(selected));
            assertEquals(target,actual,"no missing or duplicated source quads in representation "+tier);
        }
        for(int i=0;i<segments.masks().length;i++) {
            int a=segments.ranges().first[i],b=a+segments.ranges().count[i]-1;
            assertEquals(a/PredictionMeshletBounds.QUADS,b/PredictionMeshletBounds.QUADS);
        }
        var seam=plan.clip(selected,bounds,true);
        for(int kind:seam.kinds())assertEquals(2,kind);
    }
    private static PredictionDrawRanges range(int... values) {
        int[] f=new int[values.length/2],c=f.clone();
        for(int i=0;i<f.length;i++){f[i]=values[i*2];c[i]=values[i*2+1];}
        return new PredictionDrawRanges(f,c);
    }
    private static BitSet bits(PredictionDrawRanges ranges) {
        var result=new BitSet();
        for(int i=0;i<ranges.first.length;i++)result.set(ranges.first[i],ranges.first[i]+ranges.count[i]);
        return result;
    }
}
