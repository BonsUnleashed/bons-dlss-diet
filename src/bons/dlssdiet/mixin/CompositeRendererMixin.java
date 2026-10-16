package bons.dlssdiet.mixin;

import bons.dlssdiet.runtime.DlssRuntime;
import com.google.common.collect.ImmutableSet;
import java.util.function.Supplier;
import net.irisshaders.iris.gl.program.Program;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DLSS runs right before the composite6 program: createProgram() is handed the pass's source (its name) and its
 * read-side set ("flipped" = stageReadsFromAlt), so the program it returns identifies the pass in renderAll() without
 * reflection or replacing Oculus' renderer. Oculus has already bound composite6's framebuffer and viewport at that
 * point; the runtime's compute passes touch neither and restore every binding they change.
 */
@Mixin(value = CompositeRenderer.class, remap = false)
public abstract class CompositeRendererMixin {
    @Shadow(remap = false)
    @Final
    private RenderTargets renderTargets;

    @Shadow(remap = false)
    @Final
    private WorldRenderingPipeline pipeline;

    @Unique
    private Program bons$trigger;

    @Unique
    private ImmutableSet<Integer> bons$triggerReadsFromAlt;

    @Inject(method = "createProgram", at = @At("RETURN"), remap = false)
    private void bons$watchProgram(ProgramSource source, ImmutableSet<Integer> flipped,
                                   ImmutableSet<Integer> flippedAtLeastOnceSnapshot,
                                   Supplier<ShadowRenderTargets> shadowTargetsSupplier, CallbackInfoReturnable<Program> cir) {
        if (source != null && DlssRuntime.isTriggerPass(source.getName())) {
            bons$trigger = cir.getReturnValue();
            bons$triggerReadsFromAlt = flipped;
            DlssRuntime.onNewTrigger();
        }
    }

    @Redirect(method = "renderAll", remap = false,
            at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/gl/program/Program;use()V", remap = false))
    private void bons$beforeProgram(Program program) {
        if (program != null && program == bons$trigger) {
            DlssRuntime.dispatch(renderTargets, bons$triggerReadsFromAlt, pipeline);
        }
        program.use();
    }
}
