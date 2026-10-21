package dev.xantha.vss.client.prediction;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.Arrays;
import java.util.Collection;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTile;

/**
 * Immutable ownership subdivisions inside the geometry's source cells. A coarse
 * height sample is not an ownership boundary: a 64-block child can replace only
 * part of a 1024-block source cell. Only cells crossed by resident tile edges
 * split, so even a world-sized fallback does not allocate a world-sized bitmap.
 * This is part of the existing boundary transaction, not a second owner index.
 */
final class PredictionCoverageGrid {
    /** Unused by leaf flags (terrain=128, features=64, boundary directions=15). */
    static final int BRANCH = 32;
    private final int axis, spacing;
    private final int[] children, x, z, steps, source, flags;

    @FunctionalInterface interface Decision { int at(int worldX, int worldZ); }

    private PredictionCoverageGrid(Builder b, Decision decision, PredictionTile tile) {
        axis = tile.cellAxis(); spacing = tile.spacingBlocks();
        children = b.children.toIntArray(); x = b.x.toIntArray(); z = b.z.toIntArray();
        steps = b.steps.toIntArray(); source = b.source.toIntArray(); flags = new int[children.length];
        for (int node = flags.length - 1; node >= 0; node--) {
            int child = children[node];
            if (child == 0) flags[node] = decision.at(tile.baseBlockX() + x[node] + steps[node] / 2,
                    tile.baseBlockZ() + z[node] + steps[node] / 2);
            else for (int q = 0; q < 4; q++) flags[node] |= flags[child + q];
        }
    }

    /** Null preserves the ordinary fixed-grid fast path when no source cell splits. */
    static PredictionCoverageGrid resolve(PredictionTile tile, Collection<PredictionTile> overlapping,
                                           Decision decision) {
        if (tile.spacingBlocks() <= VssLodLayout.BASE_TILE_BLOCKS) return null;
        Builder b = null;
        for (var other : overlapping) {
            if (other.spanBlocks() >= tile.spacingBlocks()) continue;
            if (b == null) b = new Builder(tile);
            b.split(other.baseBlockX() - tile.baseBlockX(), other.baseBlockZ() - tile.baseBlockZ(), other.spanBlocks());
        }
        return b == null || b.children.size() == tile.cellAxis() * tile.cellAxis()
                ? null : new PredictionCoverageGrid(b, decision, tile);
    }

    int size() { return flags.length; }
    int axis() { return axis; }
    int source(int node) { return source[node]; }
    int x(int node) { return x[node]; }
    int z(int node) { return z[node]; }
    int step(int node) { return steps[node]; }
    boolean leaf(int node) { return children[node] == 0; }
    boolean ground(int node) { return (flags[node] & PredictionFeatureHandoff.TERRAIN) != 0; }
    boolean features(int node) { return (flags[node] & PredictionFeatureHandoff.FEATURES) != 0; }
    boolean complete(int bit) {
        for (int node = 0; node < flags.length; node++) if (leaf(node) && (flags[node] & bit) == 0) return false;
        return true;
    }
    boolean[] roots(int bit) {
        boolean[] result = new boolean[axis * axis];
        for (int i = 0; i < result.length; i++) result[i] = (flags[i] & bit) != 0;
        return result;
    }
    int nodeAt(int localX, int localZ) {
        if (localX < 0 || localZ < 0 || localX >= axis * spacing || localZ >= axis * spacing) return -1;
        int node = localZ / spacing * axis + localX / spacing;
        while (!leaf(node)) {
            int half = steps[node] / 2;
            node = children[node] + (localX >= x[node] + half ? 1 : 0) + (localZ >= z[node] + half ? 2 : 0);
        }
        return node;
    }
    byte[] mask(byte[] boundary) {
        byte[] result = new byte[size()];
        for (int i = 0; i < result.length; i++)
            result[i] = (byte) (flags[i] | (ground(i) && boundary != null ? boundary[i] & 15 : 0));
        return result;
    }
    /** A branch stores a 24-bit index of its four contiguous children. */
    int[] words(byte[] mask) {
        if (size() >= 1 << 24 || mask.length != size()) throw new IllegalArgumentException("Invalid ownership tree size");
        int[] result = new int[size()];
        for (int i = 0; i < result.length; i++) result[i] = leaf(i) ? mask[i] & 255 : children[i] << 8 | BRANCH | flags[i];
        return result;
    }
    boolean sameContent(PredictionCoverageGrid other) {
        return this == other || other != null && axis == other.axis && spacing == other.spacing
                && Arrays.equals(children, other.children) && Arrays.equals(x, other.x)
                && Arrays.equals(z, other.z) && Arrays.equals(flags, other.flags);
    }
    static boolean same(PredictionCoverageGrid a, PredictionCoverageGrid b) {
        return a == b || a != null && a.sameContent(b);
    }

    private static final class Builder {
        final IntArrayList children = new IntArrayList(), x = new IntArrayList(), z = new IntArrayList(),
                steps = new IntArrayList(), source = new IntArrayList();
        final int axis, spacing;
        Builder(PredictionTile tile) {
            axis = tile.cellAxis(); spacing = tile.spacingBlocks();
            for (int iz = 0; iz < axis; iz++) for (int ix = 0; ix < axis; ix++)
                add(ix * spacing, iz * spacing, spacing, iz * axis + ix);
        }
        void add(int px, int pz, int step, int cell) {
            children.add(0); x.add(px); z.add(pz); steps.add(step); source.add(cell);
        }
        void split(int px, int pz, int span) {
            // Split the immediately adjacent cells too: a source wall on the
            // child's edge must be replaceable along just that edge segment.
            int minX = Math.max(0, Math.floorDiv(px - 1, spacing)), minZ = Math.max(0, Math.floorDiv(pz - 1, spacing));
            int maxX = Math.min(axis - 1, Math.floorDiv(px + span, spacing));
            int maxZ = Math.min(axis - 1, Math.floorDiv(pz + span, spacing));
            for (int iz = minZ; iz <= maxZ; iz++) for (int ix = minX; ix <= maxX; ix++) split(iz * axis + ix, px - 1, pz - 1, span + 2, span);
        }
        void split(int node, int px, int pz, int span, int resolution) {
            int bx = x.getInt(node), bz = z.getInt(node), step = steps.getInt(node);
            if (step <= resolution || bx >= px + span || bz >= pz + span || bx + step <= px || bz + step <= pz) return;
            int first = children.getInt(node), half = step / 2;
            if (first == 0) {
                first = children.size(); children.set(node, first);
                for (int q = 0; q < 4; q++) add(bx + (q & 1) * half, bz + (q >>> 1) * half, half, source.getInt(node));
            }
            for (int q = 0; q < 4; q++) split(first + q, px, pz, span, resolution);
        }
    }
}
