package dev.xantha.vss.client.prediction;

import java.util.Arrays;
import java.util.WeakHashMap;
import dev.xantha.vss.compat.StrictVoxyCoverageChanges;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Frozen reproduction of the retired upload-only pruning bug. TEST SOURCES ONLY.
 * Render-thread coverage view previously shared by scene selection, submission and residency.
 * Uses the existing settled-column snapshot and the Voxy node/window adapter;
 * it does not keep a second ownership index or claim uploaded nodes are visible.
 * Whole-mesh exclusion and partial draw filtering therefore use the same proof.
 */
final class LegacyResidentCoverage {
    private PredictionExactCoverageMask.Snapshot index;
    private double x, y, z;
    private int radius;
    private long revision;
    private ResourceKey<Level> dimension;
    private long uploadRevision = -1;
    private final PredictionPreviewHandoff.Uploads uploads;
    private final WeakHashMap<PredictionPackedMesh, Entry> cache = new WeakHashMap<>();
    private CoverageChanges lastCoverageChanges = CoverageChanges.NONE;
    private StrictVoxyCoverageChanges.Delta lastUploadChanges;
    private boolean lastEligibilityWide;

    LegacyResidentCoverage() { this(PredictionPreviewHandoff.VOXY); }
    LegacyResidentCoverage(PredictionPreviewHandoff.Uploads uploads) { this.uploads = uploads; }

    /**
     * The camera-dependent result is invalidated by a cell crossing, while the static
     * settled-column proof stays attached to the mesh entry. Coverage changes revoke
     * only intersecting proofs; withdrawal still takes effect in the current frame.
     */
    private static final class Entry {
        final double baseX, baseZ;
        final PredictionDrawRanges[] source = new PredictionDrawRanges[64];
        final PredictionDrawRanges[] filtered = new PredictionDrawRanges[64];
        final long[] filteredRevision = new long[64];
        final long[] filteredMeshRevision = new long[64];
        final LegacyOwnershipRuns.Scratch scratch = new LegacyOwnershipRuns.Scratch();
        byte[] staticCoverage;
        boolean allStaticCoverage;
        boolean anyStaticCoverage;
        byte wholeCoverage = -1;
        long proofMeshRevision = -1;
        long selectionVersion;

        Entry(double baseX, double baseZ) {
            this.baseX = baseX;
            this.baseZ = baseZ;
            Arrays.fill(filteredRevision, -1L);
        }

    }

    private long dynamicSelectionVersion;
    /** Owned by an already bounded PreparedDraw; no extra mesh reference. */
    static final class Selection {
        private LegacyResidentCoverage owner;
        private PredictionPackedMesh packed;
        private double baseX, baseZ;
        private boolean water;
        private long revision = -1, meshRevision = -1, value;
    }

    long selectionVersion(PredictionPackedMesh packed, double baseX, double baseZ, boolean water, Selection selected) {
        long meshRevision = packed.submissionRevision();
        if (selected.owner == this && selected.packed == packed && selected.revision == revision
                && selected.meshRevision == meshRevision && selected.baseX == baseX
                && selected.baseZ == baseZ && selected.water == water) return selected.value;
        selected.value = selectionVersion(packed, baseX, baseZ, water);
        selected.owner = this; selected.packed = packed; selected.revision = revision;
        selected.meshRevision = meshRevision; selected.baseX = baseX; selected.baseZ = baseZ; selected.water = water;
        return selected.value;
    }

    void update(PredictionExactCoverageMask.Snapshot next, Vec3 camera, int radius) {
        update(next, camera, radius, null);
    }

