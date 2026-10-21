package dev.xantha.vss.client.prediction;

import java.util.Arrays;
import net.minecraft.world.phys.Vec3;

/** Selects conservative command ranges in both direct and Hi-Z modes.
 * Direction masks are built with the mesh. Unknown, double-sided, seam and
 * morphing geometry retains its original submission. No vertex decoding here. */
final class PredictionCommandDirections {
    private PredictionCommandDirections() { }

    static int mask(PredictionRenderer.Draw draw, Vec3 camera) {
        if (draw.seam() || camera == null || draw.gpu().packed().morph() != null) return 127;
        var b = draw.bounds();
        float[] extent=draw.gpu().packed().occlusionBounds.extent;
        double x0=Math.min(b.minX,draw.tile().baseBlockX()+(double)extent[0]);
        double y0=Math.min(b.minY,extent[1]);
        double z0=Math.min(b.minZ,draw.tile().baseBlockZ()+(double)extent[2]);
        double x1=Math.max(b.maxX,draw.tile().baseBlockX()+(double)extent[3]);
        double y1=Math.max(b.maxY,extent[4]);
        double z1=Math.max(b.maxZ,draw.tile().baseBlockZ()+(double)extent[5]);
        if (!Double.isFinite(camera.x) || !Double.isFinite(camera.y) || !Double.isFinite(camera.z)
                || !Double.isFinite(b.minX) || !Double.isFinite(b.minY) || !Double.isFinite(b.minZ)
                || !Double.isFinite(b.maxX) || !Double.isFinite(b.maxY) || !Double.isFinite(b.maxZ)
                || !Double.isFinite(x0) || !Double.isFinite(y0) || !Double.isFinite(z0)
                || !Double.isFinite(x1) || !Double.isFinite(y1) || !Double.isFinite(z1)) return 127;
        // Float world-space GPU arithmetic needs a guard at Minecraft's world limit.
        return 64 | (x1 > camera.x - 4 ? 1 : 0) | (x0 < camera.x + 4 ? 2 : 0)
                | (y1 > camera.y - 4 ? 4 : 0) | (y0 < camera.y + 4 ? 8 : 0)
                | (z1 > camera.z - 4 ? 16 : 0) | (z0 < camera.z + 4 ? 32 : 0);
    }

    static PredictionDrawRanges filter(PredictionMeshletBounds meshlets, PredictionDrawRanges input, int mask) {
        if (mask == 127 || input.quads == 0) return input;
        boolean removable=false;
        for (int r=0;r<input.first.length&&!removable;r++) {
            int end=input.first[r]+input.count[r];
            for (int at=input.first[r];at<end;at=PredictionMeshletBounds.end(at,end)) {
                int direction=meshlets.directions[at/PredictionMeshletBounds.QUADS];
                if (direction!=0&&(direction&mask)==0) { removable=true;break; }
            }
        }
        if (!removable) return input;
        int capacity = PredictionMeshletBounds.count(input), size = 0, quads = 0;
        int[] first = new int[capacity], count = new int[capacity];
        for (int r = 0; r < input.first.length; r++) {
            int end = input.first[r] + input.count[r];
            for (int at = input.first[r]; at < end;) {
                int next = PredictionMeshletBounds.end(at, end);
                int direction = meshlets.directions[at / PredictionMeshletBounds.QUADS];
                if (direction == 0 || (direction & mask) != 0) {
                    if (size > 0 && first[size - 1] + count[size - 1] == at) count[size - 1] += next - at;
                    else { first[size] = at; count[size++] = next - at; }
                    quads += next - at;
                }
                at = next;
            }
        }
        // Fragmented command streams showed no GPU benefit even with half the
        // vertices removed. Keep the original coarse draw when this filter
        // would trade it for many small commands or remove only a tiny suffix.
        if (quads > input.quads * .875 || size > Math.max(4,input.first.length*2)) return input;
        return new PredictionDrawRanges(Arrays.copyOf(first,size),Arrays.copyOf(count,size));
    }

    /** Camera movement inside this open box cannot change any selected direction.
     * Recompute its six limits only after crossing a bound or rebuilding a pass. */
    static final class Region {
        private double minX, minY, minZ, maxX, maxY, maxZ;
        private boolean valid;
        void clear() { valid=false; }
        void begin(Vec3 camera) {
            minX=minY=minZ=Double.NEGATIVE_INFINITY;
            maxX=maxY=maxZ=Double.POSITIVE_INFINITY;
            valid=camera!=null&&Double.isFinite(camera.x)&&Double.isFinite(camera.y)&&Double.isFinite(camera.z);
        }
        boolean contains(Vec3 camera) {
            return valid&&camera!=null&&camera.x>minX&&camera.x<maxX&&camera.y>minY&&camera.y<maxY&&camera.z>minZ&&camera.z<maxZ;
        }
        void include(PredictionRenderer.Draw draw, Vec3 camera) {
            if (!valid || draw.seam() || draw.gpu().packed().morph()!=null) return;
            var b=draw.bounds();
            float[] e=draw.gpu().packed().occlusionBounds.extent;
            double x0=Math.min(b.minX,draw.tile().baseBlockX()+(double)e[0]), x1=Math.max(b.maxX,draw.tile().baseBlockX()+(double)e[3]);
            double y0=Math.min(b.minY,e[1]),y1=Math.max(b.maxY,e[4]);
            double z0=Math.min(b.minZ,draw.tile().baseBlockZ()+(double)e[2]),z1=Math.max(b.maxZ,draw.tile().baseBlockZ()+(double)e[5]);
            minX=lower(minX,camera.x,x0-4,x1+4);maxX=upper(maxX,camera.x,x0-4,x1+4);
            minY=lower(minY,camera.y,y0-4,y1+4);maxY=upper(maxY,camera.y,y0-4,y1+4);
            minZ=lower(minZ,camera.z,z0-4,z1+4);maxZ=upper(maxZ,camera.z,z0-4,z1+4);
        }
        private static double lower(double current,double eye,double a,double b) {
            return Math.max(current,Math.max(a<=eye?a:Double.NEGATIVE_INFINITY,b<=eye?b:Double.NEGATIVE_INFINITY));
        }
        private static double upper(double current,double eye,double a,double b) {
            return Math.min(current,Math.min(a>=eye?a:Double.POSITIVE_INFINITY,b>=eye?b:Double.POSITIVE_INFINITY));
        }
    }
}
