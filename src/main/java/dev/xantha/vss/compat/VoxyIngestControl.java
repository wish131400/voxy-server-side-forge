package dev.xantha.vss.compat;

import dev.xantha.vss.config.VSSClientConfig;

/**
 * Separates Voxy's local vanilla-chunk ingestion from VSS server ingestion.
 *
 * <p>Voxy routes both paths through {@code VoxelIngestService}. A scoped marker
 * lets the VSS raw-ingest bridge bypass the local-ingestion gate without
 * globally enabling client-side chunk conversion.</p>
 */
public final class VoxyIngestControl {
    private static final ThreadLocal<Integer> SERVER_INGEST_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    private VoxyIngestControl() {
    }

    public static boolean isLocalChunkIngestionEnabled() {
        return VSSClientConfig.CONFIG.enableLocalChunkIngestion;
    }

    public static boolean isServerIngestActive() {
        return SERVER_INGEST_DEPTH.get() > 0;
    }

    static boolean runServerIngest(ThrowingBooleanSupplier action) throws Throwable {
        int previousDepth = SERVER_INGEST_DEPTH.get();
        SERVER_INGEST_DEPTH.set(previousDepth + 1);
        try {
            return action.getAsBoolean();
        } finally {
            if (previousDepth == 0) {
                SERVER_INGEST_DEPTH.remove();
            } else {
                SERVER_INGEST_DEPTH.set(previousDepth);
            }
        }
    }

    @FunctionalInterface
    interface ThrowingBooleanSupplier {
        boolean getAsBoolean() throws Throwable;
    }
}
