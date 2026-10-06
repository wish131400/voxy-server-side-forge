package dev.xantha.vss.networking.server.generation;

import dev.xantha.vss.config.VSSServerConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenerationToggleTest {
    @Test void explicitJobBypassesActiveAndQueuedQuotasAndUnregistersCleanly() throws Exception {
        VSSServerConfig config = new VSSServerConfig();
        config.enableChunkGeneration = true;
        config.generationConcurrencyLimitPerPlayer = 1;
        config.generationConcurrencyLimitGlobal = 1;
        ChunkGenerationService service = new ChunkGenerationService(config);
        UUID background = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        var activeField = ChunkGenerationService.class.getDeclaredField("active");
        activeField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var active = (java.util.Map<Object, Object>) activeField.get(service);
        try {
            service.registerBackgroundOwner(background);
            var countField = ChunkGenerationService.class.getDeclaredField("perPlayerActiveCount");
            countField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var counts = (java.util.Map<UUID, Integer>) countField.get(service);
            counts.put(background, Integer.MAX_VALUE);
            counts.put(player, 1);

            Class<?> pendingType = java.util.Arrays.stream(ChunkGenerationService.class.getDeclaredClasses())
                    .filter(type -> type.getSimpleName().equals("PendingGeneration")).findFirst().orElseThrow();
            var constructor = pendingType.getDeclaredConstructor(net.minecraft.world.level.ChunkPos.class,
                    net.minecraft.server.level.ServerLevel.class, long.class);
            constructor.setAccessible(true);
            Object backgroundWork = constructor.newInstance(null, null, 0L);
            Object playerWork = constructor.newInstance(null, null, 0L);
            var callbacksField = pendingType.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var backgroundCallbacks = (java.util.List<ChunkGenerationService.GenerationCallback>)
                    callbacksField.get(backgroundWork);
            backgroundCallbacks.add(new ChunkGenerationService.GenerationCallback(background, null, 1, false));
            @SuppressWarnings("unchecked")
            var playerCallbacks = (java.util.List<ChunkGenerationService.GenerationCallback>) callbacksField.get(playerWork);
            playerCallbacks.add(new ChunkGenerationService.GenerationCallback(player, null, 2, false));
            active.put(null, playerWork);
            var canStart = ChunkGenerationService.class.getDeclaredMethod("canStart", pendingType);
            canStart.setAccessible(true);
            assertTrue((boolean) canStart.invoke(service, backgroundWork));
            assertFalse((boolean) canStart.invoke(service, playerWork));

            var queuedCountField = ChunkGenerationService.class.getDeclaredField("perPlayerQueuedCount");
            queuedCountField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var queuedCounts = (java.util.Map<UUID, Integer>) queuedCountField.get(service);
            queuedCounts.put(background, Integer.MAX_VALUE);
            queuedCounts.put(player, 1);
            var canQueue = ChunkGenerationService.class.getDeclaredMethod("canQueue", UUID.class);
            canQueue.setAccessible(true);
            assertTrue((boolean) canQueue.invoke(service, background));
            assertFalse((boolean) canQueue.invoke(service, player));

            service.removeBackgroundOwner(background);
            assertFalse((boolean) canStart.invoke(service, backgroundWork));
        } finally {
            // These records exercise admission only; they do not own Minecraft tickets.
            active.clear();
            service.shutdown();
        }
    }
    @Test void disabledGenerationRejectsLateStorageCallbacksBeforeAccessingTheWorld() {
        VSSServerConfig config = new VSSServerConfig();
        config.enableChunkGeneration = false;
        ChunkGenerationService service = new ChunkGenerationService(config);
        try {
            service.applyRuntimeConfig();
            assertFalse(service.submitGeneration(UUID.randomUUID(), null, 1, null, 0, 0, 0));
        } finally { service.shutdown(); }
    }
}
