package dev.xantha.vss.client.prediction;

import java.util.List;
import org.joml.Matrix4f;
import net.minecraft.world.phys.Vec3;

/** Pre-change batch versus the integrated selection policy and production batch. */
final class RetainedComparisonBatch implements AutoCloseable {
    private final RetainedBaselineIndirectBatch baseline;
    private final PredictionIndirectBatch candidate;
    private final PredictionGpuSelectionInput input = new PredictionGpuSelectionInput();
    private long lastVisibleQuads;
    private boolean selected;
    boolean selected() { return selected; }
    RetainedComparisonBatch(boolean before) {
        baseline = before ? new RetainedBaselineIndirectBatch() : null;
        candidate = before ? null : new PredictionIndirectBatch();
    }
    List<PredictionRenderer.Draw> select(List<PredictionRenderer.Draw> visible, List<PredictionRenderer.Draw> resident,
            int depth, Matrix4f transform, Vec3 camera,
            java.util.IdentityHashMap<PredictionRenderer.Draw, PredictionDrawRanges> ranges) {
        if (baseline != null) return visible;
        candidate.workload(selected ? input.visibleQuads(visible, ranges) : lastVisibleQuads);
        selected = candidate.prepareGpuSelection(depth, 512, 512, transform, camera, 1, 1,
                input, resident, 4096);
        if (!selected) candidate.cancelGpuSelection();
        return selected ? input.candidates() : visible;
    }
    void begin(PredictionTerrainProgram program,int depth,int width,int height,Matrix4f transform,
            Vec3 camera,long residency,long ownership,long workload,long quads) {
        if (!selected) lastVisibleQuads = quads;
        if(baseline!=null) { baseline.workload(quads); baseline.begin(program,depth,width,height,transform,camera,residency,ownership,workload); }
        else { candidate.workload(quads); candidate.begin(program,depth,width,height,transform,camera,residency,ownership,workload); }
    }
    long replayPass(Object input,long version,boolean average,Vec3 camera,long now) {
        return baseline!=null?baseline.replayPass(input,version,average,camera,now):candidate.replayPass(input,version,average,camera,now);
    }
    void nextOcclusionBucket(){if(baseline!=null)baseline.nextOcclusionBucket();else candidate.nextOcclusionBucket();}
    void nextRegion(int region){if(baseline!=null)baseline.nextRegion(region);else candidate.nextRegion(region);}
    boolean add(PredictionRenderer.Draw draw,PredictionDrawRanges ranges,Vec3 camera,boolean water,boolean average,long now) {
        return baseline!=null?baseline.add(draw,ranges,camera,water,average,now):candidate.add(draw,ranges,camera,water,average,now);
    }
    long end(){return baseline!=null?baseline.end():candidate.end();}
    void flush(){if(baseline!=null)baseline.flush();else candidate.flush();}
    void fallback(PredictionRenderer.Draw draw,PredictionDrawRanges ranges,Vec3 camera) {
        if(baseline!=null)baseline.fallback(draw,ranges,camera);else candidate.fallback(draw,ranges,camera);
    }
    long encodedItems(){return baseline!=null?baseline.encodedItems():candidate.encodedItems();}
    long uploadedBytes(){return baseline!=null?baseline.uploadedBytes():candidate.uploadedBytes();}
    String diagnostics(){return baseline!=null?baseline.diagnostics():candidate.diagnostics();}
    public void close(){if(baseline!=null)baseline.close();else candidate.close();}
}
