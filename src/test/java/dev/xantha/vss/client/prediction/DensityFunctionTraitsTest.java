package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.api.worldgen.DensityFunctionTraits;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.junit.jupiter.api.*;

class DensityFunctionTraitsTest {
    @BeforeAll static void bootstrap() { ClientTerrainSamplerTest.bootstrapMinecraft(); }
    @AfterEach void cleanup() { DensityFunctionTraits.unregister(Owned.class); DensityFunctionTraits.unregister(Horizontal.class); }
    @Test void runtimeOwnerIsNotVisitedWrappedOrRecompiledThroughParents() throws Exception {
        DensityFunctionTraits.register(Owned.class, DensityFunctionTraits.Traits.runtimeOwned());
        var external = new Owned();
        DensityFunction root = DensityFunctions.add(DensityFunctions.constant(2), external);
        var wrapped = DensityMemo.wrapRoots(root)[0];
        assertSame(root, wrapped);
        var point = new DensityFunction.SinglePointContext(-12, 72, 31);
        assertEquals(5, wrapped.compute(point)); external.value = 8;
        assertEquals(10, wrapped.compute(point));
        try (var compilation = new DensityCompilation(root)) {
            assertSame(root, compilation.roots()[0]);
            assertEquals("state=external", compilation.diagnostics());
        }
        var compiled = DensityGraphCompiler.compile(new DensityFunction[]{root});
        assertEquals(10, compiled.compute(0, point)); external.value = 11;
        assertEquals(13, compiled.compute(0, point));
    }
    @Test void explicitHorizontalNodesReuseValuesAcrossYButNotAcrossXZ() {
        DensityFunctionTraits.register(Horizontal.class, DensityFunctionTraits.Traits.coordinate(true));
        var node = new Horizontal(); var wrapped = DensityMemo.wrapRoots(node)[0];
        assertTrue(new PredictionRawDensity().inspect(wrapped).horizontal());
        assertEquals(5, wrapped.compute(new DensityFunction.SinglePointContext(2, -64, 3)));
        assertEquals(5, wrapped.compute(new DensityFunction.SinglePointContext(2, 300, 3)));
        assertEquals(1, node.calls);
        assertEquals(6, wrapped.compute(new DensityFunction.SinglePointContext(3, 300, 3)));
        assertEquals(2, node.calls);
    }
    @Test void exactTypeRegistrationDoesNotGrantPurityToDifferentSubclass() {
        DensityFunctionTraits.register(Horizontal.class, DensityFunctionTraits.Traits.coordinate(true));
        assertNull(DensityFunctionTraits.find(new VerticalChild()));
        assertFalse(new PredictionRawDensity().inspect(new VerticalChild()).horizontal());
    }
    @Test void instanceContractCanRetainContextIdentityAndRepeatedCoordinateState() {
        var source = new AwareOwned();
        var root = DensityFunctions.mul(DensityFunctions.constant(2), source);
        var wrapped = DensityMemo.wrapRoots(root)[0];
        var point = new DensityFunction.SinglePointContext(1, 2, 3);
        assertSame(root, wrapped); assertEquals(6, wrapped.compute(point)); source.value = 9;
        assertEquals(18, wrapped.compute(point)); assertSame(point, source.last);
    }
    @Test void invalidHorizontalStateContractIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DensityFunctionTraits.Traits(false, true, false));
        assertThrows(IllegalArgumentException.class, () -> new DensityFunctionTraits.Traits(true, true, true));
    }
    static class Owned implements DensityFunction.SimpleFunction {
        double value = 3; FunctionContext last;
        public double compute(FunctionContext context) { last = context; return value; }
        public double minValue() { return -100; }
        public double maxValue() { return 100; }
        public KeyDispatchDataCodec<? extends DensityFunction> codec() { throw new UnsupportedOperationException(); }
        @Override public DensityFunction mapAll(DensityFunction.Visitor visitor) {
            throw new AssertionError("runtime owner must never be transformed");
        }
    }
    static class AwareOwned extends Owned implements DensityFunctionTraits.Aware {
        public DensityFunctionTraits.Traits vssDensityTraits() { return DensityFunctionTraits.Traits.runtimeOwned(); }
    }
    static class Horizontal implements DensityFunction.SimpleFunction {
        int calls;
        public double compute(FunctionContext context) { calls++; return context.blockX() + context.blockZ(); }
        public double minValue() { return -100000000; }
        public double maxValue() { return 100000000; }
        public KeyDispatchDataCodec<? extends DensityFunction> codec() { throw new UnsupportedOperationException(); }
    }
    static class VerticalChild extends Horizontal {
        public double compute(FunctionContext context) { return context.blockY(); }
    }
}
