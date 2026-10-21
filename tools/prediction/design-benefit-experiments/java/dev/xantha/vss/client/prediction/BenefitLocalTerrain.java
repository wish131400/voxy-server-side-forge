package dev.xantha.vss.client.prediction;

import java.util.*;

/** Experimental 2x2 terrain patches. Every internal patch ring remains exact. */
final class BenefitLocalTerrain {
    record Patch(int x, int z, int span, int start, int count, float[] errors,
                 PredictionDrawRanges[][] ranges) { }
    record Result(int[] words, List<Patch> patches, int sourceQuads) { }
    static Result build(int[] source, int axis) {
        int half=axis/2, quads=source.length/12;
        var patches=new ArrayList<Patch>();var parts=new ArrayList<int[]>();int length=0;
        for(int z=0;z<2;z++)for(int x=0;x<2;x++) {
            int minX=x*half,minZ=z*half;
            var records=new ArrayList<Integer>();
            for(int q=0;q<quads;q++) {
                int p=q*12;
                int cx=(source[p]&65535),cz=(source[p+2]&65535);
                if(cx>=minX&&cx<minX+half&&cz>=minZ&&cz<minZ+half)records.add(q);
            }
            int[] local=new int[records.size()*12];int q=0;
            for(int original:records) {
                System.arraycopy(source,original*12,local,q*12,12);
                translate(local,q*12,-minX,-minZ);local[q*12+8]=(source[original*12+8]%axis-minX)
                        +(source[original*12+8]/axis-minZ)*half;q++;
            }
            int[] first=new int[5],count={q,0,0,0,0};int[][] retained=new int[4][5];
            var built=PredictionDisplayGeometry.build(local,first,count,retained,half,new boolean[256]);
            int[] words=built.words();
            for(int p=0;p<words.length;p+=12) {
                translate(words,p,minX,minZ);
                words[p+8]=(words[p+8]%half+minX)+(words[p+8]/half+minZ)*axis;
            }
            PredictionDrawRanges[][] ranges=new PredictionDrawRanges[4][32];
            for(int tier=1;tier<4;tier++)for(int mask=0;mask<32;mask++) {
                var r=built.range(tier,0,mask,0);if(r==null)continue;
                int[] starts=r.first.clone();for(int i=0;i<starts.length;i++)starts[i]+=length/12;
                ranges[tier][mask]=new PredictionDrawRanges(starts,r.count);
            }
            patches.add(new Patch(minX,minZ,half,length/12,q,built.errors(),ranges));parts.add(words);length+=words.length;
        }
        int[] combined=new int[length];int at=0;for(int[] p:parts){System.arraycopy(p,0,combined,at,p.length);at+=p.length;}
        return new Result(combined,patches,quads);
    }
    private static void translate(int[] words,int p,int x,int z) {
        int flags=words[p+6];int scale=(flags&PredictionPackedMesh.FLAG_FINE_COORDINATES)!=0?16
                :1; // Fixture uses unit X/Z; general encodings are explicitly out of this experiment.
        if((flags >>> PredictionPackedMesh.XZ_SHIFT_BITS&15)!=0)throw new IllegalArgumentException("Non-unit fixture");
        for(int i=0;i<2;i++) {
            int a=words[p+i];words[p+i]=((a&65535)+x*scale)|((a>>>16)+x*scale)<<16;
            a=words[p+2+i];words[p+2+i]=((a&65535)+z*scale)|((a>>>16)+z*scale)<<16;
        }
    }
    static PredictionDrawRanges select(Result result, net.minecraft.world.phys.Vec3 camera,double scale,double error,boolean scope) {
        int[] first=new int[result.patches.size()*16],count=new int[first.length];int n=0;
        for(var patch:result.patches) {
            double dx=Math.max(0,Math.max(patch.x-camera.x,camera.x-patch.x-patch.span));
            double dz=Math.max(0,Math.max(patch.z-camera.z,camera.z-patch.z-patch.span));
            var lod=new PredictionDisplayLod();lod.update(patch.errors,Math.hypot(dx,dz),scale,256,scope,error);
            var r=lod.tier()==0?new PredictionDrawRanges(new int[]{patch.start},new int[]{patch.count}):patch.ranges[lod.tier()][31];
            for(int i=0;i<r.first.length;i++){first[n]=r.first[i];count[n++]=r.count[i];}
        }
        return new PredictionDrawRanges(Arrays.copyOf(first,n),Arrays.copyOf(count,n));
    }
}