    void update(PredictionExactCoverageMask.Snapshot next, Vec3 camera, int radius, ResourceKey<Level> dimension) {
        double x = Math.floor(camera.x / 8) * 8 + 4, y = Math.floor(camera.y / 8) * 8 + 4;
        double z = Math.floor(camera.z / 8) * 8 + 4;
        boolean cameraChanged = this.x != x || this.y != y || this.z != z;
        boolean radiusChanged = this.radius != radius;
        CoverageChanges changes = coverageChanges(index, next);
        boolean dimensionChanged = this.dimension != dimension;
        long nextUploadRevision = uploads.revision();
        var uploaded = nextUploadRevision == uploadRevision ? null : uploads.changesSince(uploadRevision);
        this.dimension = dimension;
        uploadRevision = nextUploadRevision;
        boolean uploadsChanged = dimensionChanged;
        if (!uploadsChanged && uploaded != null) for (var cached : cache.entrySet()) {
            if (uploadAffected(cached.getKey(), cached.getValue(), uploaded)) { uploadsChanged = true; break; }
        }
        if (!cameraChanged && !radiusChanged && changes.empty() && !uploadsChanged) { index = next; return; }
        index = next;
        this.x = x; this.y = y; this.z = z; this.radius = radius;
        long previousRevision = revision++;
        lastCoverageChanges = changes;
        lastUploadChanges = uploaded;
        lastEligibilityWide = cameraChanged || radiusChanged || dimensionChanged || changes.all;
        if (!changes.empty() || uploadsChanged) for (var cached : cache.entrySet()) {
            Entry entry = cached.getValue();
            boolean affected = changes.all || LegacyOwnershipRuns.of(cached.getKey()).intersectsColumns(
                    entry.baseX, entry.baseZ, changes.x, changes.z, changes.xx, changes.zz) && !changes.empty()
                    || dimensionChanged || uploadAffected(cached.getKey(), entry, uploaded);
            if (affected) {
                entry.staticCoverage = null;
                entry.allStaticCoverage = false;
                entry.anyStaticCoverage = false;
                entry.wholeCoverage = -1;
                entry.selectionVersion = revision;
                Arrays.fill(entry.filteredRevision, -1L);
            } else if (!cameraChanged && !radiusChanged) {
                // Results depend on these columns, not the snapshot object's identity.
                for (int slot = 0; slot < entry.filteredRevision.length; slot++)
                    if (entry.filteredRevision[slot] == previousRevision) entry.filteredRevision[slot] = revision;
            }
        }
        if (cameraChanged || radiusChanged) dynamicSelectionVersion = revision;
    }

    /**
     * Fast broad phase for the visible plan. It mirrors the cache invalidation
     * rules above, so a remote Voxy update cannot force every retained draw to
     * call fullyOwned(). Unknown or reset coverage remains conservative.
     */
    boolean mayAffect(PredictionPackedMesh packed, double baseX, double baseZ) {
        if (packed == null) return true;
        if (lastEligibilityWide) return true;
        if (!lastCoverageChanges.empty()
                && (lastCoverageChanges.all || LegacyOwnershipRuns.of(packed).intersectsColumns(baseX, baseZ,
                lastCoverageChanges.x, lastCoverageChanges.z,
                lastCoverageChanges.xx, lastCoverageChanges.zz))) return true;
        return uploadAffected(packed, baseX, baseZ, lastUploadChanges);
    }

    private static boolean uploadAffected(PredictionPackedMesh mesh, Entry entry,
                                           StrictVoxyCoverageChanges.Delta delta) {
        return uploadAffected(mesh, entry.baseX, entry.baseZ, delta);
    }

    private static boolean uploadAffected(PredictionPackedMesh mesh, double baseX, double baseZ,
                                           StrictVoxyCoverageChanges.Delta delta) {
        if (!LegacyOwnershipRuns.of(mesh).hasRetirableRuns || delta == null) return false;
        if (delta.reset()) return true;
        for (var area : delta.areas()) if (LegacyOwnershipRuns.of(mesh).intersectsColumns(baseX, baseZ,
                area.minX(), area.minZ(), area.maxX() - 1, area.maxZ() - 1)) return true;
        for (var window : delta.windows()) if (LegacyOwnershipRuns.of(mesh).intersectsWindow(baseX, baseZ, window)) return true;
        return false;
    }

