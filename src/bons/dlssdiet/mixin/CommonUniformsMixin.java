package bons.dlssdiet.mixin;

import bons.dlssdiet.runtime.DlssRuntime;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.shaderpack.IdMap;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SRJitterOffset for every program: Oculus builds the pipeline's shared custom-uniform holder through
 * addNonDynamicUniforms (IrisRenderingPipeline), and FrameUpdateNotifier.onNewFrame runs before that holder updates,
 * so the jitter advanced there is the one every program of the frame sees.
 */
@Mixin(value = CommonUniforms.class, remap = false)
public abstract class CommonUniformsMixin {
    @Inject(method = "addNonDynamicUniforms", at = @At("RETURN"), remap = false)
    private static void bons$jitter(UniformHolder uniforms, IdMap idMap, PackDirectives directives,
                                    FrameUpdateNotifier updateNotifier, CallbackInfo ci) {
        DlssRuntime.registerUniforms(uniforms, updateNotifier);
    }
}
