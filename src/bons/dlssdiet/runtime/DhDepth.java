package bons.dlssdiet.runtime;

import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Matrix4f;

/**
 * What the input pass needs to hand DLSS one depth for the world AND Distant Horizons' LODs (config dh_depth): DH's
 * depth texture, the main and DH projections' depth terms (OpenGL m22/m32) and the combined depth's near (the main
 * camera's) and far (DH's). Without it DLSS sees LOD terrain at depth 1.0 like the sky. The same matrices the pack's
 * motion vectors use (gbufferProjection, dhProjection).
 */
final class DhDepth {
    static final DhDepth NONE = new DhDepth(0, 0, 0, 0, 0, 0, 0);

    final int texture;
    final float mainM22, mainM32, dhM22, dhM32, near, far;

    private DhDepth(int texture, float mainM22, float mainM32, float dhM22, float dhM32, float near, float far) {
        this.texture = texture;
        this.mainM22 = mainM22;
        this.mainM32 = mainM32;
        this.dhM22 = dhM22;
        this.dhM32 = dhM32;
        this.near = near;
        this.far = far;
    }

    static DhDepth of(WorldRenderingPipeline pipeline) {
        try {
            if (!(pipeline instanceof IrisRenderingPipeline iris) || !DHCompat.hasRenderingEnabled()) {
                return NONE;
            }
            DHCompat dh = iris.getDHCompat();
            if (dh == null) {
                return NONE;
            }
            int tex = dh.getDepthTex();
            Matrix4f main = CapturedRenderingState.INSTANCE.getGbufferProjection();
            Matrix4f lod = DHCompat.getProjection();
            if (tex <= 0 || main == null || lod == null) {
                return NONE;
            }
            float m22 = main.m22(), m32 = main.m32(), d22 = lod.m22(), d32 = lod.m32();
            float near = m32 / (m22 - 1.0f);   // OpenGL perspective: n = m32 / (m22 - 1), f = m32 / (m22 + 1)
            float far = d32 / (d22 + 1.0f);
            if (!(near > 0.0f) || !(far > near) || Float.isInfinite(far)) {
                return NONE;
            }
            return new DhDepth(tex, m22, m32, d22, d32, near, far);
        } catch (Throwable t) {
            return NONE;
        }
    }
}
