package dev.xantha.vss.mixin.client;

import dev.xantha.vss.networking.client.FarPlayerClientRenderer;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.client.multiplayer.ClientLevel$EntityCallbacks")
public abstract class ClientLevelFarPlayerLeaveEventMixin {
    @Redirect(
            method = "onTrackingEnd(Lnet/minecraft/world/entity/Entity;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraftforge/eventbus/api/IEventBus;post(Lnet/minecraftforge/eventbus/api/Event;)Z",
                    remap = false
            ),
            require = 0
    )
    private boolean vss$skipSyntheticFarPlayerLeaveEvent(IEventBus eventBus, Event event) {
        if (event instanceof EntityLeaveLevelEvent leaveEvent
                && FarPlayerClientRenderer.isSyntheticFarPlayer(leaveEvent.getEntity())) {
            return false;
        }
        return eventBus.post(event);
    }
}
