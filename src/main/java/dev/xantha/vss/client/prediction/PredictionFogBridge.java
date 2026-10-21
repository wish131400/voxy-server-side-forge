package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.FogShape;
import dev.xantha.vss.config.VSSClientConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/** One distance/fog rule on both sides of the normal Voxy/prediction handoff. */
public final class PredictionFogBridge {
    private PredictionFogBridge() { }

    static final int HORIZONTAL = 0, SPHERE = 1, CYLINDER = 2;
    record Fog(float start, float end, float hazeStart, float density, int shape) {
        Fog(float start, float end, float hazeStart, float density) {
            this(start, end, hazeStart, density, HORIZONTAL);
        }
    }

    // Render-thread state, valid only after this frame's terrain fog setup.
    private static Fog environmentalFog;
    private static final float[] environmentalColor = new float[4];

    static void beginFrame() { environmentalFog = null; }

    /** Called after loader fog events, before Voxy can extend or cache the range. */
    public static void captureTerrain(Camera camera, boolean foggy) {
        environmentalFog = null;
        if (!environmental(camera, foggy)) return;
        environmentalFog = new Fog(RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(),
                0, 0, RenderSystem.getShaderFogShape() == FogShape.SPHERE ? SPHERE : CYLINDER);
        System.arraycopy(RenderSystem.getShaderFogColor(), 0, environmentalColor, 0, 4);
    }

    static boolean environmental(Camera camera, boolean foggy) {
        if (foggy || camera.getFluidInCamera() != FogType.NONE) return true;
        return camera.getEntity() instanceof LivingEntity entity
                && (entity.hasEffect(MobEffects.BLINDNESS) || entity.hasEffect(MobEffects.DARKNESS));
    }

    static Fog fog(Minecraft minecraft) {
        return environmentalFog != null ? environmentalFog : PredictionRenderer.normalFog(minecraft);
    }

    static float[] color() {
        return environmentalFog != null ? environmentalColor : RenderSystem.getShaderFogColor();
    }

    static final String GLSL = """
            uniform bool VssPredictionFogEnabled;
            uniform vec4 VssPredictionFog; // start, end, haze start, density
            uniform vec3 VssPredictionFogColor;
            uniform int VssPredictionFogShape; // horizontal haze, vanilla sphere, vanilla cylinder
            vec3 vssPredictionFog(vec3 color, vec3 relative) {
                float distance = VssPredictionFogShape == 1 ? length(relative)
                        : VssPredictionFogShape == 2 ? max(length(relative.xz), abs(relative.y))
                        : length(relative.xz);
                float fog = clamp((distance - VssPredictionFog.x)
                        / max(0.0001, VssPredictionFog.y - VssPredictionFog.x), 0.0, 1.0);
                if (VssPredictionFogShape != 0) {
                    // Match vanilla linear_fog's smooth transition, without distance haze.
                    fog = fog * fog * (3.0 - 2.0 * fog);
                    return mix(color, VssPredictionFogColor, fog);
                }
                vec3 hazed = mix(color, VssPredictionFogColor,
                        1.0 - exp(-max(distance - VssPredictionFog.z, 0.0) * VssPredictionFog.w));
                return mix(hazed, VssPredictionFogColor, fog);
            }
            """;

    /** Only the ordinary final colour blit is patched; unknown shader layouts stay intact. */
    public static String patch(String path, String source) {
        source = PredictionNormalDepthBridge.patch(path, source);
        if (!"voxy:post/blit_texture_depth_cutout.frag".equals(path) || source == null) return source;
        String color = "colour = texture(colourTex, UV.xy);";
        if (!source.contains(color) || !source.contains("vec3 point = rev3d(")) return source;
        return source.replace("void main()", GLSL + "\nvoid main()")
                .replace(color, color + "\n    if (VssPredictionFogEnabled) {\n"
                        + "        colour.rgb = vssPredictionFog(colour.rgb, point);\n"
                        + "        if (colour.a == 0.0) discard;\n        return;\n    }");
    }

    /** Called immediately before Voxy's final blit while its program is bound. */
    public static void bindVoxy() {
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int enabled = GL20.glGetUniformLocation(program, "VssPredictionFogEnabled");
        if (enabled < 0) return;
        Minecraft minecraft = Minecraft.getInstance();
        boolean active = minecraft.level != null && (environmentalFog != null
                || VSSClientConfig.CONFIG.enablePrediction && PredictionRenderer.normalFogActive());
        GL20.glUniform1i(enabled, active ? 1 : 0);
        if (active) {
            var fog = fog(minecraft);
            bind(program, fog.start(), fog.end(), fog.hazeStart(), fog.density(), color(), fog.shape());
        }
    }

    static void bind(int program, float start, float end, float hazeStart, float density, float[] color) {
        bind(program, start, end, hazeStart, density, color, HORIZONTAL);
    }

    static void bind(int program, float start, float end, float hazeStart, float density, float[] color, int shape) {
        GL20.glUniform4f(GL20.glGetUniformLocation(program, "VssPredictionFog"), start, end, hazeStart, density);
        GL20.glUniform3f(GL20.glGetUniformLocation(program, "VssPredictionFogColor"), color[0], color[1], color[2]);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "VssPredictionFogShape"), shape);
    }
}