    private byte[] staticCoverage(PredictionPackedMesh packed, double baseX, double baseZ) {
        return LegacyOwnershipRuns.of(packed).staticCoverage(index, baseX, baseZ, uploadedCoverage(packed));
    }

    private LegacyOwnershipRuns.UploadedCoverage uploadedCoverage(PredictionPackedMesh packed) {
        return (x, y, z, xx, yy, zz) -> uploads.covers(dimension, x,
                packed.morph() == null ? y : (int) Math.floor(Math.min(y, packed.morphMinY())), z, xx,
                packed.morph() == null ? yy : (int) Math.ceil(Math.max(yy, packed.morphMaxY())), zz);
    }

    private Entry entry(PredictionPackedMesh packed, double baseX, double baseZ) {
        Entry entry = cache.get(packed);
        if (entry == null || entry.baseX != baseX || entry.baseZ != baseZ) {
            entry = new Entry(baseX, baseZ); entry.selectionVersion = revision; cache.put(packed, entry);
        }
        if (entry.proofMeshRevision != packed.submissionRevision()) {
            entry.staticCoverage = null; entry.wholeCoverage = -1;
            entry.allStaticCoverage = entry.anyStaticCoverage = false;
            entry.proofMeshRevision = packed.submissionRevision();
        }
        return entry;
    }

    private record CoverageChanges(boolean all, int x, int z, int xx, int zz) {
        static final CoverageChanges NONE = new CoverageChanges(false, 0, 0, -1, -1);
        static final CoverageChanges ALL = new CoverageChanges(true, 0, 0, 0, 0);
        boolean empty() { return !all && xx < x; }
    }

    private static CoverageChanges coverageChanges(PredictionExactCoverageMask.Snapshot previous,
                                                   PredictionExactCoverageMask.Snapshot next) {
        if (previous == next) return CoverageChanges.NONE;
        if (previous == null || next == null || previous.originX() != next.originX()
                || previous.originZ() != next.originZ() || previous.size() != next.size()) return CoverageChanges.ALL;
        // CPU removal only trusts 255 (settled interior). Indexed edges continue
        // through the current depth test, so their 0/128 changes need no CPU rebuild.
        int size = next.size(), x = size, z = size, xx = -1, zz = -1;
        byte[] before = previous.columns(), after = next.columns();
        for (int i = 0; i < after.length; i++) if ((before[i] == (byte) 255) != (after[i] == (byte) 255)) {
            int cx = i % size, cz = i / size;
            x = Math.min(x, cx); z = Math.min(z, cz); xx = Math.max(xx, cx); zz = Math.max(zz, cz);
        }
        return xx < x ? CoverageChanges.NONE : new CoverageChanges(false,
                next.originX() + x, next.originZ() + z, next.originX() + xx, next.originZ() + zz);
    }

    long revision() { return revision; }

    /** Dimension-aware scene proof; unknown dimensions always retain prediction. */
    boolean fullyOwned(ResourceKey<Level> dimension, PredictionPackedMesh packed, double baseX, double baseZ) {
        return dimension != null && dimension.equals(this.dimension) && fullyOwned(packed, baseX, baseZ);
    }

    /** Local range invalidation. A remote coverage edit must not rebuild every visible mesh. */
    long selectionVersion(PredictionPackedMesh packed, double baseX, double baseZ, boolean water) {
        float min = !water && packed.morph() != null ? packed.morphMinY() : Float.POSITIVE_INFINITY;
        float max = !water && packed.morph() != null ? packed.morphMaxY() : Float.NEGATIVE_INFINITY;
        if (index == null || radius <= 0 || !LegacyOwnershipRuns.of(packed).intersectsRange(baseX, baseZ, x, y, z, radius, min, max)) return 0;
        Entry entry = cache.get(packed);
        if (entry == null || entry.baseX != baseX || entry.baseZ != baseZ) {
            entry = new Entry(baseX, baseZ); entry.selectionVersion = revision; cache.put(packed, entry);
        }
        return Math.max(entry.selectionVersion, dynamicSelectionVersion) + 1;
    }

