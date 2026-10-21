package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.testutil.VoxyMixinHarness;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

class VoxyIngestMixinApplicationTest {
    String jarProperty() { return "vss.voxyJar"; }

    @Test void realSpongeApplicationRetainsTheReturnHookAndVerifiesTheOuterWrapper() throws Exception {
        String configuration = """
            {"required":true,"minVersion":"0.8","package":"dev.xantha.vss.mixin",
             "plugin":"dev.xantha.vss.mixin.voxy.StrictVoxyIngestPlugin","compatibilityLevel":"JAVA_17",
             "client":["voxy.StrictVoxyIngestMixin"],"injectors":{"defaultRequire":1}}
            """;
        var harness = new VoxyMixinHarness(System.getProperty(jarProperty()), configuration);
        byte[] transformed = harness.apply("me.cortex.voxy.common.world.service.VoxelIngestService");
        ClassNode node = new ClassNode();
        new ClassReader(transformed).accept(node, 0);
        assertTrue(node.methods.stream().anyMatch(m -> m.name.equals("vss$ingestTrackingReady")));
        MethodNode body = node.methods.stream().filter(m -> m.name.equals("vss$processTrackedIngestBody"))
                .findFirst().orElseThrow();
        assertTrue(node.methods.stream().anyMatch(m -> java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false)
                .anyMatch(i -> i instanceof MethodInsnNode call && call.name.equals("workerDone"))));
        assertTrue(java.util.stream.StreamSupport.stream(body.instructions.spliterator(), false)
                .anyMatch(i -> i instanceof MethodInsnNode call && call.name.contains("vss$committedIngest")));
        for (MethodNode method : node.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
    }
}
