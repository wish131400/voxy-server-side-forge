package dev.xantha.vss.compat;

import dev.xantha.vss.config.VSSClientConfig;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

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
    private static final ClassValue<MethodHandle> VOXY_INGEST_SWITCH = new ClassValue<>() {
        @Override protected MethodHandle computeValue(Class<?> type) {
            try {
                var field = type.getField("ingestEnabled");
                field.setAccessible(true);
                return MethodHandles.lookup().unreflectGetter(field)
                        .asType(MethodType.methodType(boolean.class, Object.class));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot access Voxy ingest switch", failure);
            }
        }
    };

    private VoxyIngestControl() {
    }

    public static boolean isLocalChunkIngestionEnabled() {
        return VSSClientConfig.CONFIG.enableLocalChunkIngestion;
    }

    public static boolean isServerIngestActive() {
        return SERVER_INGEST_DEPTH.get() > 0;
    }

    /** Overrides only the ordinary switch; Voxy's other guards run unchanged. */
    public static boolean isVoxyIngestEnabled(Object config) {
        if (isServerIngestActive()) return true;
        try {
            return (boolean) VOXY_INGEST_SWITCH.get(config.getClass()).invokeExact(config);
        } catch (Throwable failure) {
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("Cannot read Voxy ingest switch", failure);
        }
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
