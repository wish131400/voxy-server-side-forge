package dev.xantha.vss.client.prediction;

import dev.xantha.vss.compat.ModCompat;
import dev.xantha.vss.config.VSSClientConfig;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Hides Voxy's outward skirt only where resident prediction terrain can replace it. */
public final class PredictionVoxyBoundaryBridge {
    private static final String VERTEX = "voxy:lod/gl46/quads3.vert";
    private static final String FRAGMENT = "voxy:lod/gl46/quads.frag";
    private static final int[] EMPTY = new int[4];
    private static final Pattern INTER_DATA_DECLARATION = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*0\\s*\\)\\s*out\\s+flat\\s+uvec4\\s+interData\\s*;");
    private static final Pattern SETUP_QUAD = Pattern.compile(
            "setupQuad\\s*\\(\\s*quad\\s*,\\s*quadData\\s*\\[\\s*uint\\s*\\(\\s*gl_VertexID\\s*\\)\\s*>>\\s*2\\s*\\]\\s*,\\s*"
                    + "(?:pos|positionBuffer\\s*\\[\\s*gl_BaseInstance\\s*\\])\\s*,\\s*"
                    + "\\(\\s*gl_VertexID\\s*&\\s*3\\s*\\)\\s*==\\s*1\\s*\\)\\s*;");

    private PredictionVoxyBoundaryBridge() { }

    public static String patch(String path, String source) {
        if (source == null) return null;
        if (VERTEX.equals(path)) return patchVertex(source);
        if (FRAGMENT.equals(path)) return patchFragment(source);
        return source;
    }

    private static String patchVertex(String source) {
        if (source.contains("vssBoundaryCandidate")) return source;
        Matcher declaration = INTER_DATA_DECLARATION.matcher(source);
        Matcher setup = SETUP_QUAD.matcher(source);
        if (!declaration.find()) return source;
        String patched = source.substring(0, declaration.end()) + "\n" + """
                layout(location = 8) out flat uint vssBoundarySector;
                layout(location = 9) out flat uint vssBoundaryCandidate;
                uniform bool VssBoundaryEnabled;
                uniform float VssBoundaryRadius;
                """ + source.substring(declaration.end());
        setup = SETUP_QUAD.matcher(patched);
        if (!setup.find()) {
            int main = patched.indexOf("void main");
            int body = main < 0 ? -1 : patched.indexOf('{', main);
            if (body < 0) return source;
            return patched.substring(0, body + 1)
                    + "\nvssBoundaryCandidate = 0u;\nvssBoundarySector = 0u;\n"
                    + patched.substring(body + 1);
        }
        patched = patched.substring(0, setup.end()) + "\n" + """
                vssBoundaryCandidate = 0u;
                vssBoundarySector = 0u;
                if (VssBoundaryEnabled) {
                    uint vssFace = extractFace(quadData[uint(gl_VertexID)>>2]);
                    if (vssFace >= 2u && vssFace <= 5u) {
                        vec3 vssCenter3 = quad.basePoint +
                                swizzelDataAxis(quad.axis, vec3(quad.quadSizeAddin * quad.lodScale * 0.5, 0.0));
                        vec2 vssRelative = vssCenter3.xz - cameraSubPos.xz;
                        float vssDistance = length(vssRelative);
                        float vssHalfExtent = max(192.0,
                                max(quad.quadSizeAddin.x, quad.quadSizeAddin.y) * quad.lodScale * 0.75);
                        if (abs(vssDistance - VssBoundaryRadius) <= vssHalfExtent) {
                            vec2 vssNormal = vssFace == 2u ? vec2(0.0, -1.0) :
                                    vssFace == 3u ? vec2(0.0, 1.0) :
                                    vssFace == 4u ? vec2(-1.0, 0.0) : vec2(1.0, 0.0);
                            if (dot(vssRelative, vssNormal) > VssBoundaryRadius * 0.35) {
                                float vssAngle = atan(vssRelative.y, vssRelative.x);
                                vssBoundarySector = min(127u, uint(floor((vssAngle + 3.141592653589793)
                                        * (128.0 / 6.283185307179586))));
                                vssBoundaryCandidate = 1u;
                            }
                        }
                    }
                }
                """ + patched.substring(setup.end());
        return patched;
    }

    private static String patchFragment(String source) {
        if (source.contains("VssBoundaryCoverage")) return source;
        String declaration = "layout(location = 0) in flat uvec4 interData;";
        String main = "void main() {";
        if (!source.contains(declaration) || !source.contains(main)) return source;
        String patched = source.replace(declaration, declaration + "\n" + """
                layout(location = 8) in flat uint vssBoundarySector;
                layout(location = 9) in flat uint vssBoundaryCandidate;
                uniform uvec4 VssBoundaryCoverage;
                """).replace(main, main + "\n" + """
                if (vssBoundaryCandidate != 0u &&
                    (VssBoundaryCoverage[int(vssBoundarySector >> 5u)] &
                     (1u << (vssBoundarySector & 31u))) != 0u) discard;
                """);
        return patched;
    }

    /** Called with Voxy's opaque terrain program bound, before its indirect draw. */
    public static void bind() {
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (program <= 0) return;
        int enabledLocation = GL20.glGetUniformLocation(program, "VssBoundaryEnabled");
        if (enabledLocation < 0) return;
        Minecraft minecraft = Minecraft.getInstance();
        int chunks = ModCompat.getVoxyViewDistanceChunks().orElse(0);
        boolean enabled = VSSClientConfig.CONFIG.enablePrediction && minecraft.level != null
                && minecraft.player != null && chunks > 0;
        int[] coverage = enabled
                ? PredictionRenderer.voxyBoundaryCoverage(minecraft.level.dimension(),
                    minecraft.gameRenderer.getMainCamera().getPosition(), chunks * 16)
                : EMPTY;
        enabled = enabled && (coverage[0] | coverage[1] | coverage[2] | coverage[3]) != 0;
        GL20.glUniform1i(enabledLocation, enabled ? 1 : 0);
        if (!enabled) return;
        GL20.glUniform1f(GL20.glGetUniformLocation(program, "VssBoundaryRadius"), chunks * 16.0f);
        int maskLocation = GL20.glGetUniformLocation(program, "VssBoundaryCoverage");
        if (maskLocation >= 0) GL30.glUniform4ui(maskLocation, coverage[0], coverage[1], coverage[2], coverage[3]);
    }
}
