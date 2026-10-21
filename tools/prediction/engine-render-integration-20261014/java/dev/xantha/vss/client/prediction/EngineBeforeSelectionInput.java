package dev.xantha.vss.client.prediction;

import java.util.List;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import net.minecraft.world.phys.Vec3;

/** Summarizes immutable resident input only when geometry or residency changes. */
final class EngineBeforeSelectionInput {
    private List<PredictionRenderer.Draw> source;
    private List<PredictionRenderer.Draw> candidates = List.of();
    private Vec3 camera;
    private double horizon;
    private long meshEpoch = Long.MIN_VALUE, gpuEpoch = Long.MIN_VALUE;
    private long quads;
    private int terrain, seams;
    private boolean allBatched;
    private final IdentityHashMap<PredictionRenderer.Draw, PredictionRenderer.Draw> normalized = new IdentityHashMap<>();

    void update(List<PredictionRenderer.Draw> draws, Vec3 nextCamera, double nextHorizon) {
        long nextMesh = PredictionPackedMesh.submissionEpoch(), nextGpu = PredictionGpuTile.submissionEpoch();
        if (source == draws && meshEpoch == nextMesh && gpuEpoch == nextGpu
                && nextCamera.equals(camera) && nextHorizon == horizon) return;
        quads = 0; terrain = seams = 0; allBatched = true;
        var selected = new ArrayList<PredictionRenderer.Draw>(draws.size());
        normalized.clear();
        boolean unchanged = true;
        for (var draw : draws) {
            var original = draw;
            var packed = draw.gpu().packed();
            if (packed == null) { unchanged = false; continue; }
            if (!draw.seam()) {
                // Offscreen residents may still carry the faces of an older
                // camera position. Normalize them once after motion, and keep
                // exactly the Scene horizon contract for terrain candidates.
                var geometry = new PredictionRenderGeometry.Entry(draw.tile());
                if (!geometry.visible(nextCamera, null, nextHorizon)) { unchanged = false; continue; }
                int faces = VssLodFaceGroup.visibleMask(geometry.bounds(), nextCamera);
                if (packed.downFaces()) faces |= 1 << VssLodFaceGroup.HORIZONTAL;
                if (faces != draw.faces()) {
                    draw = new PredictionRenderer.Draw(draw.tile(), draw.gpu(), draw.allowed(), draw.features(),
                            false, faces, draw.morph(), draw.bounds(), draw.completeOwnership());
                    unchanged = false;
                }
            }
            selected.add(draw);
            normalized.put(original, draw);
            if (draw.gpu().arenaSlice() == null) { allBatched = false; continue; }
            quads += packed.drawRanges(false, draw.faces()).quads;
            if (draw.seam()) seams++; else terrain++;
        }
        candidates = unchanged ? draws : List.copyOf(selected);
        source = draws; meshEpoch = nextMesh; gpuEpoch = nextGpu;
        camera = nextCamera; horizon = nextHorizon;
    }
    List<PredictionRenderer.Draw> candidates() { return candidates; }
    long visibleQuads(List<PredictionRenderer.Draw> visible,
                      IdentityHashMap<PredictionRenderer.Draw, PredictionDrawRanges> ranges) {
        long quads = 0;
        for (var draw : visible) {
            var candidate = normalized.get(draw);
            var selected = ranges.get(candidate);
            // New members or changed geometry need ordinary preparation first.
            if (candidate == null || candidate.gpu().packed() == null) return -1;
            if (selected != null) quads += selected.quads;
        }
        return quads;
    }
    long quads() { return quads; }
    int terrainTiles() { return terrain; }
    int seamTiles() { return seams; }
    boolean allBatched() { return allBatched; }
    void clear() { source = null; candidates = List.of(); normalized.clear(); camera = null; quads = 0; terrain = seams = 0; allBatched = false; }
}
