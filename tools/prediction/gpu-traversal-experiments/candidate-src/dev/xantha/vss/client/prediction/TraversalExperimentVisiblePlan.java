package dev.xantha.vss.client.prediction;

import java.util.List;
import java.util.function.Function;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Baseline visibility calls the current platform's production implementation. */
final class TraversalExperimentVisiblePlan<K,T> {
    private final PredictionVisiblePlan<K,T> production;
    private final TraversalCandidateVisiblePlan<K,T> candidate;
    TraversalExperimentVisiblePlan(boolean baseline,Function<T,AABB> bounds) {
        production=baseline?new PredictionVisiblePlan<>(bounds):null;
        candidate=baseline?null:new TraversalCandidateVisiblePlan<>(bounds);
    }
    void put(K key,T value){if(production!=null)production.put(key,value);else candidate.put(key,value);}
    List<T> selectStable(Vec3 camera,Matrix4f view,Matrix4f projection,Frustum frustum) {
        return production!=null?production.selectStable(camera,view,projection,frustum):candidate.selectStable(camera,view,projection,frustum);
    }
    List<T> residents(){return candidate.residents();}
}
