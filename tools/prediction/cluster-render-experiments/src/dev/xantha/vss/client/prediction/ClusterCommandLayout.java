package dev.xantha.vss.client.prediction;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Experimental static spatial hierarchy. Command payload and baseInstance are not changed. */
final class ClusterCommandLayout {
    static final int GROUP = 64, SUPER_GROUP = 16;
    final int[] indices;
    final float[] parents;

    ClusterCommandLayout(ByteBuffer boxes) {
        int count = boxes.remaining() / 32, groups = (count + GROUP - 1) / GROUP;
        indices = new int[count];
        parents = new float[(groups + (groups + SUPER_GROUP - 1) / SUPER_GROUP) * 8];
        double[] lo = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] hi = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int i = 0; i < count; i++) for (int a = 0; a < 3; a++) {
            double center = center(boxes, i, a);
            if (Double.isFinite(center)) { lo[a] = Math.min(lo[a], center); hi[a] = Math.max(hi[a], center); }
        }
        double span = Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2]));
        double scale = span > 0 && Double.isFinite(span) ? 1023.0 / span : 0;
        long[] keys = new long[count];
        for (int i = 0; i < count; i++) {
            int[] p = new int[3]; boolean valid = true;
            for (int a = 0; a < 3; a++) {
                double c = center(boxes, i, a); valid &= Double.isFinite(c);
                p[a] = Math.max(0, Math.min(1023, (int)((c - lo[a]) * scale)));
            }
            long morton = valid ? spread(p[0]) | spread(p[1]) << 1 | spread(p[2]) << 2 : 0x3fffffffL;
            keys[i] = morton << 32 | Integer.toUnsignedLong(i);
        }
        Arrays.sort(keys);
        for (int b = 0; b < parents.length; b += 8) for (int a = 0; a < 3; a++) {
            parents[b + a] = Float.POSITIVE_INFINITY; parents[b + 4 + a] = Float.NEGATIVE_INFINITY;
        }
        for (int i = 0; i < count; i++) {
            int source = indices[i] = (int)keys[i], target = i / GROUP * 8, p = boxes.position() + source * 32;
            for (int a = 0; a < 3; a++) {
                parents[target + a] = Math.min(parents[target + a], boxes.getFloat(p + a * 4));
                parents[target + 4 + a] = Math.max(parents[target + 4 + a], boxes.getFloat(p + 16 + a * 4));
            }
        }
        for (int i = 0; i < groups; i++) {
            int source = i * 8, target = (groups + i / SUPER_GROUP) * 8;
            for (int a = 0; a < 3; a++) {
                parents[target + a] = Math.min(parents[target + a], parents[source + a]);
                parents[target + 4 + a] = Math.max(parents[target + 4 + a], parents[source + 4 + a]);
            }
        }
    }

    private static double center(ByteBuffer b, int i, int a) {
        int p = b.position() + i * 32 + a * 4;
        return ((double)b.getFloat(p) + b.getFloat(p + 16)) * .5;
    }

    private static long spread(int v) {
        long x = v & 1023;
        x = (x | x << 16) & 0x030000ffL;
        x = (x | x << 8) & 0x0300f00fL;
        x = (x | x << 4) & 0x030c30c3L;
        return (x | x << 2) & 0x09249249L;
    }
}
