package dev.xantha.vss.client.prediction;

import it.unimi.dsi.fastutil.ints.IntArrayList;

/** Range-only composition of shared terrain, exact exceptions and voxel layers.
 * Coordinates live exclusively in PackedMesh's compressible upload payload. */
final class PredictionFeatureVoxelRanges {
    final PredictionDisplayGeometry.Result base;
    final PredictionVegetationRanges source;
    final PredictionDrawRanges removed;
    final PredictionVegetationRanges[] proxies;
    final float[] errors;
    final int[] terrainTiers;
    private final PredictionDrawRanges[][][] cache = new PredictionDrawRanges[4][4*4*PredictionGroundCoverLod.TIERS][];
    private long retainedBytes;

    PredictionFeatureVoxelRanges(PredictionDisplayGeometry.Result base, PredictionVegetationRanges source,
            PredictionDrawRanges removed, PredictionVegetationRanges[] proxies, float[] errors,int[] terrainTiers) {
        this.base=base;this.source=source;this.removed=removed;this.proxies=proxies;this.errors=errors;
        this.terrainTiers=terrainTiers;
        retainedBytes=512L+removed.first.length*8L+cache.length*(16L+cache[0].length*4L);
        if(base.ranges()!=null)for(var tier:base.ranges())if(tier!=null)
            for(var range:tier)if(range!=null)retainedBytes+=64L+range.first.length*8L;
    }

    PredictionDisplayGeometry.Result result(int[] words) {
        var ranges=new PredictionDrawRanges[4][32];
        for(int tier=1;tier<4;tier++)for(int visible=0;visible<32;visible++)ranges[tier][visible]=range(tier,0,visible,0,0);
        return new PredictionDisplayGeometry.Result(words,ranges,errors,base.vegetation(),base.sharedFeatures(),this);
    }

    PredictionDrawRanges original(int tier,int aquatic,int visible,int plants,int cover) {
        var range=terrainTiers[tier]==0?null:base.range(terrainTiers[tier],aquatic,visible,plants,cover);
        return range==null?source.range(visible,plants,aquatic,cover):range;
    }

    PredictionDrawRanges range(int tier,int aquatic,int visible,int plants,int cover) {
        if(proxies[tier]==null) return original(tier,aquatic,visible,plants,cover);
        int key=(plants*PredictionGroundCoverLod.TIERS+cover)*4+aquatic;
        var cached=cache[tier][key];
        if(cached==null){cache[tier][key]=cached=new PredictionDrawRanges[32];retainedBytes+=16L+cached.length*4L;}
        if(cached[visible]!=null)return cached[visible];
        var original=original(tier,aquatic,visible,plants,cover);
        var starts=new IntArrayList();var counts=new IntArrayList();int removedAt=0;
        for(int r=0;r<original.first.length;r++) {
            int at=original.first[r],end=at+original.count[r];
            while(removedAt<removed.first.length&&removed.first[removedAt]+removed.count[removedAt]<=at)removedAt++;
            for(int i=removedAt;i<removed.first.length&&removed.first[i]<end;i++) {
                append(starts,counts,at,Math.min(end,removed.first[i])-at);
                at=Math.max(at,removed.first[i]+removed.count[i]);
            }
            append(starts,counts,at,end-at);
        }
        var proxy=proxies[tier].range(visible,0,0,0);
        for(int r=0;r<proxy.first.length;r++)append(starts,counts,proxy.first[r],proxy.count[r]);
        var result=new PredictionDrawRanges(starts.toIntArray(),counts.toIntArray());
        retainedBytes+=64L+result.first.length*8L;
        return cached[visible]=result;
    }
    private static void append(IntArrayList first,IntArrayList count,int at,int length) {
        if(length<=0)return;
        int n=first.size();
        if(n>0&&first.getInt(n-1)+count.getInt(n-1)==at)count.set(n-1,count.getInt(n-1)+length);
        else{first.add(at);count.add(length);}
    }
    long retainedHeapBytes(){
        long bytes=retainedBytes;
        for(int t=1;t<4;t++)if(proxies[t]!=null&&proxies[t]!=proxies[t-1])bytes+=proxies[t].retainedHeapBytes();
        return bytes;
    }
}
