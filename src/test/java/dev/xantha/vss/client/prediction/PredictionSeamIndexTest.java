package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import dev.xantha.vss.client.prediction.PredictionTileManager.PredictionTileKey;

class PredictionSeamIndexTest {
    @Test void replacementsRemovalsAndTurnsKeepEveryVisiblePatchIncludingExtendedBounds() {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        var index = new PredictionSeamIndex<PredictionTileKey>();
        var boxes = new HashMap<PredictionTileKey,AABB>();
        var random = new Random(0x5ea712);
        for (int round = 0; round < 12; round++) {
            for (int i = 0; i < 200; i++) {
                int lod = random.nextInt(8), x = random.nextInt(100) - 50, z = random.nextInt(100) - 50;
                var key = new PredictionTileKey(Level.OVERWORLD,x,z,lod);
                double span = 64 << lod;
                // Patches can exceed nominal tile extents in every dimension.
                var box = new AABB(x*span-70, -64-random.nextInt(256), z*span-70,
                        (x+1)*span+70, 100+random.nextInt(900), (z+1)*span+70);
                boxes.put(key,box); index.put(key,key,box);
            }
            for (var key : List.copyOf(boxes.keySet())) if (random.nextInt(9) == 0) {
                index.remove(key); index.remove(key); boxes.remove(key);
            }
            for (int heading = 0; heading < 360; heading += 45) {
                var view = new Matrix4f().rotateY((float)Math.toRadians(heading));
                var projection = new Matrix4f().perspective((float)Math.toRadians(70),1.6F,.1F,20000);
                var frustum = new Frustum(view,projection); frustum.prepare(-128,512,128);
                var candidates = new HashSet<PredictionTileKey>(); index.intersectFrustum(frustum,candidates::add);
                for (var entry : boxes.entrySet()) if (frustum.isVisible(entry.getValue()))
                    assertTrue(candidates.contains(entry.getKey()), "dropped visible connector: " + entry);
            }
        }
        var all = new HashSet<PredictionTileKey>(); index.intersectFrustum(null,all::add);
        assertEquals(boxes.keySet(),all);
        index.clear(); all.clear(); index.intersectFrustum(null,all::add); assertTrue(all.isEmpty());
    }
}
