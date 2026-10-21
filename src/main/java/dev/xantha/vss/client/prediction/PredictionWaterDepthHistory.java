package dev.xantha.vss.client.prediction;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL43;
import static org.lwjgl.opengl.GL33C.*;

/** Exact main-depth provenance for water drawn after prediction exports its opaque depth. */
final class PredictionWaterDepthHistory implements AutoCloseable {
    static final int REAL_UNIT = 9;
    static final int EXPORTED_UNIT = 10;
    static final int TEXTURE_UNITS = EXPORTED_UNIT + 1;
    private int real = -1, exported = -1, source = -1, width, height;
    private boolean available;

    int realTexture() { return real; }
    int exportedTexture() { return exported; }
    boolean available() { return available; }
    void reset() { available = false; }

    void beforeExport(RenderTarget main) {
        available = false;
        int depth = main.getDepthTextureId();
        if (real == -1 || source != depth || width != main.width || height != main.height) {
            close();
            source = depth; width = main.width; height = main.height;
            // Match the actual storage: a D24 -> D32F conversion would lose
            // the bit-for-bit comparison with later main/translucent depth.
            PredictionGlState.activeTexture(GL_TEXTURE0);
            PredictionGlState.bindTexture(depth);
            int format = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT);
            if (format == GL_DEPTH_COMPONENT) {
                int bits = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_DEPTH_SIZE);
                format = bits <= 16 ? GL_DEPTH_COMPONENT16 : bits <= 24 ? GL_DEPTH_COMPONENT24
                        : glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_DEPTH_TYPE) == GL_FLOAT
                        ? GL_DEPTH_COMPONENT32F : GL_DEPTH_COMPONENT32;
            }
            real = create(format);
            exported = create(format);
        }
        copy(main, real);
    }

    void afterExport(RenderTarget main) {
        copy(main, exported);
        available = true;
    }

    private int create(int format) {
        int texture = glGenTextures();
        PredictionGlState.activeTexture(GL_TEXTURE0);
        PredictionGlState.bindTexture(texture);
        try (var unpack = PredictionPixelUnpack.begin()) {
            glTexImage2D(GL_TEXTURE_2D, 0, format, width, height, 0,
                    GL_DEPTH_COMPONENT, GL_FLOAT, (java.nio.ByteBuffer) null);
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        PredictionGlState.bindTexture(0);
        return texture;
    }

    private void copy(RenderTarget main, int to) {
        if (GL.getCapabilities().glCopyImageSubData != 0L) {
            GL43.glCopyImageSubData(main.getDepthTextureId(), GL_TEXTURE_2D, 0, 0, 0, 0,
                    to, GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
        } else {
            int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, main.frameBufferId);
            PredictionGlState.activeTexture(GL_TEXTURE0);
            PredictionGlState.bindTexture(to);
            glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
            PredictionGlState.bindTexture(0);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
        }
    }

    @Override public void close() {
        if (real != -1) glDeleteTextures(real);
        if (exported != -1) glDeleteTextures(exported);
        real = exported = source = -1; width = height = 0;
        available = false;
    }
}
