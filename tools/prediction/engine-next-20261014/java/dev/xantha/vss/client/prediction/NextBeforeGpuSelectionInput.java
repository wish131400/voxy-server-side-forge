package dev.xantha.vss.client.prediction;

import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import net.minecraft.world.phys.Vec3;

/** Render-thread cache of immutable resident inputs, validated by local resource versions. */
final class NextBeforeGpuSelectionInput {
    // Cache metadata only: never pin/decompress a mesh or retain a second scene.
    // Larger scenes still render, using the ordinary normalization path.
    private static final int MAX_RECORDS = 16384;
    private static final Cached[] EMPTY = new Cached[0];
    private static final class Cached {
        PredictionRenderer.Draw original, draw;
        PredictionPackedMesh packed;
        long meshRevision, gpuRevision, quads;
        boolean batched;
    }

    private List<PredictionRenderer.Draw> source;
    private List<PredictionRenderer.Draw> candidates = List.of();
    private Vec3 camera;
    private double horizon;
    private long meshEpoch = Long.MIN_VALUE, gpuEpoch = Long.MIN_VALUE;
    private long quads, rebuiltRecords;
    private int terrain, seams, recordCount;
    private boolean allBatched;
    private Cached[] records = EMPTY;
    private IdentityHashMap<PredictionRenderer.Draw, PredictionRenderer.Draw> normalized = new IdentityHashMap<>();
    private ArrayList<PredictionRenderer.Draw> selected = new ArrayList<>();

    void update(List<PredictionRenderer.Draw> draws, Vec3 nextCamera, double nextHorizon) {
        long nextMesh = PredictionPackedMesh.submissionEpoch(), nextGpu = PredictionGpuTile.submissionEpoch();
        boolean sameView = nextCamera.equals(camera) && nextHorizon == horizon;
        boolean sameSource = source == draws;
        if (sameSource && meshEpoch == nextMesh && gpuEpoch == nextGpu && sameView) return;
        resize(draws.size(), !bulkReplacement(draws));
        if (!sameSource) normalized.clear();
        selected.clear();
        quads = 0; terrain = seams = 0; allBatched = true;
        boolean originalOrder = recordCount == 0 ? collectDirect(draws, nextCamera, nextHorizon) : true;
        for (int i = 0; i < recordCount; i++) {
            var original = draws.get(i);
            var packed = original.gpu().packed();
            Cached entry = records[i];
            if (entry == null) records[i] = entry = new Cached();
            long meshRevision = packed == null ? -1 : packed.submissionRevision();
            long gpuRevision = original.gpu().submissionRevision();
            boolean reuse = entry.original == original && sameView && entry.packed == packed
                    && entry.meshRevision == meshRevision && entry.gpuRevision == gpuRevision;
            PredictionRenderer.Draw draw;
            boolean batched;
            long count;
            if (reuse) {
                draw = entry.draw; batched = entry.batched; count = entry.quads;
            } else {
                draw = normalize(original, packed, nextCamera, nextHorizon,
                        entry.original == original ? entry.draw : null);
                batched = draw != null && draw.gpu().arenaSlice() != null;
                count = batched ? packed.drawRanges(false, draw.faces()).quads : 0;
                entry.original = original; entry.draw = draw; entry.packed = packed;
                entry.meshRevision = meshRevision; entry.gpuRevision = gpuRevision;
                entry.batched = batched; entry.quads = count;
                rebuiltRecords++;
            }
            if (!sameSource || !reuse) {
                if (draw == null) normalized.remove(original); else normalized.put(original, draw);
            }
            if (draw == null) { originalOrder = false; continue; }
            selected.add(draw);
            originalOrder &= draw == original;
            if (!batched) { allBatched = false; continue; }
            quads += count;
            if (draw.seam()) seams++; else terrain++;
        }
        // Keep the exact list and Draw identities on unrelated global epochs;
        // downstream static commands and PreparedPass can then replay unchanged.
        boolean identical = selected.size() == candidates.size();
        for (int i = 0; identical && i < selected.size(); i++) identical = selected.get(i) == candidates.get(i);
        if (!identical) candidates = originalOrder ? draws : List.copyOf(selected);
        selected.clear(); // scratch must not retain an evicted scene
        source = draws; meshEpoch = nextMesh; gpuEpoch = nextGpu;
        camera = nextCamera; horizon = nextHorizon;
    }

    private boolean collectDirect(List<PredictionRenderer.Draw> draws, Vec3 camera, double horizon) {
        boolean originalOrder = true;
        for (var original : draws) {
            var packed = original.gpu().packed();
            var draw = normalize(original, packed, camera, horizon, null);
            if (draw == null) { normalized.remove(original); originalOrder = false; continue; }
            normalized.put(original, draw); selected.add(draw);
            originalOrder &= draw == original;
            if (draw.gpu().arenaSlice() == null) { allBatched = false; continue; }
            quads += packed.drawRanges(false, draw.faces()).quads;
            if (draw.seam()) seams++; else terrain++;
        }
        rebuiltRecords += draws.size();
        return originalOrder;
    }

