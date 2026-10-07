package dev.xantha.vss.mixin.voxy;

import dev.xantha.vss.compat.VoxyIngestControl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Blocks local chunk conversion while preserving VSS-scoped raw ingestion. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.common.world.service.VoxelIngestService", remap = false)
public abstract class VoxelIngestServiceSourceMixin {
    @Inject(
            method = "enqueueIngest(Lme/cortex/voxy/common/world/WorldEngine;Lnet/minecraft/world/level/chunk/LevelChunk;)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0)
    private void vss$gateLocalChunkIngest(CallbackInfoReturnable<Boolean> cir) {
        if (!VoxyIngestControl.isLocalChunkIngestionEnabled()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(
            method = {
                    "rawIngest(Lme/cortex/voxy/commonImpl/WorldIdentifier;Lnet/minecraft/world/level/chunk/LevelChunkSection;IIILnet/minecraft/world/level/chunk/DataLayer;Lnet/minecraft/world/level/chunk/DataLayer;)Z",
                    "rawIngest(Lme/cortex/voxy/common/world/WorldEngine;Lnet/minecraft/world/level/chunk/LevelChunkSection;IIILnet/minecraft/world/level/chunk/DataLayer;Lnet/minecraft/world/level/chunk/DataLayer;)Z",
                    "rawIngest(Lme/cortex/voxy/commonImpl/WorldIdentifier;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/chunk/LevelChunkSection;IIILnet/minecraft/world/level/chunk/DataLayer;Lnet/minecraft/world/level/chunk/DataLayer;)Z",
                    "rawIngest(Lme/cortex/voxy/common/world/WorldEngine;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/chunk/LevelChunkSection;IIILnet/minecraft/world/level/chunk/DataLayer;Lnet/minecraft/world/level/chunk/DataLayer;)Z"
            },
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0)
    private static void vss$gateLocalRawIngest(CallbackInfoReturnable<Boolean> cir) {
        if (!VoxyIngestControl.isLocalChunkIngestionEnabled()
                && !VoxyIngestControl.isServerIngestActive()) {
            cir.setReturnValue(false);
        }
    }
}
