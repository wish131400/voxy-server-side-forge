package dev.xantha.vss.api.worldgen;

import java.util.Optional;

/** One provider may cover an entire worldgen framework; no mod-name whitelist is required. */
public interface TerrainSnapshotProvider {
    String id();
    default int priority() { return 0; }
    Optional<TerrainSnapshot> capture(TerrainSnapshotContext context);
}
