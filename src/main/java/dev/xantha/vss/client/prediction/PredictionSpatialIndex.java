package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.function.Consumer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Single-dimension quadtree. Empty subtrees are skipped, including beneath coarse changes. */
final class PredictionSpatialIndex<T> {
    private static final int TOP = PredictionTileManager.MAX_LOD_LEVEL;
    private final Long2ObjectOpenHashMap<T>[] values;
    private final Long2IntOpenHashMap[] counts = new Long2IntOpenHashMap[TOP + 1];
    private final Long2IntOpenHashMap[] minY = new Long2IntOpenHashMap[TOP + 1];
    private final Long2IntOpenHashMap[] maxY = new Long2IntOpenHashMap[TOP + 1];
    private long visited;

    @SuppressWarnings("unchecked")
    PredictionSpatialIndex() {
        values = new Long2ObjectOpenHashMap[TOP + 1];
        for (int lod=0;lod<=TOP;lod++) {
            values[lod]=new Long2ObjectOpenHashMap<>();
            counts[lod]=new Long2IntOpenHashMap();
            minY[lod]=new Long2IntOpenHashMap();minY[lod].defaultReturnValue(Integer.MAX_VALUE);
            maxY[lod]=new Long2IntOpenHashMap();maxY[lod].defaultReturnValue(Integer.MIN_VALUE);
        }
    }

    void put(PredictionTileKey key,T value) {
        put(key,value,Integer.MIN_VALUE,Integer.MAX_VALUE);
    }

    void put(PredictionTileKey key,T value,int tileMinY,int tileMaxY) {
        long at=pack(key.tileX(),key.tileZ());
        T previous=values[key.lod()].put(at,value);
        minY[key.lod()].put(at,tileMinY);maxY[key.lod()].put(at,tileMaxY);
        if(previous==null) adjust(key,1);
        else recomputeAncestors(key);
    }

    void remove(PredictionTileKey key) {
        long at=pack(key.tileX(),key.tileZ());
        if (values[key.lod()].remove(at)!=null) {
            minY[key.lod()].remove(at);maxY[key.lod()].remove(at);
            adjust(key,-1);
        }
    }

    private void adjust(PredictionTileKey key,int delta) {
        for (int lod=key.lod();lod<=TOP;lod++) {
            long at=pack(key.tileX()>>(lod-key.lod()),key.tileZ()>>(lod-key.lod()));
            int count=counts[lod].get(at)+delta;
            if (count==0) counts[lod].remove(at); else counts[lod].put(at,count);
            recomputeBounds((int)(at>>32),(int)at,lod);
        }
    }

    private void recomputeAncestors(PredictionTileKey key) {
        for(int lod=key.lod();lod<=TOP;lod++)
            recomputeBounds(key.tileX()>>(lod-key.lod()),key.tileZ()>>(lod-key.lod()),lod);
    }

    private void recomputeBounds(int x,int z,int lod) {
        long at=pack(x,z);
        int low=Integer.MAX_VALUE,high=Integer.MIN_VALUE;
        if(values[lod].containsKey(at)) {
            low=minY[lod].get(at);high=maxY[lod].get(at);
        }
        if(lod>0) for(int dz=0;dz<2;dz++) for(int dx=0;dx<2;dx++) {
            long child=pack(x*2+dx,z*2+dz);
            if(!counts[lod-1].containsKey(child)) continue;
            low=Math.min(low,minY[lod-1].get(child));
            high=Math.max(high,maxY[lod-1].get(child));
        }
        if(low==Integer.MAX_VALUE) minY[lod].remove(at);else minY[lod].put(at,low);
        if(high==Integer.MIN_VALUE) maxY[lod].remove(at);else maxY[lod].put(at,high);
    }

    void intersect(long minX,long minZ,long maxX,long maxZ,Consumer<T> visit) {
        if (minX>=maxX || minZ>=maxZ) return;
        long span=64L<<TOP;
        for (long z=Math.floorDiv(minZ,span);z<=Math.floorDiv(maxZ-1,span);z++)
            for (long x=Math.floorDiv(minX,span);x<=Math.floorDiv(maxX-1,span);x++)
                intersect((int)x,(int)z,TOP,minX,minZ,maxX,maxZ,visit);
    }

    private void intersect(int x,int z,int lod,long minX,long minZ,long maxX,long maxZ,Consumer<T> visit) {
        visited++;
        long key=pack(x,z);
        if (!counts[lod].containsKey(key)) return;
        long span=64L<<lod, bx=x*span,bz=z*span;
        if (bx>=maxX || bz>=maxZ || bx+span<=minX || bz+span<=minZ) return;
        T value=values[lod].get(key);
        if (value!=null) visit.accept(value);
        if (lod==0) return;
        for (int dz=0;dz<2;dz++) for(int dx=0;dx<2;dx++)
            intersect(x*2+dx,z*2+dz,lod-1,minX,minZ,maxX,maxZ,visit);
    }

