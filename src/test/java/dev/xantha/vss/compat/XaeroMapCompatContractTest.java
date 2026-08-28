package dev.xantha.vss.compat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class XaeroMapCompatContractTest {
    @Test
    void reflectiveSurfaceResolvesAgainstXaeroPackageStubs() {
        assertDoesNotThrow(() -> XaeroMapCompat.Handles.resolve(Class::forName));
    }

    @Test
    void registrationAndLatestTileQueueDoNotNeedXaeroAtCompileTime() throws Exception {
        List<dev.xantha.vss.api.VoxelColumnConsumer> consumers = new ArrayList<>();
        XaeroMapCompat bridge = new XaeroMapCompat(
                XaeroMapCompat.Handles.resolve(Class::forName),
                new XaeroMapCompat.LevelOps() {
                    @Override
                    public Object dimension(Object world) {
                        return world;
                    }

                    @Override
                    public boolean isChunkLoaded(Object world, int chunkX, int chunkZ) {
                        return false;
                    }
                },
                () -> true,
                () -> true,
                consumers::add,
                consumers::remove);

        bridge.maybeRegister();
        assertEquals(1, consumers.size());
        XaeroTileExtractor.PreparedTile first = emptyTile(4, 9);
        XaeroTileExtractor.PreparedTile replacement = emptyTile(4, 9);
        Object dimension = new Object();
        bridge.offerPrepared(dimension, first);
        bridge.offerPrepared(dimension, replacement);

        assertEquals(1, bridge.queuedForTest());
        assertTrue(bridge.hasQueuedForTest(4, 9));
        bridge.clearQueue();
        assertEquals(0, bridge.queuedForTest());
    }

    @Test
    void productionWiringIncludesInitPumpAndDisconnect() throws Exception {
        String modCompat = Files.readString(Path.of(
                "src/main/java/dev/xantha/vss/compat/ModCompat.java"));
        String networking = Files.readString(Path.of(
                "src/main/java/dev/xantha/vss/networking/client/VSSClientNetworking.java"));

        assertTrue(modCompat.contains("isLoaded(\"xaeroworldmap\")"));
        assertTrue(modCompat.contains("XaeroMapCompat.init()"));
        assertTrue(modCompat.contains("XaeroMapCompat.clientTick()"));
        assertTrue(modCompat.contains("XaeroMapCompat.renderFrame()"));
        assertTrue(modCompat.contains("XaeroMapCompat.onDisconnect()"));
        assertTrue(networking.contains("ModCompat.onDisconnect()"));
        assertTrue(networking.contains("ModCompat.renderFrame()"));
        assertFalse(networking.contains("XaeroMapCompat."),
                "networking should depend on the optional-mod facade only");
    }

    @Test
    void fullyLoadedThreeByThreeNeighbourhoodIsOwnedByXaero() {
        Set<Long> loaded = loadedThreeByThree(20, -30);

        assertTrue(bridgeForLoadedChunks(loaded).nativelyWritable(new Object(), 20, -30));
    }

    @Test
    void anyMissingNeighbourKeepsLoadedBoundaryChunkOwnedByBridge() {
        Set<Long> loaded = loadedThreeByThree(20, -30);
        XaeroMapCompat bridge = bridgeForLoadedChunks(loaded);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                long neighbour = pack(20 + dx, -30 + dz);
                loaded.remove(neighbour);
                assertFalse(bridge.nativelyWritable(new Object(), 20, -30),
                        "missing neighbour " + dx + "," + dz + " must be bridge-written");
                loaded.add(neighbour);
            }
        }
    }

    private static Set<Long> loadedThreeByThree(int chunkX, int chunkZ) {
        Set<Long> loaded = new HashSet<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                loaded.add(pack(chunkX + dx, chunkZ + dz));
            }
        }
        return loaded;
    }

    private static XaeroMapCompat bridgeForLoadedChunks(Set<Long> loaded) {
        return new XaeroMapCompat(null, new XaeroMapCompat.LevelOps() {
            @Override
            public Object dimension(Object world) {
                return world;
            }

            @Override
            public boolean isChunkLoaded(Object world, int chunkX, int chunkZ) {
                return loaded.contains(pack(chunkX, chunkZ));
            }
        }, () -> true, () -> true, ignored -> { }, ignored -> { });
    }

    private static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    @SuppressWarnings("unchecked")
    private static XaeroTileExtractor.PreparedTile emptyTile(int chunkX, int chunkZ) {
        return new XaeroTileExtractor.PreparedTile(
                chunkX,
                chunkZ,
                -64,
                new net.minecraft.world.level.block.state.BlockState[256],
                new short[256],
                new short[256],
                (net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>[]) new net.minecraft.resources.ResourceKey<?>[256],
                new byte[256],
                new boolean[256],
                new XaeroTileExtractor.OverlayRun[256][]);
    }
}
