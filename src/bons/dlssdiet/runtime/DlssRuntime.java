package bons.dlssdiet.runtime;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.loading.FMLConfig;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector2f;

/**
 * Bons DLSS Diet's own DLSS runtime for packs carrying our patch (shader-pack interface v3 of the Super Resolution mod,
 * so the patch is unchanged). Three Oculus hooks call in here (bons.dlssdiet.mixin): the defines a pack is loaded with,
 * the jitter uniform, and the dispatch right before the composite6 program (inputs = that pass's read side). Everything
 * runs on the render thread. Off when Super Resolution is installed (it serves the same interface).
 */
public final class DlssRuntime {
    public static final Logger LOG = LogManager.getLogger("Bons DLSS Diet");
    public static final String TRIGGER_PASS = "composite6";
    /** Euphoria Patcher's "update available" notice: a version part >= 10 becomes the undefined glyph _10 (final.fsh). */
    private static final Set<String> EP_NOTICE = Set.of("NEW_EUPHORIA_PATCHES_UPDATE", "NEXT_EUPHORIA_PATCHES_VERSION");

    private enum State { UNTESTED, READY, UNAVAILABLE, FAILED }

    private static State state = State.UNTESTED;
    private static String reason = "";
    private static DietConfig config;
    private static DietConfig.Mode activeMode = DietConfig.Mode.QUALITY;
    private static boolean packActive;
    private static boolean uniformsSeen;
    private static boolean dhLogged;
    private static int framesSinceDispatch;
    private static String activePack = "";
    private static final Map<FrameUpdateNotifier, Boolean> NOTIFIERS = Collections.synchronizedMap(new WeakHashMap<>());

    // per frame
    private static long frameIndex;
    private static int phases = 18;
    private static float jitterX, jitterY;
    private static boolean resetNext = true;
    private static long lastDispatchNanos;
    private static Vec3 lastCamera;
    private static Object lastDimension;
    private static long statsSince;
    private static int dispatches;

    private DlssRuntime() {
    }

    // ------------------------------------------------------------------------------------------------ mixin plumbing

    public static boolean isTriggerPass(String programName) {
        return TRIGGER_PASS.equals(programName);
    }

    // ------------------------------------------------------------------------------------------------ pack load

