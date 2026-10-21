package dev.xantha.vss.mixin.client;

import dev.xantha.vss.client.prediction.ClientPredictionState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe received edits, not only VSS dirty packets; one burst becomes one local capture. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkPredictionCaptureMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void vss$captureChangedColumn(BlockPos pos, BlockState state, boolean moving,
                                         CallbackInfoReturnable<BlockState> cir) {
        var chunk = (LevelChunk) (Object) this;
        if (cir.getReturnValue() != null && cir.getReturnValue() != state
                && chunk.getLevel() instanceof ClientLevel level)
            ClientPredictionState.onLocalColumnChanged(level, chunk.getPos().x, chunk.getPos().z, true);
    }
}
