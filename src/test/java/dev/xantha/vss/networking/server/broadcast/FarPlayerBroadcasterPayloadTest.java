package dev.xantha.vss.networking.server.broadcast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.xantha.vss.networking.payloads.FarPlayersS2CPayload;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class FarPlayerBroadcasterPayloadTest {
    @Test
    void fullVehicleSnapshotsCanBeReducedToPoseOnlyWithoutInitializationData() {
        byte[] largeSpawnData = new byte[480 * 1024];
        FarPlayersS2CPayload.VehicleSnapshot first = vehicle(10, largeSpawnData);
        FarPlayersS2CPayload.VehicleSnapshot second = vehicle(11, largeSpawnData);

        FarPlayersS2CPayload.VehicleSnapshot[] vehicles = FarPlayerBroadcaster.poseOnlyVehicles(
                new FarPlayersS2CPayload.VehicleSnapshot[] {first, second});

        assertEquals(2, vehicles.length);
        assertFalse(vehicles[0].fullData());
        assertFalse(vehicles[1].fullData());
        assertEquals(0, vehicles[0].spawnData().length);
        assertEquals(first.x(), vehicles[0].x());
    }

    private static FarPlayersS2CPayload.VehicleSnapshot vehicle(int entityId, byte[] spawnData) {
        return new FarPlayersS2CPayload.VehicleSnapshot(
                entityId,
                ResourceLocation.fromNamespaceAndPath("test", "vehicle"),
                1.0D,
                2.0D,
                3.0D,
                0.0F,
                0.0F,
                0.0F,
                0.0F,
                true,
                false,
                false,
                false,
                0.0F,
                true,
                null,
                spawnData);
    }

}
