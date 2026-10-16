package bons.dlssdiet.mixin;

import bons.dlssdiet.runtime.GlVersionProbe;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Minecraft's window (earlyWindowControl=false) asks for OpenGL 3.2; raise the request to what GlVersionProbe finds
 * (4.6 or 4.5) so the DLSS runtime can run without Forge's early window, which costs ~15 % FPS in heavy scenes (it
 * creates the OpenGL context before Embeddium's NVIDIA workaround can switch the driver's threaded optimizations off).
 * Forge's patched constructor: glfwDefaultWindowHints, the hints (139265 client API first, 139266 = major 3,
 * 139267 = minor 2), then ImmediateWindowHandler.setupMinecraftWindow. Only a @ModifyArg: Forge's Mixin rejects an
 * @Inject at an INVOKE inside a constructor (rc4, run 20261016-042826). The probe runs at the first hint, when the
 * hints are still at their defaults, and leaves them at their defaults again. Optional (require = 0).
 */
@Mixin(value = Window.class, remap = false)
public abstract class WindowMixin {
    @ModifyArg(method = "<init>", remap = false, require = 0, index = 1, at = @At(value = "INVOKE", remap = false,
            target = "Lorg/lwjgl/glfw/GLFW;glfwWindowHint(II)V"))
    private int bons$glVersion(int hint, int value) {
        GlVersionProbe.probe();   // once
        return GlVersionProbe.hint(hint, value);
    }
}
