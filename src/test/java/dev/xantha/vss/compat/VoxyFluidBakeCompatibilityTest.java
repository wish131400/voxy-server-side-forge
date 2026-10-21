package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.mixin.voxy.ModelFactoryFluidBakeOrderMixin;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

class VoxyFluidBakeCompatibilityTest {
    private static final class CurrentTarget extends ModelFactoryFluidBakeOrderMixin {
        private final ConcurrentLinkedDeque<Object> blockBakeQueue = new ConcurrentLinkedDeque<>();
    }
    private static final class LegacyTarget extends ModelFactoryFluidBakeOrderMixin {
        private final ConcurrentLinkedDeque<Object> bakeQueue = new ConcurrentLinkedDeque<>();
    }
    private static final class BothTarget extends ModelFactoryFluidBakeOrderMixin {
        private final ConcurrentLinkedDeque<Object> blockBakeQueue = new ConcurrentLinkedDeque<>();
        private final ConcurrentLinkedDeque<Object> bakeQueue = new ConcurrentLinkedDeque<>();
    }

    @Test void queueGetterSupportsTheNewNameAndTheVerifiedLegacyFallback() throws Exception {
        var getter = ModelFactoryFluidBakeOrderMixin.class.getDeclaredMethod("vss$getBakeQueue");
        getter.setAccessible(true);
        var current = new CurrentTarget();
        var legacy = new LegacyTarget();
        var both = new BothTarget();
        assertSame(current.blockBakeQueue, getter.invoke(current));
        assertSame(current.blockBakeQueue, getter.invoke(current), "cached field keeps the correct queue");
        assertSame(legacy.bakeQueue, getter.invoke(legacy));
        assertSame(both.blockBakeQueue, getter.invoke(both), "the block-model queue takes precedence");
    }

    @Test void realSpongeFluidCallbackMatchesTheReleasedModelFactory() throws Exception {
        String path = System.getProperty("vss.voxyJar");
        String target = "me.cortex.voxy.client.core.model.ModelFactory";
        try (var zip = new ZipFile(path)) {
            ClassNode node = new ClassNode();
            new ClassReader(zip.getInputStream(zip.getEntry(target.replace('.', '/') + ".class"))).accept(node, 0);
            assertTrue(node.fields.stream().anyMatch(f -> f.name.equals("blockBakeQueue")
                    && f.desc.equals("Ljava/util/concurrent/ConcurrentLinkedDeque;")));
        }
        var harness = new VoxyMixinHarness(path, """
            {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin",
             "compatibilityLevel":"JAVA_17","client":["voxy.ModelFactoryFluidBakeOrderMixin"]}
            """);
        ClassNode transformed = new ClassNode();
        new ClassReader(harness.apply(target)).accept(transformed, 0);
        MethodNode process = transformed.methods.stream().filter(m -> m.name.equals("processModelResult"))
                .findFirst().orElseThrow();
        assertTrue(java.util.stream.StreamSupport.stream(process.instructions.spliterator(), false)
                .anyMatch(i -> i instanceof MethodInsnNode c && c.name.contains("vss$deferWaterloggedBake")));
        for (MethodNode method : transformed.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(transformed.name, method);
        }
    }
}
