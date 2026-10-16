package bons.dlssdiet.mixin;

import bons.dlssdiet.runtime.Hooks;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applies the Oculus hooks only where the built-in DLSS runtime can run: Windows x64, Oculus present, and NOT the
 * Super Resolution mod (it serves the same shader-pack interface; with it installed this mod stays a pack patcher).
 * Successful applications are reported to the runtime, which uses DLSS only when all hooks are in place.
 */
public final class DietMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOG = LogManager.getLogger("Bons DLSS Diet");
    private boolean enabled;

    @Override
    public void onLoad(String mixinPackage) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean windows64 = os.startsWith("windows") && (arch.equals("amd64") || arch.equals("x86_64"));
        boolean oculus = present("oculus", "net/irisshaders/iris/Iris.class");
        boolean superResolution = present("super_resolution", "io/homo/superresolution/common/SuperResolution.class");
        boolean off = "off".equalsIgnoreCase(System.getProperty("bons.dlss.runtime", ""));
        enabled = windows64 && oculus && !superResolution && !off;
        Hooks.enabled(enabled);
        LOG.info("Bons DLSS Diet: built-in DLSS runtime hooks {} (windows x64 {}, oculus {}, super_resolution {}{})",
                enabled ? "enabled" : "disabled", windows64, oculus, superResolution, off ? ", -Dbons.dlss.runtime=off" : "");
    }

    /** Mod list first (no class loading); class-file lookup as the fallback. */
    private static boolean present(String modId, String classFile) {
        try {
            var list = net.minecraftforge.fml.loading.LoadingModList.get();
            if (list != null) {
                return list.getModFileById(modId) != null;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return DietMixinPlugin.class.getClassLoader().getResource(classFile) != null;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return enabled;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        Hooks.applied(mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1));
    }
}
