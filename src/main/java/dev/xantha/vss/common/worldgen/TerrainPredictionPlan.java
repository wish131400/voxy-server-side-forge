package dev.xantha.vss.common.worldgen;

import com.google.gson.JsonObject;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** A versioned execution contract, resolved once per dimension, never per sample. */
public record TerrainPredictionPlan(Kind kind, String backend, int revision,
                                    boolean nativeCompatible, String reason) {
    public static final String FIELD = "vss_terrain_plan";
    public static final int SCHEMA = 1;
    public enum Kind { NOISE_GRAPH, FLAT_LAYERS, LOD_SOURCE, UNAVAILABLE }

    public TerrainPredictionPlan {
        Objects.requireNonNull(kind);
        if (backend == null || ResourceLocation.tryParse(backend) == null || revision < 1)
            throw new IllegalArgumentException("Invalid terrain backend identity");
        if (nativeCompatible && kind != Kind.NOISE_GRAPH)
            throw new IllegalArgumentException("Only a noise projection can use the native graph backend");
        reason = reason == null ? "" : reason;
        if (reason.length() > 2048) reason = reason.substring(0, 2048);
    }

    public static TerrainPredictionPlan noise(String backend, int revision, boolean nativeCompatible) {
        return new TerrainPredictionPlan(Kind.NOISE_GRAPH, backend, revision, nativeCompatible, "");
    }

    public static TerrainPredictionPlan unavailable(String reason) {
        return new TerrainPredictionPlan(Kind.UNAVAILABLE, "vss:authoritative", 1, false, reason);
    }

    public JsonObject encode() {
        var result = new JsonObject();
        result.addProperty("schema", SCHEMA);
        result.addProperty("kind", kind.name());
        result.addProperty("backend", backend);
        result.addProperty("revision", revision);
        result.addProperty("native_compatible", nativeCompatible);
        if (!reason.isEmpty()) result.addProperty("reason", reason);
        return result;
    }

    /** Old snapshots retain their existing noise path; malformed new plans never become noise implicitly. */
    public static TerrainPredictionPlan read(JsonObject generator) {
        if (!generator.has(FIELD)) return noise("vss:noise_graph", 1, true);
        var value = generator.getAsJsonObject(FIELD);
        if (value.get("schema").getAsInt() != SCHEMA)
            throw new IllegalArgumentException("Unsupported VSS terrain plan schema");
        return new TerrainPredictionPlan(Kind.valueOf(value.get("kind").getAsString()),
                value.get("backend").getAsString(), value.get("revision").getAsInt(),
                value.get("native_compatible").getAsBoolean(),
                value.has("reason") ? value.get("reason").getAsString() : "");
    }
}
