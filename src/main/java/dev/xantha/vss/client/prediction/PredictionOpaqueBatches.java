package dev.xantha.vss.client.prediction;

import dev.xantha.vss.client.prediction.PredictionRenderer.Draw;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Comparator;
import java.util.HashMap;
import net.minecraft.world.phys.Vec3;

/** Render-thread page grouping within bounded near-to-far distance buckets. */
final class PredictionOpaqueBatches {
    private static final int BUCKET_LIMIT = 8;
    private static final int REGION_BYTES = 16 * 1024 * 1024;
    private final ArrayList<Bucket> buckets = new ArrayList<>();
    /**
     * The visible plan is immutable between scene changes, but the renderer
     * used to rebuild every page map on every frame anyway. Keep the last
     * grouping and use the render-residency revision as its invalidation token.
     * This is deliberately render-thread-only: no synchronization is needed
     * and a residency change simply causes a miss on the next frame.
     */
    private List<Draw> preparedInput;
    private IdentityHashMap<Draw, PredictionDrawRanges> preparedRanges;
    private long preparedResidencyRevision = Long.MIN_VALUE;
    private int preparedBucketCount;
    private long cacheHits;
    private long cacheMisses;
    private long cameraCellX, cameraCellZ;

    int prepare(List<Draw> draws) {
        return prepare(draws, Long.MIN_VALUE);
    }

    int prepare(List<Draw> draws, long residencyRevision) {
        return prepare(draws, residencyRevision, null);
    }

    /**
     * Prepares page groups using the ranges already built by the owning
     * renderer pass. A render frame must not ask every packed mesh for the
     * same opaque range a second time just to decide whether it belongs in a
     * page group.
     */
    int prepare(List<Draw> draws, long residencyRevision,
                IdentityHashMap<Draw, PredictionDrawRanges> drawableRanges) {
        return prepare(draws, residencyRevision, drawableRanges, Vec3.ZERO);
    }

    int prepare(List<Draw> draws, long residencyRevision,
                IdentityHashMap<Draw, PredictionDrawRanges> drawableRanges, Vec3 camera) {
        long cellX = (long) Math.floor(camera.x / 128), cellZ = (long) Math.floor(camera.z / 128);
        // The legacy overload has no residency token. Keep its historical
        // rebuild semantics; only the production caller may reuse a grouping.
        if (residencyRevision != Long.MIN_VALUE
                && draws == preparedInput && residencyRevision == preparedResidencyRevision
                && drawableRanges == preparedRanges && cellX == cameraCellX && cellZ == cameraCellZ) {
            cacheHits++;
            return preparedBucketCount;
        }
        cacheMisses++;
        while (buckets.size() < BUCKET_LIMIT) buckets.add(new Bucket());
        clear();
        int bucketCount = 0;
        double cx = cellX * 128 + 64, cz = cellZ * 128 + 64;
        for (int index = 0; index < draws.size(); index++) {
            Draw draw = draws.get(index);
            PredictionPackedMesh packed = draw.gpu().packed();
            if (packed == null) continue;
            if (drawableRanges != null) {
                if (!drawableRanges.containsKey(draw)) continue;
            } else if (packed.drawRanges(false, draw.faces()).quads == 0) {
                continue;
            }
            int bucketIndex = distanceBucket(draw, cx, cz);
            bucketCount = Math.max(bucketCount, bucketIndex + 1);
            PredictionTerrainArena.Slice slice = draw.gpu().arenaSlice();
            if (slice == null) buckets.get(bucketIndex).fallback.add(draw);
            else buckets.get(bucketIndex).add(slice.page, slice.offset / REGION_BYTES, draw);
        }
        for (Bucket bucket : buckets) for (PageGroup group : bucket.pages)
            group.draws.sort(Comparator.comparingInt(draw -> draw.gpu().arenaSlice().offset));
        preparedInput = draws;
        preparedRanges = drawableRanges;
        preparedResidencyRevision = residencyRevision;
        preparedBucketCount = bucketCount;
        cameraCellX = cellX; cameraCellZ = cellZ;
        return bucketCount;
    }

    static int distanceBucket(Draw draw, double x, double z) {
        var bounds = draw.bounds();
        double dx = Math.max(0, Math.max(bounds.minX - x, x - bounds.maxX));
        double dz = Math.max(0, Math.max(bounds.minZ - z, z - bounds.maxZ));
        double distance = dx * dx + dz * dz;
        int bucket = 0;
        double edge = 256.0 * 256;
        while (bucket < BUCKET_LIMIT - 1 && distance >= edge) { bucket++; edge *= 4; }
        return bucket;
    }

    Bucket bucket(int index) { return buckets.get(index); }

    void clear() {
        // Also clear inactive buckets so a smaller scene releases its old Draw/GPU references.
        for (Bucket bucket : buckets) bucket.clear();
        preparedInput = null;
        preparedRanges = null;
        preparedResidencyRevision = Long.MIN_VALUE;
        preparedBucketCount = 0;
    }

    long cacheHits() { return cacheHits; }
    long cacheMisses() { return cacheMisses; }

    static final class Bucket {
        final ArrayList<PageGroup> pages = new ArrayList<>();
        final ArrayList<Draw> fallback = new ArrayList<>();
        private final HashMap<GroupKey, PageGroup> byPage = new HashMap<>();
        private final ArrayList<PageGroup> reusable = new ArrayList<>();

        private void clear() {
            for (PageGroup group : pages) group.draws.clear();
            pages.clear();
            byPage.clear();
            fallback.clear();
        }

        private void add(PredictionTerrainArena.Page page, int region, Draw draw) {
            var key = new GroupKey(page, region);
            PageGroup group = byPage.get(key);
            if (group == null) {
                int index = pages.size();
                if (index == reusable.size()) reusable.add(new PageGroup());
                group = reusable.get(index);
                group.region = region;
                byPage.put(key, group);
                pages.add(group);
            }
            group.draws.add(draw);
        }
    }

    static final class PageGroup {
        final ArrayList<Draw> draws = new ArrayList<>();
        int region;
    }
    private record GroupKey(PredictionTerrainArena.Page page, int region) { }
}
