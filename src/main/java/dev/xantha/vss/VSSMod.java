package dev.xantha.vss;

import dev.xantha.vss.client.VSSClientConfigScreens;
import dev.xantha.vss.common.VSSConstants;
import dev.xantha.vss.common.VSSLogger;
import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.compat.ftbchunks.FTBChunksForceLoadCompat;
import dev.xantha.vss.networking.VSSNetworking;
import dev.xantha.vss.networking.client.FarPlayerClientRenderer;
import dev.xantha.vss.networking.client.VSSClientNetworking;
import dev.xantha.vss.networking.server.broadcast.FarPlayerBroadcaster;
import dev.xantha.vss.networking.server.command.VSSServerCommands;
import dev.xantha.vss.networking.server.VSSServerNetworking;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

@Mod(VSSConstants.MOD_ID)
public final class VSSMod {
    public VSSMod() {
        VSSNetworking.register();
        MinecraftForge.EVENT_BUS.register(VSSServerNetworking.class);
        MinecraftForge.EVENT_BUS.register(FarPlayerBroadcaster.class);
        MinecraftForge.EVENT_BUS.register(VSSServerCommands.class);
        MinecraftForge.EVENT_BUS.register(FTBChunksForceLoadCompat.class);
        DistExecutor.safeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> ClientInit::init);
    }

    private static final class ClientInit {
        private static void init() {
            VSSClientConfigScreens.register();
            registerEmbeddiumOptionsBridge();
            ModCompat.init();
            MinecraftForge.EVENT_BUS.register(VSSClientNetworking.class);
            MinecraftForge.EVENT_BUS.register(FarPlayerClientRenderer.class);
        }

        private static void registerEmbeddiumOptionsBridge() {
            if (!classExists("org.embeddedt.embeddium.api.OptionGUIConstructionEvent")) {
                return;
            }
            try {
                Class<?> bridge = Class.forName("dev.xantha.vss.client.VSSEmbeddiumOptionsEventBridge");
                bridge.getMethod("register").invoke(null);
            } catch (Throwable e) {
                VSSLogger.warn("Failed to register Embeddium options bridge", e);
            }
        }

        private static boolean classExists(String className) {
            try {
                Class.forName(className, false, VSSMod.class.getClassLoader());
                return true;
            } catch (ClassNotFoundException e) {
                return false;
            }
        }
    }
}
