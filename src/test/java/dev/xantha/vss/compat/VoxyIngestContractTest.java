package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

@EnabledIfSystemProperty(named = "vss.voxyJar", matches = ".+")
class VoxyIngestContractTest {
    @Test
    void actualVoxyContainsReplayGuardAndBothIngestionEntryPoints() throws Exception {
        try (JarFile jar = new JarFile(System.getProperty("vss.voxyJar"))) {
            ClassNode client = read(jar, "me/cortex/voxy/client/VoxyClientInstance");
            for (var field : client.fields) {
                if (field.name.equals("noIngestOverride")) assertEquals("Z", field.desc);
            }
            assertTrue(client.methods.stream().anyMatch(method -> method.name.equals("isIngestEnabled")
                    && method.desc.equals("(Lme/cortex/voxy/commonImpl/WorldIdentifier;)Z")));
            ClassNode ingest = read(jar, "me/cortex/voxy/common/world/service/VoxelIngestService");
            assertTrue(ingest.methods.stream().anyMatch(method -> method.name.equals("enqueueIngest")
                    && method.desc.equals("(Lme/cortex/voxy/common/world/WorldEngine;Lnet/minecraft/world/level/chunk/LevelChunk;)Z")));
            assertTrue(ingest.methods.stream().anyMatch(method -> method.name.equals("rawIngest")
                    && method.desc.equals("(Lme/cortex/voxy/commonImpl/WorldIdentifier;Lnet/minecraft/world/level/chunk/LevelChunkSection;IIILnet/minecraft/world/level/chunk/DataLayer;Lnet/minecraft/world/level/chunk/DataLayer;)Z")));
        }
    }

    @Test
    void actualVoxyControlFlowRetainsReplayProtectionWhenServerSwitchIsRedirected() throws Throwable {
        try (JarFile jar = new JarFile(System.getProperty("vss.voxyJar"))) {
            ClassNode client = read(jar, "me/cortex/voxy/client/VoxyClientInstance");
            boolean hasReplayGuard = client.fields.stream().anyMatch(field -> field.name.equals("noIngestOverride"));
            MethodNode enabled = client.methods.stream().filter(method -> method.name.equals("isIngestEnabled"))
                    .findFirst().orElseThrow();
            String name = "dev/xantha/vss/compat/VoxyIngestProbe";
            for (var instruction : enabled.instructions.toArray()) {
                if (!(instruction instanceof FieldInsnNode field)) continue;
                if (field.name.equals("noIngestOverride")) field.owner = name;
                else if (field.name.equals("CONFIG")) enabled.instructions.set(field, new VarInsnNode(Opcodes.ALOAD, 1));
                else if (field.name.equals("ingestEnabled")) enabled.instructions.set(field,
                        new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/xantha/vss/compat/VoxyIngestControl",
                                "isVoxyIngestEnabled", "(Ljava/lang/Object;)Z", false));
            }
            // Execute the real target's method body with the same FIELD redirect,
            // replacing only its dependencies on the full Minecraft instance.
            enabled.desc = "(Ljava/lang/Object;)Z";
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
            writer.visitField(Opcodes.ACC_PRIVATE, "noIngestOverride", "Z", null, null).visitEnd();
            var init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Z)V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitVarInsn(Opcodes.ILOAD, 1);
            init.visitFieldInsn(Opcodes.PUTFIELD, name, "noIngestOverride", "Z");
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            enabled.accept(writer);
            writer.visitEnd();
            byte[] bytes = writer.toByteArray();
            class ProbeLoader extends ClassLoader {
                ProbeLoader() { super(VoxyIngestControl.class.getClassLoader()); }
                Class<?> define() { return defineClass(name.replace('/', '.'), bytes, 0, bytes.length); }
            }
            Class<?> probe = new ProbeLoader().define();
            var method = probe.getMethod("isIngestEnabled", Object.class);
            var config = new VoxyIngestProtectionTest.Config();
            Object ordinary = probe.getConstructor(boolean.class).newInstance(false);
            Object replay = probe.getConstructor(boolean.class).newInstance(true);
            assertFalse((boolean) method.invoke(ordinary, config));
            assertTrue(VoxyIngestControl.runServerIngest(() -> (boolean) method.invoke(ordinary, config)));
            assertEquals(!hasReplayGuard, VoxyIngestControl.runServerIngest(() -> (boolean) method.invoke(replay, config)));
        }
    }

    private ClassNode read(JarFile jar, String name) throws Exception {
        var entry = jar.getJarEntry(name + ".class");
        assertNotNull(entry, name);
        try (var input = jar.getInputStream(entry)) {
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
