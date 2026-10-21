package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class PredictionGraphicsQualityTest {
    @Test void explicitLeavesOverridesGraphicsAndDefaultFollowsFastFancyAndFabulous() {
        for (GraphicsStatus mode : GraphicsStatus.values()) {
            assertTrue(PredictionGraphicsQuality.fastLeaves(mode, PredictionGraphicsQuality.Leaves.FAST));
            assertFalse(PredictionGraphicsQuality.fastLeaves(mode, PredictionGraphicsQuality.Leaves.FANCY));
            assertEquals(mode == GraphicsStatus.FAST,
                    PredictionGraphicsQuality.fastLeaves(mode, PredictionGraphicsQuality.Leaves.DEFAULT));
        }
    }

    public static class OptionalMod {
        static boolean loading;
        static Settings applied = new Settings();
        public static Settings options() { if (loading) throw new IllegalStateException("not ready"); return applied; }
    }
    public static class Settings { public Quality quality = new Quality(); }
    public static class Quality { public Mode leavesQuality = Mode.DEFAULT; }
    enum Mode { DEFAULT, FANCY, FAST, UNKNOWN }

    @Test void optionalReaderObservesAppliedChangesAndReplacementWithoutDependingOnTheMod() throws Exception {
        var reader = PredictionGraphicsQuality.reader(OptionalMod.class);
        try {
            OptionalMod.loading = true;
            assertEquals(PredictionGraphicsQuality.Leaves.DEFAULT, reader.leaves());
            OptionalMod.loading = false;
            OptionalMod.applied.quality.leavesQuality = Mode.FAST;
            assertEquals(PredictionGraphicsQuality.Leaves.FAST, reader.leaves());
            OptionalMod.applied = new Settings(); OptionalMod.applied.quality.leavesQuality = Mode.FANCY;
            assertEquals(PredictionGraphicsQuality.Leaves.FANCY, reader.leaves());
            OptionalMod.applied.quality.leavesQuality = Mode.UNKNOWN;
            assertEquals(PredictionGraphicsQuality.Leaves.DEFAULT, reader.leaves());
        } finally { OptionalMod.loading = false; OptionalMod.applied = new Settings(); }
    }

    @Test void leafMaskSurvivesColdMaterialRestoreAndRejectsSharedPlantRows() throws Exception {
        ClientTerrainSamplerTest.bootstrapMinecraft();
        // The loader normally assigns state IDs during loader startup, which a
        // plain headless Bootstrap does not run.
        for (var block : net.minecraft.core.registries.BuiltInRegistries.BLOCK)
            for (var state : block.getStateDefinition().getPossibleStates())
                if (net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(state) == -1)
                    net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.add(state);
        VssLodSpriteTable.close();
        var image = new com.mojang.blaze3d.platform.NativeImage(16, 16, false);
        image.fillRect(0, 0, 16, 16, 0x80559955);
        try (var contents = new net.minecraft.client.renderer.texture.SpriteContents(
                new net.minecraft.resources.ResourceLocation("minecraft", "block/quality_leaves"),
                new net.minecraft.client.resources.metadata.animation.FrameSize(16, 16), image,
                net.minecraft.client.resources.metadata.animation.AnimationMetadataSection.EMPTY)) {
            var sprite = new Sprite(contents);
            int row = VssLodSpriteTable.registerSprite(sprite);
            assertSame(Blocks.OAK_LEAVES, net.minecraft.world.level.block.Block.stateById(
                    net.minecraft.world.level.block.Block.getId(Blocks.OAK_LEAVES.defaultBlockState())).getBlock());
            map("INDEX_BY_STATE_FACE").put((long)net.minecraft.world.level.block.Block.getId(Blocks.OAK_LEAVES.defaultBlockState()) << 3, row);
            int[] first = VssLodSpriteTable.leafSprites();
            assertTrue(leaf(first, row)); assertSame(first, VssLodSpriteTable.leafSprites());
            var bytes = new ByteArrayOutputStream(); VssLodSpriteTable.writeMaterial(new DataOutputStream(bytes), row);
            VssLodSpriteTable.close(); assertFalse(leaf(VssLodSpriteTable.leafSprites(), row));
            int restored = VssLodSpriteTable.readMaterial(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), name -> sprite);
            assertTrue(leaf(VssLodSpriteTable.leafSprites(), restored));
            // Another cache descriptor can restore the same sprite row with
            // a different representative. It must invalidate an existing mask
            // even though MODEL_BLOCKS already contains the first descriptor.
            byte[] shared = bytes.toByteArray();
            java.nio.ByteBuffer.wrap(shared).putInt(shared.length - 4,
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getId(Blocks.FERN));
            assertEquals(restored, VssLodSpriteTable.readMaterial(
                    new DataInputStream(new ByteArrayInputStream(shared)), name -> sprite));
            assertFalse(leaf(VssLodSpriteTable.leafSprites(), restored));
            VssLodSpriteTable.close();
            restored = VssLodSpriteTable.readMaterial(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), name -> sprite);
            assertTrue(leaf(VssLodSpriteTable.leafSprites(), restored));
            map("INDEX_BY_STATE_FACE").put((long)net.minecraft.world.level.block.Block.getId(Blocks.FERN.defaultBlockState()) << 3, restored);
            Field cache = VssLodSpriteTable.class.getDeclaredField("leafSpritesCache"); cache.setAccessible(true); cache.set(null, null);
            assertFalse(leaf(VssLodSpriteTable.leafSprites(), restored), "a texture shared with plants keeps its cutout holes");
        } finally { VssLodSpriteTable.close(); }
    }

    @SuppressWarnings("unchecked") private static Map<Long, Integer> map(String name) throws Exception {
        Field f = VssLodSpriteTable.class.getDeclaredField(name); f.setAccessible(true); return (Map<Long, Integer>)f.get(null);
    }
    private static boolean leaf(int[] mask, int row) { return (mask[row >> 5] & 1 << (row & 31)) != 0; }
    private static final class Sprite extends net.minecraft.client.renderer.texture.TextureAtlasSprite {
        Sprite(net.minecraft.client.renderer.texture.SpriteContents contents) {
            super(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS, contents, 16, 16, 0, 0);
        }
    }
}
