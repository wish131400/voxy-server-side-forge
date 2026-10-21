package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.xantha.vss.api.VoxelColumnData;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.config.VSSClientConfig;
import dev.xantha.vss.testutil.MinecraftTestBootstrap;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Reusing the adapter across reconnects must never preserve old session or coverage answers. */
class ClientLodSessionAdapterTest {
    private final ClientLodSessionAdapter adapter = new ClientLodSessionAdapter();
    private final Map<Field, Object> previousState = new LinkedHashMap<>();
    private VSSClientConfig previousConfig;

    @BeforeAll static void bootstrap() { MinecraftTestBootstrap.initialize(); }

    @BeforeEach void isolateSession() throws Exception {
        for (String name : new String[] {"serverEnabled", "serverLodDistance", "serverCapabilities",
                "waitingForHandshake", "requestManager"}) {
            Field field = field(name);
            previousState.put(field, field.get(null));
        }
        for (String name : new String[] {"currentSession", "pendingStorageIdentity"}) {
            Field field = ClientConnectionIdentity.class.getDeclaredField(name);
            field.setAccessible(true);
            previousState.put(field, field.get(null));
        }
        ClientConnectionIdentity.endSession();
        previousConfig = VSSClientConfig.CONFIG;
        VSSClientConfig.CONFIG = new VSSClientConfig();
        set("serverEnabled", false);
        set("serverLodDistance", 0);
        set("serverCapabilities", 0);
        set("waitingForHandshake", false);
        set("requestManager", null);
    }

    @AfterEach void restoreSession() throws Exception {
        for (var entry : previousState.entrySet()) entry.getKey().set(null, entry.getValue());
        VSSClientConfig.CONFIG = previousConfig;
    }

    @Test void configReplacementAndNegotiationChangesReachTheSameAdapterImmediately() throws Exception {
        assertFalse(adapter.isServerEnabled());
        assertFalse(adapter.shouldApplyStrictLodOrder());
        assertFalse(adapter.isClientLodSessionActive());
        assertEquals(0, adapter.getEffectiveLodDistanceChunks());

        set("serverEnabled", true);
        set("serverLodDistance", 160);
        set("serverCapabilities", VSSConstants.CAPABILITY_PREDICTIVE_WORLDGEN);
        VSSClientConfig.CONFIG.enablePrediction = true;
        VSSClientConfig.CONFIG.lodDistanceChunks = 48;
        assertTrue(adapter.isServerEnabled());
        assertTrue(adapter.shouldApplyStrictLodOrder());
        assertTrue(adapter.isPredictionActive());
        assertEquals(160, adapter.getServerLodDistance());
        assertEquals(48, adapter.getEffectiveLodDistanceChunks());

        VSSClientConfig replacement = new VSSClientConfig();
        replacement.lodDistanceChunks = 96;
        replacement.enablePrediction = false;
        VSSClientConfig.CONFIG = replacement;
        assertEquals(96, adapter.getEffectiveLodDistanceChunks());
        assertFalse(adapter.isPredictionActive());
        replacement.enablePrediction = true;
        set("serverCapabilities", 0);
        assertFalse(adapter.isPredictionActive(), "local settings cannot invent a server capability");

        set("serverEnabled", false);
        set("serverLodDistance", 0);
        assertFalse(adapter.shouldApplyStrictLodOrder());
        assertEquals(0, adapter.getEffectiveLodDistanceChunks());
    }

    @Test void replacingOrRemovingTheRequestManagerRetractsOldReadiness() throws Exception {
        LodRequestManager first = new LodRequestManager("adapter-test", new ClientRequestTracker(ignored -> {}));
        LevelChunkSection solid = mock(LevelChunkSection.class);
        when(solid.hasOnlyAir()).thenReturn(false);
        first.recordStrictSections(4, -7, new VoxelColumnData(new VoxelColumnData.SectionData[] {
                new VoxelColumnData.SectionData(-2, solid, null, null),
                new VoxelColumnData.SectionData(3, solid, null, null)
        }, 1L));
        set("requestManager", first);
        assertTrue(adapter.strictColumnReady(4, -7));
        assertFalse(adapter.strictColumnRenderReady(4, -7, y -> y == -2),
                "receiving sections is not proof that every section has reached the GPU");
        assertTrue(adapter.strictColumnRenderReady(4, -7, y -> y == -2 || y == 3));

        set("requestManager", new LodRequestManager("adapter-new", new ClientRequestTracker(ignored -> {})));
        assertFalse(adapter.strictColumnReady(4, -7));
        assertFalse(adapter.strictColumnRenderReady(4, -7, y -> { fail("new session has no sections"); return true; }));
        set("requestManager", null);
        assertFalse(adapter.strictColumnReady(4, -7));
        assertFalse(adapter.strictColumnRenderReady(4, -7, y -> { fail("disconnected session has no sections"); return true; }));
    }

    @Test void cacheScopeTracksIdentityNegotiationReconnectAndDisconnectWithoutAnAdapterSnapshot() {
        assertNull(adapter.currentPredictionScope());
        ClientConnectionIdentity.beginSession((String) null, false);
        assertNull(adapter.currentPredictionScope(), "an unresolved endpoint cannot select a persistent world");
        ClientConnectionIdentity.updateSession("Example.COM:25565", false);
        assertEquals("server:example.com:25565", adapter.currentPredictionScope());
        ClientConnectionIdentity.acceptServerIdentity("7K4M9PXA", true, "node-a");
        assertEquals("vss:7k4m9pxa", adapter.currentPredictionScope());
        ClientConnectionIdentity.endSession();
        assertNull(adapter.currentPredictionScope());
        ClientConnectionIdentity.beginSession("other.example:25566", false);
        assertEquals("server:other.example:25566", adapter.currentPredictionScope());
        ClientConnectionIdentity.acceptServerIdentity("8K4M9PXA", false, "node-b");
        assertEquals("vss:8k4m9pxa-node-b", adapter.currentPredictionScope());
    }

    private static void set(String name, Object value) throws Exception { field(name).set(null, value); }
    private static Field field(String name) throws Exception {
        Field field = VSSClientNetworking.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