    boolean fullyOwned(PredictionPackedMesh packed, double baseX, double baseZ) {
        if (index == null || radius <= 0 || packed.quadCount() == 0) return false;
        if (!LegacyOwnershipRuns.of(packed).ready()) { cache.remove(packed); return false; }
        if (!LegacyOwnershipRuns.of(packed).fullyInside(baseX, baseZ, x, y, z, radius,
                packed.morph() == null ? Float.POSITIVE_INFINITY : packed.morphMinY(),
                packed.morph() == null ? Float.NEGATIVE_INFINITY : packed.morphMaxY())) return false;
        // Cache the whole-volume upload proof too, so renderer resets revoke
        // the fast path and unrelated uploads never repeat its regional query.
        Entry entry = entry(packed, baseX, baseZ);
        if (entry.wholeCoverage < 0) entry.wholeCoverage = (byte) (LegacyOwnershipRuns.of(packed).wholeStaticCoverage(
                index, baseX, baseZ, uploadedCoverage(packed)) ? 1 : 0);
        if (entry.wholeCoverage != 0) return true;
        if (entry.staticCoverage == null) {
            entry.staticCoverage = staticCoverage(packed, baseX, baseZ);
            if (entry.staticCoverage == null) return false;
            entry.allStaticCoverage = LegacyOwnershipRuns.allStaticCoverage(entry.staticCoverage);
            entry.anyStaticCoverage = LegacyOwnershipRuns.anyStaticCoverage(entry.staticCoverage);
        }
        return entry.allStaticCoverage;
    }

    PredictionDrawRanges filter(PredictionPackedMesh packed, PredictionDrawRanges ranges,
                                double baseX, double baseZ, boolean water, int faces) {
        if (index == null || radius <= 0 || ranges.quads == 0) return ranges;
        if (!LegacyOwnershipRuns.of(packed).ready()) {
            cache.remove(packed); LegacyOwnershipRuns.of(packed).requestRestore(); return ranges;
        }
        float morphMin = !water && packed.morph() != null ? packed.morphMinY() : Float.POSITIVE_INFINITY;
        float morphMax = !water && packed.morph() != null ? packed.morphMaxY() : Float.NEGATIVE_INFINITY;
        if (!LegacyOwnershipRuns.of(packed).intersectsRange(baseX, baseZ, x, y, z, radius, morphMin, morphMax)) return ranges;
        Entry entry = entry(packed, baseX, baseZ);
        int slot = (water ? 32 : 0) + faces;
        if (entry.source[slot] != ranges) {
            entry.source[slot] = ranges;
            entry.filtered[slot] = null;
            entry.filteredRevision[slot] = -1L;
        }
        if (entry.staticCoverage == null) {
            entry.staticCoverage = staticCoverage(packed, baseX, baseZ);
            if (entry.staticCoverage == null) return ranges;
            entry.allStaticCoverage = LegacyOwnershipRuns.allStaticCoverage(entry.staticCoverage);
            entry.anyStaticCoverage = LegacyOwnershipRuns.anyStaticCoverage(entry.staticCoverage);
        }
        if (!entry.anyStaticCoverage) return ranges;
        if (entry.filteredRevision[slot] != revision || entry.filteredMeshRevision[slot] != packed.submissionRevision()) {
            entry.filtered[slot] = LegacyOwnershipRuns.of(packed).filter(ranges, index, baseX, baseZ, x, y, z, radius,
                    morphMin, morphMax, entry.staticCoverage, entry.allStaticCoverage,
                    entry.scratch, entry.filtered[slot]);
            entry.filteredRevision[slot] = revision;
            entry.filteredMeshRevision[slot] = packed.submissionRevision();
        }
        return entry.filtered[slot];
    }

    void clear() {
        index = null;
        dimension = null;
        uploadRevision = -1;
        cache.clear();
        lastCoverageChanges = CoverageChanges.ALL;
        lastUploadChanges = null;
        lastEligibilityWide = true;
        revision++;
    }
}
