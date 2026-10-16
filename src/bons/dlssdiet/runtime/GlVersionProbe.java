package bons.dlssdiet.runtime;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;

/**
 * The OpenGL version Minecraft's own window asks for. Vanilla asks for 3.2 core and NVIDIA's driver returns exactly
 * 3.2; the DLSS runtime needs 4.5 (memory objects and semaphores shared with Vulkan, DSA). Forge's early window asks for
 * 4.6, but runs about 15 % slower in heavy scenes (run 20261016-030314 on an RTX 3080 at 3840x2066: forest 36 FPS with
 * earlyWindowControl=true against 42 with false, no DLSS in either), so the runtime is meant to run with
 * earlyWindowControl=false and Minecraft's window raised to 4.6 or 4.5 (DlssRuntime switches the early window off once
 * DLSS has run; config forge_early_window=keep leaves it). The version is
 * probed with a hidden window first (4.6, then 4.5); with neither, Minecraft's 3.2 stays and the runtime stays off.
 * Free of Minecraft/Oculus classes: it runs inside Minecraft's Window constructor.
 */
public final class GlVersionProbe {
    private static final Logger LOG = LogManager.getLogger("Bons DLSS Diet");
    private static final int[][] VERSIONS = {{4, 6}, {4, 5}};
    private static volatile int major = -1, minor = -1;   // -1: leave Minecraft's request alone
    private static boolean probed;

    private GlVersionProbe() {
    }

    /** Right before Minecraft's Window constructor resets the GLFW window hints (main thread, GLFW initialised). */
    public static void probe() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            if (earlyWindow()) {
                LOG.info("Bons DLSS Diet: Forge's early window creates the OpenGL context (earlyWindowControl=true); for the"
                        + " best frame rate set earlyWindowControl=false in config/fml.toml");
                return;
            }
            // Minecraft's boot error callback throws on any GLFW error: a version the driver lacks must not crash
            GLFWErrorCallback previous = GLFW.glfwSetErrorCallback(null);
            try {
                for (int[] v : VERSIONS) {
                    GLFW.glfwDefaultWindowHints();
                    GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
                    GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
                    GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_NATIVE_CONTEXT_API);
                    GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, v[0]);
                    GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, v[1]);
                    GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
                    GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
                    long w = GLFW.glfwCreateWindow(16, 16, "Bons DLSS Diet OpenGL probe", 0L, 0L);
                    if (w != 0L) {
                        GLFW.glfwDestroyWindow(w);
                        minor = v[1];
                        major = v[0];
                        break;
                    }
                }
            } finally {
                GLFW.glfwSetErrorCallback(previous);
                GLFW.glfwDefaultWindowHints();   // Minecraft sets all of its hints right after this
            }
            if (major > 0) {
                LOG.info("Bons DLSS Diet: Minecraft's window asks for OpenGL {}.{} core instead of 3.2 (the DLSS runtime needs 4.5)",
                        major, minor);
            } else {
                LOG.warn("Bons DLSS Diet: no OpenGL 4.5 context on this system; Minecraft's 3.2 stays and DLSS will be off");
            }
        } catch (Throwable t) {
            major = minor = -1;
            LOG.warn("Bons DLSS Diet: OpenGL version probe failed ({}); Minecraft's request stays", t.toString());
        }
    }

    /** Minecraft's glfwWindowHint(hint, value) calls in the Window constructor. */
    public static int hint(int hint, int value) {
        if (major < 0) {
            return value;
        }
        if (hint == GLFW.GLFW_CONTEXT_VERSION_MAJOR) {
            return major;
        }
        if (hint == GLFW.GLFW_CONTEXT_VERSION_MINOR) {
            return minor;
        }
        return value;
    }

    private static boolean earlyWindow() {
        try {
            return net.minecraftforge.fml.loading.FMLConfig.getBoolConfigValue(
                    net.minecraftforge.fml.loading.FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL);
        } catch (Throwable t) {
            return false;
        }
    }
}
