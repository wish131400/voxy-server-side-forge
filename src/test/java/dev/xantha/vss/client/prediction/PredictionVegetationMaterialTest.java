package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.renderer.texture.*;
import org.junit.jupiter.api.*;

class PredictionVegetationMaterialTest {
    @BeforeEach void prepare() { ClientTerrainSamplerTest.bootstrapMinecraft(); VssLodSpriteTable.close(); }
    @AfterEach void clear() { VssLodSpriteTable.close(); }

    @Test void warmPlantRowsRemainVegetationAndSharedSolidDescriptorsInvalidateTheSnapshot() throws Exception {
        try (var contents = contents("block/vss_vegetation_material")) {
            var sprite = new Sprite(contents);
            for (var block : new Block[]{Blocks.GRASS, Blocks.FERN, Blocks.TALL_GRASS, Blocks.KELP_PLANT, Blocks.OAK_LEAVES}) {
                VssLodSpriteTable.close();
                int row = restore(sprite, block);
                boolean[] first = VssLodSpriteTable.vegetationSprites();
                assertTrue(first[row], block.toString());
                assertSame(first, VssLodSpriteTable.vegetationSprites(), "unchanged material tables reuse their snapshot");
                assertEquals(row, restore(sprite, Blocks.STONE));
                assertFalse(VssLodSpriteTable.vegetationSprites()[row], "shared solid rows must retain exact geometry");
                assertTrue(first[row], "published worker snapshots must remain immutable");
            }
        }
    }

    @Test void appendingAKnownPlantSpriteInvalidatesAnAlreadyPublishedClassification() {
        boolean[] first = VssLodSpriteTable.vegetationSprites();
        try (var contents = contents("block/short_grass")) {
            int row = VssLodSpriteTable.registerSprite(new Sprite(contents));
            assertFalse(first[row]);
            assertTrue(VssLodSpriteTable.vegetationSprites()[row]);
        }
    }

    static int restore(TextureAtlasSprite sprite, Block block) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeUTF(sprite.contents().name().toString()); out.writeBoolean(false); out.writeInt(-1);
        out.writeInt(BuiltInRegistries.BLOCK.getId(block));
        return VssLodSpriteTable.readMaterial(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), name -> sprite);
    }

    static SpriteContents contents(String path) {
        var image = new com.mojang.blaze3d.platform.NativeImage(16,16,false);
        image.fillRect(0,0,16,16,0xff55aa55);
        return new SpriteContents(new net.minecraft.resources.ResourceLocation("minecraft",path),
                new net.minecraft.client.resources.metadata.animation.FrameSize(16,16), image,
                net.minecraft.client.resources.metadata.animation.AnimationMetadataSection.EMPTY);
    }

    static final class Sprite extends TextureAtlasSprite {
        Sprite(SpriteContents contents) { super(TextureAtlas.LOCATION_BLOCKS,contents,16,16,0,0); }
    }
}
