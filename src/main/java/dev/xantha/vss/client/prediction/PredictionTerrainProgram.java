package dev.xantha.vss.client.prediction;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Direction;

/**
 * The prediction terrain program, packed-quad renderer: one raw GL
 * program whose vertex stage expands compact or canonical quads from a texture
 * buffer using {@code gl_VertexID} (four corners per quad, no vertex
 * attributes at all).  Terrain and fluids share the fragment path — the
 * opaque pass forces alpha to one through {@link #setOpaqueAlpha(float)},
 * the translucent fluid pass re-draws the fluid ranges with blending on and
 * lets the sprite's own alpha (water ships ~0.7 in the atlas) show the depth
 * behind it, exactly like the reference LOD renderer.
 */
final class PredictionTerrainProgram implements AutoCloseable {
    private final GlProgram program;
    private final int modelView;
    private final int projection;
    private final int waterInverseMvp;
    private final int viewOrigin;
    private final int realCoverageBounds;
    private final int exactCoverage, exactCoverageGrid;
    private final int horizonDistance;
    private final int tileOffset;
    private final int spacing;
    private final int cellAxis;
    private final int quads;
    private final int yield;
    private final int coverageTree;
    private boolean boundCoverageTree;
    void setCoverageTree(boolean enabled) {
        if(boundCoverageTree!=enabled) { GL20.glUniform1i(coverageTree,enabled?1:0);boundCoverageTree=enabled; }
    }
    private final int atlas;
    private final int lightmap;
    private final int spriteRects;
    private final int vanillaMaskSampler;
    private final int vanillaMaskOrigin;
    private final int vanillaMaskSize;
    private final int vanillaRenderDistance;
    private final int mainDepth;
    private final int mainDepthPlanes;
    private final int depthBias;
    private final int colorModulator;
    private final int fogColor;
    private final int fogStart;
    private final int fogEnd;
    private final int fogDensity;
    private final int hazeStart;
    private final int lodColorScale;
    private final int lightEnabled;
    private final int detailDistance;
    private final int useAverage;
    private final int morph;
    private final int morphBounds;
    private final int opaqueAlpha;
    private final int directionalTint;
    private final int fastLeaves, leafSprites;
    private boolean boundFastLeaves;
    private int[] boundLeafSprites;
    private final int sharedFog;
    private final int sharedFogColor;
    private final int sharedFogShape;
    private final float[] faceTints = new float[7];
    private final float[] matrixValues = new float[16];
    private int[] boundMaterialIds;
    private float boundSpacing = Float.NaN;
    private int boundCellAxis = -1, boundAverage = -1;
    private boolean morphActive;
    private int boundaryReplacement = -1;
    private final int batchEnabled;
    private final int quadBase;
    private final int paletteBase;
    private final int batchCameraOffset;
    private final int batchTimeSeconds;
    void setQuadBase(int offset) { GL20.glUniform1i(quadBase, offset); }
    void setPaletteBase(int offset) { GL20.glUniform1i(paletteBase, offset); }
    boolean supportsBatch() { return batchEnabled >= 0; }
    void batch(boolean enabled) { if (batchEnabled >= 0) GL20.glUniform1i(batchEnabled, enabled ? 1 : 0); }
    void setBatchCameraOffset(float x, float y, float z) {
        if (batchCameraOffset >= 0) GL20.glUniform3f(batchCameraOffset, x, y, z);
    }
    void setBatchTimeSeconds(float seconds) {
        if (batchTimeSeconds >= 0) GL20.glUniform1f(batchTimeSeconds, seconds);
    }

    private PredictionTerrainProgram() {
        this(TERRAIN_VERTEX, TERRAIN_FRAGMENT);
    }

    private PredictionTerrainProgram(String vertex, String fragment) {
        this.program = GlProgram.link("vss_prediction_terrain",
                vertex, Boolean.getBoolean("vss.disableFixedOwnershipReuse")
                        ? fragment.replace("const bool ReuseFixedOwnership = true;", "const bool ReuseFixedOwnership = false;") : fragment);
        this.batchEnabled = program.uniform("BatchEnabled");
        this.quadBase = program.uniform("QuadBaseTexel");
        this.paletteBase = program.uniform("PaletteBaseTexel");
        this.batchCameraOffset = program.uniform("BatchCameraOffset");
        this.batchTimeSeconds = program.uniform("BatchTimeSeconds");
        this.modelView = program.uniform("ModelViewMat");
        this.projection = program.uniform("ProjMat");
        this.waterInverseMvp = program.uniform("WaterInverseMvp");
        this.viewOrigin = program.uniform("ViewOrigin");
        this.realCoverageBounds = program.uniform("RealCoverageBounds");
        this.exactCoverage = program.uniform("ExactCoverage");
        this.exactCoverageGrid = program.uniform("ExactCoverageGrid");
        this.horizonDistance = program.uniform("HorizonDistance");
        this.tileOffset = program.uniform("TileOffset");
        this.spacing = program.uniform("Spacing");
        this.cellAxis = program.uniform("CellAxis");
        this.quads = program.uniform("QuadPayload");
        this.yield = program.uniform("Yield");
        this.coverageTree = program.uniform("CoverageTree");
        this.atlas = program.uniform("Atlas");
        this.lightmap = program.uniform("Lightmap");
        this.spriteRects = program.uniform("SpriteTable");
        this.vanillaMaskSampler = program.uniform("VanillaMask");
        this.vanillaMaskOrigin = program.uniform("VanillaMaskOrigin");
        this.vanillaMaskSize = program.uniform("VanillaMaskSize");
        this.vanillaRenderDistance = program.uniform("VanillaRenderDistance");
        this.mainDepth = program.uniform("MainDepth");
        this.mainDepthPlanes = program.uniform("MainDepthPlanes");
        this.depthBias = program.uniform("DepthBias");
        this.colorModulator = program.uniform("ColorModulator");
        this.fogColor = program.uniform("LodFogColor");
        this.fogStart = program.uniform("LodFogStart");
        this.fogEnd = program.uniform("LodFogEnd");
        this.fogDensity = program.uniform("LodFogDensity");
        this.hazeStart = program.uniform("LodHazeStart");
        this.lodColorScale = program.uniform("LodColorScale");
        this.lightEnabled = program.uniform("LodLightmap");
        this.detailDistance = program.uniform("DetailDistance");
        this.useAverage = program.uniform("UseAverage");
        this.morph = program.uniform("TerrainMorph");
        this.morphBounds = program.uniform("MorphBounds");
        this.opaqueAlpha = program.uniform("OpaqueAlpha");
        this.directionalTint = program.uniform("DirectionalTint[0]");
        this.fastLeaves = program.uniform("FastLeaves");
        this.leafSprites = program.uniform("LeafSprites[0]");
        this.sharedFog = program.uniform("VssPredictionFog");
        this.sharedFogColor = program.uniform("VssPredictionFogColor");
        this.sharedFogShape = program.uniform("VssPredictionFogShape");
    }

    static PredictionTerrainProgram create() {
        return new PredictionTerrainProgram();
    }

    static PredictionTerrainProgram createBatch() {
        return new PredictionTerrainProgram(batchVertex(TERRAIN_VERTEX), batchFragment(TERRAIN_FRAGMENT));
    }

    private static final String BATCH_RECORDS = """
            uniform bool BatchEnabled;
            uniform vec3 BatchCameraOffset;
            uniform float BatchTimeSeconds;
            struct BatchRecord { vec4 offsetSpacing; ivec4 data; vec4 morph; ivec4 flags; };
            layout(std430,binding=7) readonly buffer BatchRecords { BatchRecord records[]; };
            float VssBatchMorphAmount(float age) {
                float t = clamp(age / 0.35, 0.0, 1.0);
                return 1.0 - t * t * (3.0 - 2.0 * t);
            }
            """;
    private static String batchUniform(String source, String type, String name, String value) {
        return source.replaceAll("\\b" + name + "\\b", "Vss" + name)
                .replace("uniform " + type + " Vss" + name + ";", "uniform " + type + " " + name
                        + ";\n#define Vss" + name + " (BatchEnabled ? " + value + " : " + name + ")");
    }
    static String batchVertex(String source) {
        source = source.replace("#version 150", "#version 460 core")
                .replace("uniform usamplerBuffer QuadPayload;", BATCH_RECORDS + "\nflat out int BatchSlot;\nuniform usamplerBuffer QuadPayload;");
        source = batchUniform(source, "vec3", "TileOffset", "records[gl_BaseInstance].offsetSpacing.xyz + BatchCameraOffset");
        source = batchUniform(source, "float", "Spacing", "records[gl_BaseInstance].offsetSpacing.w");
        source = batchUniform(source, "int", "CellAxis", "records[gl_BaseInstance].data.x");
        source = batchUniform(source, "bool", "CoverageTree", "((records[gl_BaseInstance].flags.x & 4) != 0)");
        source = batchUniform(source, "int", "UseAverage", "records[gl_BaseInstance].data.y");
        source = batchUniform(source, "vec2", "TerrainMorph",
                "vec2(records[gl_BaseInstance].morph.x, uintBitsToFloat(records[gl_BaseInstance].flags.w)"
                        + " * VssBatchMorphAmount(BatchTimeSeconds - uintBitsToFloat(records[gl_BaseInstance].flags.z)))");
        source = batchUniform(source, "vec2", "MorphBounds", "records[gl_BaseInstance].morph.zw");
        source = batchUniform(source, "int", "QuadBaseTexel", "records[gl_BaseInstance].data.z");
        source = batchUniform(source, "int", "PaletteBaseTexel", "records[gl_BaseInstance].flags.y");
        source = batchMask(source,"gl_BaseInstance");
        return source.replace("int quad = gl_VertexID >> 2;", "BatchSlot=gl_BaseInstance;\nint quad = gl_VertexID >> 2;");
    }
    static String batchFragment(String source) {
        source = source.replace("#version 150", "#version 460 core")
                .replace("uniform sampler2D Yield;", BATCH_RECORDS + "\nflat in int BatchSlot;\nuniform usamplerBuffer QuadPayload;\nuniform sampler2D Yield;");
        source = batchUniform(source, "float", "Spacing", "records[BatchSlot].offsetSpacing.w");
        source = batchUniform(source, "int", "CellAxis", "records[BatchSlot].data.x");
        source = batchUniform(source, "bool", "CoverageTree", "((records[BatchSlot].flags.x & 4) != 0)");
        source = batchUniform(source, "int", "UseAverage", "records[BatchSlot].data.y");
        source = batchUniform(source, "bool", "ReplaceBoundaryWalls", "((records[BatchSlot].flags.x & 1) != 0)");
        return batchMask(source,"BatchSlot");
    }

