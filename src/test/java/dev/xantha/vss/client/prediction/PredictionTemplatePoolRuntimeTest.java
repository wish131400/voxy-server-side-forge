package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PredictionTemplatePoolRuntimeTest {
    public interface PoolAccess {
        Iterable<?> getLithostitchedTemplates();
        void compileRawTemplates();
    }

    public static final class WeightedPool implements PoolAccess {
        final List<Integer> weights = List.of(2, 5, 1);
        final List<Integer> compiled = new ArrayList<>();
        int compilations;
        public Iterable<?> getLithostitchedTemplates() { return compiled; }
        public void compileRawTemplates() { compilations++; compiled.addAll(weights); }
    }

    @Test void decodedPoolIsCompletedOnceAndExistingServerWeightsArePreserved() {
        var decoded = new WeightedPool();
        var alreadyCompiled = new WeightedPool();
        alreadyCompiled.compileRawTemplates();
        List<Object> pools = List.of(decoded, alreadyCompiled, new Object());
        PredictionTemplatePoolRuntime.initialize(pools, PoolAccess.class);
        PredictionTemplatePoolRuntime.initialize(pools, PoolAccess.class);
        assertEquals(decoded.weights, decoded.compiled);
        assertEquals(8, decoded.compiled.stream().mapToInt(Integer::intValue).sum());
        assertEquals(1, decoded.compilations);
        assertEquals(1, alreadyCompiled.compilations);
        assertEquals(alreadyCompiled.weights, alreadyCompiled.compiled);
    }

    @Test void absentOptionalModNeverTouchesVanillaPools() {
        Iterable<?> noTraversal = () -> { throw new AssertionError("Absent mod must not scan pools"); };
        PredictionTemplatePoolRuntime.initialize(noTraversal, new ClassLoader(null) { });
    }

    @Test void unsupportedOptionalContractFailsExplicitly() {
        assertThrows(IllegalStateException.class,
                () -> PredictionTemplatePoolRuntime.initialize(List.of(new Object()), Object.class));
    }
}