    /**
     * Conservative frustum broad phase for render candidates. The node AABB
     * uses the full tracked height range, so a false positive remains possible
     * but no terrain or seam key can be removed merely because its parent node
     * was tested. Exact culling still happens in PredictionVisiblePlan.
     */
    void intersectFrustum(Frustum frustum, Vec3 camera, double horizon,
                          int minY, int maxY, Consumer<T> visit) {
        if (camera == null || horizon < 0.0D) return;
        if (minY >= maxY) { minY = Integer.MIN_VALUE; maxY = Integer.MAX_VALUE; }
        long minX = (long)Math.floor(camera.x - horizon), minZ = (long)Math.floor(camera.z - horizon);
        long maxX = (long)Math.ceil(camera.x + horizon) + 1L, maxZ = (long)Math.ceil(camera.z + horizon) + 1L;
        long span = 64L << TOP;
        for (long z=Math.floorDiv(minZ,span); z<=Math.floorDiv(maxZ-1,span); z++)
            for (long x=Math.floorDiv(minX,span); x<=Math.floorDiv(maxX-1,span); x++)
                intersectFrustum((int)x,(int)z,TOP,camera,horizon,minY,maxY,frustum,visit);
    }

    private void intersectFrustum(int x, int z, int lod, Vec3 camera, double horizon,
                                   int minY, int maxY, Frustum frustum, Consumer<T> visit) {
        visited++;
        long key=pack(x,z);
        if (!counts[lod].containsKey(key)) return;
        long span=64L<<lod, bx=(long)x*span, bz=(long)z*span;
        double dx=camera.x<bx ? bx-camera.x : camera.x>bx+span ? camera.x-(bx+span) : 0.0D;
        double dz=camera.z<bz ? bz-camera.z : camera.z>bz+span ? camera.z-(bz+span) : 0.0D;
        double margin=Math.sqrt(2.0D)*span*0.5D;
        if (dx*dx+dz*dz>(horizon+margin)*(horizon+margin)) return;
        int nodeMinY=this.minY[lod].get(key),nodeMaxY=this.maxY[lod].get(key);
        if(nodeMinY==Integer.MAX_VALUE || nodeMaxY==Integer.MIN_VALUE) { nodeMinY=minY;nodeMaxY=maxY; }
        // The exact tile culling box is inflated by two blocks for seam and
        // rasterization tolerance. Keep the broad phase at least as wide so
        // a tile visible only on a frustum edge cannot be dropped here.
        if (frustum != null && !frustum.isVisible(new AABB(bx,nodeMinY,bz,bx+span,nodeMaxY,bz+span).inflate(2.0D))) return;
        T value=values[lod].get(key);
        if (value!=null) visit.accept(value);
        if (lod==0) return;
        for(int dzChild=0;dzChild<2;dzChild++) for(int dxChild=0;dxChild<2;dxChild++)
            intersectFrustum(x*2+dxChild,z*2+dzChild,lod-1,camera,horizon,minY,maxY,frustum,visit);
    }

    long visited() { return visited; }
    boolean any(long minX,long minZ,long maxX,long maxZ) {
        if(counts[TOP].isEmpty() || minX>=maxX || minZ>=maxZ) return false;
        long span=64L<<TOP;
        for(long z=Math.floorDiv(minZ,span);z<=Math.floorDiv(maxZ-1,span);z++)
            for(long x=Math.floorDiv(minX,span);x<=Math.floorDiv(maxX-1,span);x++)
                if(any((int)x,(int)z,TOP,minX,minZ,maxX,maxZ)) return true;
        return false;
    }
    private boolean any(int x,int z,int lod,long minX,long minZ,long maxX,long maxZ) {
        long key=pack(x,z);if(!counts[lod].containsKey(key)) return false;
        long span=64L<<lod,bx=x*span,bz=z*span;
        if(bx>=maxX || bz>=maxZ || bx+span<=minX || bz+span<=minZ) return false;
        if(values[lod].containsKey(key)) return true;
        if(lod==0) return false;
        for(int dz=0;dz<2;dz++) for(int dx=0;dx<2;dx++)
            if(any(x*2+dx,z*2+dz,lod-1,minX,minZ,maxX,maxZ)) return true;
        return false;
    }
    void clear() { for(int lod=0;lod<=TOP;lod++) { values[lod].clear();counts[lod].clear();minY[lod].clear();maxY[lod].clear(); } }
    private static long pack(int x,int z) { return (long)x<<32 | z&0xffffffffL; }
}