    /**
     * The defines Oculus loads a pack with (shaders.properties, every program, the id map). For a pack carrying our
     * patch: Euphoria Patcher's update notice is dropped, and when DLSS works here the interface macros are added.
     */
    public static ImmutableList<StringPair> packDefines(Path shadersRoot, ImmutableList<StringPair> defines) {
        boolean wasActive = packActive;
        packActive = false;
        String name = packName(shadersRoot);
        if (!isOurPatch(shadersRoot)) {
            if (wasActive && state == State.READY) {
                NativeDlss.nReleaseFrameResources();
            }
            return defines;
        }
        config = DietConfig.load(FMLPaths.CONFIGDIR.get().resolve("bons_dlss_diet.properties"));
        List<StringPair> out = new ArrayList<>(defines.size() + 12);
        int dropped = 0;
        for (StringPair p : defines) {
            if (EP_NOTICE.contains(p.key())) {
                dropped++;
            } else {
                out.add(p);
            }
        }
        if (dropped > 0) {
            LOG.info("Bons DLSS Diet: left out Euphoria Patcher's update notice for \"{}\" (its version glyphs break the pack's final pass)", name);
        }
        String why = null;
        if (!config.runtimeEnabled || config.mode == DietConfig.Mode.OFF) {
            why = "switched off in config/bons_dlss_diet.properties";
        } else if (!Hooks.enabled()) {
            why = "the Oculus hooks are off (Super Resolution installed, not Windows x64, or -Dbons.dlss.runtime=off)";
        } else if (!ensureReady()) {
            why = reason;
        }
        if (why != null) {
            LOG.info("Bons DLSS Diet: \"{}\" runs without DLSS: {}", name, why);
            return ImmutableList.copyOf(out);
        }
        if (config.mode != activeMode || state == State.READY) {
            NativeDlss.nSetMode(config.mode.ngxValue, config.preset);
        }
        // no GPU timestamp queries unless the timing log wants them (each GL timestamp waits for the GPU to idle)
        NativeDlss.nSetOptions(config.timingLogSeconds > 0, config.leanInterop);
        activeMode = config.mode;
        float scale = activeMode.scale;
        out.add(new StringPair("SR_INSTALLED", "1"));
        out.add(new StringPair("SR_ENABLE", "1"));
        out.add(new StringPair("SR_DISABLE", "0"));
        out.add(new StringPair("SR_SHOULD_APPLY_SCALE", "1"));
        out.add(new StringPair("SR_SHOULD_APPLY_JITTER", "1"));
        out.add(new StringPair("SR_RENDER_SCALE_FACTOR", Float.toString(scale)));
        out.add(new StringPair("SR_RENDER_SCALE_FACTOR_HALF", Float.toString(scale * 0.5f)));
        out.add(new StringPair("SR_UPSCALE_RATIO", Float.toString(1.0f / scale)));
        out.add(new StringPair("SR_UPSCALE_RATIO_HALF", Float.toString(0.5f / scale)));
        out.add(new StringPair("SR_CONFIG_SCHEMA_VERSION", "3"));
        out.add(new StringPair("BONS_DLSS_DIET_RUNTIME", "1"));
        phases = Math.max(8, (int) Math.ceil(8.0 / (scale * scale)));
        packActive = true;
        activePack = name;
        resetNext = true;
        lastCamera = null;
        uniformsSeen = false;
        dhLogged = false;
        framesSinceDispatch = 0;
        LOG.info("Bons DLSS Diet: DLSS on for \"{}\" on {} ({}, render scale {}, preset {}, {} jitter phases)", name,
                NativeDlss.nDevice(), activeMode.name().toLowerCase(), scale, config.presetName, phases);
        return ImmutableList.copyOf(out);
    }

    private static String packName(Path shadersRoot) {
        Path parent = shadersRoot.getParent();
        Path p = parent != null && parent.getFileName() != null ? parent.getFileName() : shadersRoot.getFileName();
        return p == null ? shadersRoot.toString() : p.toString();
    }