    private static String batchMask(String source,String slot) {
        return source.replace("return vssTextureOwnership(node);", "return BatchEnabled ? ((records["+slot+"].flags.x & 2) != 0 ? 192u : texelFetch(QuadPayload, (records["+slot+"].data.w + node) / 4)[(records["+slot+"].data.w + node) % 4]) : vssTextureOwnership(node);");
    }

    /** Same bounded ownership traversal for direct, MDI and Iris. Geometry/morph keep their source grid. */
    private static final String OWNERSHIP_GLSL = """
            uniform bool CoverageTree;
            uint vssTextureOwnership(int node) {
                int width = CoverageTree ? textureSize(Yield, 0).x : CellAxis;
                vec2 value = texelFetch(Yield, ivec2(node % width, node / width), 0).rg;
                return uint(round(value.r * 255.0)) | (CoverageTree ? uint(round(value.g)) << 8u : 0u);
            }
            uint vssOwnershipWord(int node) { return vssTextureOwnership(node); }
            uint vssOwnershipAt(vec2 xz, out vec2 origin, out float size) {
                ivec2 cell = ivec2(floor(xz / Spacing));
                if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, ivec2(CellAxis)))) return 0u;
                int node = cell.y * CellAxis + cell.x;
                origin = vec2(cell) * Spacing; size = Spacing;
                uint word = vssOwnershipWord(node);
                if (CoverageTree) for (int level = 0; level < 20; level++) {
                    if ((word & 32u) == 0u) break;
                    size *= 0.5;
                    ivec2 quadrant = ivec2(greaterThanEqual(xz, origin + vec2(size)));
                    node = int(word >> 8u) + quadrant.y * 2 + quadrant.x;
                    origin += vec2(quadrant) * size;
                    word = vssOwnershipWord(node);
                }
                return word;
            }
            """;

    static PredictionTerrainProgram createIris(String taa, java.util.function.UnaryOperator<String> patch) {
        return new PredictionTerrainProgram(irisVertex(taa), patch.apply(irisFragment()));
    }

    static PredictionTerrainProgram createIrisBatch(String taa, java.util.function.UnaryOperator<String> patch) {
        // Transform our uniforms before the adapter appends pack-owned source.
        return new PredictionTerrainProgram(batchVertex(irisVertex(taa)), patch.apply(batchFragment(irisFragment())));
    }

    static String irisVertex(String taa) {
        String source = TERRAIN_VERTEX.replace("#version 150", "#version 460 core\n#define VSS_IRIS");
        return source.replace("uniform usamplerBuffer", taa + "\nuniform usamplerBuffer");
    }

    static String irisFragment() {
        return TERRAIN_FRAGMENT.replace("#version 150", "#version 460 core\n#define VSS_IRIS");
    }

    void setIrisFrame(Matrix4f inverseProjection, int width, int height, boolean zeroToOne, int[] ids) {
        setIrisFrame(inverseProjection, width, height, zeroToOne, ids, 0.0F);
    }

    void setIrisFrame(Matrix4f inverseProjection, int width, int height, boolean zeroToOne, int[] ids, float clearDepth) {
        GL20.glUniformMatrix4fv(program.uniform("VssInverseProjection"), false, inverseProjection.get(matrixValues));
        GL20.glUniform2f(program.uniform("VssViewport"), width, height);
        GL20.glUniform1i(program.uniform("VssZeroToOne"), zeroToOne ? 1 : 0);
        GL20.glUniform1f(program.uniform("VssClearDepth"), clearDepth);
        if (!java.util.Arrays.equals(boundMaterialIds, ids)) {
            org.lwjgl.opengl.GL30.glUniform1uiv(program.uniform("VssMaterialIds[0]"), ids);
            boundMaterialIds = ids.clone();
        }
    }

    void use() {
        program.use();
    }

    void setLeaves(boolean fast, int[] sprites) {
        if (fast != boundFastLeaves) {
            GL20.glUniform1i(fastLeaves, fast ? 1 : 0);
            boundFastLeaves = fast;
        }
        if (fast && sprites != boundLeafSprites) {
            GL20.glUniform1iv(leafSprites, sprites);
            boundLeafSprites = sprites;
        }
    }

    void setCamera(Matrix4f modelView, Matrix4f projection) {
        GL20.glUniformMatrix4fv(this.modelView, false, modelView.get(matrixValues));
        GL20.glUniformMatrix4fv(this.projection, false, projection.get(matrixValues));
        if (waterInverseMvp >= 0) GL20.glUniformMatrix4fv(waterInverseMvp, false,
                new org.joml.Matrix4d(projection).mul(new org.joml.Matrix4d(modelView)).invert().get(matrixValues));
        var origin = new Matrix4f(modelView).invert().transformPosition(new org.joml.Vector3f());
        GL20.glUniform3f(viewOrigin, origin.x, origin.y, origin.z);
    }

    void setRealCoverage(float minX, float minZ, float maxX, float maxZ) {
        GL20.glUniform4f(realCoverageBounds, minX, minZ, maxX, maxZ);
    }

    void setHorizon(float blocks) {
        GL20.glUniform1f(horizonDistance, blocks);
    }

    void setBoundaryReplacement(boolean enabled) {
        int value = enabled ? 1 : 0;
        if (boundaryReplacement != value) {
            GL20.glUniform1i(program.uniform("ReplaceBoundaryWalls"), value);
            boundaryReplacement = value;
        }
    }

    void setRealRenderDistance(float blocks) {
        GL20.glUniform1f(program.uniform("RealRenderDistance"), blocks);
    }

    void bindExactCoverage(PredictionExactCoverageMask mask, ClientLevel level,
                           net.minecraft.world.phys.Vec3 camera, int radius) {
        mask.bind(level, camera, radius, exactCoverage, exactCoverageGrid);
    }

    void bindRealCoverage(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                          net.minecraft.world.phys.Vec3 camera) {
        var snapshot = dev.xantha.vss.compat.StrictLodVisibility.snapshot();
        if (!dev.xantha.vss.compat.StrictLodVisibility.active() || !dimension.equals(snapshot.dimension())
                || snapshot.visibleRing() < 0) {
            setRealCoverage(0, 0, 0, 0);
            return;
        }
        int ring = snapshot.visibleRing();
        setRealCoverage((float)(((long)snapshot.x() - ring) * 16 - camera.x),
                (float)(((long)snapshot.z() - ring) * 16 - camera.z),
                (float)(((long)snapshot.x() + ring + 1) * 16 - camera.x),
                (float)(((long)snapshot.z() + ring + 1) * 16 - camera.z));
    }

    void setTile(float offsetX, float offsetY, float offsetZ, float spacing, int cellAxis,
                 boolean useAverage) {
        if (morphActive) {
            GL20.glUniform2f(morph, 0, 0);
            morphActive = false;
        }
        GL20.glUniform3f(tileOffset, offsetX, offsetY, offsetZ);
        if (boundSpacing != spacing) {
            GL20.glUniform1f(this.spacing, spacing); boundSpacing = spacing;
        }
        if (boundCellAxis != cellAxis) {
            GL20.glUniform1i(this.cellAxis, cellAxis); boundCellAxis = cellAxis;
        }
        int average = useAverage ? 1 : 0;
        if (boundAverage != average) {
            GL20.glUniform1i(this.useAverage, average); boundAverage = average;
        }
    }

    void setMorph(PredictionPackedMesh mesh, float amount) {
        if (amount <= 0) {
            if (morphActive) GL20.glUniform2f(morph, 0, 0);
            morphActive = false;
            return;
        }
        morphActive = true;
        GL20.glUniform2f(morph, mesh.morphBaseTexel(), amount);
        GL20.glUniform2f(morphBounds, mesh.morphMinY(), mesh.morphMaxY());
    }

    void setSamplers(int atlasUnit, int lightmapUnit, int spriteRectUnit, int yieldUnit,
                     int quadUnit) {
        GL20.glUniform1i(atlas, atlasUnit);
        GL20.glUniform1i(lightmap, lightmapUnit);
        GL20.glUniform1i(spriteRects, spriteRectUnit);
        GL20.glUniform1i(yield, yieldUnit);
        GL20.glUniform1i(quads, quadUnit);
        GL20.glUniform1i(vanillaMaskSampler, 6);
        GL20.glUniform1i(exactCoverage, 8);
    }

    void bindVanillaMask(PredictionVanillaMask mask, net.minecraft.world.phys.Vec3 camera) {
        mask.bind(6, camera, vanillaMaskOrigin, vanillaMaskSize);
        GL20.glUniform1f(vanillaRenderDistance, mask.renderDistanceBlocks());
    }

