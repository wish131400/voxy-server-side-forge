package dev.xantha.vss.client.prediction;

import java.util.*;

/** Experimental coarse occupied canopy voxels with exact trunk geometry. */
final class BenefitCanopyProxy {
    record Voxel(int x,int y,int z,int sprite) { }
    static List<Voxel> forest(int shape) {
        var result=new LinkedHashMap<String,Voxel>();
        for(int z=8;z<128;z+=16)for(int x=8;x<128;x+=16) {
            int low=84,high=88;
            for(int y=80;y<high+4;y++)put(result,new Voxel(x,y,z,2));
            for(int layer=0;layer<(shape==0?1:3);layer++) {
                int cy=high+layer*5;
                for(int dy=0;dy<3;dy++)for(int dz=-5;dz<=5;dz++)for(int dx=-5;dx<=5;dx++) {
                    if(dx*dx+dz*dz>25||dx==0&&dz==0)continue;
                    // Isolated sparse edges and modded material variation are negative cases.
                    if(shape==2&&((dx+dz+dy)&1)==0)continue;
                    int row=shape==2&&Math.floorMod(dx+dz,7)==0?3:1;
                    put(result,new Voxel(x+dx,cy+dy,z+dz,row));
                }
            }
        }
        return new ArrayList<>(result.values());
    }
    private static void put(Map<String,Voxel> voxels,Voxel v){voxels.put(v.x+":"+v.y+":"+v.z,v);}
    static List<Voxel> reduce(List<Voxel> source,int step) {
        if(step==1)return source;
        var trunks=new ArrayList<Voxel>();var votes=new LinkedHashMap<String,int[]>();
        for(var v:source) {
            if(v.sprite==2){trunks.add(v);continue;}
            int x=Math.floorDiv(v.x,step)*step,y=Math.floorDiv(v.y,step)*step,z=Math.floorDiv(v.z,step)*step;
            int[] counts=votes.computeIfAbsent(x+":"+y+":"+z,k->new int[]{x,y,z,0,0});counts[v.sprite==1?3:4]++;
        }
        for(int[] v:votes.values())trunks.add(new Voxel(v[0],v[1],v[2],v[3]>=v[4]?1:3));
        return trunks;
    }
    static int[] mesh(List<Voxel> source,int step) {
        var occupied=new HashSet<String>();for(var v:source)if(v.sprite!=2)occupied.add(v.x+":"+v.y+":"+v.z);
        var records=new ArrayList<int[]>();
        int[][] delta={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
        for(var v:source)for(int dir=0;dir<6;dir++) {
            int size=v.sprite==2?1:step;int[] d=delta[dir];
            if(v.sprite!=2&&occupied.contains((v.x+d[0]*size)+":"+(v.y+d[1]*size)+":"+(v.z+d[2]*size)))continue;
            int[] w=new int[12];int normal=dir/2,axis=normal==0?1:normal==1?0:2;boolean pos=(dir&1)!=0;
            int[] a={v.x,v.y,v.z}, b=a.clone(), c=a.clone(), e=a.clone();
            int u=(normal+1)%3,t=(normal+2)%3;
            if(pos)a[normal]=b[normal]=c[normal]=e[normal]=a[normal]+size;
            b[u]+=size;c[u]+=size;c[t]+=size;e[t]+=size;
            int[][] points={a,b,c,e};
            for(int corner=0;corner<4;corner++) {
                int shift=(corner&1)*16;
                w[corner/2]|=points[corner][0]<<shift;w[2+corner/2]|=points[corner][2]<<shift;
                w[4+corner/2]|=(32768+points[corner][1]*4)<<shift;
            }
            w[6]=v.sprite|(axis<<24)|(axis==0&&!pos?PredictionPackedMesh.FLAG_BANK:axis!=0&&pos?PredictionPackedMesh.FLAG_FACE_POSITIVE:0)
                    |(v.sprite!=2?PredictionPackedMesh.FLAG_CUTOUT:0);
            for(int i:new int[]{7,9,10,11})w[i]=v.sprite==2?0x997744:v.sprite==1?0x668844:0x55aa33;
            w[8]=Math.min(127,v.x)+Math.min(127,v.z)*128;w[10]|=PredictionPackedMesh.FEATURE;
            records.add(w);
        }
        int[] result=new int[records.size()*12];int at=0;for(int[] w:records){System.arraycopy(w,0,result,at,12);at+=12;}return result;
    }
}
