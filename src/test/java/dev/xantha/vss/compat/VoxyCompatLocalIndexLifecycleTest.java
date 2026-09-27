package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;

class VoxyCompatLocalIndexLifecycleTest {
    @Test
    void keepsWorldAliveUntilStorageScanCompletes() throws Throwable {
        FakeEngine engine = new FakeEngine();
        Map<Field, Object> originalHandles = installFakeHandles();
        try {
            Object index = newIndex();
            startBuild(engine, index);
            assertTrue(engine.storage.started.await(5, TimeUnit.SECONDS));
            assertEquals(1, engine.references.get());
            engine.storage.continueScan.countDown();
            assertTrue(engine.released.await(5, TimeUnit.SECONDS));
            assertEquals(0, engine.references.get());
            assertTrue(booleanField(index, "ready"));
        } finally {
            engine.storage.continueScan.countDown();
            engine.released.await(5, TimeUnit.SECONDS);
            VoxyCompat.onDisconnect();
            restoreHandles(originalHandles);
        }
    }

    @Test
    void disconnectCancelsScanAndReleasesWorldWithoutPublishing() throws Throwable {
        FakeEngine engine = new FakeEngine();
        Map<Field, Object> originalHandles = installFakeHandles();
        try {
            Object index = newIndex();
            localIndexes().put(engine, index);
            startBuild(engine, index);
            assertTrue(engine.storage.started.await(5, TimeUnit.SECONDS));
            VoxyCompat.onDisconnect();
            assertTrue(booleanField(index, "cancelled"));
            engine.storage.continueScan.countDown();
            assertTrue(engine.released.await(5, TimeUnit.SECONDS));
            assertEquals(0, engine.references.get());
            assertFalse(booleanField(index, "ready"));
            assertTrue(localIndexes().isEmpty());
        } finally {
            engine.storage.continueScan.countDown();
            engine.released.await(5, TimeUnit.SECONDS);
            VoxyCompat.onDisconnect();
            restoreHandles(originalHandles);
        }
    }

    @Test
    void failedAcquisitionDoesNotLeaveBuildMarkedRunning() throws Throwable {
        FakeEngine engine = new FakeEngine();
        engine.failAcquire = true;
        Map<Field, Object> originalHandles = installFakeHandles();
        try {
            Object index = newIndex();
            startBuild(engine, index);
            assertFalse(((java.util.concurrent.atomic.AtomicBoolean) field(index, "buildStarted")).get());
            assertEquals(0, engine.references.get());
            assertEquals(1, engine.storage.started.getCount());
        } finally {
            VoxyCompat.onDisconnect();
            restoreHandles(originalHandles);
        }
    }

    private static Map<Field, Object> installFakeHandles() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Map<Field, Object> originals = new HashMap<>();
        replaceHandle(originals, "acquireWorldRef", lookup.findVirtual(FakeEngine.class, "acquireRef",
                MethodType.methodType(void.class)).asType(MethodType.methodType(void.class, Object.class)));
        replaceHandle(originals, "releaseWorldRef", lookup.findVirtual(FakeEngine.class, "releaseRef",
                MethodType.methodType(void.class)).asType(MethodType.methodType(void.class, Object.class)));
        replaceHandle(originals, "getStorage", lookup.findGetter(FakeEngine.class, "storage", FakeStorage.class)
                .asType(MethodType.methodType(Object.class, Object.class)));
        replaceHandle(originals, "iterateStoredSectionPositions", lookup.findVirtual(FakeStorage.class,
                "iteratePositions", MethodType.methodType(void.class, LongConsumer.class))
                .asType(MethodType.methodType(void.class, Object.class, LongConsumer.class)));
        return originals;
    }

    private static void replaceHandle(Map<Field, Object> originals, String name, MethodHandle handle)
            throws ReflectiveOperationException {
        Field field = VoxyCompat.class.getDeclaredField(name);
        field.setAccessible(true);
        originals.put(field, field.get(null));
        field.set(null, handle);
    }

    private static void restoreHandles(Map<Field, Object> originals) throws ReflectiveOperationException {
        for (Map.Entry<Field, Object> entry : originals.entrySet()) {
            entry.getKey().set(null, entry.getValue());
        }
    }

    private static Object newIndex() throws ReflectiveOperationException {
        Class<?> type = Class.forName("dev.xantha.vss.compat.VoxyCompat$LocalSectionIndex");
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void startBuild(FakeEngine engine, Object index) throws ReflectiveOperationException {
        Method method = VoxyCompat.class.getDeclaredMethod("startLocalIndexBuild", Object.class, index.getClass());
        method.setAccessible(true);
        method.invoke(null, engine, index);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> localIndexes() throws ReflectiveOperationException {
        Field field = VoxyCompat.class.getDeclaredField("localIndexes");
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(null);
    }

    private static Object field(Object object, String name) throws ReflectiveOperationException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static boolean booleanField(Object object, String name) throws ReflectiveOperationException {
        Object value = field(object, name);
        return value instanceof Boolean ? (boolean) value : ((java.util.concurrent.atomic.AtomicBoolean) value).get();
    }

    public static final class FakeEngine {
        public final FakeStorage storage = new FakeStorage();
        final AtomicInteger references = new AtomicInteger();
        final CountDownLatch released = new CountDownLatch(1);
        boolean failAcquire;

        public void acquireRef() {
            if (failAcquire) throw new IllegalStateException("world closed");
            references.incrementAndGet();
        }

        public void releaseRef() {
            references.decrementAndGet();
            released.countDown();
        }
    }

    public static final class FakeStorage {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch continueScan = new CountDownLatch(1);

        public void iteratePositions(LongConsumer consumer) {
            started.countDown();
            try {
                if (!continueScan.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("scan was not resumed");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            consumer.accept(0L);
        }
    }
}
