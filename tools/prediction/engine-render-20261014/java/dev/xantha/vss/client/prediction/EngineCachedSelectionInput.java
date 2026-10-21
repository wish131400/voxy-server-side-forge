package dev.xantha.vss.client.prediction;

import java.util.*;
import net.minecraft.world.phys.Vec3;

/** Experimental reuse of unchanged records; all resources remain version checked. */
final class EngineCachedSelectionInput {
    private List<PredictionRenderer.Draw> source, candidates = List.of();
    private Vec3 camera;
    private double horizon;
    private long meshEpoch = Long.MIN_VALUE, gpuEpoch = Long.MIN_VALUE;
    private long quads, rebuiltRecords;
    private int terrain, seams;
    private boolean allBatched;
    private IdentityHashMap<PredictionRenderer.Draw, Cached> records = new IdentityHashMap<>();
    private IdentityHashMap<PredictionRenderer.Draw, Cached> spare = new IdentityHashMap<>();

    private record Cached(PredictionRenderer.Draw draw, PredictionPackedMesh packed,
                          long meshRevision, long gpuRevision, long quads, boolean batched) { }

    void update(List<PredictionRenderer.Draw> draws, Vec3 nextCamera, double nextHorizon) {
        long nextMesh = PredictionPackedMesh.submissionEpoch(), nextGpu = PredictionGpuTile.submissionEpoch();
        boolean sameView = nextCamera.equals(camera) && nextHorizon == horizon;
        if (source == draws && meshEpoch == nextMesh && gpuEpoch == nextGpu && sameView) return;
        quads = 0; terrain = seams = 0; allBatched = true;
        var selected = new ArrayList<PredictionRenderer.Draw>(draws.size());
        boolean unchanged = true;
        spare.clear();
        for (var original : draws) {
            var packed = original.gpu().packed();
            long meshRevision = packed == null ? -1 : packed.submissionRevision();
            long gpuRevision = original.gpu().submissionRevision();
            var entry = sameView ? records.get(original) : null;
            if (entry == null || entry.packed != packed || entry.meshRevision != meshRevision
                    || entry.gpuRevision != gpuRevision) {
                entry = normalize(original, packed, meshRevision, gpuRevision, nextCamera, nextHorizon);
                rebuiltRecords++;
            }
            spare.put(original, entry);
            var draw = entry.draw;
            if (draw == null) { unchanged = false; continue; }
            selected.add(draw);
            unchanged &= draw == original;
            if (!entry.batched) { allBatched = false; continue; }
            quads += entry.quads;
            if (draw.seam()) seams++; else terrain++;
        }
        var retired = records; records = spare; spare = retired; spare.clear();
        // An unrelated resource epoch must not republish identical normalized draws.
        boolean sameCandidates = selected.size() == candidates.size();
        for (int i = 0; sameCandidates && i < selected.size(); i++) sameCandidates = selected.get(i) == candidates.get(i);
        if (!sameCandidates) candidates = unchanged ? draws : List.copyOf(selected);
        source = draws; meshEpoch = nextMesh; gpuEpoch = nextGpu;
        camera = nextCamera; horizon = nextHorizon;
    }

    private static Cached normalize(PredictionRenderer.Draw original, PredictionPackedMesh packed,
                                    long meshRevision, long gpuRevision, Vec3 camera, double horizon) {
        var draw = original;
        if (packed == null) return new Cached(null, null, meshRevision, gpuRevision, 0, false);
        if (!draw.seam()) {
            var geometry = new PredictionRenderGeometry.Entry(draw.tile());
            if (!geometry.visible(camera, null, horizon)) return new Cached(null, packed, meshRevision, gpuRevision, 0, false);
            int faces = VssLodFaceGroup.visibleMask(geometry.bounds(), camera);
            if (packed.downFaces()) faces |= 1 << VssLodFaceGroup.HORIZONTAL;
            if (faces != draw.faces()) draw = new PredictionRenderer.Draw(draw.tile(), draw.gpu(), draw.allowed(),
                    draw.features(), false, faces, draw.morph(), draw.bounds(), draw.completeOwnership());
        }
        boolean batched = draw.gpu().arenaSlice() != null;
        return new Cached(draw, packed, meshRevision, gpuRevision,
                batched ? packed.drawRanges(false, draw.faces()).quads : 0, batched);
    }

    List<PredictionRenderer.Draw> candidates() { return candidates; }
    long visibleQuads(List<PredictionRenderer.Draw> visible, IdentityHashMap<PredictionRenderer.Draw, PredictionDrawRanges> ranges) {
        long quads = 0;
        for (var original : visible) {
            var entry = records.get(original);
            if (entry == null || entry.draw == null || entry.draw.gpu().packed() == null) return -1;
            var selected = ranges.get(entry.draw);
            if (selected != null) quads += selected.quads;
        }
        return quads;
    }
    long quads() { return quads; }
    int terrainTiles() { return terrain; }
    int seamTiles() { return seams; }
    boolean allBatched() { return allBatched; }
    long rebuiltRecords() { return rebuiltRecords; }
    int retainedRecords() { return records.size() + spare.size(); }
    void clear() {
        source = null; candidates = List.of(); camera = null; records.clear(); spare.clear();
        quads = 0; terrain = seams = 0; allBatched = false;
    }
}
