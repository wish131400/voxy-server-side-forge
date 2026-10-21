package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.PredictionRenderer;
import dev.xantha.vss.client.prediction.ClientPredictionState;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Withdraw coverage immediately on unload; bounded polling discovers compiled arrivals. */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCachePredictionMaskMixin {
    @Inject(method = "drop", at = @At("HEAD"))
    private void vss$coverageUnload(int x, int z, CallbackInfo ci) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level != null) ClientPredictionState.beforeLocalColumnUnload(level, x, z);
        PredictionRenderer.invalidateVanillaChunk(x, z);
    }
    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void vss$coverageArrival(CallbackInfoReturnable<LevelChunk> ci) {
        var chunk = ci.getReturnValue();
        if (chunk != null) {
            PredictionRenderer.invalidateVanillaChunk(chunk.getPos().x, chunk.getPos().z);
            if (chunk.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel level)
                ClientPredictionState.onLocalColumnChanged(level, chunk.getPos().x, chunk.getPos().z, false);
        }
    }
}
