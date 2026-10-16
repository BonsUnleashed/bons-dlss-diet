package bons.dlssdiet.runtime;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * config/bons_dlss_diet.properties (written with defaults when missing, plain UTF-8, no BOM). Read when a shader pack
 * loads, so a change applies at the next shader reload (shader pack screen, or the reload key).
 */
public final class DietConfig {
    /** DLSS quality modes: render scale, NGX PerfQuality value. */
    public enum Mode {
        QUALITY(1.0f / 1.5f, 2), BALANCED(0.58f, 1), PERFORMANCE(0.5f, 0), ULTRA_PERFORMANCE(1.0f / 3.0f, 3),
        DLAA(1.0f, 5), OFF(1.0f, -1);

        public final float scale;
        public final int ngxValue;

        Mode(float scale, int ngxValue) {
            this.scale = scale;
            this.ngxValue = ngxValue;
        }
    }

    static final String DEFAULTS = "# Bons DLSS Diet\n"
            + "# mode: quality (render at 67 %), balanced (58 %), performance (50 %), ultra_performance (33 %),\n"
            + "#       dlaa (full resolution, DLSS anti-aliasing only) or off\n"
            + "mode=quality\n"
            + "# DLSS preset: K (default: best image), J, L, M, or default (NVIDIA's choice per mode)\n"
            + "preset=K\n"
            + "# runtime: auto (DLSS on supported NVIDIA cards) or off\n"
            + "runtime=auto\n"
            + "# folder holding NVIDIA's nvngx_dlss.dll; empty = config/bons_dlss_diet, then the copy inside the mod\n"
            + "dlss_dll_dir=\n"
            + "# log the GPU time of the DLSS passes every N seconds (0 = off)\n"
            + "timing_log_seconds=0\n"
            + "# give DLSS the depth of Distant Horizons' LODs too (true/false; experimental)\n"
            + "dh_depth=false\n"
            + "# OpenGL <-> Vulkan hand-off: strict (ownership transfers every frame) or lean (images stay in one layout)\n"
            + "interop=strict\n"
            + "# Forge's early loading window costs about 15 % FPS with Embeddium on NVIDIA cards (it opens the OpenGL\n"
            + "# context before Embeddium's driver workaround can apply). off: once DLSS runs, this mod switches it off in\n"
            + "# config/fml.toml (earlyWindowControl), from the next start on. keep: leave it as it is\n"
            + "forge_early_window=off\n";

    public final Mode mode;
    public final int preset;
    public final String presetName;
    public final boolean runtimeEnabled;
    public final String dlssDllDir;
    public final int timingLogSeconds;
    public final boolean dhDepth;
    public final boolean leanInterop;
    public final boolean earlyWindowOff;

    private DietConfig(Properties p) {
        dhDepth = "true".equalsIgnoreCase(p.getProperty("dh_depth", "false").trim());
        leanInterop = "lean".equalsIgnoreCase(p.getProperty("interop", "strict").trim());
        earlyWindowOff = !"keep".equalsIgnoreCase(p.getProperty("forge_early_window", "off").trim());
        Mode m;
        try {
            m = Mode.valueOf(p.getProperty("mode", "quality").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            m = Mode.QUALITY;
        }
        mode = m;
        String pr = p.getProperty("preset", "K").trim().toUpperCase(Locale.ROOT);
        presetName = pr.isEmpty() ? "DEFAULT" : pr;
        preset = switch (presetName) {
            case "J" -> 10;
            case "K" -> 11;
            case "L" -> 12;
            case "M" -> 13;
            case "F" -> 6;
            default -> 0;
        };
        runtimeEnabled = !"off".equalsIgnoreCase(p.getProperty("runtime", "auto").trim());
        dlssDllDir = p.getProperty("dlss_dll_dir", "").trim();
        int t;
        try {
            t = Integer.parseInt(p.getProperty("timing_log_seconds", "0").trim());
        } catch (NumberFormatException e) {
            t = 0;
        }
        timingLogSeconds = Math.max(0, t);
    }

    public static DietConfig load(Path file) {
        Properties p = new Properties();
        try {
            if (!Files.isRegularFile(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, DEFAULTS, StandardCharsets.UTF_8);
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '﻿') {
                text = text.substring(1);
            }
            p.load(new StringReader(text));
        } catch (IOException e) {
            DlssRuntime.LOG.warn("Bons DLSS Diet: could not read {} ({}), using defaults", file, e.toString());
        }
        return new DietConfig(p);
    }
}
