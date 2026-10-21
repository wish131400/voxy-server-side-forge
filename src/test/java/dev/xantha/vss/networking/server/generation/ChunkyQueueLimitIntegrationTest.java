package dev.xantha.vss.networking.server.generation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.xantha.vss.config.VSSServerConfig;
import dev.xantha.vss.networking.server.runtime.DiskTaskRuntime;
import dev.xantha.vss.networking.server.storage.ColumnLodCache;
import dev.xantha.vss.networking.server.storage.PersistentColumnLodStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ChunkyQueueLimitIntegrationTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraftforge.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Path.of("build", "tmp", "generation-tests"));
        net.minecraftforge.fml.loading.LoadingModList.of(List.of(), List.of(), null);
        dev.xantha.vss.client.prediction.ForgeTestBootstrap.prepare();
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void serviceScansOnlyConfiguredOutstandingColumnsAndZeroRemovesTheLimit() throws Exception {
        int previousLimit = VSSServerConfig.CONFIG.chunkyQueueLimit;
        try {
            VSSServerConfig.CONFIG.chunkyQueueLimit = 1;
            var fixture = new Fixture();
            fixture.scan();
            assertEquals(1, fixture.queue.inFlightCount());
            fixture.scan();
            assertEquals(1, fixture.queue.inFlightCount());
            verify(fixture.cache, times(1)).get(Level.OVERWORLD, 0, 0);
            VSSServerConfig.CONFIG.chunkyQueueLimit = 3;
            fixture.scan();
            assertEquals(3, fixture.queue.inFlightCount());
            VSSServerConfig.CONFIG.chunkyQueueLimit = 0;
            fixture.scan();
            assertEquals(6, fixture.queue.inFlightCount());
            assertTrue(fixture.queue.allReserved());
        } finally { VSSServerConfig.CONFIG.chunkyQueueLimit = previousLimit; }
    }

    @Test
    void fullQueueDoesNotScheduleScannersAndFreedSlotResumesReading() throws Exception {
        int previousLimit = VSSServerConfig.CONFIG.chunkyQueueLimit;
        try {
            VSSServerConfig.CONFIG.chunkyQueueLimit = 1;
            var fixture = new Fixture();
            var first = fixture.queue.reserve();
            fixture.startScanners();
            verifyNoInteractions(fixture.disk);
            fixture.queue.finish(first, true);
            when(fixture.disk.submitReadUnrestricted(any(Runnable.class), any())).thenAnswer(call -> {
                ((Runnable) call.getArgument(0)).run();
                return true;
            });
            fixture.startScanners();
            verify(fixture.disk, times(1)).submitReadUnrestricted(any(Runnable.class), any());
            verify(fixture.cache).get(Level.OVERWORLD, 1, 0);
            assertEquals(1, fixture.queue.inFlightCount());
            assertEquals(1, fixture.queue.completed());
            assertFalse(fixture.queue.allReserved());
        } finally { VSSServerConfig.CONFIG.chunkyQueueLimit = previousLimit; }
    }

    private static final class Fixture {
        final ColumnLodCache cache = mock(ColumnLodCache.class);
        final PersistentColumnLodStore store = mock(PersistentColumnLodStore.class);
        final DiskTaskRuntime disk = mock(DiskTaskRuntime.class);
        final dev.xantha.vss.networking.server.runtime.ServerLifecycleGuard lifecycle =
                new dev.xantha.vss.networking.server.runtime.ServerLifecycleGuard();
        final ChunkyGenerationService service = new ChunkyGenerationService(null, cache, store, null, disk, null,
                lifecycle, new dev.xantha.vss.networking.server.session.PlayerSessionLookup(
                        new dev.xantha.vss.networking.server.state.PlayerRequestRegistry(), lifecycle));
        final Class<?> jobType = Arrays.stream(ChunkyGenerationService.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("Job")).findFirst().orElseThrow();
        final Object job;
        final ChunkyWorkQueue queue;

        Fixture() throws Exception {
            var level = mock(ServerLevel.class);
            when(level.getServer()).thenReturn(mock(MinecraftServer.class));
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            var constructor = jobType.getDeclaredConstructor(CommandSourceStack.class, ServerLevel.class, ChunkyArea.class, java.util.function.BiConsumer.class);
            constructor.setAccessible(true);
            job = constructor.newInstance(null, level, new ChunkyArea(0, 0, 5, 0),
                    (java.util.function.BiConsumer<Object, Object>) (ignoredJob, ignoredNotice) -> { });
            Field scanning = jobType.getDeclaredField("scanning");
            scanning.setAccessible(true);
            scanning.setBoolean(job, true);
            Field queueField = jobType.getDeclaredField("queue");
            queueField.setAccessible(true);
            queue = (ChunkyWorkQueue) queueField.get(job);
        }

        void scan() throws Exception { invoke("scanCache"); }
        void startScanners() throws Exception { invoke("startCacheScanners"); }

        private void invoke(String name) throws Exception {
            Method method = ChunkyGenerationService.class.getDeclaredMethod(name, jobType);
            method.setAccessible(true);
            method.invoke(service, job);
        }
    }
}
