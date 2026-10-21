package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Same-JVM alternating baseline/current production samplers; no FPS inference from these measurements. */
@EnabledIfSystemProperty(named = "vss.terrainBaselineClasses", matches = ".+")
class TerrainCapabilityBenchmark {
    @BeforeAll static void bootstrap() { dev.xantha.vss.testutil.TerrainPredictionFixtures.bootstrap(); }
    @Test void compareNormalNoiseSamplingAndAllocationsWithoutChangingOutputs() throws Throwable {
        Path baseline = Path.of(System.getProperty("vss.terrainBaselineClasses"));
        try (var loader = new URLClassLoader(new java.net.URL[]{baseline.toUri().toURL()}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("dev.xantha.vss.client.prediction.")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        }) {
            String previous = System.getProperty("vss.javaHeightCache");
            System.setProperty("vss.javaHeightCache", "off");
            Object old = null, current = null;
            try {
                old = sampler(loader.loadClass(ClientTerrainSampler.class.getName()), loader);
                current = sampler(ClientTerrainSampler.class, getClass().getClassLoader());
                var oldCall = call(old.getClass()); var newCall = call(current.getClass());
                // Trigger both independent density/climate compilers before warmup.
                Object ignoredOld = (Object) oldCall.invokeExact(old, 10, 20);
                Object ignoredNew = (Object) newCall.invokeExact(current, 10, 20);
                awaitCompilation(old); awaitCompilation(current);
                var rows = new ArrayList<String>();
                rows.add("round,points,baselineMs,currentMs,baselineCpuMs,currentCpuMs,baselineBytes,currentBytes");
                Object[] a = new Object[1024], b = new Object[1024];
                var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
                bean.setThreadAllocatedMemoryEnabled(true);
                for (int round = 0; round < 32; round++) {
                    int base = -120000 + round * 3072;
                    long[] left = null, right = null;
                    if ((round & 1) == 0) { left = measure(oldCall, old, a, base, bean); right = measure(newCall, current, b, base, bean); }
                    else { right = measure(newCall, current, b, base, bean); left = measure(oldCall, old, a, base, bean); }
                    for (int i = 0; i < a.length; i++) assertEquals(a[i].toString(), b[i].toString(), "column " + i + " round " + round);
                    if (round >= 16) rows.add(round + ",1024," + left[0]/1e6 + ',' + right[0]/1e6 + ','
                            + left[1]/1e6 + ',' + right[1]/1e6 + ',' + left[2] + ',' + right[2]);
                }
                Path output = Path.of(System.getProperty("vss.terrainBenchOutput"));
                Files.createDirectories(output.getParent()); Files.write(output, rows);
            } finally {
                if (old != null) ((AutoCloseable) old).close();
                if (current != null) ((AutoCloseable) current).close();
                if (previous == null) System.clearProperty("vss.javaHeightCache"); else System.setProperty("vss.javaHeightCache", previous);
            }
        }
    }
    private static Object sampler(Class<?> type, ClassLoader loader) throws Exception {
        var fixture = dev.xantha.vss.testutil.TerrainPredictionFixtures.noise();
        var settings = fixture.generatorSettings().value();
        var heights = LevelHeightAccessor.create(-64, 384);
        var profile = new dev.xantha.vss.networking.payloads.WorldgenProfileS2CPayload.DimensionProfile(
                new net.minecraft.resources.ResourceLocation("minecraft", "overworld"), 42, -64, 384, "noise", "minecraft:overworld", 0);
        var constructor = type.getDeclaredConstructor(long.class, profile.getClass(), fixture.getClass(), RandomState.class,
                LevelHeightAccessor.class, int.class, loader.loadClass("dev.xantha.vss.client.prediction.ClientWorldgenRegistries"),
                net.minecraft.core.RegistryAccess.class);
        constructor.setAccessible(true);
        return constructor.newInstance(42L, profile, fixture, RandomState.create(settings,
                dev.xantha.vss.testutil.TerrainPredictionFixtures.access().registryOrThrow(Registries.NOISE).asLookup(), 42L),
                heights, settings.seaLevel(), null, dev.xantha.vss.testutil.TerrainPredictionFixtures.access());
    }
    private static MethodHandle call(Class<?> type) throws Exception {
        return MethodHandles.publicLookup().unreflect(type.getMethod("sampleSurface", int.class, int.class))
                .asType(MethodType.methodType(Object.class, Object.class, int.class, int.class));
    }
    private static long[] measure(MethodHandle call, Object sampler, Object[] target, int base,
                                   com.sun.management.ThreadMXBean bean) throws Throwable {
        long allocated = bean.getThreadAllocatedBytes(Thread.currentThread().getId()), cpu = bean.getCurrentThreadCpuTime(), start = System.nanoTime();
        for (int i = 0; i < target.length; i++) target[i] = (Object) call.invokeExact(sampler, base + (i & 31) * 16, -base + (i >>> 5) * 16);
        return new long[]{System.nanoTime() - start, bean.getCurrentThreadCpuTime() - cpu,
                bean.getThreadAllocatedBytes(Thread.currentThread().getId()) - allocated};
    }
    private static void awaitCompilation(Object sampler) throws Exception {
        var density = sampler.getClass().getDeclaredMethod("densityCompilerDiagnostics"); density.setAccessible(true);
        var climate = sampler.getClass().getDeclaredMethod("climateCompilerDiagnostics"); climate.setAccessible(true);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            String a = density.invoke(sampler).toString(), b = climate.invoke(sampler).toString();
            if (!pending(a) && !pending(b)) return;
            Thread.sleep(10);
        }
        fail("density/climate compiler did not settle before warmup");
    }
    private static boolean pending(String value) {
        return value.startsWith("state=queued") || value.startsWith("state=compiling");
    }
}
