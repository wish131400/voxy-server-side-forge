package dev.xantha.vss.networking.server.session;

import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload;
import net.minecraft.server.MinecraftServer;

/**
 * Server-side cache equivalent to the WorldgenSyncHolder.  Registry
 * encoding is cached per requested dimension and worldgen lifetime, not once
 * per player login or refresh broadcast.
 */
public final class WorldgenProfileHolder {
    private static MinecraftServer owner;
    private static final java.util.LinkedHashMap<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, WorldgenProfileS2CPayload>
            payloads = new java.util.LinkedHashMap<>(8, .75f, true);

    private WorldgenProfileHolder() {
    }

    public static synchronized WorldgenProfileS2CPayload payloadFor(
            MinecraftServer server, long revision) {
        return payloadFor(server, revision, null);
    }
    public static synchronized WorldgenProfileS2CPayload payloadFor(MinecraftServer server, long revision,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        if (server == null) {
            throw new IllegalArgumentException("server cannot be null");
        }
        if (owner != server) { payloads.clear(); owner = server; }
        WorldgenProfileS2CPayload cached = payloads.get(dimension);
        if (cached != null) {
            if (cached.revision() != revision) {
                // Rate/generation toggles change revision; only reload/clear changes worldgen inputs.
                cached = new WorldgenProfileS2CPayload(cached.formatVersion(), cached.seed(), revision,
                        cached.registriesCompression(), cached.registriesRawSize(), cached.registries(), cached.dimensions());
                payloads.put(dimension, cached);
            }
            return cached;
        }
        long started = System.nanoTime();
        WorldgenProfileS2CPayload built = WorldgenProfileBuilder.build(server, revision, dimension);
        owner = server;
        payloads.put(dimension, built);
        while (payloads.size() > 4) payloads.remove(payloads.keySet().iterator().next());
        // Bandwidth diagnostic: what a client actually receives when it joins.
        long registryWire = built.registries().length;
        long registryRaw = built.registriesRawSize();
        long generatorWire = 0L;
        long generatorRaw = 0L;
        for (WorldgenProfileS2CPayload.DimensionProfile entry : built.dimensions()) {
            generatorWire += entry.generatorData().length;
            generatorRaw += entry.generatorRawSize();
        }
        long totalWire = registryWire + generatorWire;
        VSSLogger.info("VSS built worldgen profile for " + built.dimensions().size()
                + " dimensions in " + ((System.nanoTime() - started) / 1_000_000L) + " ms"
                + "; registries " + registryRaw + "->" + registryWire + " B"
                + ", generators " + generatorRaw + "->" + generatorWire + " B"
                + ", total wire " + totalWire + " B (" + (totalWire / 1048576.0) + " MiB)");
        return built;
    }

    public static synchronized void clear() {
        owner = null;
        payloads.clear();
    }
}
