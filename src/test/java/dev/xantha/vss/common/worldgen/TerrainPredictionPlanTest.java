package dev.xantha.vss.common.worldgen;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import dev.xantha.vss.api.worldgen.TerrainSnapshot;
import org.junit.jupiter.api.Test;

class TerrainPredictionPlanTest {
    @Test void contractsRoundTripWithoutChangingExecutorOrNativePermission() {
        for (var kind : TerrainPredictionPlan.Kind.values()) {
            var plan = new TerrainPredictionPlan(kind, "example:framework", 7,
                    kind == TerrainPredictionPlan.Kind.NOISE_GRAPH, "test context");
            var generator = new JsonObject(); generator.add(TerrainPredictionPlan.FIELD, plan.encode());
            assertEquals(plan, TerrainPredictionPlan.read(generator));
        }
    }
    @Test void oldNoiseSnapshotKeepsItsFastPath() {
        assertEquals(TerrainPredictionPlan.noise("vss:noise_graph", 1, true), TerrainPredictionPlan.read(new JsonObject()));
    }
    @Test void unknownSchemaAndMalformedPlanCannotSilentlyBecomeNoise() {
        var root = new JsonObject(); var plan = TerrainPredictionPlan.noise("vss:noise_graph", 1, true).encode();
        plan.addProperty("schema", 2); root.add(TerrainPredictionPlan.FIELD, plan);
        assertThrows(IllegalArgumentException.class, () -> TerrainPredictionPlan.read(root));
        root.add(TerrainPredictionPlan.FIELD, new JsonObject());
        assertThrows(RuntimeException.class, () -> TerrainPredictionPlan.read(root));
    }
    @Test void nativePermissionCannotBeGrantedToOpaqueAlgorithms() {
        assertThrows(IllegalArgumentException.class, () -> new TerrainPredictionPlan(
                TerrainPredictionPlan.Kind.LOD_SOURCE, "example:framework", 1, true, ""));
        assertThrows(IllegalArgumentException.class, () -> TerrainPredictionPlan.noise("bad id", 0, true));
    }
    @Test void providerParametersAreImmutableAndNeedACompleteProjection() {
        var parameters = new JsonObject(); parameters.addProperty("height", 120);
        var snapshot = TerrainSnapshot.lod("example:framework", 1, parameters);
        parameters.addProperty("height", 0);
        snapshot.parameters().addProperty("height", 12);
        assertEquals(120, snapshot.parameters().get("height").getAsInt());
        assertThrows(IllegalArgumentException.class, () -> TerrainSnapshot.noise("example:framework", 1, true, parameters));
        parameters.add(TerrainPredictionPlan.FIELD, new JsonObject());
        assertThrows(IllegalArgumentException.class, () -> TerrainSnapshot.lod("example:framework", 1, parameters));
    }
    @Test void noiseProjectionCannotReplaceTheActualGeneratorOrPrivateMetadata() {
        for (String field : java.util.List.of("vss_generator", "vss_generator_error", "vss_lod_parameters", "vss_unsupported_reason")) {
            var projection = new JsonObject();
            projection.add("settings", new JsonObject()); projection.add("biome_source", new JsonObject());
            projection.addProperty(field, "unexpected");
            assertThrows(IllegalArgumentException.class, () -> TerrainSnapshot.noise("example:framework", 1, true, projection));
        }
    }
}
