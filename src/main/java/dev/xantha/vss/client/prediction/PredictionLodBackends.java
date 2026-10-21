package dev.xantha.vss.client.prediction;

import dev.xantha.vss.common.worldgen.TerrainPredictionPlan;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;

/** Register on the client during mod setup; one lookup selects the executor for the whole dimension. */
public final class PredictionLodBackends {
    private record Backend(int firstRevision, int lastRevision, Function<PredictionLodContext, PredictionLodSource> factory) { }
    private static final ConcurrentHashMap<String, Backend> BACKENDS = new ConcurrentHashMap<>();
    private PredictionLodBackends() { }
    public static void register(String id, int firstRevision, int lastRevision,
                                Function<PredictionLodContext, PredictionLodSource> factory) {
        if (ResourceLocation.tryParse(id) == null || firstRevision < 1 || lastRevision < firstRevision)
            throw new IllegalArgumentException("Invalid LOD backend identity or revision range");
        BACKENDS.put(id, new Backend(firstRevision, lastRevision, Objects.requireNonNull(factory)));
    }
    public static void unregister(String id) { BACKENDS.remove(id); }

    static ClientTerrainSampler open(PredictionLodContext context) {
        TerrainPredictionPlan plan = context.plan();
        Backend backend = BACKENDS.get(plan.backend());
        if (backend == null) throw new IllegalStateException("Missing LOD backend " + plan.backend());
        if (plan.revision() < backend.firstRevision || plan.revision() > backend.lastRevision)
            throw new IllegalStateException("Unsupported LOD backend revision " + plan.backend() + ':' + plan.revision());
        PredictionLodSource source = null;
        try {
            source = Objects.requireNonNull(backend.factory.apply(context), "LOD backend returned no source");
            if (source.cacheRevision() < 1) throw new IllegalArgumentException("Invalid LOD source cache revision");
            return new PredictionLodSampler(context, source);
        } catch (RuntimeException | LinkageError failure) {
            try { if (source != null) source.close(); }
            finally { context.closeUnused(); }
            throw failure;
        }
    }
}
