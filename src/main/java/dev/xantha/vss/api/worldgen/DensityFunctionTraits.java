package dev.xantha.vss.api.worldgen;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Explicit opt-in contracts for third-party nodes, consulted during graph construction only. */
public final class DensityFunctionTraits {
    public record Traits(boolean coordinatePure, boolean horizontal, boolean ownsRuntimeContext) {
        public Traits {
            if (horizontal && (!coordinatePure || ownsRuntimeContext))
                throw new IllegalArgumentException("Horizontal memo requires a coordinate-pure node");
        }
        public static Traits runtimeOwned() { return new Traits(false, false, true); }
        public static Traits coordinate(boolean horizontal) { return new Traits(true, horizontal, false); }
    }
    /** A node can publish instance-specific traits, including ownership of compiled caches. */
    public interface Aware { Traits vssDensityTraits(); }
    private static final ConcurrentHashMap<Class<?>, Traits> TYPES = new ConcurrentHashMap<>();
    private DensityFunctionTraits() { }

    /** Exact classes only: subclasses may introduce Y or runtime-state dependencies. */
    public static void register(Class<? extends DensityFunction> type, Traits traits) {
        TYPES.put(Objects.requireNonNull(type), Objects.requireNonNull(traits));
    }
    public static void unregister(Class<? extends DensityFunction> type) { TYPES.remove(type); }
    public static Traits find(Object node) {
        return node instanceof Aware aware ? Objects.requireNonNull(aware.vssDensityTraits()) : TYPES.get(node.getClass());
    }
}
