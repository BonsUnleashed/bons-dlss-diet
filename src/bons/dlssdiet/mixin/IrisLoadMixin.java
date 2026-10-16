package bons.dlssdiet.mixin;

import bons.dlssdiet.runtime.DlssRuntime;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Map;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.helpers.StringPair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The defines Oculus builds a shader pack with (Iris.loadExternalShaderpack -> new ShaderPack(root, configs, defines)):
 * one list feeds shaders.properties, every program's preprocessor and the id map. Runs on the render thread.
 */
@Mixin(value = Iris.class, remap = false)
public abstract class IrisLoadMixin {
    @ModifyArg(method = "loadExternalShaderpack", remap = false, index = 2, at = @At(value = "INVOKE", remap = false,
            target = "Lnet/irisshaders/iris/shaderpack/ShaderPack;<init>(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;)V"))
    private static ImmutableList<StringPair> bons$packDefines(Path root, Map<String, String> changedConfigs,
                                                             ImmutableList<StringPair> defines) {
        try {
            return DlssRuntime.packDefines(root, defines);
        } catch (Throwable t) {
            DlssRuntime.LOG.error("Bons DLSS Diet: define hook failed; the pack loads without DLSS", t);
            return defines;
        }
    }
}
