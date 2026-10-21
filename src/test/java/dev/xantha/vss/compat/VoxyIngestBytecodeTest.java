package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.mixin.voxy.StrictVoxyIngestPlugin;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public class VoxyIngestBytecodeTest {
    private static final String FIXTURE = "dev/xantha/vss/compat/IngestQueueFixture";
    private static final String NATIVE = "me/cortex/voxy/common/world/service/VoxelIngestService";
    private static final String WORLD = "me/cortex/voxy/common/world/WorldEngine";
    private static final String TRACKER = "dev/xantha/vss/compat/VoxyIngestTracker";

    @BeforeEach void reset() { VoxyIngestTracker.reset(); }

    @Test void replacementCompletesTheActualTaskInsteadOfTheStaleQueueNode() throws Throwable {
        Probe p = fixture();
        Object first = new Object(), second = new Object();
        assertTrue(p.offer(first, 0));
        assertTrue(p.offer(second, 0));
        assertEquals(0, VoxyIngestTracker.pendingSection(first));
        assertEquals(1, VoxyIngestTracker.pendingSection(second));
        p.process();
        assertEquals(0, VoxyIngestTracker.pendingSection(second));
        assertFalse(VoxyIngestTracker.workerScopeActive());
        assertEquals(0, p.refs());
        p.process(); // stale node has no pending task
        assertEquals(0, VoxyIngestTracker.taskCount());
    }

    @Test void capEvictionEmptyAndExceptionalPathsCleanExactlyOnce() throws Throwable {
        Probe p = fixture();
        Object a = new Object(), b = new Object(), c = new Object();
        p.offer(a, 0); p.offer(b, 1); p.offer(c, 2);
        assertEquals(0, VoxyIngestTracker.pendingSection(a));
        assertEquals(1, VoxyIngestTracker.pendingSection(b));
        assertEquals(1, VoxyIngestTracker.pendingSection(c));
        p.process(); p.process(); p.process(); p.process();
        assertEquals(0, VoxyIngestTracker.taskCount());
        assertEquals(0, p.refs());
        p.queued().set(4);
        Object dropped = new Object();
        assertTrue(p.offer(dropped, 9));
        assertFalse(VoxyIngestTracker.pendingColumn(9, 0));
        p.queued().set(0);
        RuntimeException failure = new RuntimeException("worker body");
        p.offer(failure, 8);
        assertSame(failure, assertThrows(RuntimeException.class, p::process));
        assertFalse(VoxyIngestTracker.workerScopeActive());
        assertEquals(0, VoxyIngestTracker.pendingSection(failure));
        assertEquals(0, p.refs());
    }

    @Test void falseThenLateWorkerCannotConsumeTheNextSameSectionSubmission() throws Throwable {
        Probe p = fixture();
        Object section = new Object();
        Object service = p.type.getField("service").get(p.instance);
        service.getClass().getField("fail").setBoolean(service, true);
        assertFalse(p.offer(section, 0));
        service.getClass().getField("fail").setBoolean(service, false);
        assertTrue(p.offer(section, 1));
        p.process();
        assertEquals(1, VoxyIngestTracker.pendingSection(section));
        p.process();
        assertEquals(0, VoxyIngestTracker.pendingSection(section));
        assertEquals(0, p.refs());
    }

    @Test void unknownQueueLayoutsDoNotAdvertiseTracking() throws Exception {
        ClassNode node = fixtureNode();
        node.fields.removeIf(f -> f.name.equals("pending"));
        assertFalse(StrictVoxyIngestPlugin.instrument(node));
        assertFalse(node.methods.stream().anyMatch(m -> m.name.equals("vss$ingestTrackingReady")));
        ClassNode noWorker = fixtureNode();
        noWorker.methods.removeIf(m -> m.name.equals("processJob"));
        assertFalse(StrictVoxyIngestPlugin.instrument(noWorker));
    }

    @Test void actualVoxyQueuesRetainNativeInstructionsAndReferences() throws Exception {
        for (String property : List.of("vss.voxyJar", "vss.legacyVoxyJar")) {
            String path = System.getProperty(property);
            assertNotNull(path, property + " must identify a real Voxy JAR");
            ClassNode node = nativeNode(path);
            Map<String, List<String>> before = nativeInstructions(node);
            assertTrue(StrictVoxyIngestPlugin.instrument(node), path);
            assertEquals(before, nativeInstructions(node));
            MethodNode wrapper = node.methods.stream().filter(m -> m.name.equals("processJob")).findFirst().orElseThrow();
            assertEquals(1, wrapper.tryCatchBlocks.size());
            assertTrue(wrapper.instructions.iterator().hasNext());
            assertEquals(1, node.methods.stream().filter(m -> m.name.equals("vss$ingestTrackingReady")).count());
            verifyTransformedMethods(node);
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            assertTrue(writer.toByteArray().length > 0);
        }
    }

    @Test void originalForkEnqueueExecutesTheReal40000NodeCapAndSupersession() throws Throwable {
        ClassNode node = nativeNode(Objects.requireNonNull(System.getProperty("vss.voxyJar")));
        assertTrue(StrictVoxyIngestPlugin.instrument(node));
        MethodNode original = node.methods.stream().filter(m -> m.name.equals("enqueueJob")).findFirst().orElseThrow();
        ClassNode fixture = fixtureNode();
        fixture.methods.removeIf(m -> m.name.equals("enqueueJob"));
        Remapper remapper = new Remapper() {
            @Override public String map(String name) {
                if (name.equals(NATIVE)) return FIXTURE;
                if (name.startsWith(NATIVE + "$")) return FIXTURE + name.substring(NATIVE.length());
                if (name.equals(WORLD)) return FIXTURE + "$WorldEngine";
                if (name.equals("me/cortex/voxy/common/thread/Service")) return FIXTURE + "$Service";
                if (name.equals("me/cortex/voxy/commonImpl/PerfStats")) return FIXTURE + "$PerfStats";
                if (name.equals("me/cortex/voxy/common/Logger")) return FIXTURE + "$Logger";
                if (name.startsWith("net/minecraft/")) return "java/lang/Object";
                return name;
            }
        };
        MethodNode copy = new MethodNode(original.access, original.name, remapper.mapMethodDesc(original.desc), null, null);
        original.accept(new MethodRemapper(copy, remapper));
        fixture.methods.add(copy);
        // Only the enqueue method is replaced with native bytecode. Constructor/worker are fixture resources.
        Probe p = load(fixture);
        p.queued().set(40000);
        Object section = new Object();
        assertTrue(p.offer(section, 5));
        assertEquals(0, p.refs());
        assertEquals(0, VoxyIngestTracker.pendingSection(section));
        assertTrue(((Map<?, ?>) p.type.getField("pending").get(p.instance)).isEmpty());
        p.queued().set(0);
        p.offer(section, 5);
        p.offer(new Object(), 5);
        assertEquals(0, VoxyIngestTracker.pendingSection(section));
        assertEquals(1, VoxyIngestTracker.taskCount());
    }

    private static Probe fixture() throws Exception {
        ClassNode node = fixtureNode();
        assertTrue(StrictVoxyIngestPlugin.instrument(node));
        return load(node);
    }

    private static ClassNode fixtureNode() throws Exception {
        ClassNode source = new ClassNode();
        try (var stream = VoxyIngestBytecodeTest.class.getClassLoader().getResourceAsStream(FIXTURE + ".class")) {
            new ClassReader(Objects.requireNonNull(stream)).accept(source, 0);
        }
        ClassNode node = new ClassNode();
        source.accept(new ClassRemapper(node, new SimpleRemapper(FIXTURE + "$WorldEngine", WORLD)));
        return node;
    }

    private static ClassNode nativeNode(String path) throws Exception {
        try (ZipFile jar = new ZipFile(path)) {
            ClassNode node = new ClassNode();
            new ClassReader(jar.getInputStream(jar.getEntry(NATIVE + ".class"))).accept(node, 0);
            return node;
        }
    }

    private static Probe load(ClassNode node) throws Exception {
        ClassNode remapped = new ClassNode();
        node.accept(new ClassRemapper(remapped, new SimpleRemapper(WORLD, FIXTURE + "$WorldEngine")));
        ClassWriter writer = new ClassWriter(0);
        remapped.accept(writer);
        byte[] root = writer.toByteArray();
        ClassLoader loader = new ClassLoader(VoxyIngestBytecodeTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith(FIXTURE.replace('/', '.'))) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> found = findLoadedClass(name);
                    if (found == null) {
                        try {
                            byte[] data = name.equals(FIXTURE.replace('/', '.')) ? root
                                    : getParent().getResourceAsStream(name.replace('.', '/') + ".class").readAllBytes();
                            found = defineClass(name, data, 0, data.length);
                        } catch (java.io.IOException e) { throw new ClassNotFoundException(name, e); }
                    }
                    if (resolve) resolveClass(found);
                    return found;
                }
            }
        };
        return new Probe(loader.loadClass(FIXTURE.replace('/', '.')));
    }

    private static Map<String, List<String>> nativeInstructions(ClassNode node) {
        Map<String, List<String>> result = new TreeMap<>();
        for (MethodNode m : node.methods) {
            String name = m.name.equals("vss$processTrackedIngestBody") ? "processJob" : m.name;
            if (m.name.equals("processJob") && node.methods.stream().anyMatch(x -> x.name.equals("vss$processTrackedIngestBody"))) continue;
            if (!Set.of("processJob", "enqueueJob", "rawIngest0").contains(name)) continue;
            List<String> calls = new ArrayList<>();
            for (var insn : m.instructions) {
                if (insn instanceof MethodInsnNode call && !call.owner.equals(TRACKER))
                    calls.add(call.getOpcode() + ":" + call.owner + "." + call.name + call.desc);
                if (insn instanceof FieldInsnNode field)
                    calls.add(field.getOpcode() + ":" + field.owner + "." + field.name + field.desc);
            }
            // The publication hook adds one task.section read; all native accesses remain ordered.
            for (int i = 0; i < m.instructions.size(); i++) {
                var insn = m.instructions.get(i);
                if (insn instanceof MethodInsnNode c && c.owner.equals(TRACKER) && c.name.equals("submitted")) {
                    FieldInsnNode added = (FieldInsnNode) insn.getPrevious();
                    calls.remove(added.getOpcode() + ":" + added.owner + "." + added.name + added.desc);
                }
            }
            result.put(name + m.desc, calls);
        }
        return result;
    }

    private static void verifyTransformedMethods(ClassNode node) throws Exception {
        for (MethodNode method : node.methods) {
            if (!Set.of("processJob", "vss$processTrackedIngestBody", "enqueueJob", "rawIngest0")
                    .contains(method.name)) continue;
            try {
                new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
            } catch (Exception failure) {
                throw new AssertionError("invalid stack map in transformed " + node.name + "."
                        + method.name + method.desc, failure);
            }
        }
    }

    private static final class Probe {
        final Class<?> type, worldType, taskType;
        final Object instance, world;
        Probe(Class<?> type) throws Exception {
            this.type = type;
            instance = type.getConstructor().newInstance();
            worldType = type.getClassLoader().loadClass(FIXTURE.replace('/', '.') + "$WorldEngine");
            taskType = type.getClassLoader().loadClass(FIXTURE.replace('/', '.') + "$IngestSection");
            world = worldType.getConstructor().newInstance();
        }
        boolean offer(Object section, int x) throws Throwable {
            Object task = taskType.getConstructors()[0].newInstance(x, 0, 0, world, null, section, null, null);
            var method = type.getDeclaredMethod("enqueueJob", worldType, taskType);
            method.setAccessible(true);
            return VoxyIngestTracker.runRawIngest(section, x, 0, () -> {
                try { return (boolean) method.invoke(instance, world, task); }
                catch (InvocationTargetException e) { throw e.getCause(); }
            });
        }
        void process() throws Throwable {
            try { type.getMethod("processJob").invoke(instance); }
            catch (InvocationTargetException e) { throw e.getCause(); }
        }
        java.util.concurrent.atomic.AtomicInteger queued() throws Exception {
            return (java.util.concurrent.atomic.AtomicInteger) type.getField("queuedNodes").get(instance);
        }
        int refs() throws Exception { return worldType.getField("refs").getInt(world); }
    }
}