    private static PredictionRenderer.Draw normalize(PredictionRenderer.Draw draw, PredictionPackedMesh packed,
                                                     Vec3 camera, double horizon, PredictionRenderer.Draw previous) {
        if (packed == null) return null;
        if (draw.seam()) return draw;
        // Same bounds/horizon contract as RenderGeometry.Entry, without allocating
        // an Entry and two AABBs for every record on a bulk replacement or move.
        var tile = draw.tile();
        double minX = tile.baseBlockX(), minZ = tile.baseBlockZ();
        double maxX = minX + tile.spanBlocks(), maxZ = minZ + tile.spanBlocks();
        double dx = Math.max(Math.max(minX - camera.x, camera.x - maxX), 0);
        double dz = Math.max(Math.max(minZ - camera.z, camera.z - maxZ), 0);
        double radius = horizon + Math.sqrt(2) * tile.spanBlocks() * .5;
        if (!(dx * dx + dz * dz <= radius * radius)) return null;
        int faces = camera.y > tile.depthBound().minY() || packed.downFaces() ? 1 << VssLodFaceGroup.HORIZONTAL : 0;
        if (camera.z < maxZ) faces |= 1 << VssLodFaceGroup.NORTH;
        if (camera.z > minZ) faces |= 1 << VssLodFaceGroup.SOUTH;
        if (camera.x < maxX) faces |= 1 << VssLodFaceGroup.WEST;
        if (camera.x > minX) faces |= 1 << VssLodFaceGroup.EAST;
        // A payload change is handled by PreparedPass's local versions. Keep
        // its immutable key when normalization itself has not changed.
        if (previous != null && faces == previous.faces()) return previous;
        return faces == draw.faces() ? draw : new PredictionRenderer.Draw(tile, draw.gpu(), draw.allowed(), draw.features(),
                false, faces, draw.morph(), draw.bounds(), draw.completeOwnership(), draw.completeTerrainOwnership());
    }

    private boolean bulkReplacement(List<PredictionRenderer.Draw> draws) {
        if (source == null || source == draws || draws.size() < 128) return false;
        int common = Math.min(source.size(), draws.size());
        if (common < draws.size() / 2) return true;
        int samples = Math.min(32, common), replaced = 0;
        for (int i = 0; i < samples; i++) {
            int at = (int) ((long) i * common / samples);
            if (source.get(at) != draws.get(at)) replaced++;
        }
        // A reorder is also safe to rebuild. This is only a cost decision, never
        // an assumption about the correctness or ownership of untested records.
        return replaced * 4 >= samples * 3;
    }

    private void resize(int count, boolean cache) {
        int wanted = !cache || count > MAX_RECORDS ? 0 : count;
        if (wanted == 0) records = EMPTY;
        else if (wanted > records.length || wanted < records.length / 4) {
            int capacity = Math.max(16, Integer.highestOneBit(wanted - 1) << 1);
            records = Arrays.copyOf(records, capacity);
        }
        // Reuse mutable metadata storage on bulk changes. No per-update Cached
        // allocations, identity-map lookups or second map of retired records.
        for (int i = wanted; i < Math.min(recordCount, records.length); i++) records[i] = null;
        if (source != null && count < source.size() / 4) {
            normalized = new IdentityHashMap<>(count);
            selected = new ArrayList<>(count);
        }
        recordCount = wanted;
    }

    List<PredictionRenderer.Draw> candidates() { return candidates; }
    long visibleQuads(List<PredictionRenderer.Draw> visible,
                      IdentityHashMap<PredictionRenderer.Draw, PredictionDrawRanges> ranges) {
        long quads = 0;
        for (var draw : visible) {
            var candidate = normalized.get(draw);
            var selected = ranges.get(candidate);
            if (candidate == null || candidate.gpu().packed() == null) return -1;
            if (selected != null) quads += selected.quads;
        }
        return quads;
    }
    long quads() { return quads; }
    int terrainTiles() { return terrain; }
    int seamTiles() { return seams; }
    boolean allBatched() { return allBatched; }
    long rebuiltRecords() { return rebuiltRecords; }
    int retainedRecords() { return recordCount; }
    int recordCapacity() { return records.length; }
    void clear() {
        source = null; candidates = List.of(); camera = null; records = EMPTY; recordCount = 0;
        normalized = new IdentityHashMap<>(); selected = new ArrayList<>();
        meshEpoch = gpuEpoch = Long.MIN_VALUE;
        quads = 0; terrain = seams = 0; allBatched = false;
    }
}
