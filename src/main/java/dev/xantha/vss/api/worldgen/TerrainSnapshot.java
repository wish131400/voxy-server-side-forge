package dev.xantha.vss.api.worldgen;

import com.google.gson.JsonObject;
import dev.xantha.vss.common.worldgen.TerrainPredictionPlan;
import java.util.Objects;

/** Parameters must contain a complete noise projection or configuration for a registered LOD source. */
public record TerrainSnapshot(TerrainPredictionPlan plan, JsonObject parameters) {
    public TerrainSnapshot {
        Objects.requireNonNull(plan);
        parameters = Objects.requireNonNull(parameters).deepCopy();
        if (parameters.has(TerrainPredictionPlan.FIELD))
            throw new IllegalArgumentException("Terrain parameters cannot replace the execution contract");
        if (plan.kind() == TerrainPredictionPlan.Kind.NOISE_GRAPH
                && (!parameters.has("settings") || !parameters.has("biome_source")))
            throw new IllegalArgumentException("A noise projection needs settings and biome_source");
        if (plan.kind() == TerrainPredictionPlan.Kind.NOISE_GRAPH) {
            for (String field : new String[]{"vss_generator", "vss_generator_error", "vss_lod_parameters", "vss_unsupported_reason"})
                if (parameters.has(field)) throw new IllegalArgumentException("Reserved terrain snapshot field: " + field);
        }
        if (plan.kind() != TerrainPredictionPlan.Kind.NOISE_GRAPH
                && plan.kind() != TerrainPredictionPlan.Kind.LOD_SOURCE)
            throw new IllegalArgumentException("Providers expose a noise projection or a LOD source");
    }
    @Override public JsonObject parameters() { return parameters.deepCopy(); }

    public static TerrainSnapshot noise(String id, int revision, boolean nativeCompatible, JsonObject projection) {
        return new TerrainSnapshot(TerrainPredictionPlan.noise(id, revision, nativeCompatible), projection);
    }
    public static TerrainSnapshot lod(String id, int revision, JsonObject parameters) {
        return new TerrainSnapshot(new TerrainPredictionPlan(TerrainPredictionPlan.Kind.LOD_SOURCE,
                id, revision, false, ""), parameters);
    }
}
