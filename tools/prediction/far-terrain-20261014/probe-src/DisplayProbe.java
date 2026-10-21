package dev.xantha.vss.client.prediction;
import java.io.*; import java.util.*;
final class PredictionPackedMesh {
 static final int STRIDE_INTS=12,FLAG_UNSHADED=1<<27,FLAG_MODEL_UV=1<<21,
 FLAG_LOD_TEXTURE_SCALE=1<<31,FLAGS_FLUID_SHIFT=22,FLAG_CUTOUT=1<<26,
 FEATURE=1<<24,DISPLAY_APPROXIMATE=1<<25,PREVIEW=1<<26,FLAGS_AXIS_SHIFT=24,
 FLAG_FINE_COORDINATES=1<<20,FLAG_FACE_POSITIVE=1<<30,XZ_SHIFT_BITS=16;
}
final class PredictionMeshCompression { static final int MAX_BYTES=64*1024*1024; }
final class VssLodSpriteTable {
 static boolean[] approx=new boolean[256],aquatic=new boolean[256],land=new boolean[256],plants=new boolean[256];
 static boolean[] displayApproximationSprites(){return approx.clone();}
 static boolean[] aquaticSprites(){return aquatic.clone();}
 static boolean[] landPlantSprites(){return land.clone();}
 static boolean[] vegetationSprites(){return plants.clone();}
}
public class DisplayProbe {
 static int group(int f,boolean cross){if(cross&&(f&1<<27)!=0&&(f&1<<21)==0)return 0; int a=f>>>24&3;boolean p=(f&1<<30)!=0;return a==1?(p?4:3):a==2?(p?2:1):0;}
 public static void main(String[] args)throws Exception {
  var in=new DataInputStream(new BufferedInputStream(System.in,1048576));
  for(int i=0;i<256;i++){VssLodSpriteTable.approx[i]=in.readBoolean();VssLodSpriteTable.aquatic[i]=in.readBoolean();VssLodSpriteTable.land[i]=in.readBoolean();VssLodSpriteTable.plants[i]=in.readBoolean();}
  while(true){int id;try{id=in.readInt();}catch(EOFException e){break;}
   int length=in.readInt(),terrain=in.readInt(),water=in.readInt(),axis=in.readInt(),spacing=in.readInt();int[] w=new int[length];for(int i=0;i<length;i++)w[i]=in.readInt();
   boolean cross=true;for(int q=1;q<terrain;q++)if(group(w[q*12+6],true)<group(w[(q-1)*12+6],true)){cross=false;break;}
   int[] first=new int[5],count=new int[5];for(int q=0;q<terrain;q++)count[group(w[q*12+6],cross)]++;int cursor=0;for(int g=0;g<5;g++){first[g]=cursor;cursor+=count[g];}
   var merged=PredictionScanMerge.merge(w,first,count,axis,spacing,true,16);var retained=PredictionSpatialOrder.arrange(merged.words(),merged.first(),merged.count(),VssLodSpriteTable.plants);
   PredictionVegetationRanges.arrange(merged.words(),merged.first(),merged.count(),VssLodSpriteTable.plants,VssLodSpriteTable.aquatic);
   Arrays.fill(PredictionDisplayGeometry.rejection,0);var r=PredictionDisplayGeometry.build(merged.words(),merged.first(),merged.count(),retained,axis);
   System.out.println("{\"id\":"+id+",\"source\":"+Arrays.stream(merged.count()).sum()+",\"extra\":"+(r.words().length-merged.words().length)/12+",\"reasons\":"+Arrays.toString(PredictionDisplayGeometry.rejection)+"}");
  }
 }
}