    /** Our shader patch (generated "+ DLSS" packs and hand-patched copies alike) is recognised by its own helper file. */
    static boolean isOurPatch(Path shadersRoot) {
        try {
            Path common = shadersRoot.resolve("lib/sr/srCommon.glsl");
            if (!Files.isRegularFile(common)) {
                return false;
            }
            String text = Files.readString(common, StandardCharsets.UTF_8);
            return text.contains("#define SR_PATCH_VERSION") && text.contains("srDepthUV(uv)");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean ensureReady() {
        if (state == State.READY) {
            return true;
        }
        if (state == State.FAILED || state == State.UNAVAILABLE) {
            return false;
        }
        Path base = FMLPaths.GAMEDIR.get().resolve("bons_dlss_diet");
        String loadError = NativeDlss.load(base.resolve("natives"));
        if (loadError != null) {
            return unavailable(loadError);
        }
        Path dlssDir = DlssDll.find(config, base);
        if (dlssDir == null) {
            return unavailable("NVIDIA's nvngx_dlss.dll was not found; put it in config/bons_dlss_diet/");
        }
        try {
            Files.createDirectories(base.resolve("ngx"));
        } catch (Exception e) {
            return unavailable("cannot create " + base.resolve("ngx") + ": " + e);
        }
        int r = NativeDlss.nInit(base.resolve("ngx").toAbsolutePath().toString(), dlssDir.toAbsolutePath().toString(),
                config.mode.ngxValue, config.preset);
        drainNativeLog();
        if (r != 0) {
            return unavailable(NativeDlss.nLastError());
        }
        state = State.READY;
        activeMode = config.mode;
        LOG.info("Bons DLSS Diet: DLSS runtime ready on {} (nvngx_dlss.dll from {})", NativeDlss.nDevice(), dlssDir);
        return true;
    }

    private static boolean unavailable(String why) {
        state = State.UNAVAILABLE;
        reason = why;
        LOG.warn("Bons DLSS Diet: DLSS unavailable: {}", why);
        return false;
    }

    // ------------------------------------------------------------------------------------------------ uniforms + frames

    public static void registerUniforms(UniformHolder uniforms, FrameUpdateNotifier notifier) {
        if (!packActive) {
            return;
        }
        try {
            uniforms.uniform2f(UniformUpdateFrequency.PER_FRAME, "SRJitterOffset", () -> new Vector2f(jitterX, -jitterY));
            uniformsSeen = true;
            if (notifier != null && NOTIFIERS.putIfAbsent(notifier, Boolean.TRUE) == null) {
                notifier.addListener(DlssRuntime::onNewFrame);
            }
        } catch (Throwable t) {
            LOG.error("Bons DLSS Diet: could not register the jitter uniform; DLSS stays off", t);
        }
    }

    /** Oculus' frame start (before its uniforms update): next Halton(2,3) jitter, in render pixels. */
    private static void onNewFrame() {
        if (!packActive) {
            return;
        }
        // watchdog: the pack renders into a corner and waits for DLSS; if the dispatch hook never fires (an Oculus
        // version our hooks do not fit), give up on DLSS rather than leave the picture broken
        if (++framesSinceDispatch > 300 && state == State.READY) {
            framesSinceDispatch = Integer.MIN_VALUE / 2;
            fail("the DLSS dispatch hook did not run for 300 frames (Oculus hooks " + Hooks.describe() + ")");
            return;
        }
        frameIndex++;
        int i = (int) (frameIndex % phases) + 1;
        jitterX = halton(i, 2) - 0.5f;
        jitterY = halton(i, 3) - 0.5f;
    }

    static float halton(int index, int base) {
        float f = 1.0f, r = 0.0f;
        while (index > 0) {
            f /= base;
            r += f * (index % base);
            index /= base;
        }
        return r;
    }

    /** A new composite renderer built the trigger program: new pipeline (pack reload, dimension change). */
    public static void onNewTrigger() {
        resetNext = true;
        lastCamera = null;
    }

    // ------------------------------------------------------------------------------------------------ dispatch

    /**
     * Right before the composite6 program is used: DLSS from the pass's read side into colortex11. Never throws into
     * Oculus' renderer: any error turns DLSS off and reloads the pack without it.
     */
    public static void dispatch(RenderTargets targets, ImmutableSet<Integer> readsFromAlt, WorldRenderingPipeline pipeline) {
        framesSinceDispatch = 0;
        if (!packActive || state != State.READY || targets == null || readsFromAlt == null) {
            return;
        }
        try {
            dispatchFrame(targets, readsFromAlt, pipeline);
        } catch (Throwable t) {
            LOG.error("Bons DLSS Diet: DLSS dispatch failed", t);
            fail("dispatch error: " + t);
        }
    }

    private static void dispatchFrame(RenderTargets targets, ImmutableSet<Integer> readsFromAlt, WorldRenderingPipeline pipeline) {
        if (!uniformsSeen) {
            // the pack was loaded with the interface macros but the jitter uniform hook never ran: no jitter, so DLSS
            // would only blur; fall back to the pack's own path
            fail("the jitter uniform hook did not run (Oculus hooks " + Hooks.describe() + ")");
            return;
        }
        RenderTarget color = targets.get(3), motion = targets.get(12), output = targets.get(11);
        if (color == null || motion == null || output == null) {
            fail("the pack's colortex3/11/12 are missing");
            return;
        }
        int rw = color.getWidth(), rh = color.getHeight(), ow = output.getWidth(), oh = output.getHeight();
        if (rw < 32 || rh < 32 || ow < 32 || oh < 32) {
            return; // minimised or tiny window: nothing worth upscaling, and no reason to give up on DLSS
        }
        if (motion.getWidth() != rw || motion.getHeight() != rh || ow < rw || oh < rh) {
            fail("unexpected buffer sizes (colour " + rw + "x" + rh + ", motion " + motion.getWidth() + "x"
                    + motion.getHeight() + ", output " + ow + "x" + oh + ")");
            return;
        }
        long now = System.nanoTime();
        float ms = lastDispatchNanos == 0 ? 16.7f : Math.max(0.1f, Math.min(1000.0f, (now - lastDispatchNanos) / 1.0e6f));
        lastDispatchNanos = now;
        boolean reset = resetNext || cameraCut();
        resetNext = false;
        DhDepth dh = config != null && config.dhDepth ? DhDepth.of(pipeline) : DhDepth.NONE;
        if (config != null && config.dhDepth && !dhLogged) {
            dhLogged = true;
            LOG.info(dh.texture != 0
                    ? String.format("Bons DLSS Diet: DLSS gets one depth for the world and the Distant Horizons LODs (near %.3f, far %.0f)", dh.near, dh.far)
                    : "Bons DLSS Diet: dh_depth is on but Distant Horizons' depth is not available here; main depth only");
        }
        int r = NativeDlss.nDispatch(side(color, 3, readsFromAlt), targets.getDepthTexture(), side(motion, 12, readsFromAlt),
                side(output, 11, readsFromAlt), rw, rh, ow, oh, jitterX, jitterY, reset, ms,
                dh.texture, dh.mainM22, dh.mainM32, dh.dhM22, dh.dhM32, dh.near, dh.far);
        if (r != 0) {
            drainNativeLog();
            fail(NativeDlss.nLastError());
            return;
        }
        dispatches++;
        if ((dispatches & 255) == 0) {
            drainNativeLog();
        }
        if (dispatches == 120) {
            earlyWindowOff();   // DLSS really runs here: from the next start on, without Forge's early window
        }
        timingLog(now);
    }

    private static int side(RenderTarget t, int index, ImmutableSet<Integer> readsFromAlt) {
        return readsFromAlt.contains(index) ? t.getAltTexture() : t.getMainTexture();
    }

    /**
     * Teleports, respawns and dimension changes make DLSS' history useless: start it again. (Compiled against the SRG
     * client jar: getInstance m_91087_, level f_91073_, gameRenderer f_91063_, getMainCamera m_109153_, getPosition
     * m_90583_, dimension m_46472_, distanceToSqr m_82557_; tools/srg_lookup.py.)
     */
    private static boolean cameraCut() {
        Minecraft mc = Minecraft.m_91087_();
        if (mc.f_91073_ == null) {
            lastCamera = null;
            return true;
        }
        Vec3 pos = mc.f_91063_.m_109153_().m_90583_();
        Object dim = mc.f_91073_.m_46472_();
        boolean cut = lastCamera == null || dim != lastDimension || lastCamera.m_82557_(pos) > 32.0 * 32.0;
        lastCamera = pos;
        lastDimension = dim;
        return cut;
    }

    private static void timingLog(long now) {
        if (config == null || config.timingLogSeconds <= 0) {
            return;
        }
        if (statsSince == 0) {
            statsSince = now;
            NativeDlss.nTakeStats();
            return;
        }
        if (now - statsSince < config.timingLogSeconds * 1_000_000_000L) {
            return;
        }
        double[] s = NativeDlss.nTakeStats();
        double secs = (now - statsSince) / 1e9;
        statsSince = now;
        if (s != null && s.length >= 10 && s[0] > 0 && s[3] > 0 && s[5] > 0) {
            // GPU figures come from the sampled frames (one in 16 carries timestamp queries), the frame count and the
            // render-thread figures from every frame. "GL wait": the GL timeline from the end of our input pass to the
            // end of the wait for DLSS = DLSS + both OpenGL/Vulkan hand-offs (the part of the frame the pack cannot use)
            LOG.info(String.format("Bons DLSS Diet timing (%.0f s, %.0f frames): input %.3f ms, DLSS %.3f ms, output %.3f ms,"
                            + " GL wait %.3f ms; render thread %.3f ms per frame (Vulkan %.3f ms, slowest %.3f ms)",
                    secs, s[5], s[1] / s[0] / 1e6, s[4] / s[3] / 1e6, s[2] / s[0] / 1e6, s[9] / s[0] / 1e6, s[6] / s[5] / 1e6,
                    s[7] / s[5] / 1e6, s[8] / 1e6));
        }
    }

    /**
     * Our own native lines go to the game log; NGX's (hundreds at start-up, mostly the probing of optional features)
     * only when they look like a real problem. NGX keeps its full log in bons_dlss_diet/ngx/ either way.
     */
    private static void drainNativeLog() {
        String line;
        int n = 0;
        while (n++ < 1024 && (line = NativeDlss.nPollLog()) != null) {
            if (line.startsWith("ERROR")) {
                LOG.error("Bons DLSS Diet native: {}", line);
            } else if (!line.startsWith("NGX[")) {
                LOG.info("Bons DLSS Diet native: {}", line);
            } else if (NGX_PROBLEM.matcher(line).find() && !NGX_EXPECTED.matcher(line).find()) {
                LOG.warn("Bons DLSS Diet native: {}", line);
            }
        }
    }

    private static final java.util.regex.Pattern NGX_PROBLEM = java.util.regex.Pattern.compile("(?i)error|fail|invalid|unsupported");
    // NGX probes every feature folder and its model cache before taking the DLL we point it to: expected, not a problem
    private static final java.util.regex.Pattern NGX_EXPECTED = java.util.regex.Pattern.compile(
            "NGXLoadFromPath failed|failed to load .* from cache|IsFeatureDenied|NvAPI_DRS_FindApplicationByName");

    /**
     * Forge's early loading window (fml.toml earlyWindowControl=true, Forge's default) opens the OpenGL context before
     * Embeddium can switch NVIDIA's threaded optimizations off, which costs about 15 % FPS in heavy scenes (RTX 3080 at
     * 3840x2066: forest 36 FPS with it, 42 without, no DLSS in either). Once DLSS has run on this machine, switch it off
     * through Forge's own config API (saved to fml.toml, read at the next start); config forge_early_window=keep leaves
     * it. GlVersionProbe then raises Minecraft's own window to OpenGL 4.6, which the runtime needs.
     */
    private static void earlyWindowOff() {
        if (config == null || !config.earlyWindowOff) {
            return;
        }
        try {
            if (!FMLConfig.getBoolConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL)) {
                return;
            }
            FMLConfig.updateConfig(FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL, false);
            LOG.info("Bons DLSS Diet: switched Forge's early loading window off in config/fml.toml (earlyWindowControl=false):"
                    + " about 15 % more FPS from the next start; forge_early_window=keep in config/bons_dlss_diet.properties"
                    + " leaves it on");
            Minecraft mc = Minecraft.m_91087_();
            mc.execute(() -> {
                if (mc.f_91065_ != null && mc.f_91074_ != null) {
                    mc.f_91065_.m_93076_().m_93785_(Component.m_237113_("Bons DLSS Diet: DLSS is on. Restart the game once"
                            + " for about 15 % more FPS (Forge's early loading window is now switched off)."));
                }
            });
        } catch (Throwable t) {
            LOG.warn("Bons DLSS Diet: could not switch Forge's early loading window off ({})", t.toString());
        }
    }

    /** A failure mid-game: DLSS goes off and the pack reloads without the interface macros (its normal TAA path). */
    private static void fail(String why) {
        if (state == State.FAILED) {
            return;
        }
        state = State.FAILED;
        reason = why;
        LOG.error("Bons DLSS Diet: DLSS turned off: {}", why);
        // SRG: getInstance m_91087_, gui f_91065_, player f_91074_, getChat m_93076_, addMessage m_93785_, literal m_237113_
        Minecraft mc = Minecraft.m_91087_();
        mc.execute(() -> {
            try {
                Iris.reload();
            } catch (Exception e) {
                LOG.error("Bons DLSS Diet: shader reload after the failure did not work", e);
            }
            if (mc.f_91065_ != null && mc.f_91074_ != null) {
                mc.f_91065_.m_93076_().m_93785_(Component.m_237113_("Bons DLSS Diet: DLSS turned off (" + why
                        + "). Shaders reloaded without it; details in the log."));
            }
        });
    }
}
