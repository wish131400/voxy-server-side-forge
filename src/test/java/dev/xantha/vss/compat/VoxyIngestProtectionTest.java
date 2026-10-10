package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.*;
import dev.xantha.vss.mixin.voxy.VoxyClientInstanceServerIngestMixin;
import dev.xantha.vss.mixin.voxy.VoxelIngestServiceSourceMixin;
import dev.xantha.vss.config.VSSClientConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

class VoxyIngestProtectionTest {
    private static final class Target extends VoxyClientInstanceServerIngestMixin { }
    private static final class LocalTarget extends VoxelIngestServiceSourceMixin { }
    public static final class Config { public boolean ingestEnabled; }

    @BeforeAll
    static void initializeConfigDirectory() {
        if (net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get() == null)
            net.minecraftforge.fml.loading.FMLPaths.loadAbsolutePaths(java.nio.file.Path.of("build", "tmp", "ingest-tests"));
    }

    @Test
    void disablingLocalConversionBlocksLocalEntryPointsAndKeepsServerRawData() throws Throwable {
        boolean previous = VSSClientConfig.CONFIG.enableLocalChunkIngestion;
        VSSClientConfig.CONFIG.enableLocalChunkIngestion = false;
        try {
            var enqueue = VoxelIngestServiceSourceMixin.class.getDeclaredMethod("vss$gateLocalChunkIngest", CallbackInfoReturnable.class);
            enqueue.setAccessible(true);
            var local = new CallbackInfoReturnable<Boolean>("enqueueIngest", true, true);
            enqueue.invoke(new LocalTarget(), local);
            assertFalse(local.getReturnValue());
            var raw = VoxelIngestServiceSourceMixin.class.getDeclaredMethod("vss$gateLocalRawIngest", CallbackInfoReturnable.class);
            raw.setAccessible(true);
            var ordinaryRaw = new CallbackInfoReturnable<Boolean>("rawIngest", true, true);
            raw.invoke(null, ordinaryRaw);
            assertFalse(ordinaryRaw.getReturnValue());
            assertTrue(VoxyIngestControl.runServerIngest(() -> {
                var server = new CallbackInfoReturnable<Boolean>("rawIngest", true, true);
                raw.invoke(null, server);
                assertFalse(server.isCancelled());
                return server.getReturnValue();
            }));
        } finally {
            VSSClientConfig.CONFIG.enableLocalChunkIngestion = previous;
        }
    }

    @Test
    void serverDataCanPassOrdinaryIngestSwitch() throws Throwable {
        var result = VoxyIngestControl.runServerIngest(() -> invokeGate(false));
        assertTrue(result);
    }

    @Test
    void switchRedirectDoesNotReplaceTheOriginalMethodOrRequireReplayField() throws Exception {
        var method = VoxyClientInstanceServerIngestMixin.class.getDeclaredMethod("vss$allowServerIngest", Object.class);
        var redirect = method.getAnnotation(org.spongepowered.asm.mixin.injection.Redirect.class);
        assertNotNull(redirect);
        assertEquals("FIELD", redirect.at().value());
        assertEquals("Lme/cortex/voxy/client/config/VoxyConfig;ingestEnabled:Z", redirect.at().target());
        assertEquals(0, VoxyClientInstanceServerIngestMixin.class.getDeclaredFields().length);
    }

    @Test
    void localIngestionKeepsVoxyDecision() throws Exception {
        assertFalse(invokeGate(false));
        assertTrue(invokeGate(true));
    }

    private boolean invokeGate(boolean enabled) throws Exception {
        var target = new Target();
        var config = new Config();
        config.ingestEnabled = enabled;
        var method = VoxyClientInstanceServerIngestMixin.class.getDeclaredMethod("vss$allowServerIngest", Object.class);
        method.setAccessible(true);
        return (boolean) method.invoke(target, config);
    }
}
