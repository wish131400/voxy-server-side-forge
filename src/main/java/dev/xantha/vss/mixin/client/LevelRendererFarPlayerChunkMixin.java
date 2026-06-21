package dev.xantha.vss.mixin.client;

import dev.xantha.vss.networking.client.FarPlayerClientRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererFarPlayerChunkMixin {
    @Inject(method = {"isChunkCompiled", "m_202430_"}, at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void vss$allowFarPlayerEntityRendering(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (FarPlayerClientRenderer.hasActiveFarPlayerAt(pos)) {
            cir.setReturnValue(true);
        }
    }
}
