package dev.xantha.vss.client.prediction;

import org.joml.Matrix4f;
import net.minecraft.world.phys.Vec3;

/** Compares the current production batch with an isolated experimental batch. */
final class TraversalExperimentBatch implements AutoCloseable {
    private final PredictionIndirectBatch production;
    private final TraversalCandidateIndirectBatch candidate;
    TraversalExperimentBatch(boolean baseline) {
        production=baseline?new PredictionIndirectBatch():null;
        candidate=baseline?null:new TraversalCandidateIndirectBatch();
    }
    void begin(PredictionTerrainProgram program,int depth,int width,int height,Matrix4f transform,
            Vec3 camera,long residency,long ownership,long workload) {
        if(production!=null)production.begin(program,depth,width,height,transform,camera,residency,ownership,workload);
        else candidate.begin(program,depth,width,height,transform,camera,residency,ownership,workload);
    }
    long replayPass(Object input,long version,boolean average,Vec3 camera,long now) {
        return production!=null?production.replayPass(input,version,average,camera,now):candidate.replayPass(input,version,average,camera,now);
    }
    void nextOcclusionBucket(){if(production!=null)production.nextOcclusionBucket();else candidate.nextOcclusionBucket();}
    void nextRegion(int region){if(production!=null)production.nextRegion(region);else candidate.nextRegion(region);}
    boolean add(PredictionRenderer.Draw draw,PredictionDrawRanges ranges,Vec3 camera,boolean water,boolean average,long now) {
        return production!=null?production.add(draw,ranges,camera,water,average,now):candidate.add(draw,ranges,camera,water,average,now);
    }
    long end(){return production!=null?production.end():candidate.end();}
    void flush(){if(production!=null)production.flush();else candidate.flush();}
    void fallback(PredictionRenderer.Draw draw,PredictionDrawRanges ranges,Vec3 camera) {
        if(production!=null)production.fallback(draw,ranges,camera);else candidate.fallback(draw,ranges,camera);
    }
    long encodedItems(){return production!=null?production.encodedItems():candidate.encodedItems();}
    long uploadedBytes(){return production!=null?production.uploadedBytes():candidate.uploadedBytes();}
    String diagnostics(){return production!=null?production.diagnostics():candidate.diagnostics();}
    public void close(){if(production!=null)production.close();else candidate.close();}
}
