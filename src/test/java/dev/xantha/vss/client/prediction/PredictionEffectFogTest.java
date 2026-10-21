package dev.xantha.vss.client.prediction;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.xantha.vss.mixin.client.PredictionFogCaptureMixin;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.injection.Inject;

class PredictionEffectFogTest {
    @BeforeAll static void bootstrap() { AlgorithmBootstrap.bootstrapMinecraft(); }

    @Test void statusFogUsesTheViewedEntity() {
        Camera camera = mock(Camera.class);
        LivingEntity viewed = mock(LivingEntity.class);
        when(camera.getFluidInCamera()).thenReturn(FogType.NONE);
        when(camera.getEntity()).thenReturn(viewed);
        assertFalse(PredictionFogBridge.environmental(camera, false));
        when(viewed.hasEffect(MobEffects.BLINDNESS)).thenReturn(true);
        assertTrue(PredictionFogBridge.environmental(camera, false), "spectating a blinded entity follows its fog");
        when(viewed.hasEffect(MobEffects.BLINDNESS)).thenReturn(false);
        when(viewed.hasEffect(MobEffects.DARKNESS)).thenReturn(true);
        assertTrue(PredictionFogBridge.environmental(camera, false));
        when(camera.getEntity()).thenReturn(mock(Entity.class));
        assertFalse(PredictionFogBridge.environmental(camera, false), "an unaffected camera does not inherit another player's effects");
    }

    @Test void fluidsAndWorldFogTakePriorityOverDistantHaze() {
        Camera camera = mock(Camera.class);
        for (FogType fluid : FogType.values()) {
            when(camera.getFluidInCamera()).thenReturn(fluid);
            assertEquals(fluid != FogType.NONE, PredictionFogBridge.environmental(camera, false), fluid.name());
            assertTrue(PredictionFogBridge.environmental(camera, true));
        }
    }

    @Test void captureHookMatchesTheLoaderCallBeforeVoxyReturnHooks() throws Exception {
        var handler = PredictionFogCaptureMixin.class.getDeclaredMethods()[0];
        Inject injection = handler.getAnnotation(Inject.class);
        String target = injection.at()[0].target();
        ClassNode node = new ClassNode();
        try (var source = FogRenderer.class.getResourceAsStream("FogRenderer.class")) {
            assertNotNull(source); new ClassReader(source).accept(node, ClassReader.SKIP_FRAMES);
        }
        int matches = 0;
        for (var method : node.methods) if (method.name.equals("setupFog")) {
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                    && target.equals("L" + call.owner + ";" + call.name + call.desc)) {
                matches++;
                var next = call.getNext();
                while (next != null && next.getOpcode() < 0) next = next.getNext();
                assertNotNull(next);
                assertEquals(org.objectweb.asm.Opcodes.RETURN, next.getOpcode(),
                        "the loader fog event is the last call before RETURN mixins");
            }
        }
        assertEquals(1, matches, "the required injection must match this platform's actual Minecraft bytecode");
        assertEquals(org.spongepowered.asm.mixin.injection.At.Shift.AFTER, injection.at()[0].shift());
    }
}