    void bindMainDepth(int texture, VssLodProjection.MatrixData projection) {
        PredictionGlState.activeTexture(GL13.GL_TEXTURE7);
        PredictionGlState.bindTexture(texture);
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, texture);
        GL20.glUniform1i(mainDepth, 7);
        GL20.glUniform2f(mainDepthPlanes, projection.vanillaA(), projection.vanillaB());
        GL20.glUniform1f(depthBias, PredictionRenderTarget.DEPTH_BIAS_BLOCKS);
        GL20.glUniform1i(program.uniform("VoxyDepthAvailable"), 0);
        GL20.glUniform1i(program.uniform("VoxyDepth"), 5);
        GL20.glUniform1i(program.uniform("WaterDepthHistoryAvailable"), 0);
    }

    void bindWaterDepthHistory(PredictionWaterDepthHistory history) {
        boolean available = history != null && history.available();
        GL20.glUniform1i(program.uniform("WaterDepthHistoryAvailable"), available ? 1 : 0);
        if (!available) return;
        PredictionGlState.activeTexture(GL13.GL_TEXTURE0 + PredictionWaterDepthHistory.REAL_UNIT);
        PredictionGlState.bindTexture(history.realTexture());
        PredictionGlState.activeTexture(GL13.GL_TEXTURE0 + PredictionWaterDepthHistory.EXPORTED_UNIT);
        PredictionGlState.bindTexture(history.exportedTexture());
        org.lwjgl.opengl.GL33.glBindSampler(PredictionWaterDepthHistory.REAL_UNIT, 0);
        org.lwjgl.opengl.GL33.glBindSampler(PredictionWaterDepthHistory.EXPORTED_UNIT, 0);
        GL20.glUniform1i(program.uniform("WaterRealDepth"), PredictionWaterDepthHistory.REAL_UNIT);
        GL20.glUniform1i(program.uniform("WaterExportedDepth"), PredictionWaterDepthHistory.EXPORTED_UNIT);
        PredictionGlState.activeTexture(GL13.GL_TEXTURE0);
    }

    void bindVoxyDepth(PredictionVoxyDepth.Frame frame, Matrix4f mainMvp) {
        GL20.glUniform1i(program.uniform("VoxyDepthAvailable"), frame == null ? 0 : 1);
        if (frame == null) return;
        // The borrowed texture keeps Voxy's clip convention, independently of Minecraft.
        GL20.glUniform3f(program.uniform("VoxyDepthTransform"), frame.zeroToOne() ? 1 : 2,
                frame.zeroToOne() ? 0 : -1, frame.reverseZ() ? 1 : -1);
        PredictionGlState.activeTexture(GL13.GL_TEXTURE5);
        PredictionGlState.bindTexture(frame.texture());
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, frame.texture());
        // Recover the main projection's clip W from Voxy's original depth.
        // Using both homogeneous rows preserves view bob and camera rotation.
        org.joml.Vector4f numerator = new Matrix4f(mainMvp).mul(frame.inverseMvp()).getRow(3, new org.joml.Vector4f());
        org.joml.Vector4f denominator = frame.inverseMvp().getRow(3, new org.joml.Vector4f());
        GL20.glUniform4f(program.uniform("VoxyDistanceNumerator"), numerator.x, numerator.y, numerator.z, numerator.w);
        GL20.glUniform4f(program.uniform("VoxyDistanceDenominator"), denominator.x, denominator.y, denominator.z, denominator.w);

    }

    void setFrame(float[] fogColor, float start, float end, float density,
                  float hazeFloor, boolean lightOn, float colorScale) {
        setFrame(fogColor, start, end, density, hazeFloor, lightOn, colorScale, PredictionFogBridge.HORIZONTAL);
    }

    void setFrame(float[] fogColor, float start, float end, float density,
                  float hazeFloor, boolean lightOn, float colorScale, int shape) {
        GL20.glUniform4f(this.fogColor, fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
        GL20.glUniform1f(fogStart, start);
        GL20.glUniform1f(fogEnd, end);
        GL20.glUniform1f(fogDensity, density);
        GL20.glUniform1f(hazeStart, hazeFloor);
        GL20.glUniform4f(sharedFog, start, end, hazeFloor, density);
        GL20.glUniform3f(sharedFogColor, fogColor[0], fogColor[1], fogColor[2]);
        GL20.glUniform1i(sharedFogShape, shape);
        GL20.glUniform1f(lodColorScale, colorScale);
        GL20.glUniform1f(lightEnabled, lightOn ? 1.0F : 0.0F);
        GL20.glUniform4f(colorModulator, 1.0F, 1.0F, 1.0F, 1.0F);
    }

    void setDetailDistance(float blocks) {
        GL20.glUniform1f(detailDistance, Math.max(0.0F, blocks));
    }

    void setDirectionalLighting(ClientLevel level) {
        for (Direction face : Direction.values()) {
            faceTints[face.get3DDataValue()] = level.getShade(face, true);
        }
        faceTints[6] = level.getShade(Direction.UP, false);
        GL20.glUniform1fv(directionalTint, faceTints);
    }

    /**
     * Floor for the written alpha: one for the opaque pass, zero for the
     * translucent fluid pass where the sprite's own alpha rules.
     */
    void setOpaqueAlpha(float alpha) {
        GL20.glUniform1f(opaqueAlpha, alpha);
    }

    @Override
    public void close() {
        program.close();
    }

    private static final String TERRAIN_VERTEX = """
            #version 150
            uniform usamplerBuffer QuadPayload;
            // The per-tile ownership mask is sampled in the vertex stage for
            // quads whose packed record names one fixed source cell.  Such a
            // quad can be rejected before rasterisation when a finer owner
            // has taken that cell.  Merged quads keep the fragment path,
            // because their local coordinates can span several cells.
            uniform sampler2D Yield;
            uniform int QuadBaseTexel;
            uniform int PaletteBaseTexel;
            uniform vec2 TerrainMorph;
            uniform vec2 MorphBounds;
            uniform sampler2D SpriteTable;
            uniform mat4 ModelViewMat;
            uniform mat4 ProjMat;
            uniform vec3 TileOffset;
            uniform float Spacing;
            uniform int CellAxis;
            uniform float DirectionalTint[7];
            uniform bool FastLeaves;
            uniform int LeafSprites[8];
             uniform int UseAverage;
             uniform float DetailDistance;
            flat out int vMaterialValid;
            flat out vec4 vMaterialAverage;
            flat out vec4 vMaterialRect;
            flat out float vSprite;
            out vec4 vColor;
            out float vSkyLight;
            flat out float vModelUv;
            flat out float vCutout;
            flat out float vWater;
             out vec2 tileUv;
             out vec2 localXZ;
             out vec3 relative;
            out float vDistance;
            flat out uint vCell;
            flat out float vCellLocal;
            flat out int vOwnershipBits;
            flat out uint vCoverageAxis;
            flat out vec3 vFaceNormal;
            flat out vec4 vFluidPlane;
            flat out float vSurfaceVisible;
            flat out uint vRealBoundary;
            flat out uint vTerrainWall;
            flat out uint vFeature;
            flat out uint vDisplayApproximate;

            // One packed quad spans three RGBA32UI texels: texel A holds the
            // x/z words, texel B the y words, attributes and tint, and texel C
            // the source cell used by non-merged geometry at coverage seams.
            // Each 16-bit half belongs to one corner, low half first.
            float halfX(uvec4 texel, int corner) {
                uint word = corner < 2 ? texel.x : texel.y;
                return float((corner & 1) == 0 ? (word & 0xFFFFu) : (word >> 16u));
            }

            float halfZ(uvec4 texel, int corner) {
                uint word = corner < 2 ? texel.z : texel.w;
                return float((corner & 1) == 0 ? (word & 0xFFFFu) : (word >> 16u));
            }

            float half16(uvec4 texel, int corner) {
                uint word = corner < 2 ? texel.x : texel.y;
                return float((corner & 1) == 0 ? (word & 0xFFFFu) : (word >> 16u));
            }

            float morphAt(ivec2 p) {
                int axis = CellAxis + 1;
                p = clamp(p, ivec2(0), ivec2(CellAxis));
                int i = p.y * axis + p.x;
                uvec4 value = texelFetch(QuadPayload, QuadBaseTexel + int(TerrainMorph.x) + i / 4);
                return float(int(value[i % 4])) / 256.0;
            }
            float terrainDelta(vec2 xz) {
                vec2 grid = clamp(xz / Spacing, vec2(0), vec2(CellAxis));
                ivec2 p = ivec2(floor(grid));
                vec2 t = fract(grid);
                return mix(mix(morphAt(p), morphAt(p + ivec2(1,0)), t.x),
                           mix(morphAt(p + ivec2(0,1)), morphAt(p + ivec2(1,1)), t.x), t.y);
            }
            """ + OWNERSHIP_GLSL + """
            void main() {
                int quad = gl_VertexID >> 2;
                int corner = gl_VertexID & 3;
                bool rectangleRecord = false, crossRecord = false;
                uvec3 rectangle0 = uvec3(0u), rectangle2 = uvec3(0u), rectangleCorner = uvec3(0u);
                int recordBase = QuadBaseTexel + quad * (PaletteBaseTexel < 0 ? 1 : PaletteBaseTexel > 0 ? 2 : 3);
                uvec4 texelA = texelFetch(QuadPayload, recordBase);
                uvec4 texelB;
                if (PaletteBaseTexel < 0) {
                    // Four aligned coordinate/attribute words, then one cell/color
                    // word per quad. ceil(5*n/4) is the palette texel offset;
                    // floor(offset*4/5) recovers n exactly without an extra header.
                    int metadataBase = abs(PaletteBaseTexel) * 4 / 5;
                    uint cellColor = texelFetch(QuadPayload, QuadBaseTexel + metadataBase + quad / 4)[quad % 4];
                    uint a = texelA.x, b = texelA.y, c = texelA.z, attr = texelA.w;
                    uint axis = (attr >> 24u) & 3u;
                    uint plane = axis == 1u ? a : axis == 2u ? b : c;
                    if ((plane & 0x80000000u) != 0u) {
                        texelA = texelFetch(QuadPayload, QuadBaseTexel + int(a & 0x7fffffffu));
                        uvec4 y = texelFetch(QuadPayload, QuadBaseTexel + int(a & 0x7fffffffu) + 1);
                        texelB = uvec4(y.xy, attr, cellColor);
                    } else {
                        rectangleRecord = true;
                        crossRecord = (plane & 0x40000000u) != 0u;
                        if (crossRecord) {
                            if (axis == 1u) a &= 0xbfffffffu;
                            else b &= 0xbfffffffu;
                        }
                        rectangle0 = uvec3(a & 65535u, c & 65535u, b & 65535u);
                        rectangle2 = uvec3(a >> 16u, c >> 16u, b >> 16u);
                        if (crossRecord) {
                            // Cross corners 0/3 share XZ and 0/1 share Y.
                            // The two diagonals retain their original UV axes and winding.
                            bool otherXZ = corner == 1 || corner == 2;
                            rectangleCorner = uvec3(otherXZ ? rectangle2.x : rectangle0.x,
                                    corner >= 2 ? rectangle2.y : rectangle0.y,
                                    otherXZ ? rectangle2.z : rectangle0.z);
                        } else {
                            if (axis == 0u) rectangle2.y = rectangle0.y;
                            else if (axis == 1u) rectangle2.x = rectangle0.x;
                            else rectangle2.z = rectangle0.z;
                            uint mask = corner == 0 ? 0u : corner == 2 ? 7u : corner == 1 ? plane >> 16u : (plane >> 16u) ^ 7u;
                            rectangleCorner = uvec3((mask & 1u) != 0u ? rectangle2.x : rectangle0.x,
                                    (mask & 2u) != 0u ? rectangle2.y : rectangle0.y,
                                    (mask & 4u) != 0u ? rectangle2.z : rectangle0.z);
                        }
                        texelB = uvec4(0u, 0u, attr, cellColor);
                    }
                } else texelB = texelFetch(QuadPayload, recordBase + 1);
                uvec4 texelC;
                if (PaletteBaseTexel != 0) {
                    uvec4 colors = texelFetch(QuadPayload, QuadBaseTexel + abs(PaletteBaseTexel) + int(texelB.w >> 16u));
                    texelC = uvec4(texelB.w & 65535u, colors.yzw);
                    texelB.w = colors.x;
                } else {
                    texelC = texelFetch(QuadPayload, recordBase + 2);
                }
                uint attr = texelB.z;
                vCell = texelC.x;
                vRealBoundary = (texelC.y >> 25u) & 3u;
                vTerrainWall = (texelC.y >> 27u) & 1u;
                vFeature = (texelC.z >> 24u) & 1u;
                vDisplayApproximate = (texelC.z >> 25u) & 1u;
                vCellLocal = (texelC.y & 0x01000000u) == 0u ? 1.0 : 0.0;
                bool sourceOwned = true;
                vOwnershipBits = 0;
                if (vCellLocal < 0.5 && CellAxis > 0) {
                    ivec2 coverageCell = ivec2(int(vCell) % CellAxis,
                            int(vCell) / CellAxis);
                    int ownershipBits = int(vssOwnershipWord(coverageCell.y * CellAxis + coverageCell.x) & 255u);
                    vOwnershipBits = ownershipBits;
                    sourceOwned = coverageCell.y < CellAxis && (ownershipBits & (vFeature != 0u ? 64 : 128)) != 0;
                }
                // Ownership is constant for every corner of this fixed-cell quad.
                // Do not fetch morph/material data or expand a fully replaced primitive.
                if (!sourceOwned) {
                    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                    return;
                }
                bool fineCoordinates = (attr & (1u << 20)) != 0u;
                uint fluid = (attr >> 22) & 3u;
                bool fluidFineY = fluid != 0u && (attr & (1u << 21)) != 0u;
                float horizontalScale = fineCoordinates ? 16.0 : 1.0 / exp2(float((attr >> 16u) & 15u));
                vec3 local = rectangleRecord ? vec3(float(rectangleCorner.x) / horizontalScale,
                        (float(rectangleCorner.y) - 32768.0) / (fineCoordinates || fluidFineY ? 16.0 : 4.0),
                        float(rectangleCorner.z) / horizontalScale) : vec3(halfX(texelA, corner) / horizontalScale,
                        (half16(texelB, corner) - 32768.0) / (fineCoordinates || fluidFineY ? 16.0 : 4.0),
                        halfZ(texelA, corner) / horizontalScale);
                uint sprite = attr & 0xFFFFu;
                uint axis = (attr >> 24) & 3u;
                vCoverageAxis = axis;
                bool positive = (attr & (1u << 30)) != 0u;
                bool down = fluid == 0u && (attr & (1u << 29)) != 0u;
                bool modelUv = fluid == 0u && (attr & (1u << 21)) != 0u;
                vModelUv = modelUv ? 1.0 : 0.0;
                bool unshaded = (attr & (1u << 27)) != 0u;
                bool diagonal = unshaded && !modelUv;
                int face = unshaded ? 6 : axis == 0u ? (down ? 0 : 1)
                        : axis == 1u ? (positive ? 5 : 4) : (positive ? 3 : 2);
                vec3 materialPosition = local;
                // One continuous displacement field for every shared vertex.
                // Boundary rows and banks are fixed by the worker; water and
                // feature primitives do not move. No overlapping terrain layer.
                if (TerrainMorph.y > 0.0 && fluid == 0u && vFeature == 0u)
                    local.y = clamp(local.y + terrainDelta(local.xz) * TerrainMorph.y,
                                    min(local.y, MorphBounds.x), max(local.y, MorphBounds.y));
                bool uvYPos = (attr & (1u << 28)) != 0u;
                // Regular X-facing walls use Z as U and regular Z-facing
                // walls use X as U. Grass/flower crosses are diagonal, so
                // their U axis is the varying world coordinate of the plane;
                // using the constant perpendicular coordinate turns the
                // cutout into a one-pixel vertical stripe.
                tileUv = axis == 0u ? materialPosition.xz
                       : diagonal
                       ? (axis == 1u
                           ? vec2(materialPosition.x, uvYPos ? materialPosition.y : -materialPosition.y)
                           : vec2(materialPosition.z, uvYPos ? materialPosition.y : -materialPosition.y))
                       : (axis == 1u
                           ? vec2(materialPosition.z, uvYPos ? materialPosition.y : -materialPosition.y)
                           : vec2(materialPosition.x, uvYPos ? materialPosition.y : -materialPosition.y));
                // Geometry spacing never changes block texture scale. Tile
                // origins are whole blocks, so these UVs also match adjacent LODs.
                if (modelUv && int(sprite) < textureSize(SpriteTable, 0).x) {
                    vec4 uv = texelFetch(SpriteTable, ivec2(int(sprite), 2 + corner / 2), 0);
                    tileUv = (corner & 1) == 0 ? uv.xy : uv.zw;
                }
                localXZ = local.xz;
             relative = local + TileOffset;
                vDistance = length(relative.xz);
                vSprite = float(sprite);
               // Existing/cold meshes retain their original cutout flag. The applied
               // quality setting changes only verified leaf rows, so cached geometry
               // can switch immediately and grass/flowers retain their alpha holes.
               bool opaqueLeaf = FastLeaves && fluid == 0u && !unshaded && sprite < 256u
                       && ((uint(LeafSprites[int(sprite) >> 5]) >> (sprite & 31u)) & 1u) != 0u;
               bool cutout = (attr & (1u << 26)) != 0u && !opaqueLeaf;
               // A flat varying must agree at every corner, including quads crossing
               // the cutoff. Only skip lookup when the complete XZ box is outside it.
               // Cutouts and fluids always need their texture/alpha. Only solid
               // faces can drop material lookup by distance, so avoid rebuilding
               // a four-corner XZ box for every grass, flower and leaf vertex.
               bool keepMaterial = cutout || fluid != 0u || DetailDistance <= 0.0;
               if (!keepMaterial) {
                   vec2 quadMin = (rectangleRecord ? vec2(min(rectangle0.xz, rectangle2.xz)) : vec2(min(min(halfX(texelA, 0), halfX(texelA, 1)), min(halfX(texelA, 2), halfX(texelA, 3))),
                                           min(min(halfZ(texelA, 0), halfZ(texelA, 1)), min(halfZ(texelA, 2), halfZ(texelA, 3))))) / horizontalScale + TileOffset.xz;
                   vec2 quadMax = (rectangleRecord ? vec2(max(rectangle0.xz, rectangle2.xz)) : vec2(max(max(halfX(texelA, 0), halfX(texelA, 1)), max(halfX(texelA, 2), halfX(texelA, 3))),
                                           max(max(halfZ(texelA, 0), halfZ(texelA, 1)), max(halfZ(texelA, 2), halfZ(texelA, 3))))) / horizontalScale + TileOffset.xz;
                   vec2 nearestMaterial = max(max(quadMin, -quadMax), vec2(0.0));
                   bool farMaterial = DetailDistance > 0.0
                           && dot(nearestMaterial, nearestMaterial) > DetailDistance * DetailDistance;
                   keepMaterial = !farMaterial;
               }
               vMaterialValid = (UseAverage == 0 || (attr & (1u << 27)) != 0u)
                       && keepMaterial && sprite > 0u && int(sprite) < textureSize(SpriteTable, 0).x ? 1 : 0;
                vMaterialAverage = vec4(1.0);
                vMaterialRect = vec4(0.0);
                if (vMaterialValid != 0) {
                    vMaterialAverage = texelFetch(SpriteTable, ivec2(int(sprite), 1), 0);
                    vMaterialRect = texelFetch(SpriteTable, ivec2(int(sprite), 0), 0);
                    if (UseAverage != 0 && vMaterialRect.x >= 0.0) vMaterialValid = 0;
                }
                uint cornerColor = corner == 0 ? texelB.w
                        : corner == 1 ? texelC.y : corner == 2 ? texelC.z : texelC.w;
                vSkyLight = 15.0 - float(cornerColor >> 28u);
                vColor = vec4(
                        float((cornerColor >> 16) & 255u) / 255.0,
                        float((cornerColor >> 8) & 255u) / 255.0,
                        float(cornerColor & 255u) / 255.0,
                        vMaterialValid != 0 && vMaterialRect.x < 0.0 ? 1.0 : DirectionalTint[face]);
                vCutout = cutout ? 1.0 : 0.0;
                vWater = float(fluid);
                gl_Position = ProjMat * ModelViewMat * vec4(relative, 1.0);
                // All four vertices of a fixed-cell quad take this branch,
                // so the primitive is clipped as a whole.  Do not apply it to
                // merged/local quads: those still need per-fragment coverage
                // for their cell-spanning geometry.
                if (!sourceOwned) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                #ifdef VSS_IRIS
                gl_Position.xy += vssTaaShift() * gl_Position.w;
                #endif
                // Crossed plants are double-sided and carry no fluid plane.
                // Their position is final; skip the solid face-plane/culling work.
                if (diagonal && fluid == 0u) {
                    vFaceNormal = vec3(0.0);
                    vSurfaceVisible = 1.0;
                    vFluidPlane = vec4(0.0);
                    return;
                }
                // The triangle stream has mixed winding. Cull using its
                // explicit outward normal instead, per face, not per tile.
                // Only explicit cavity ceilings face down. Exterior tops
                // remain one-sided and crossed plants remain double-sided.
                vec3 faceNormal = axis == 0u ? vec3(0.0, down ? -1.0 : 1.0, 0.0)
                        : axis == 1u ? vec3(positive ? 1.0 : -1.0, 0.0, 0.0)
                        : vec3(0.0, 0.0, positive ? 1.0 : -1.0);
                vFaceNormal = diagonal ? vec3(0.0) : faceNormal;
                // Moving only some corners outside clip space stretches the
                // remaining triangles toward that artificial clip position.
                // A sloping or morphing top can straddle eye height. Its
                // axis normal is only an approximation, so this test need
                // not agree at all four corners. Only skip rasterization
                // when the face-normal coordinate is constant for the quad.
                // Other faces retain their real positions and flat discard.
                uint planeA = axis == 0u ? texelB.x : axis == 1u ? texelA.x : texelA.z;
                uint planeB = axis == 0u ? texelB.y : axis == 1u ? texelA.y : texelA.w;
                bool constantFacePlane = (axis != 0u || fluid != 0u || TerrainMorph.y <= 0.0)
                        && (rectangleRecord && !crossRecord || planeA == planeB && (planeA & 65535u) == (planeA >> 16u));
                // An axis normal proves back-facing only for a constant plane.
                // Sloped/morphed tops can cross eye height; a flat varying from
                // their provoking vertex used to delete just one triangle.
                bool surfaceVisible = diagonal || !constantFacePlane || dot(faceNormal, relative) < 0.0;
                vSurfaceVisible = surfaceVisible ? 1.0 : 0.0;
                vFluidPlane = fluid != 0u && constantFacePlane
                        ? vec4(faceNormal, -dot(faceNormal, relative)) : vec4(0.0);
                if (!surfaceVisible && constantFacePlane) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
            }
            """;

    private static final String TERRAIN_FRAGMENT = """
            #version 150
            uniform sampler2D Atlas;
            uniform sampler2D Lightmap;
             uniform sampler2D SpriteTable;
             uniform sampler2D Yield;
            uniform sampler3D VanillaMask;
            uniform vec3 VanillaMaskOrigin;
            uniform ivec3 VanillaMaskSize;
            uniform float VanillaRenderDistance;
            uniform mat4 ProjMat;
            uniform sampler2D MainDepth;
            #ifndef VSS_IRIS
            uniform bool WaterDepthHistoryAvailable;
            uniform sampler2D WaterRealDepth;
            uniform sampler2D WaterExportedDepth;
            #endif
            uniform vec2 MainDepthPlanes;
            uniform float DepthBias;
            uniform vec3 ViewOrigin;
            uniform vec4 RealCoverageBounds;
            uniform sampler2D ExactCoverage;
            uniform vec3 ExactCoverageGrid; // camera-relative origin X/Z and column count
            uniform float HorizonDistance;
            uniform float RealRenderDistance;
            uniform bool ReplaceBoundaryWalls;
            """ + PredictionFogBridge.GLSL + """
            #ifndef VSS_IRIS
            uniform sampler2D VoxyDepth;
            uniform bool VoxyDepthAvailable;
            uniform vec3 VoxyDepthTransform; // window-to-clip scale, bias, direction toward near
            uniform vec4 VoxyDistanceNumerator;
            uniform vec4 VoxyDistanceDenominator;
            #endif
            uniform vec4 ColorModulator;
            uniform vec4 LodFogColor;
            uniform float LodFogStart;
            uniform float LodFogEnd;
            uniform float LodFogDensity;
            uniform float LodHazeStart;
             uniform float LodColorScale;
             uniform float LodLightmap;
             uniform float DetailDistance;
            uniform int UseAverage;
            uniform float Spacing;
            uniform int CellAxis;
            uniform float OpaqueAlpha;
            flat in int vMaterialValid;
            flat in vec4 vMaterialAverage;
            flat in vec4 vMaterialRect;
            flat in float vSprite;
            in vec4 vColor;
            flat in float vCutout;
            flat in float vWater;
            in float vSkyLight;
            flat in float vModelUv;
             flat in uint vCell;
             flat in float vCellLocal;
            flat in int vOwnershipBits;
            const bool ReuseFixedOwnership = true;
            flat in uint vCoverageAxis;
            flat in vec3 vFaceNormal;
            flat in vec4 vFluidPlane;
            uniform mat4 WaterInverseMvp;
            flat in float vSurfaceVisible;
            flat in uint vRealBoundary;
            flat in uint vTerrainWall;
            flat in uint vFeature;
            flat in uint vDisplayApproximate;
             in vec2 tileUv;
             in vec2 localXZ;
             in vec3 relative;
            in float vDistance;
            #ifdef VSS_IRIS
            struct VoxyFragmentParameters {
                vec4 sampledColour; vec2 tile; vec2 uv; uint face; uint modelId;
                vec2 lightMap; vec4 tinting; uint customId;
            };
            void voxy_emitFragment(VoxyFragmentParameters parameters);
            uniform uint VssMaterialIds[256];
            uniform mat4 VssInverseProjection;
            uniform vec2 VssViewport;
            uniform bool VssZeroToOne;
            uniform float VssClearDepth;
            #else
            out vec4 fragColor;
            """ + PredictionVoxyDepth.RESOLVE_GLSL + """
            #endif

             vec2 spriteUv(vec2 rectMin, vec2 rectMax, vec2 uv, vec2 uvDx, vec2 uvDy) {
                 vec2 size = rectMax - rectMin;
                 vec2 pixels = max(size * vec2(textureSize(Atlas, 0)), vec2(1.0));
                 float rho = max(length(uvDx * pixels), length(uvDy * pixels));
                 float mip = max(0.0, ceil(log2(max(rho, 1.0))));
                 // Keep the filter footprint inside this sprite, including
                 // the next mip sampled by trilinear filtering during zoom.
                 vec2 inset = min(vec2(0.5), vec2(0.5 * exp2(mip)) / pixels);
                 vec2 repeated = clamp(vModelUv > 0.5 ? uv : fract(uv), inset, vec2(1.0) - inset);
                 return rectMin + repeated * size;
             }

             float realColumnCoverage(vec2 position) {
                 ivec2 cell = ivec2(floor((position - ExactCoverageGrid.xy) / 16.0));
                 return ExactCoverageGrid.z > 0.0 && all(greaterThanEqual(cell, ivec2(0)))
                         && all(lessThan(cell, ivec2(ExactCoverageGrid.z)))
                         ? texelFetch(ExactCoverage, cell, 0).r : 0.0;
             }

             bool hasRealColumn(vec2 position) {
                 return realColumnCoverage(position) > 0.5;
             }

             bool realCoverageOwnsSurface(float realDistance, float predictedDistance, bool realGround) {
                 // A rear cut wall is not the real ground at this column.
                 // Cached columns can prefer a rendered ground surface, but
                 // cannot make the buried side of a section erase its fallback.
                 if (vFeature != 0u || !realGround || vWater > 0.5 || realDistance <= 0.0 || predictedDistance <= 0.0) return false;
                 // Reproject along the actual camera ray, including view bob.
                 // Both ends of the ray segment must belong to confirmed real
                 // coverage. Readiness alone never removes a prediction tile;
                 // missing real pixels retain their fallback this frame.
                 vec3 realPosition = ViewOrigin + (relative - ViewOrigin) * (realDistance / predictedDistance);
                 // Cached columns are not proof of visibility beyond the renderer's range.
                 if (RealRenderDistance >= 0.0 && (dot(relative.xz, relative.xz) > RealRenderDistance * RealRenderDistance
                         || dot(realPosition.xz, realPosition.xz) > RealRenderDistance * RealRenderDistance)) return false;
                 // A different mountain along the same ray must not erase foreground terrain.
                 vec2 separation = abs(realPosition.xz - relative.xz);
                 if (max(separation.x, separation.y) > min(64.0, max(32.0, Spacing * 2.0))) return false;
                 if (hasRealColumn(relative.xz) && hasRealColumn(realPosition.xz)) return true;
                 return all(greaterThanEqual(relative.xz, RealCoverageBounds.xy))
                         && all(lessThan(relative.xz, RealCoverageBounds.zw))
                         && all(greaterThanEqual(realPosition.xz, RealCoverageBounds.xy))
                         && all(lessThan(realPosition.xz, RealCoverageBounds.zw));
             }


             // Vegetation does not reconstruct ground normals or resolve fluid ties.
             // Keep its material sample and handoff in one bounded path, with the
             // exact current-frame depth/mask rules used by the general shader.
            """ + OWNERSHIP_GLSL + """
             void renderFeature(vec2 uvDx, vec2 uvDy, float footprint) {
                if (vSurfaceVisible < 0.5) discard;
                float horizontalDistance2 = dot(relative.xz, relative.xz);
                if (HorizonDistance > 0.0 && horizontalDistance2 > HorizonDistance * HorizonDistance) discard;
                ivec2 sourceCell = ivec2(int(vCell) % CellAxis, int(vCell) / CellAxis);
                ivec2 cell = vCellLocal > 0.5 ? ivec2(floor(localXZ / Spacing)) : sourceCell;
                if (vCoverageAxis == 1u) cell.x = sourceCell.x;
                if (vCoverageAxis == 2u) cell.y = sourceCell.y;
                if (cell.x < 0 || cell.y < 0 || cell.x >= CellAxis || cell.y >= CellAxis) discard;
                vec2 ownershipOrigin=vec2(cell)*Spacing; float ownershipSize=Spacing;
                int ownershipBits = CoverageTree ? int(vssOwnershipAt(localXZ,ownershipOrigin,ownershipSize) & 255u)
                        : ReuseFixedOwnership && vCellLocal < 0.5 ? vOwnershipBits : int(vssOwnershipWord(cell.y*CellAxis+cell.x) & 255u);
                if ((ownershipBits & 64) == 0) discard;
                float detailWeight = 1.0 - smoothstep(0.5, 1.0, footprint);
                if (DetailDistance > 0.0 && vCutout < 0.5 && vWater < 0.5)
                    detailWeight *= 1.0 - smoothstep(DetailDistance * 0.75, DetailDistance, length(relative.xz));
                vec4 color = vColor * ColorModulator;
                if (color.a == 0.0) {
                    discard;
                }
                bool lava = vWater > 1.5 && vWater < 2.5;
                // Alpha-tested material is resolved before fetching lightmap.
                // Transparent pixels and synthetic sprites do not need that read.
                // The vanilla water sprite has alpha 180/255. Preserve the
                // resource pack's actual alpha whenever a material is present.
                vec4 albedo = vec4(color.rgb, vWater > 0.5 && vWater < 1.5 ? 180.0 / 255.0 : 1.0);
                vec3 materialTint = vec3(1.0);
                vec2 materialUv = fract(tileUv);
                 if (vMaterialValid != 0) {
                    vec4 averageRow = vMaterialAverage;
                    vec3 tintRatio = min(color.rgb / max(averageRow.rgb, vec3(0.004)),
                            vec3(1.25));
                    materialTint = tintRatio;
                    vec4 rect = vMaterialRect;
                    vec2 size = rect.zw - rect.xy;
                    if (rect.x < 0.0) {
                        ivec2 pixel = ivec2(clamp(fract(tileUv) * 16.0, vec2(0.0), vec2(15.0)));
                        albedo = texelFetch(SpriteTable, ivec2(pixel.x, 4 + int(rect.y) * 16 + pixel.y), 0);
                        if (albedo.a < 0.1) discard;
                        materialTint = vec3(1.0);
                    } else if (detailWeight > 0.0
                            && size.x > 0.0 && size.y > 0.0) {
                        vec2 uv = spriteUv(rect.xy, rect.zw, tileUv, uvDx, uvDy);
                        materialUv = uv;
                        vec4 tex = textureGrad(Atlas, uv, uvDx * size, uvDy * size);
                        if (vCutout > 0.5 && tex.a < 0.1) {
                            discard;
                        }
                        albedo = mix(vec4(color.rgb, averageRow.a),
                                vec4(tex.rgb * tintRatio, tex.a), detailWeight);
                    } else {
                        if (vCutout > 0.5 && size.x > 0.0 && size.y > 0.0) {
                            vec2 uv = spriteUv(rect.xy, rect.zw, tileUv, uvDx, uvDy);
                            if (textureGrad(Atlas, uv, uvDx * size, uvDy * size).a < 0.1) {
                                discard;
                            }
                        }
                        albedo = vec4(color.rgb, averageRow.a);
                     }
                 }

                bool realDepthRelevant = RealRenderDistance < 0.0
                        || horizontalDistance2 <= (RealRenderDistance + 64.0) * (RealRenderDistance + 64.0);
                if (realDepthRelevant) {
                    float mainDepth = texelFetch(MainDepth, ivec2(gl_FragCoord.xy), 0).r;
                    #ifdef VSS_IRIS
                    if (mainDepth != VssClearDepth) {
                        float clipDepth = VssZeroToOne ? mainDepth : mainDepth * 2.0 - 1.0;
                        vec4 mainView = VssInverseProjection * vec4(gl_FragCoord.xy / VssViewport * 2.0 - 1.0, clipDepth, 1.0);
                        if (abs(mainView.w) > 1e-10 && abs(mainView.z / mainView.w)
                                <= 1.0 / max(gl_FragCoord.w, 1e-30) + 0.02) discard;
                    }
                    #else
                    if (mainDepth < 1.0) {
                        float distance = 1.0 / max(gl_FragCoord.w, 1e-30) + DepthBias;
                        float projectedDepth = clamp((-MainDepthPlanes.x + MainDepthPlanes.y / distance) * 0.5 + 0.5, 0.0, 1.0);
                        float realDistance, voxyMainDepth;
                        bool originalVoxyDepth = vssResolveVoxyDepth(mainDepth, MainDepthPlanes, true,
                                realDistance, voxyMainDepth);
                        if (originalVoxyDepth ? distance >= realDistance : projectedDepth >= mainDepth) discard;
                    }
                    #endif
                }
                if (dot(relative, relative) < VanillaRenderDistance * VanillaRenderDistance) {
                    ivec3 section = ivec3(floor((relative - vFaceNormal * 0.01 - VanillaMaskOrigin) / 16.0));
                    if (all(greaterThanEqual(section, ivec3(0))) && all(lessThan(section, VanillaMaskSize))
                            && texelFetch(VanillaMask, section, 0).r > 0.5) discard;
                }
                vec3 lightmap = LodLightmap > 0.5 && !(vMaterialValid != 0 && vMaterialRect.x < 0.0)
                        ? texture(Lightmap, lava ? vec2(15.5 / 16.0, 15.5 / 16.0)
                                                 : vec2(0.5 / 16.0, (vSkyLight + 0.5) / 16.0)).rgb
                        : vec3(1.0);
                #ifndef VSS_IRIS
                // The infinite reversed-depth projection built by
                // VssLodProjection maps window depth to 1 / clip-W, which is
                // exactly gl_FragCoord.w.  Do not write gl_FragDepth here:
                // keeping the fixed-function depth result lets the driver run
                // its early depth test before this fragment shader performs
                // the ownership, mask, and material work.  Writing the same
                // value explicitly disables that optimization on common
                // desktop drivers and was a large cost in dense prediction
                // scenes.
                #endif
                 #ifdef VSS_IRIS
                uint face = abs(vFaceNormal.y) > 0.5 ? 1u : abs(vFaceNormal.x) > 0.5
                        ? (vFaceNormal.x > 0.0 ? 5u : 4u) : (vFaceNormal.z > 0.0 ? 3u : 2u);
                uint customId = VssMaterialIds[clamp(int(vSprite), 0, 255)];
                vec2 lm = lava || (vMaterialValid != 0 && vMaterialRect.x < 0.0)
                        ? vec2(248.0/256.0) : vec2(8.0/256.0, (vSkyLight * 16.0 + 8.0)/256.0);
                albedo.a = max(albedo.a, OpaqueAlpha);
                albedo.rgb /= max(materialTint, vec3(0.004));
                voxy_emitFragment(VoxyFragmentParameters(albedo, floor(tileUv), materialUv,
                        face, 0u, lm, vec4(materialTint, 1.0), customId));
                #else
                vec3 shaded = albedo.rgb * vColor.a * lightmap;
                // Prediction and Voxy use different lighting/tint paths. A
                // short near-field grade keeps the handoff from reading as
                // a bright green band while leaving the far horizon at full
                // brightness. LodHazeStart is the exact-field boundary in
                // blocks, supplied by the renderer for this purpose.
                float seamStart = max(64.0, LodHazeStart * 0.50);
                float seamEnd = max(seamStart + 1.0, LodHazeStart * 1.75);
                float seam = 1.0 - smoothstep(seamStart, seamEnd, vDistance);
                shaded *= mix(1.0, LodColorScale, seam);
                float alpha = max(albedo.a, OpaqueAlpha);
                fragColor = vec4(vssPredictionFog(shaded, relative), alpha);
                #endif

             }

             void main() {
                 // Derivatives must be available before coverage/cutout
                 // discard for textured quads.  Far solid faces carry their
                 // final averaged colour and have no material lookup at all;
                 // avoid calculating derivatives for those pixels.
                 vec2 uvDx = vec2(0.0);
                 vec2 uvDy = vec2(0.0);
                 float footprint = 0.0;
                 if (vMaterialValid != 0) {
                     uvDx = dFdx(tileUv);
                     uvDy = dFdy(tileUv);
                     footprint = max(length(uvDx), length(uvDy));
                 }
                // The shorter path pays off for resolved vegetation. Tiny distant
                // cutouts retain the general path instead of carrying a sampled
                // colour across their depth/mask checks. UV derivatives already
                // exist, so selection needs no extra texture or CPU work.
                if (vFeature != 0u && vWater < 0.5 && vMaterialValid != 0
                        && (vCutout > 0.5 || vMaterialRect.x < 0.0) && footprint <= 0.0625
                        && vRealBoundary == 0u && vTerrainWall == 0u && vDisplayApproximate == 0u) {
                    renderFeature(uvDx, uvDy, footprint);
                    return;
                }
                // Compare in the main target's depth space. Equal/quantized
                // depths belong to Voxy; a closer prediction still occludes
                // distant cut faces. Sky must remain fillable at any distance.
                 bool realDepthRelevant = RealRenderDistance < 0.0
                         || dot(relative.xz, relative.xz) <= (RealRenderDistance + 64.0)
                         * (RealRenderDistance + 64.0);
                 #ifdef VSS_IRIS
                 float mainDepth = realDepthRelevant
                         ? texelFetch(MainDepth, ivec2(gl_FragCoord.xy), 0).r : VssClearDepth;
                 #else
                 float mainDepth = realDepthRelevant
                         ? texelFetch(MainDepth, ivec2(gl_FragCoord.xy), 0).r : 1.0;
                 float sampledMainDepth = mainDepth;
                 bool predictionExported = false;
                 if (vWater > 0.5 && realDepthRelevant && WaterDepthHistoryAvailable) {
                     ivec2 pixel = ivec2(gl_FragCoord.xy);
                     float before = texelFetch(WaterRealDepth, pixel, 0).r;
                     float after = texelFetch(WaterExportedDepth, pixel, 0).r;
                     // Only undo an unchanged depth value actually written by
                     // our opaque export. A nearer later real surface remains
                     // authoritative. The 32F prediction attachment separately
                     // tests our riverbed without D24 quantization or feedback.
                     predictionExported = mainDepth == after && after < before;
                     if (predictionExported) mainDepth = before;
                 }
                 #endif
                // W is the same clip distance used by the rasterizer, including
                // view bob. Reconstruct real geometry before any discard so
                // screen derivatives remain defined across the fragment quad.
                float predictedDistance = 1.0 / max(gl_FragCoord.w, 1e-30);
                #ifndef VSS_IRIS
                // Large near-clipped water triangles can accumulate reciprocal-W
                // interpolation error. Intersect this pixel's ray with the exact
                // flat face instead; homogeneous coordinates retain view bob.
                if (vWater > 0.5 && dot(vFluidPlane.xyz, vFluidPlane.xyz) > 0.5) {
                    vec2 ndc = gl_FragCoord.xy / vec2(textureSize(MainDepth, 0)) * 2.0 - 1.0;
                    vec4 a = WaterInverseMvp * vec4(ndc, 1.0, 1.0);
                    vec4 b = WaterInverseMvp * vec4(ndc, -1.0, 1.0);
                    float da = dot(vFluidPlane, a), db = dot(vFluidPlane, b);
                    float denominator = a.w * db - b.w * da;
                    float planeDistance = (db - da) / denominator;
                    if (planeDistance > 0.0 && planeDistance < 1e30) predictedDistance = planeDistance;
                }
                #endif
                 #ifdef VSS_IRIS
                float clipDepth = VssZeroToOne ? mainDepth : mainDepth * 2.0 - 1.0;
                vec4 mainView = VssInverseProjection * vec4(gl_FragCoord.xy / VssViewport * 2.0 - 1.0, clipDepth, 1.0);
                float realDistance = abs(mainView.w) > 1e-10 ? abs(mainView.z / mainView.w) : 0.0;
                #else
                // Rasterizer W retains reciprocal clip distance. Recovering it
                // from window Z subtracts nearly equal numbers at altitude and
                // makes coplanar water alternate ownership in horizontal bands.
                float distance = predictedDistance;
                // A fluid tie is measured perpendicular to the face, not
                // along view Z. At grazing angles a two-centimetre plane
                // tolerance spans much more clip distance. This also bounds
                // subpixel rasterization error on large near-clipped quads.
                float tieBias = vWater > 0.5 ? DepthBias * max(1.0,
                        distance / max(abs(dot(vFaceNormal, relative)), DepthBias)) : DepthBias;
                distance += tieBias;
                float projectedDepth = clamp((-MainDepthPlanes.x
                        + MainDepthPlanes.y / distance) * 0.5 + 0.5, 0.0, 1.0);
                float realDistance = MainDepthPlanes.y / (mainDepth * 2.0 - 1.0 + MainDepthPlanes.x);
                bool originalVoxyDepth = false;
                // Water and features need precise ties inside the vanilla far
                // plane too. General ground keeps its existing sampling tolerance.
                if (realDepthRelevant && (mainDepth < 1.0 || predictionExported)
                        && (vWater > 0.5 || vFeature != 0u || mainDepth >= 1.0 - 2.0 / 16777215.0)) {
                    float voxyDistance, voxyMainDepth;
                    originalVoxyDepth = vssResolveVoxyDepth(predictionExported ? sampledMainDepth : mainDepth,
                            MainDepthPlanes, vWater > 0.5 || vFeature != 0u, voxyDistance, voxyMainDepth);
                    if (originalVoxyDepth) {
                        realDistance = voxyDistance;
                        if (predictionExported) mainDepth = min(mainDepth, voxyMainDepth);
                    }
                }
                #endif
                bool realGround = false;
                if (vWater < 0.5 && vFeature == 0u) {
                    float finiteDistance = realDistance > 0.0 && realDistance < 1e30 ? realDistance : 0.0;
                    vec3 realPosition = ViewOrigin + (relative - ViewOrigin) * (finiteDistance / predictedDistance);
                    vec3 normal = cross(dFdx(realPosition), dFdy(realPosition));
                    // A surface facing upward can correct a sampled height.
                    // A vertical side behind prediction must obey depth order.
                    // Reuse the current depth fetch; no extra texture or scan.
                    realGround = normal.y > 0.0 && normal.y * normal.y > 0.25 * dot(normal, normal);
                }
                if (vSurfaceVisible < 0.5) discard;
                // Parent meshes extend beyond the planned circle. Keep their
                // useful interior, but never display unrefinable outer slabs.
                if (HorizonDistance > 0.0 && dot(relative.xz, relative.xz)
                        > HorizonDistance * HorizonDistance) discard;
                // Reject cells owned by another prediction tile before handoff.
                ivec2 sourceCell = ivec2(int(vCell) % CellAxis, int(vCell) / CellAxis);
                ivec2 cell = vCellLocal > 0.5
                        ? ivec2(int(floor(localXZ.x / Spacing)),
                                int(floor(localXZ.y / Spacing)))
                        : sourceCell;
                // A wall lies on a cell boundary. Its perpendicular coordinate
                // must stay on the owning column, including at the tile edge.
                if (vDisplayApproximate != 0u) {
                    cell = ivec2(floor((localXZ - vFaceNormal.xz * 0.01) / Spacing));
                } else {
                    if (vCoverageAxis == 1u) cell.x = sourceCell.x;
                    if (vCoverageAxis == 2u) cell.y = sourceCell.y;
                }
                if (cell.x < 0 || cell.y < 0 || cell.x >= CellAxis || cell.y >= CellAxis) discard;
                // Vertex ownership is exact for fixed-cell primitives, including
                // boundary bits. Mixed/merged quads still select each fragment's cell.
                vec2 ownershipOrigin=vec2(cell)*Spacing; float ownershipSize=Spacing;
                vec2 ownershipPosition=localXZ;
                if(vCoverageAxis!=0u && (vTerrainWall!=0u || vDisplayApproximate!=0u)) ownershipPosition-=vFaceNormal.xz*0.01;
                float ownership = CoverageTree ? float(vssOwnershipAt(ownershipPosition,ownershipOrigin,ownershipSize) & 255u)/255.0
                        : ReuseFixedOwnership && vCellLocal < 0.5 ? float(vOwnershipBits)/255.0
                        : float(vssOwnershipWord(cell.y*CellAxis+cell.x) & 255u)/255.0;
                int ownershipBits = int(round(ownership * 255.0));
                if ((ownershipBits & (vFeature != 0u ? 64 : 128)) == 0) discard;
                // A confirmed source column does not identify the real surface
                // at this pixel. The shared depth/ground handoff below checks
                // both ray endpoints so a rear cut wall or distant mountain
                // cannot erase this coarse foreground during partial loading.
                // Only worker-tagged ground walls can be replaced by a current seam.
                if (ReplaceBoundaryWalls && vTerrainWall != 0u && vWater < 0.5 && vCutout < 0.5 && vModelUv < 0.5
                        && vRealBoundary == 0u && vCoverageAxis != 0u && dot(vFaceNormal, vFaceNormal) > 0.5) {
                    int flags = int(round(ownership * 255.0));
                    bool xNormal = vCoverageAxis == 1u;
                    float plane = xNormal ? localXZ.x : localXZ.y;
                    float origin = xNormal ? ownershipOrigin.x : ownershipOrigin.y;
                    int edge = abs(plane - origin) < 0.01 ? (xNormal ? 0 : 2)
                            : abs(plane - origin - ownershipSize) < 0.01 ? (xNormal ? 1 : 3) : -1;
                    if (edge >= 0 && (flags & (1 << edge)) != 0) discard;
                }
                #ifdef VSS_IRIS
                if (mainDepth != VssClearDepth && abs(mainView.w) > 1e-10
                        && realCoverageOwnsSurface(realDistance, predictedDistance, realGround)) discard;
                float fluidTie = 0.02 * max(1.0, predictedDistance / max(abs(dot(vFaceNormal, relative)), 0.02));
                if (vWater > 0.5 && mainDepth != VssClearDepth) {
                    // Compare in window depth, avoiding cancellation when an
                    // inverse projection reconstructs distant coplanar water.
                    // Two bins cover independent rasterization/storage rounding;
                    // the additional 2 cm bias is perpendicular to the surface.
                    vec4 biasedClip = ProjMat * vec4(0.0, 0.0, -predictedDistance - fluidTie, 1.0);
                    float biasedDepth = biasedClip.z / biasedClip.w;
                    if (!VssZeroToOne) biasedDepth = biasedDepth * 0.5 + 0.5;
                    float bins = 2.0 / 16777215.0;
                    if (VssClearDepth < 0.5 ? mainDepth >= biasedDepth - bins : mainDepth <= biasedDepth + bins) discard;
                }
                // Prefer rendered ground within its sampling tolerance; rear
                // section walls must not borrow the same height preference.
                if (vWater < 0.5 && mainDepth != VssClearDepth && abs(mainView.w) > 1e-10
                        && realDistance <= predictedDistance + (realGround && vFeature == 0u ? min(16.0, max(1.0, Spacing * 2.0)) : 0.02)) discard;
                #else
                if (mainDepth < 1.0 && (originalVoxyDepth ? distance >= realDistance : projectedDepth >= mainDepth)) {
                    discard;
                }
                // Sample-space error must not put the coarse fallback above
                // a rendered real surface. Bound the preference so distant
                // Voxy cut faces do not punch through foreground prediction.
                if (vFeature == 0u && realGround && vWater < 0.5 && mainDepth < 1.0 && abs(realDistance - distance) <= min(16.0, max(1.0, Spacing * 2.0))) discard;
                if (mainDepth < 1.0 && realCoverageOwnsSurface(realDistance, predictedDistance, realGround)) discard;
                #endif
                // Boundary faces belong to the solid on their inward side.
                ivec3 section = ivec3(floor((relative - vFaceNormal * 0.01
                        - VanillaMaskOrigin) / 16.0));
                // A compiled column can outlive its renderer's distance limit.
                // In particular, flying above loaded chunks must reveal the
                // prediction fallback. Use camera-relative 3D distance, which
                // stays independent of pitch, FOV and walking view bobbing.
                bool withinVanilla = dot(relative, relative) < VanillaRenderDistance * VanillaRenderDistance;
                if ((vRealBoundary & 1u) != 0u) {
                    // A height connector owns the prediction side even when
                    // its higher surface is in the compiled real column.
                    vec3 outward = (vRealBoundary & 2u) != 0u ? -vFaceNormal : vFaceNormal;
                    ivec3 inside = ivec3(floor((relative - outward * 0.01 - VanillaMaskOrigin) / 16.0));
                    ivec3 outside = ivec3(floor((relative + outward * 0.01 - VanillaMaskOrigin) / 16.0));
                    bool realInside = all(greaterThanEqual(inside, ivec3(0))) && all(lessThan(inside, VanillaMaskSize))
                            && texelFetch(VanillaMask, inside, 0).r > 0.5;
                    bool realOutside = all(greaterThanEqual(outside, ivec3(0))) && all(lessThan(outside, VanillaMaskSize))
                            && texelFetch(VanillaMask, outside, 0).r > 0.5;
                    if (!withinVanilla || !realInside || realOutside) discard;
                } else if (withinVanilla
                        && all(greaterThanEqual(section, ivec3(0)))
                        && all(lessThan(section, VanillaMaskSize))
                        && texelFetch(VanillaMask, section, 0).r > 0.5) {
                    discard;
                }
                float detailWeight = 1.0 - smoothstep(0.5, 1.0, footprint);
                if (DetailDistance > 0.0 && vCutout < 0.5 && vWater < 0.5)
                    detailWeight *= 1.0 - smoothstep(DetailDistance * 0.75, DetailDistance, length(relative.xz));
                vec4 color = vColor * ColorModulator;
                if (color.a == 0.0) {
                    discard;
                }
                bool lava = vWater > 1.5 && vWater < 2.5;
                // Alpha-tested material is resolved before fetching lightmap.
                // Transparent pixels and synthetic sprites do not need that read.
                // The vanilla water sprite has alpha 180/255. Preserve the
                // resource pack's actual alpha whenever a material is present.
                vec4 albedo = vec4(color.rgb, vWater > 0.5 && vWater < 1.5 ? 180.0 / 255.0 : 1.0);
                vec3 materialTint = vec3(1.0);
                vec2 materialUv = fract(tileUv);
                 if (vMaterialValid != 0) {
                    vec4 averageRow = vMaterialAverage;
                    vec3 tintRatio = min(color.rgb / max(averageRow.rgb, vec3(0.004)),
                            vec3(1.25));
                    materialTint = tintRatio;
                    vec4 rect = vMaterialRect;
                    vec2 size = rect.zw - rect.xy;
                    if (rect.x < 0.0) {
                        ivec2 pixel = ivec2(clamp(fract(tileUv) * 16.0, vec2(0.0), vec2(15.0)));
                        albedo = texelFetch(SpriteTable, ivec2(pixel.x, 4 + int(rect.y) * 16 + pixel.y), 0);
                        if (albedo.a < 0.1) discard;
                        materialTint = vec3(1.0);
                    } else if (detailWeight > 0.0
                            && size.x > 0.0 && size.y > 0.0) {
                        vec2 uv = spriteUv(rect.xy, rect.zw, tileUv, uvDx, uvDy);
                        materialUv = uv;
                        vec4 tex = textureGrad(Atlas, uv, uvDx * size, uvDy * size);
                        if (vCutout > 0.5 && tex.a < 0.1) {
                            discard;
                        }
                        albedo = mix(vec4(color.rgb, averageRow.a),
                                vec4(tex.rgb * tintRatio, tex.a), detailWeight);
                    } else {
                        if (vCutout > 0.5 && size.x > 0.0 && size.y > 0.0) {
                            vec2 uv = spriteUv(rect.xy, rect.zw, tileUv, uvDx, uvDy);
                            if (textureGrad(Atlas, uv, uvDx * size, uvDy * size).a < 0.1) {
                                discard;
                            }
                        }
                        albedo = vec4(color.rgb, averageRow.a);
                     }
                 }
                vec3 lightmap = LodLightmap > 0.5 && !(vMaterialValid != 0 && vMaterialRect.x < 0.0)
                        ? texture(Lightmap, lava ? vec2(15.5 / 16.0, 15.5 / 16.0)
                                                 : vec2(0.5 / 16.0, (vSkyLight + 0.5) / 16.0)).rgb
                        : vec3(1.0);
                #ifndef VSS_IRIS
                // The infinite reversed-depth projection built by
                // VssLodProjection maps window depth to 1 / clip-W, which is
                // exactly gl_FragCoord.w.  Do not write gl_FragDepth here:
                // keeping the fixed-function depth result lets the driver run
                // its early depth test before this fragment shader performs
                // the ownership, mask, and material work.  Writing the same
                // value explicitly disables that optimization on common
                // desktop drivers and was a large cost in dense prediction
                // scenes.
                #endif
                 #ifdef VSS_IRIS
                uint face = abs(vFaceNormal.y) > 0.5 ? 1u : abs(vFaceNormal.x) > 0.5
                        ? (vFaceNormal.x > 0.0 ? 5u : 4u) : (vFaceNormal.z > 0.0 ? 3u : 2u);
                uint customId = VssMaterialIds[clamp(int(vSprite), 0, 255)];
                vec2 lm = lava || (vMaterialValid != 0 && vMaterialRect.x < 0.0)
                        ? vec2(248.0/256.0) : vec2(8.0/256.0, (vSkyLight * 16.0 + 8.0)/256.0);
                albedo.a = max(albedo.a, OpaqueAlpha);
                albedo.rgb /= max(materialTint, vec3(0.004));
                voxy_emitFragment(VoxyFragmentParameters(albedo, floor(tileUv), materialUv,
                        face, 0u, lm, vec4(materialTint, 1.0), customId));
                #else
                vec3 shaded = albedo.rgb * vColor.a * lightmap;
                // Prediction and Voxy use different lighting/tint paths. A
                // short near-field grade keeps the handoff from reading as
                // a bright green band while leaving the far horizon at full
                // brightness. LodHazeStart is the exact-field boundary in
                // blocks, supplied by the renderer for this purpose.
                float seamStart = max(64.0, LodHazeStart * 0.50);
                float seamEnd = max(seamStart + 1.0, LodHazeStart * 1.75);
                float seam = 1.0 - smoothstep(seamStart, seamEnd, vDistance);
                shaded *= mix(1.0, LodColorScale, seam);
                float alpha = max(albedo.a, OpaqueAlpha);
                fragColor = vec4(vssPredictionFog(shaded, relative), alpha);
                #endif
            }
            """;
}
