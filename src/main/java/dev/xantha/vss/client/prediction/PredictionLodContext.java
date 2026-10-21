package dev.xantha.vss.client.prediction;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.xantha.vss.common.worldgen.TerrainPredictionPlan;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.chunk.ChunkGenerator;

/** Public snapshot context for client integrations; expensive decoding is lazy and initialization-only. */
public final class PredictionLodContext {
    private final DimensionProfile profile;
    private final TerrainPredictionPlan plan;
    private final JsonObject generator;
    private final ClientWorldgenRegistries worldgen;
    private final RegistryAccess client;
    private ClientTerrainSampler noise;
    private boolean noiseAttempted;
    private ChunkGenerator decodedGenerator;

    PredictionLodContext(DimensionProfile profile, TerrainPredictionPlan plan, JsonObject generator,
                         ClientWorldgenRegistries worldgen, RegistryAccess client) {
        this.profile = profile; this.plan = plan; this.generator = generator;
        this.worldgen = worldgen; this.client = client;
    }
    public DimensionProfile profile() { return profile; }
    public TerrainPredictionPlan plan() { return plan; }
    public RegistryAccess registries() { return worldgen.access(); }
    public RegistryOps<JsonElement> ops() { return worldgen.ops(); }
    public JsonObject parameters() {
        return generator.has("vss_lod_parameters") ? generator.getAsJsonObject("vss_lod_parameters").deepCopy() : new JsonObject();
    }
    String cacheIdentity() {
        return profile.minY() + ":" + profile.height() + ":"
                + dev.xantha.vss.common.worldgen.WorldgenJson.sha256(generator) + ':'
                + dev.xantha.vss.common.worldgen.WorldgenJson.sha256(worldgen.snapshotRoot());
    }
    /** Actual codec is available when it was serializable; using it does not run fillFromNoise. */
    public ChunkGenerator generator() {
        if (decodedGenerator == null) {
            if (!generator.has("vss_generator")) throw new IllegalStateException("No reproducible custom generator codec");
            decodedGenerator = ChunkGenerator.CODEC.parse(ops(), generator.get("vss_generator")).getOrThrow(false, message -> { throw new IllegalArgumentException(message); });
        }
        return decodedGenerator;
    }
    /** Optional biome/material/decoration context. This is not a full chunk generator fallback. */
    public ClientTerrainSampler noiseContext() {
        if (!noiseAttempted) {
            noiseAttempted = true;
            if (generator.has("settings") && generator.has("biome_source"))
                noise = ClientWorldgenProfileDecoder.decodeJavaSampler(profile, generator, worldgen, client);
        }
        return noise;
    }
    ClientTerrainSampler initializedNoiseContext() { return noise; }
    void closeUnused() { if (noise != null) noise.close(); }
}
