package dev.xantha.vss.api.worldgen;

import dev.xantha.vss.common.VSSLogger;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.resources.ResourceLocation;

/** Register during mod setup on the server. Providers are evaluated once per cached dimension profile. */
public final class TerrainSnapshots {
    private static final CopyOnWriteArrayList<TerrainSnapshotProvider> PROVIDERS = new CopyOnWriteArrayList<>();
    private TerrainSnapshots() { }

    public static synchronized void register(TerrainSnapshotProvider provider) {
        if (provider == null || ResourceLocation.tryParse(provider.id()) == null)
            throw new IllegalArgumentException("Invalid terrain snapshot provider");
        PROVIDERS.removeIf(previous -> previous.id().equals(provider.id()));
        PROVIDERS.add(provider);
        PROVIDERS.sort(Comparator.comparingInt(TerrainSnapshotProvider::priority).reversed()
                .thenComparing(TerrainSnapshotProvider::id));
    }
    public static synchronized void unregister(String id) { PROVIDERS.removeIf(provider -> provider.id().equals(id)); }
    public static List<TerrainSnapshotProvider> providers() { return List.copyOf(PROVIDERS); }

    public static Optional<TerrainSnapshot> capture(TerrainSnapshotContext context) {
        for (TerrainSnapshotProvider provider : PROVIDERS) {
            try {
                Optional<TerrainSnapshot> result = provider.capture(context);
                if (result.isPresent()) return result;
            } catch (java.util.concurrent.CancellationException cancelled) {
                throw cancelled;
            } catch (RuntimeException | LinkageError failure) {
                VSSLogger.warn("VSS terrain snapshot provider " + provider.id() + " unavailable", failure);
            }
        }
        return Optional.empty();
    }
}
