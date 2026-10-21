package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class PredictionZoomLodTest {
    @Test void renderedZoomDrivesOrdinaryTargetsAndDisabledSpyglassKeepsBaseScale() {
        var layout = VssLodLayout.of(32768, 6, true, true);
        var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 4, 0, 3);
        var base = projection(70);
        var zoom = projection(8.75);
        double ordinary = VssLodProjection.selectionScale(base, 256);
        double enlarged = VssLodProjection.selectionScale(zoom, 256);
        assertTrue(enlarged > ordinary * 8);
        var model = new Matrix4f().lookAlong(1, 0, 0, 0, 1, 0);
        var camera = new Vec3(0, 80, 0);
        var before = PredictionWorkView.rendered(base, model, camera, new Vec3(1, 0, 0), ordinary);
        var after = PredictionWorkView.rendered(zoom, model, camera, new Vec3(1, 0, 0),
                VssLodProjection.refinementScale(enlarged, ordinary, true, true));
        assertEquals(8, PredictionScreenDetail.targetAxis(before.projectedSize(key, layout, -64, 384, ordinary), layout));
        assertEquals(64, PredictionScreenDetail.targetAxis(after.projectedSize(key, layout, -64, 384, ordinary), layout));
        assertTrue(after.changedFrom(before));
        assertEquals(ordinary, VssLodProjection.refinementScale(enlarged, ordinary, true, false));
        assertEquals(enlarged, VssLodProjection.refinementScale(enlarged, ordinary, false, false),
                "other FOV zooms use their rendered projection independently of the spyglass setting");
    }

    @Test void zoomInRestoresDetailAndZoomOutReducesActualPreparedRangesWithoutUploads() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        try (var state = new State()) {
            var packed = terrain();
            var draw = draw(packed);
            var pass = new PredictionPreparedPassLocalTest.Pass(false);
            var draws = List.of(draw);
            int storageQuads = packed.quadCount();
            var camera = new Vec3(64, 1040, 1344);
            state.view(projection(70), camera, false);
            pass.prepare(draws, 1, camera);
            int small = pass.ranges(draw).quads;
            assertTrue(small < 16384);
            state.view(projection(8.75), camera, true);
            pass.prepare(draws, 1, camera);
            assertEquals(16384, pass.ranges(draw).quads, "larger visible error must restore source detail this frame");
            state.view(projection(70), camera, false);
            pass.prepare(draws, 1, camera);
            assertEquals(small, pass.ranges(draw).quads);
            assertEquals(storageQuads, packed.quadCount(), "display selection cannot rebuild or upload new geometry");
        }
    }

    @Test void distantScopedTerrainKeepsPixelLodWhileMixedOwnershipMorphAndNearStayExact() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        try (var state = new State()) {
            var packed = terrain();
            var draw = draw(packed);
            var pass = new PredictionPreparedPassLocalTest.Pass(false);
            var far = new Vec3(64, 7280, 9664);
            state.view(projection(8.75), far, true);
            pass.prepare(List.of(draw), 1, far);
            assertTrue(pass.ranges(draw).quads < 16384, "a subpixel error remains eligible while scoped");
            boolean[] mixed = draw.allowed().clone(); mixed[100] = false;
            var partial = new PredictionRenderer.Draw(draw.tile(), draw.gpu(), mixed, false, 31, 0, draw.bounds());
            pass.prepare(List.of(partial), 1, far);
            assertEquals(16384, pass.ranges(partial).quads);
            packed.morph(new float[129 * 129], 80, 81);
            var transitioning = new PredictionRenderer.Draw(draw.tile(), draw.gpu(), draw.allowed(), false,
                    draw.faces(), 1, draw.bounds());
            PredictionZoomReturnTest.uploadedAt(draw.gpu(), System.nanoTime());
            pass.prepare(List.of(transitioning), 1, far);
            assertEquals(16384, pass.ranges(transitioning).quads);
            packed.morph(null, 0, 0);
            var near = new Vec3(64, 180, 192);
            state.view(projection(70), near, false);
            pass.prepare(List.of(draw), 1, near);
            assertEquals(16384, pass.ranges(draw).quads);
            state.set("displayWorkView", null); state.set("selectionScoping", true);
            pass.prepare(List.of(draw), 1, far);
            assertEquals(16384, pass.ranges(draw).quads, "missing real scoped projection stays conservative");
        }
    }

    static Matrix4f projection(double degrees) {
        return new Matrix4f().perspective((float)Math.toRadians(degrees), 1, .05f, 32768);
    }
    static int[] words() {
        int[] words = new int[128 * 128 * 12];
        for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) {
            int q = z * 128 + x, p = q * 12;
            PredictionSpatialOrderTest.quad(words, q, x, z, 80, PredictionPackedMesh.FLAG_LOD_TEXTURE_SCALE);
            int y = 32768 + 320 + ((x + z) & 1);
            words[p + 4] = words[p + 5] = y | y << 16;
            for (int c : new int[]{7, 9, 10, 11}) words[p + c] = 0x668844;
        }
        return words;
    }
    static PredictionPackedMesh terrain() { return PredictionPackedMesh.terrainRecords(words(), 128); }
    static PredictionRenderer.Draw draw(PredictionPackedMesh packed) throws Exception {
        var key = new PredictionTileManager.PredictionTileKey(Level.OVERWORLD, 0, 0, 1);
        var tile = new PredictionTileManager.PredictionTile(key, new int[0], new int[0], new ClientColumnSample[0],
                PredictionMesh.restored(16384 * 6, 0, packed, null), new PredictionDepthBound(80, 81), 0, 1, 128, 1);
        var gpu = new PredictionGpuTile(key); PredictionPreparedPassLocalTest.payload(gpu, packed);
        boolean[] all = new boolean[16384]; Arrays.fill(all, true);
        return new PredictionRenderer.Draw(tile, gpu, all, false, 31, 0, new AABB(0, 80, 0, 128, 81, 128));
    }
    static final class State implements AutoCloseable {
        private final Field[] fields = new Field[3];
        private final Object[] previous = new Object[3];
        State() throws Exception {
            String[] names = {"selectionScale", "selectionScoping", "displayWorkView"};
            for (int i = 0; i < fields.length; i++) {
                fields[i] = PredictionRenderer.class.getDeclaredField(names[i]); fields[i].setAccessible(true);
                previous[i] = fields[i].get(null);
            }

        }
        void set(String name, Object value) throws Exception {
            for (Field field : fields) if (field.getName().equals(name)) field.set(null, value);
        }
        void view(Matrix4f projection, Vec3 camera, boolean scoped) throws Exception {
            var direction = new Vec3(64 - camera.x, 80 - camera.y, 64 - camera.z).normalize();
            var model = new Matrix4f().lookAlong((float)direction.x, (float)direction.y, (float)direction.z, 0, 1, 0);
            double scale = VssLodProjection.selectionScale(projection, 256);
            set("selectionScale", scale); set("selectionScoping", scoped);
            set("displayWorkView", PredictionWorkView.rendered(projection, model, camera, direction, scale));
        }
        @Override public void close() throws Exception {
            for (int i = 0; i < fields.length; i++) fields[i].set(null, previous[i]);

        }
    }
}
