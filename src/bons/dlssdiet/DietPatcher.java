package bons.dlssdiet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Bons DLSS Diet: makes a copy of Complementary Unbound r5.7.1 + Euphoria Patches 1.8.6 that works with the Super
 * Resolution mod's shaderpack interface v3 (DLSS; FSR 2/3 and XeSS go through the same interface).
 * <p>
 * A Java port of the patch script written for the author's own pack (patch version 3), kept byte-identical to it: the
 * reference pack is read into memory (a folder or a zip), the shader sources are edited with the same anchors, the
 * result is checked, and only then written as a new folder next to the original. Nothing of the original pack ships in
 * this jar; the patched copy keeps the pack's own licence files. Free of Minecraft classes: {@link #main} runs it
 * offline for the port verification.
 * <p>
 * What the patch does (inert unless Super Resolution is installed and upscaling is on, SR_SHOULD_APPLY_SCALE == 1):
 * everything up to composite5 renders at the render size (buffers via size.buffer, vertices jittered and squeezed into
 * the lower-left corner, viewWidth/viewHeight/viewSize/texelSize remapped), depth reads scaled (depth textures stay
 * screen size in Iris), composite5 writes camera motion vectors, Super Resolution runs before composite6 (colour
 * colortex3, depth, motion colortex12, output colortex11), TAA/FXAA are skipped, block-atlas samples get the DLSS mip
 * bias and full-resolution texture derivatives, depth of field focuses on the scene centre, and (v3) the
 * screen-space reflection ray march reads the depth texture through the right coordinates.
 */
public final class DietPatcher {
    public static final String MARK = "Bons DLSS Diet patch";
    public static final int PATCH_VERSION = 3;
    public static final String PATCHER_VERSION = "1.0.0";
    public static final String DEFAULT_SHARPEN = "1.0";
    public static final String DEFAULT_MIP_EXTRA = "0.0";
    public static final String MARKER_FILE = "bons_dlss_diet.json";
    public static final String SUFFIX = " + DLSS";

    private static final String DEPTH_SAMPLERS = "(?:depthtex[0-2]|dhDepthTex[01]?|depthTextureToSample|depthtex)";
    private static final Pattern CALL_RX = Pattern.compile(
            "\\b(texture2DLod|texture2D|textureLod|texture|texelFetch)\\s*\\(\\s*(" + DEPTH_SAMPLERS + ")\\s*,");
    private static final int[] SCALED_BUFFERS = {0, 3, 4, 5, 6, 8, 9, 10, 12};
    private static final String[] REMAPPED_NAMES = {"viewWidth", "viewHeight", "viewSize", "texelSize"};
    private static final Pattern LOCAL_DECL_RX = Pattern.compile(
            "\\b(?:float|int|vec[234]|ivec[234])\\s+(" + String.join("|", REMAPPED_NAMES) + ")\\b");
    private static final String[] HOOK_PROGRAM_PREFIXES = {"gbuffers_", "dh_terrain", "dh_water"};
    private static final String[] WORLDS = {"world0", "world-1", "world1", "world_space", "world_planet"};
    private static final Pattern MIP_RX = Pattern.compile("\\btexture2D\\s*\\(\\s*(tex|normals|specular)\\s*,");
    private static final String MIP_SKIP_FILE = "program/final.glsl";
    private static final Pattern COLORWHEEL_RX = Pattern.compile(
            "#ifdef TAA(\\s*\\n\\s*)(#include \"/lib/antialiasing/jitter\\.glsl\"|"
                    + "gl_Position\\.xy = TAAJitter\\(gl_Position\\.xy, gl_Position\\.w\\);)");
    private static final Pattern PROGRAM_INCLUDE_RX = Pattern.compile("#include\\s+\"/program/[^\"]+\"");
    private static final Pattern DIRECTIVE_RX = Pattern.compile(
            "^#\\s*(if|ifdef|ifndef|else|elif|endif|define|undef|include|error|warning|line|pragma)\\b");
    private static final Pattern INCLUDE_LINE_RX = Pattern.compile("^\\s*#include\\b");
    private static final Pattern INCLUDE_OK_RX = Pattern.compile("\\s*#include\\s+\"[^\"]+\"\\s*");

    /** The pack's files, keyed by forward-slash path relative to the pack root. */
    private final LinkedHashMap<String, byte[]> files;
    private final Map<String, String> texts = new TreeMap<>();
    private final TreeSet<String> dirty = new TreeSet<>();
    private final String sharpen;
    private final String mipExtra;
    private final List<String> log = new ArrayList<>();

    public static final class PatchException extends Exception {
        private static final long serialVersionUID = 1L;

        public PatchException(String message) {
            super(message);
        }
    }

    private DietPatcher(LinkedHashMap<String, byte[]> files, String sharpen, String mipExtra) {
        this.files = files;
        this.sharpen = sharpen;
        this.mipExtra = mipExtra;
    }

    // ------------------------------------------------------------------------------------------------ public API

    /** Reads a pack folder or zip into memory. */
    public static LinkedHashMap<String, byte[]> readPack(Path source) throws IOException {
        LinkedHashMap<String, byte[]> out = new LinkedHashMap<>();
        if (Files.isDirectory(source)) {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String rel = source.relativize(file).toString().replace('\\', '/');
                    out.put(rel, Files.readAllBytes(file));
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(source))) {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    if (!e.isDirectory()) {
                        out.put(e.getName(), zip.readAllBytes());
                    }
                }
            }
        }
        return out;
    }

    /** sha256 over the sorted file list with each file's sha256: changes when any file of the pack changes. */
    public static String fingerprint(Map<String, byte[]> pack) {
        try {
            MessageDigest all = MessageDigest.getInstance("SHA-256");
            for (String k : new TreeSet<>(pack.keySet())) {
                MessageDigest one = MessageDigest.getInstance("SHA-256");
                all.update((k + "\0" + hex(one.digest(pack.get(k))) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return hex(all.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Is this (unpatched) pack one the patch was made for? Complementary Unbound r5.7.1 + Euphoria Patches 1.8.6. */
    public static String unsupportedReason(String name, Map<String, byte[]> pack) {
        if (pack.containsKey("shaders/lib/sr/srCommon.glsl")) {
            return "already carries the patch";
        }
        if (!pack.containsKey("shaders/shaders.properties") || !pack.containsKey("shaders/lib/common.glsl")) {
            return "not a Complementary-style shader pack (no shaders/shaders.properties)";
        }
        if (!name.startsWith("ComplementaryUnbound_r5.7.1 + EuphoriaPatches_1.8.6")) {
            return "not Complementary Unbound r5.7.1 with Euphoria Patches 1.8.6 (the pair this version supports)";
        }
        return null;
    }

    /** Patches a copy of the pack in memory and returns it (checked); throws when an anchor is missing. */
    public static Result patch(Map<String, byte[]> pack, String sharpen, String mipExtra) throws PatchException {
        DietPatcher p = new DietPatcher(new LinkedHashMap<>(pack), sharpen, mipExtra);
        p.apply();
        p.flush();
        Check c = p.check();
        if (!c.problems.isEmpty()) {
            throw new PatchException("the patched pack does not pass the check: " + String.join("; ", c.problems));
        }
        return new Result(p.files, p.log, c.hooks, c.biased, p.dirty.size());
    }

    public static final class Result {
        public final LinkedHashMap<String, byte[]> files;
        public final List<String> log;
        public final int hooks, biased, written;

        Result(LinkedHashMap<String, byte[]> files, List<String> log, int hooks, int biased, int written) {
            this.files = files;
            this.log = log;
            this.hooks = hooks;
            this.biased = biased;
            this.written = written;
        }
    }

    /** Writes a patched pack into a folder (must not exist). */
    public static void writePack(Map<String, byte[]> pack, Path target) throws IOException {
        for (Map.Entry<String, byte[]> e : pack.entrySet()) {
            Path f = target.resolve(e.getKey().replace('/', java.io.File.separatorChar));
            Files.createDirectories(f.getParent());
            Files.write(f, e.getValue());
        }
    }

    /** Offline use: java -cp ... bons.dlssdiet.DietPatcher &lt;source pack&gt; &lt;target folder&gt; [sharpen] [mipExtra] */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: DietPatcher <source pack dir or zip> <target folder> [sharpen] [mipExtra]");
            System.exit(2);
        }
        Path src = Path.of(args[0]), dst = Path.of(args[1]);
        String sharpen = args.length > 2 ? args[2] : DEFAULT_SHARPEN, mip = args.length > 3 ? args[3] : DEFAULT_MIP_EXTRA;
        LinkedHashMap<String, byte[]> pack = readPack(src);
        String why = unsupportedReason(src.getFileName().toString().replaceAll("\\.zip$", ""), pack);
        if (why != null && !"--force".equals(System.getProperty("bons.force", ""))) {
            System.err.println("unsupported: " + why);
            System.exit(1);
        }
        Result r = patch(pack, sharpen, mip);
        if (Files.exists(dst)) {
            throw new IOException("target exists: " + dst);
        }
        writePack(r.files, dst);
        r.log.forEach(System.out::println);
        System.out.println("check: 0 problem(s), " + r.hooks + " vertex hooks, " + r.biased + " biased atlas samples; "
                + r.written + " shader files written, " + r.files.size() + " files in all");
    }

    // ------------------------------------------------------------------------------------------- file helpers

    private boolean exists(String rel) {
        return files.containsKey("shaders/" + rel);
    }

    private String read(String rel) throws PatchException {
        String key = "shaders/" + rel;
        String t = texts.get(key);
        if (t == null) {
            byte[] b = files.get(key);
            if (b == null) {
                throw new PatchException("missing file shaders/" + rel);
            }
            t = normalize(new String(b, StandardCharsets.UTF_8));
            texts.put(key, t);
        }
        return t;
    }

    /** Python's universal newlines: text is read with \r\n and \r turned into \n (and written back with \n). */
    static String normalize(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    private void write(String rel, String text, String note) throws PatchException {
        String key = "shaders/" + rel;
        String old = files.containsKey(key) ? read(rel) : null;
        if (text.equals(old)) {
            return;
        }
        texts.put(key, text);
        dirty.add(key);
        log.add("wrote " + rel + ": " + note);
    }

    private void flush() {
        for (String key : dirty) {
            files.put(key, texts.get(key).getBytes(StandardCharsets.UTF_8));
        }
    }

    private void replaceOnce(String rel, String old, String neu, String note, String already) throws PatchException {
        String text = read(rel);
        if (text.contains(already != null ? already : neu)) {
            return;
        }
        int n = count(text, old);
        if (n != 1) {
            throw new PatchException("anchor in " + rel + " found " + n + " times (need 1): " + abbreviate(old));
        }
        write(rel, text.replace(old, neu), note);
    }

    private void replaceOnce(String rel, String old, String neu, String note) throws PatchException {
        replaceOnce(rel, old, neu, note, null);
    }

    static int count(String text, String sub) {
        int n = 0, i = 0;
        while ((i = text.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    static String abbreviate(String s) {
        return s.length() > 90 ? s.substring(0, 90) : s;
    }

    /** str.replace(old, new, count) */
    static String replaceFirstN(String s, String old, String neu, int n) {
        StringBuilder b = new StringBuilder();
        int i = 0, done = 0;
        while (done < n) {
            int j = s.indexOf(old, i);
            if (j < 0) {
                break;
            }
            b.append(s, i, j).append(neu);
            i = j + old.length();
            done++;
        }
        return b.append(s.substring(i)).toString();
    }

    static String rstrip(String s) {
        int e = s.length();
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) {
            e--;
        }
        return s.substring(0, e);
    }

    static String lstrip(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return s.substring(i);
    }

    static String stripNewlines(String s) {
        int e = s.length();
        while (e > 0 && s.charAt(e - 1) == '\n') {
            e--;
        }
        return s.substring(0, e);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    /** shaders/program/*.glsl (direct children), sorted. */
    private List<String> programGlsl() {
        List<String> out = new ArrayList<>();
        for (String k : files.keySet()) {
            if (k.startsWith("shaders/program/") && k.endsWith(".glsl") && k.indexOf('/', "shaders/program/".length()) < 0) {
                out.add(k.substring("shaders/".length()));
            }
        }
        Collections.sort(out);
        return out;
    }

    /** shaders/lib/**\/*.glsl, sorted. */
    private List<String> libGlsl() {
        List<String> out = new ArrayList<>();
        for (String k : files.keySet()) {
            if (k.startsWith("shaders/lib/") && k.endsWith(".glsl")) {
                out.add(k.substring("shaders/".length()));
            }
        }
        Collections.sort(out);
        return out;
    }

    // ------------------------------------------------------------------------------------------------- templates

    private String srCommon() {
        return "// " + MARK + " (2026-10-13, v2 2026-10-14, v3 2026-10-15): Super Resolution mod, shaderpack interface v3.\n"
                + "// Inert unless the Super Resolution mod is installed and upscaling is on (SR_SHOULD_APPLY_SCALE == 1).\n"
                + "#ifndef SR_COMMON_INCLUDED\n#define SR_COMMON_INCLUDED\n#define SR_PATCH_VERSION " + PATCH_VERSION + "\n\n"
                + "#if defined SR_INSTALLED && defined SR_SHOULD_APPLY_SCALE\n    #if SR_SHOULD_APPLY_SCALE == 1\n"
                + "        #define SR_ACTIVE\n    #endif\n#endif\n\n"
                + "// final.glsl's sharpening of Super Resolution's output, as a share of the TAA-path strength (IMAGE_SHARPENING)\n"
                + "#ifndef SR_SHARPEN_MULT\n    #define SR_SHARPEN_MULT " + sharpen + "\n#endif\n"
                + "// added to the mip bias log2(render / screen) of block-atlas samples (negative = sharper textures, more shimmer)\n"
                + "#ifndef SR_MIP_BIAS_EXTRA\n    #define SR_MIP_BIAS_EXTRA " + mipExtra + "\n#endif\n\n"
                + "#ifdef SR_ACTIVE\n"
                + "    #if defined COMPOSITE6 || defined COMPOSITE7 || defined COMPOSITE8 || defined COMPOSITE9 || defined FINAL\n"
                + "        // after the upscale (Super Resolution runs where TAA sat): screen size; the final colour is in colortex11\n"
                + "        #define SR_STAGE_POST\n        #define colortex3 colortex11\n"
                + "    #elif !defined SHADOW && !defined DH_SHADOW && !defined SHADOWCOMP && !defined DHS_BEGIN\n"
                + "        // gbuffers, Distant Horizons, deferred1 and composite..composite5 draw at the render size\n"
                + "        #define SR_SCALED_STAGE\n    #endif\n"
                + "    uniform vec2 SRJitterOffset;\n"
                + "    // depth textures stay screen-size in Iris: the scene fills their lower-left render-size corner\n"
                + "    #define srDepthUV(uv) ((uv) * SR_RENDER_SCALE_FACTOR)\n"
                + "    #ifdef SR_STAGE_POST\n        #define srScaledTexel(t) ivec2(vec2(t) * SR_RENDER_SCALE_FACTOR)\n"
                + "    #else\n        #define srScaledTexel(t) (t)\n    #endif\n"
                + "    #if defined SR_SCALED_STAGE && defined FRAGMENT_SHADER\n"
                + "        // block-atlas samples pick the mip level a full-resolution frame would (DLSS programming guide: bias =\n"
                + "        // log2(render width / screen width)); a bias argument is only allowed in fragment shaders\n"
                + "        #define SR_MIP_BIAS_ARG , (log2(SR_RENDER_SCALE_FACTOR) + SR_MIP_BIAS_EXTRA)\n"
                + "        // texture-coordinate derivatives as a full-resolution frame has them (miplevel/mipDelta for generated\n"
                + "        // normals, coated textures and water highlights; textureGrad in POM, LabPBR, the procedural water, AF)\n"
                + "        #define SR_TEX_DERIV_SCALE (SR_RENDER_SCALE_FACTOR * exp2(SR_MIP_BIAS_EXTRA))\n"
                + "    #endif\n#else\n    #define srDepthUV(uv) (uv)\n    #define srScaledTexel(t) (t)\n#endif\n"
                + "#ifndef SR_MIP_BIAS_ARG\n    #define SR_MIP_BIAS_ARG\n#endif\n"
                + "#ifndef SR_TEX_DERIV_SCALE\n    #define SR_TEX_DERIV_SCALE 1.0\n#endif\n\n#endif\n";
    }

    private static final String SR_VERTEX_PRE = "// " + MARK
            + ": run the program's main(), then jitter and scale gl_Position (lib/sr/srVertexPost.glsl)\n"
            + "#include \"/lib/sr/srCommon.glsl\"\n#ifdef SR_ACTIVE\n    #define main srOriginalMain\n#endif\n";

    private static final String SR_VERTEX_POST = "// " + MARK + "\n#ifdef SR_ACTIVE\n#undef main\nvoid main() {\n"
            + "    srOriginalMain();\n"
            + "    // Super Resolution's jitter in render pixels (viewWidth/viewHeight are the render size in these stages), then\n"
            + "    // squeeze the frame into the lower-left render-size corner of the screen-size viewport\n"
            + "    gl_Position.xy += SRJitterOffset * (2.0 / vec2(viewWidth, viewHeight)) * gl_Position.w;\n"
            + "    gl_Position.xy = gl_Position.xy * SR_RENDER_SCALE_FACTOR + (SR_RENDER_SCALE_FACTOR - 1.0) * gl_Position.w;\n"
            + "}\n#endif\n";

    private static final String SR_MOTION = "// " + MARK
            + ": camera motion vectors for Super Resolution (UV space, previous uv - current uv). Iris on 1.20.1 has no\n"
            + "// per-entity velocity, so moving mobs and ships carry only the camera's motion (DLSS's own history checks cover them).\n"
            + "#include \"/lib/misc/reprojection.glsl\"\n\n"
            + "vec2 SRMotionVector(vec2 uv, ivec2 texel) {\n"
            + "    float z = texelFetch(depthtex0, texel, 0).r;\n"
            + "    if (z < 0.56) return vec2(0.0); // the hand moves with the camera\n"
            + "    vec3 cameraOffset = cameraPosition - previousCameraPosition;\n"
            + "    #ifdef DISTANT_HORIZONS\n        if (z >= 1.0) {\n"
            + "            float zDH = texelFetch(dhDepthTex, texel, 0).r;\n            if (zDH < 1.0) {\n"
            + "                vec4 viewPos = dhProjectionInverse * vec4(vec3(uv, zDH) * 2.0 - 1.0, 1.0);\n"
            + "                viewPos /= viewPos.w;\n"
            + "                vec4 playerPos = gbufferModelViewInverse * viewPos;\n"
            + "                vec4 prevClip = dhProjection * (gbufferPreviousModelView * (playerPos + vec4(cameraOffset, 0.0)));\n"
            + "                return prevClip.xy / prevClip.w * 0.5 + 0.5 - uv;\n            }\n        }\n    #endif\n"
            + "    return Reprojection(vec3(uv, z), z < 1.0 ? cameraOffset : vec3(0.0)) - uv;\n}\n";

    private static final String SR_JSON = "{\n  \"schema_version\": 3,\n  \"profiles\": {\n    \"*\": {\n"
            + "      \"jitter\": {\n        \"enabled\": true\n      },\n      \"upscale\": {\n        \"enabled\": true,\n"
            + "        \"internal_format\": \"rgba8\",\n        \"auto_exposure\": false,\n        \"hdr\": false,\n"
            + "        \"motion_jittered\": false,\n        \"trigger\": {\n          \"type\": \"BEFORE\",\n"
            + "          \"pass\": \"composite6\"\n        },\n        \"inputs\": {\n          \"color\": {\n"
            + "            \"enabled\": true,\n            \"src\": \"colortex3\",\n            \"region\": [\n"
            + "              0,\n              0,\n              -1,\n              -1\n            ]\n          },\n"
            + "          \"depth\": {\n            \"enabled\": true,\n            \"src\": \"depthtex\",\n"
            + "            \"region\": [\n              0,\n              0,\n              -1,\n              -1\n"
            + "            ]\n          },\n          \"motion_vectors\": {\n            \"enabled\": true,\n"
            + "            \"src\": \"colortex12\",\n            \"region\": [\n              0,\n              0,\n"
            + "              -1,\n              -1\n            ]\n          }\n        },\n        \"outputs\": {\n"
            + "          \"upscaled_color\": {\n            \"enabled\": true,\n            \"target\": [\n"
            + "              \"colortex11\"\n            ],\n            \"region\": [\n              0,\n              0,\n"
            + "              -2,\n              -2\n            ]\n          }\n        },\n"
            + "        \"disabled_algorithms\": [\n          \"fsr1\",\n          \"fsr2\",\n          \"sgsr1\",\n"
            + "          \"sgsr2\"\n        ]\n      }\n    }\n  }\n}\n";

    private static String propsBlock() {
        StringBuilder b = new StringBuilder();
        b.append("\n# ").append(MARK).append(" (2026-10-13, v2 2026-10-14): Super Resolution mod (interface v3)\n");
        b.append("#ifdef SR_INSTALLED\n#if SR_SHOULD_APPLY_SCALE == 1\n");
        b.append("# buffers drawn before the upscale: render size\n");
        for (int n : SCALED_BUFFERS) {
            b.append("size.buffer.colortex").append(n).append(" = SR_RENDER_SCALE_FACTOR SR_RENDER_SCALE_FACTOR\n");
        }
        b.append("# reflections: REFLECTION_RES of the render size (the shaders address them in render pixels); this comes after\n");
        b.append("# the pack's own colortex1/7 lines, and the last value of a key wins\n");
        b.append("#if REFLECTION_RES > 0.6\n");
        b.append("size.buffer.colortex1 = SR_RENDER_SCALE_FACTOR SR_RENDER_SCALE_FACTOR\n");
        b.append("size.buffer.colortex7 = SR_RENDER_SCALE_FACTOR SR_RENDER_SCALE_FACTOR\n");
        b.append("#else\n");
        b.append("size.buffer.colortex1 = SR_RENDER_SCALE_FACTOR_HALF SR_RENDER_SCALE_FACTOR_HALF\n");
        b.append("size.buffer.colortex7 = SR_RENDER_SCALE_FACTOR_HALF SR_RENDER_SCALE_FACTOR_HALF\n");
        b.append("#endif\n");
        b.append("# composite6 writes only a 4x4 placeholder (Super Resolution runs right before it), composite7 (FXAA) is off,\n");
        b.append("# and TAA's history buffer is unused unless Long Exposure or Renko Cut needs it\n");
        b.append("size.buffer.colortex13 = 4 4\n");
        for (String w : WORLDS) {
            b.append("program.").append(w).append("/composite7.enabled=false\n");
        }
        b.append("#if LONG_EXPOSURE == 0\n#ifndef RENKO_CUT\nsize.buffer.colortex2 = 4 4\n#endif\n#endif\n");
        b.append("#endif\n#endif\n");
        return b.toString();
    }

    private static final String PIPELINE_BLOCK = "\n// " + MARK + ": Super Resolution buffers (unused without the mod)\n/*\n"
            + "const int colortex11Format = RGBA8;   // final colour at screen size: Super Resolution's output, read by final\n"
            + "const int colortex12Format = RG16F;   // camera motion vectors at render size, written by composite5\n"
            + "const int colortex13Format = R8;      // v2: the 4x4 placeholder composite6 writes under Super Resolution\n"
            + "*/\n// v2: colortex11 (Super Resolution's output copy) and colortex12 (composite5) are fully rewritten every frame\n"
            + "const bool colortex11Clear = false;\nconst bool colortex12Clear = false;\nconst bool colortex13Clear = false;\n";

    // ------------------------------------------------------------------------------------------------- the steps

    private void apply() throws PatchException {
        newFiles();
        common();
        uniforms();
        jitter();
        colorwheelGuards();
        wrappers();
        composite5();
        postPasses();
        depthOfField();
        texDerivatives();
        ambersol();
        localShadows();
        depthReads();
        mipBias();
        properties();
        pipeline();
    }

    private void newFiles() throws PatchException {
        write("lib/sr/srCommon.glsl", srCommon(), "stage detection, SR uniforms, depth helpers, mip bias, sharpening");
        write("lib/sr/srVertexPre.glsl", SR_VERTEX_PRE, "rename main for the vertex hook");
        write("lib/sr/srVertexPost.glsl", SR_VERTEX_POST, "jitter + render-scale squeeze after main");
        write("lib/sr/srMotion.glsl", SR_MOTION, "camera motion vectors");
        write("superresolution.v3.json", SR_JSON, "Super Resolution interface v3");
    }

    private void common() throws PatchException {
        replaceOnce("lib/common.glsl", "//User Settings//",
                "// " + MARK + "\n#include \"/lib/sr/srCommon.glsl\"\n\n//User Settings//", "include srCommon first");
    }

    private void uniforms() throws PatchException {
        String rel = "lib/uniforms.glsl";
        replaceOnce(rel, "uniform float viewWidth;\n",
                "uniform float viewWidth;\n#ifdef SR_SCALED_STAGE\n"
                        + "    // " + MARK + ": these passes draw at the render size (self-reference is not re-expanded)\n"
                        + "    #define viewWidth (viewWidth * SR_RENDER_SCALE_FACTOR)\n"
                        + "    #define viewHeight (viewHeight * SR_RENDER_SCALE_FACTOR)\n#endif\n",
                "viewWidth/viewHeight = render size before the upscale",
                "#define viewWidth (viewWidth * SR_RENDER_SCALE_FACTOR)");
        String text = read(rel);
        if (!(text.indexOf("uniform float viewHeight;") < text.indexOf("uniform float viewWidth;"))) {
            throw new PatchException("viewHeight order");
        }
        replaceOnce(rel, "uniform vec2 texelSize;\n",
                "uniform vec2 texelSize;\n#ifdef SR_SCALED_STAGE\n    // " + MARK + "\n"
                        + "    #define viewSize (viewSize * SR_RENDER_SCALE_FACTOR)\n"
                        + "    #define texelSize (texelSize / SR_RENDER_SCALE_FACTOR)\n#endif\n",
                "viewSize/texelSize = render size before the upscale",
                "#define viewSize (viewSize * SR_RENDER_SCALE_FACTOR)");
    }

    private void jitter() throws PatchException {
        String rel = "lib/antialiasing/jitter.glsl";
        String text = read(rel);
        if (text.contains(MARK)) {
            return;
        }
        String head = "vec2 TAAJitter(vec2 coord, float w) {\n";
        if (count(text, head) != 1) {
            throw new PatchException("TAAJitter signature changed");
        }
        int bodyStart = text.indexOf(head) + head.length();
        int bodyEnd = text.lastIndexOf('}');
        String original = text.substring(bodyStart, bodyEnd);
        String newBody = "    #ifdef SR_ACTIVE\n        // " + MARK + ": Super Resolution's jitter replaces TAA's\n"
                + "        #ifdef VERTEX_SHADER\n"
                + "            #if defined GBUFFERS_COLORWHEEL || defined GBUFFERS_COLORWHEEL_TRANSLUCENT\n"
                + "                // Colorwheel (Create/Flywheel) programs are not wrapped: jitter and squeeze here\n"
                + "                coord += SRJitterOffset * (2.0 * w) / vec2(viewWidth, viewHeight);\n"
                + "                return coord * SR_RENDER_SCALE_FACTOR + (SR_RENDER_SCALE_FACTOR - 1.0) * w;\n"
                + "            #else\n"
                + "                return coord; // lib/sr/srVertexPost.glsl jitters and squeezes after main()\n"
                + "            #endif\n"
                + "        #else\n"
                + "            return coord + SRJitterOffset * (2.0 * w) / vec2(viewWidth, viewHeight); // w = -0.5: undo it\n"
                + "        #endif\n"
                + "    #else\n" + rstrip(original) + "\n    #endif\n";
        write(rel, text.substring(0, bodyStart) + newBody + text.substring(bodyEnd), "TAAJitter: Super Resolution jitter");
    }

    private void colorwheelGuards() throws PatchException {
        for (String rel : new String[] {"program/gbuffers_terrain.glsl", "program/gbuffers_water.glsl"}) {
            String text = read(rel);
            Matcher m = COLORWHEEL_RX.matcher(text);
            int n = 0;
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                n++;
                m.appendReplacement(sb, Matcher.quoteReplacement("#if defined TAA || defined SR_ACTIVE" + m.group(1) + m.group(2)));
            }
            m.appendTail(sb);
            if (n > 0) {
                write(rel, sb.toString(), n + " TAA guards also open under SR_ACTIVE (Colorwheel)");
            }
        }
    }

    private void wrappers() throws PatchException {
        for (String w : WORLDS) {
            List<String> vsh = new ArrayList<>();
            for (String k : files.keySet()) {
                if (k.startsWith("shaders/" + w + "/") && k.endsWith(".vsh") && k.indexOf('/', ("shaders/" + w + "/").length()) < 0) {
                    vsh.add(k.substring(("shaders/" + w + "/").length()));
                }
            }
            Collections.sort(vsh);
            for (String name : vsh) {
                if (!startsWithAny(name, HOOK_PROGRAM_PREFIXES) || name.startsWith("clrwl_")) {
                    continue;
                }
                String rel = w + "/" + name;
                String text = read(rel);
                if (text.contains("srVertexPre.glsl")) {
                    continue;
                }
                Matcher m = PROGRAM_INCLUDE_RX.matcher(text);
                if (!m.find()) {
                    throw new PatchException("no #include \"/program/...\" in " + rel);
                }
                String neu = text.substring(0, m.start()) + "#include \"/lib/sr/srVertexPre.glsl\"\n" + m.group()
                        + "\n#include \"/lib/sr/srVertexPost.glsl\"" + text.substring(m.end());
                write(rel, neu, "vertex hook");
            }
        }
    }

    private static boolean startsWithAny(String s, String[] prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private void composite5() throws PatchException {
        String rel = "program/composite5.glsl";
        replaceOnce(rel, "//Program//\nvoid main() {\n    vec3 color = texture2D(colortex0, texCoord).rgb;",
                "#ifdef SR_ACTIVE\n    // " + MARK + "\n    #include \"/lib/sr/srMotion.glsl\"\n#endif\n\n"
                        + "//Program//\nvoid main() {\n    vec3 color = texture2D(colortex0, texCoord).rgb;",
                "include srMotion", "#include \"/lib/sr/srMotion.glsl\"");
        String old = "    /* DRAWBUFFERS:35 */\n    gl_FragData[0] = vec4(color, 1.0);\n"
                + "    gl_FragData[1] = vec4(vec3(0), texture5.a);\n";
        String neu = "    #ifdef SR_ACTIVE\n        // " + MARK + ": also the motion vectors for Super Resolution\n"
                + "        /* RENDERTARGETS: 3,5,12 */\n        gl_FragData[0] = vec4(color, 1.0);\n"
                + "        gl_FragData[1] = vec4(vec3(0), texture5.a);\n"
                + "        gl_FragData[2] = vec4(SRMotionVector(texCoord, texelCoord), 0.0, 1.0);\n"
                + "    #else\n" + replaceFirstN(old, "    ", "        ", 3) + "    #endif\n";
        replaceOnce(rel, old, neu, "motion vectors to colortex12", "/* RENDERTARGETS: 3,5,12");
    }

    private void postPasses() throws PatchException {
        String rel6 = "program/composite6.glsl";
        replaceOnce(rel6, "            #ifdef TAA\n                z1 = texelFetch(depthtex1, texelCoord, 0).r;",
                "            #if defined TAA && !defined SR_ACTIVE // " + MARK + ": DLSS replaces TAA\n"
                        + "                z1 = texelFetch(depthtex1, texelCoord, 0).r;",
                "skip TAA resolve", "#if defined TAA && !defined SR_ACTIVE");
        drawbuffers(rel6, "32", "11,2");
        replaceOnce(rel6, "const bool colortex3MipmapEnabled = true;\n",
                "#ifndef SR_ACTIVE // " + MARK + " v2: no TAA under Super Resolution, no mipmaps needed\n"
                        + "    const bool colortex3MipmapEnabled = true;\n#endif\n",
                "no colortex mipmaps under SR", "#ifndef SR_ACTIVE // " + MARK + " v2");
        replaceOnce(rel6, "void main() {\n    vec3 color = texelFetch(colortex3, texelCoord, 0).rgb;\n",
                "void main() {\n    #if defined SR_ACTIVE && !defined RENKO_CUT && LONG_EXPOSURE == 0\n"
                        + "        // " + MARK + " v2: Super Resolution ran right before this pass (its trigger) and wrote the\n"
                        + "        // final colour to colortex11 at screen size; TAA is replaced, so this pass only writes a 4x4 placeholder\n"
                        + "        /* RENDERTARGETS: 13 */\n        gl_FragData[0] = vec4(0.0);\n    #else\n"
                        + "    vec3 color = texelFetch(colortex3, texelCoord, 0).rgb;\n",
                "4x4 placeholder under SR", "/* RENDERTARGETS: 13 */");
        replaceOnce(rel6, "    gl_FragData[1] = vec4(temp, 0.0);\n}\n",
                "    gl_FragData[1] = vec4(temp, 0.0);\n    #endif\n}\n", "close the placeholder branch",
                "    gl_FragData[1] = vec4(temp, 0.0);\n    #endif\n}\n");
        drawbuffers("program/composite7.glsl", "3", "11");
        String rel = "program/composite7.glsl";
        String text = read(rel);
        if (!text.contains("!defined SR_ACTIVE")) {
            String guard = "#if FXAA_DEFINE == 1 && FXAA_STRENGTH > 1";
            if (count(text, guard) != 2) {
                throw new PatchException("composite7 FXAA guards: " + count(text, guard));
            }
            write(rel, text.replace(guard, guard + " && !defined SR_ACTIVE"), "skip FXAA after DLSS");
        }
        drawbuffers("program/composite8.glsl", "32", "11,2");
        drawbuffers("program/composite8.glsl", "3", "11");
        drawbuffers("program/composite9.glsl", "3", "11");
        replaceOnce("program/final.glsl", "texelFetch(colortex6, texelCoord, 0)",
                "texelFetch(colortex6, srScaledTexel(texelCoord), 0)", "render-size colortex6 read",
                "texelFetch(colortex6, srScaledTexel(texelCoord), 0)");
        replaceOnce("program/final.glsl", "            float sharpenMult = IMAGE_SHARPENING * 0.5;\n        #endif\n",
                "            float sharpenMult = IMAGE_SHARPENING * 0.5;\n        #endif\n"
                        + "        #ifdef SR_ACTIVE // " + MARK + " v2: share of the sharpening on Super Resolution's output\n"
                        + "            sharpenMult *= SR_SHARPEN_MULT;\n        #endif\n",
                "SR_SHARPEN_MULT", "sharpenMult *= SR_SHARPEN_MULT;");
    }

    private void drawbuffers(String rel, String old, String neu) throws PatchException {
        String text = read(rel);
        if (text.contains("/* RENDERTARGETS: " + neu + " */ // " + MARK)) {
            return;
        }
        Pattern rx = Pattern.compile("^([ \\t]*)/\\* DRAWBUFFERS:" + Pattern.quote(old) + " \\*/[ \\t]*$", Pattern.MULTILINE);
        Matcher m = rx.matcher(text);
        List<int[]> hits = new ArrayList<>();
        List<String> inds = new ArrayList<>();
        while (m.find()) {
            hits.add(new int[] {m.start(), m.end()});
            inds.add(m.group(1));
        }
        if (hits.isEmpty()) {
            throw new PatchException(rel + ": no DRAWBUFFERS:" + old);
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            String ind = inds.get(i);
            String block = ind + "#ifdef SR_ACTIVE\n" + ind + "    /* RENDERTARGETS: " + neu + " */ // " + MARK + "\n"
                    + ind + "#else\n" + ind + "    /* DRAWBUFFERS:" + old + " */\n" + ind + "#endif";
            text = text.substring(0, hits.get(i)[0]) + block + text.substring(hits.get(i)[1]);
        }
        write(rel, text, "DRAWBUFFERS:" + old + " -> RENDERTARGETS " + neu + " under SR");
    }

    private void depthOfField() throws PatchException {
        replaceOnce("program/composite3.glsl", "        #if WB_DOF_FOCUS == 0\n            uniform float centerDepthSmooth;\n",
                "        #if WB_DOF_FOCUS == 0\n            #ifdef SR_ACTIVE\n"
                        + "                // " + MARK + " v2: focus on the scene centre (the depth texture is screen size, the\n"
                        + "                // scene fills its render-size corner; not smoothed over time like Iris's value)\n"
                        + "                #define centerDepthSmooth texture2D(depthtex1, srDepthUV(vec2(0.5))).r\n"
                        + "            #else\n                uniform float centerDepthSmooth;\n            #endif\n",
                "DOF focus at the scene centre", "srDepthUV(vec2(0.5))");
    }

    private void texDerivatives() throws PatchException {
        replaceOnce("lib/util/dFdxdFdy.glsl", "    vec2 dcdx = dFdx(texCoord.xy);\n    vec2 dcdy = dFdy(texCoord.xy);\n",
                "    // " + MARK + " v2: SR_TEX_DERIV_SCALE = full-resolution derivatives under Super Resolution\n"
                        + "    vec2 dcdx = dFdx(texCoord.xy) * SR_TEX_DERIV_SCALE;\n"
                        + "    vec2 dcdy = dFdy(texCoord.xy) * SR_TEX_DERIV_SCALE;\n",
                "texture derivatives (miplevel, textureGrad)", "dFdx(texCoord.xy) * SR_TEX_DERIV_SCALE");
        replaceOnce("lib/materials/materialMethods/waterProcedureTexture.glsl",
                "    vec2 ddx = dFdx( uv );\n    vec2 ddy = dFdy( uv );\n",
                "    // " + MARK + " v2: full-resolution derivatives under Super Resolution\n"
                        + "    vec2 ddx = dFdx( uv ) * SR_TEX_DERIV_SCALE;\n    vec2 ddy = dFdy( uv ) * SR_TEX_DERIV_SCALE;\n",
                "procedural water texture derivatives", "dFdx( uv ) * SR_TEX_DERIV_SCALE");
        replaceOnce("lib/materials/materialMethods/anisotropicFiltering.glsl",
                "    mat2 J = inverseM(mat2(dFdx(uv), dFdy(uv)));\n",
                "    mat2 J = inverseM(mat2(dFdx(uv), dFdy(uv)) * SR_TEX_DERIV_SCALE); // " + MARK + " v2\n",
                "anisotropic filtering footprint", "dFdy(uv)) * SR_TEX_DERIV_SCALE");
    }

    private void ambersol() throws PatchException {
        String rel = "program/ambersolCompute.glsl";
        if (!exists(rel)) {
            return;
        }
        String old = "    ivec2 fullSize = textureSize(depthtex0, 0);\n"
                + "    int scale = int(round(1.0 / AC_AMBERSOL_RESOLUTION));\n"
                + "    ivec2 base = clamp(pixel * scale, ivec2(0), fullSize - 1);\n";
        String neu = "    #ifdef SR_ACTIVE\n        // " + MARK + ": the scene depth fills only the render-size corner of depthtex0\n"
                + "        ivec2 fullSize = ivec2(vec2(textureSize(depthtex0, 0)) * SR_RENDER_SCALE_FACTOR);\n"
                + "        int scale = max(int(round(SR_RENDER_SCALE_FACTOR / AC_AMBERSOL_RESOLUTION)), 1);\n"
                + "        ivec2 base = clamp(ivec2(uv * vec2(fullSize)), ivec2(0), fullSize - 1);\n"
                + "    #else\n" + replaceFirstN(old, "    ", "        ", 3) + "    #endif\n";
        replaceOnce(rel, old, neu, "render-size depth corner", "* SR_RENDER_SCALE_FACTOR);\n        int scale");
    }

    private void localShadows() throws PatchException {
        List<String> rels = new ArrayList<>(programGlsl());
        rels.addAll(libGlsl());
        for (String rel : rels) {
            if (rel.startsWith("lib/sr/") || rel.equals("lib/uniforms.glsl")) {
                continue;
            }
            String text = read(rel);
            TreeSet<String> names = new TreeSet<>();
            for (String line : text.split("\n", -1)) {
                if (line.contains("uniform")) {
                    continue;
                }
                Matcher m = LOCAL_DECL_RX.matcher(line);
                while (m.find()) {
                    names.add(m.group(1));
                }
            }
            if (names.isEmpty()) {
                continue;
            }
            String neu = text;
            for (String name : names) {
                neu = Pattern.compile("\\b" + name + "\\b").matcher(neu).replaceAll("srLocal_" + name);
            }
            write(rel, neu, "renamed shadowing locals " + String.join(", ", names));
        }
    }

    private void depthReads() throws PatchException {
        List<String> rels = new ArrayList<>(programGlsl());
        for (String r : libGlsl()) {
            if (!r.startsWith("lib/sr/")) {
                rels.add(r);
            }
        }
        for (String rel : rels) {
            String text = read(rel);
            StringBuilder out = new StringBuilder();
            int i = 0, n = 0;
            Matcher m = CALL_RX.matcher(text);
            while (m.find()) {
                int argStart = m.end();
                int j = argStart, depth = 0;
                while (j < text.length()) {
                    char ch = text.charAt(j);
                    if (ch == '(' || ch == '[') {
                        depth++;
                    } else if (ch == ')' || ch == ']') {
                        if (depth == 0) {
                            break;
                        }
                        depth--;
                    } else if (ch == ',' && depth == 0) {
                        break;
                    }
                    j++;
                }
                String arg = text.substring(argStart, j);
                String stripped = rstrip(lstrip(arg));
                if (stripped.startsWith("srDepthUV(") || stripped.startsWith("srScaledTexel(")) {
                    continue;
                }
                String wrap = m.group(1).equals("texelFetch") ? "srScaledTexel" : "srDepthUV";
                int lead = arg.length() - lstrip(arg).length();
                out.append(text, i, argStart).append(arg, 0, lead).append(wrap).append('(').append(stripped).append(')');
                i = j;
                n++;
            }
            if (n > 0) {
                out.append(text.substring(i));
                write(rel, out.toString(), n + " depth reads scaled");
            }
        }
    }

    /** (start, end of the coordinate argument, already biased) for every texture2D(tex|normals|specular, coord). */
    static List<int[]> mipSites(String text) {
        List<int[]> sites = new ArrayList<>();
        Matcher m = MIP_RX.matcher(text);
        while (m.find()) {
            int lineStart = text.lastIndexOf('\n', m.start() - 1) + 1;
            if (lstrip(text.substring(lineStart, m.start())).startsWith("#")) {
                continue;
            }
            int j = m.end(), depth = 0, commas = 0;
            while (j < text.length()) {
                char ch = text.charAt(j);
                if (ch == '(' || ch == '[') {
                    depth++;
                } else if (ch == ')' || ch == ']') {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                } else if (ch == ',' && depth == 0) {
                    commas++;
                }
                j++;
            }
            if (commas > 0) {
                continue;
            }
            sites.add(new int[] {m.start(), j, text.substring(m.end(), j).contains("SR_MIP_BIAS_ARG") ? 1 : 0});
        }
        return sites;
    }

    private void mipBias() throws PatchException {
        List<String> rels = new ArrayList<>(programGlsl());
        rels.addAll(libGlsl());
        for (String rel : rels) {
            if (rel.startsWith("lib/sr/") || rel.equals(MIP_SKIP_FILE)) {
                continue;
            }
            String text = read(rel);
            List<int[]> todo = new ArrayList<>();
            for (int[] s : mipSites(text)) {
                if (s[2] == 0) {
                    todo.add(s);
                }
            }
            if (todo.isEmpty()) {
                continue;
            }
            StringBuilder out = new StringBuilder();
            int i = 0;
            for (int[] s : todo) {
                out.append(text, i, s[1]).append(" SR_MIP_BIAS_ARG");
                i = s[1];
            }
            out.append(text.substring(i));
            write(rel, out.toString(), todo.size() + " atlas samples get the mip bias");
        }
    }

    private void properties() throws PatchException {
        String rel = "shaders.properties";
        String text = read(rel);
        String block = propsBlock();
        if (text.contains(block)) {
            return;
        }
        if (text.contains(MARK)) {
            throw new PatchException("shaders.properties has an unknown version of the Super Resolution block");
        }
        write(rel, stripNewlines(text) + "\n" + block, "Super Resolution block (render-size buffers etc.)");
    }

    private void pipeline() throws PatchException {
        String rel = "lib/pipelineSettings.glsl";
        String text = read(rel);
        if (text.contains(PIPELINE_BLOCK)) {
            return;
        }
        if (text.contains(MARK)) {
            throw new PatchException("pipelineSettings.glsl has an unknown version of the Super Resolution block");
        }
        String anchor = "const bool colortex10Clear = false;\n";
        if (count(text, anchor) != 1) {
            throw new PatchException("pipelineSettings anchor");
        }
        write(rel, text.replace(anchor, anchor + PIPELINE_BLOCK), "colortex11-13 formats");
    }

    // ----------------------------------------------------------------------------------------------------- check

    static final class Check {
        final List<String> problems = new ArrayList<>();
        int hooks, biased;
    }

    private String textOf(String key) {
        String t = texts.get(key);
        return t != null ? t : normalize(new String(files.get(key), StandardCharsets.UTF_8));
    }

    private Check check() {
        Check c = new Check();
        String[][] need = {
                {"lib/sr/srCommon.glsl", "#define SR_PATCH_VERSION " + PATCH_VERSION},
                {"lib/sr/srVertexPost.glsl", "srOriginalMain"},
                {"superresolution.v3.json", "\"internal_format\": \"rgba8\""},
                {"lib/common.glsl", "srCommon.glsl"}, {"lib/uniforms.glsl", "SR_SCALED_STAGE"},
                {"lib/antialiasing/jitter.glsl", MARK},
                {"program/composite5.glsl", "gl_FragData[2] = vec4(SRMotionVector(texCoord, texelCoord), 0.0, 1.0);"},
                {"program/composite6.glsl", "RENDERTARGETS: 13"}, {"program/composite7.glsl", "RENDERTARGETS: 11"},
                {"program/composite3.glsl", "srDepthUV(vec2(0.5))"},
                {"lib/util/dFdxdFdy.glsl", "dFdx(texCoord.xy) * SR_TEX_DERIV_SCALE"},
                {"lib/sr/srMotion.glsl", "vec2 SRMotionVector(vec2 uv, ivec2 texel)"},
                {"lib/materials/materialMethods/waterProcedureTexture.glsl", "dFdx( uv ) * SR_TEX_DERIV_SCALE"},
                {"lib/materials/materialMethods/anisotropicFiltering.glsl", "dFdy(uv)) * SR_TEX_DERIV_SCALE"},
                {"program/final.glsl", "sharpenMult *= SR_SHARPEN_MULT;"},
                {"shaders.properties", "size.buffer.colortex13 = 4 4"},
                {"lib/pipelineSettings.glsl", "colortex13Format"}};
        for (String[] n : need) {
            String key = "shaders/" + n[0];
            if (!files.containsKey(key) || !textOf(key).contains(n[1])) {
                c.problems.add("missing: " + n[0] + " (" + n[1] + ")");
            }
        }
        String props = textOf("shaders/shaders.properties");
        if (!props.contains(propsBlock())) {
            c.problems.add("shaders.properties: the Super Resolution block is not present verbatim");
        }
        List<String> rels = new ArrayList<>(programGlsl());
        for (String r : libGlsl()) {
            if (!r.contains("/sr/")) {
                rels.add(r);
            }
        }
        for (String rel : rels) {
            String text = textOf("shaders/" + rel);
            Matcher m = CALL_RX.matcher(text);
            while (m.find()) {
                String rest = lstrip(text.substring(m.end(), Math.min(text.length(), m.end() + 40)));
                if (!(rest.startsWith("srDepthUV(") || rest.startsWith("srScaledTexel("))) {
                    c.problems.add("unwrapped depth read: " + rel + ": " + text.substring(m.start(), Math.min(text.length(), m.end() + 30)));
                }
            }
        }
        List<String> all = new ArrayList<>(programGlsl());
        all.addAll(libGlsl());
        for (String rel : all) {
            if (rel.startsWith("lib/sr/") || rel.equals(MIP_SKIP_FILE)) {
                continue;
            }
            for (int[] s : mipSites(textOf("shaders/" + rel))) {
                if (s[2] == 1) {
                    c.biased++;
                } else {
                    c.problems.add("atlas sample without the mip bias: " + rel + " at char " + s[0]);
                }
            }
        }
        for (String rel : all) {
            if (rel.startsWith("lib/sr/") || rel.equals("lib/uniforms.glsl")) {
                continue;
            }
            String[] lines = textOf("shaders/" + rel).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].contains("uniform") && LOCAL_DECL_RX.matcher(lines[i]).find()) {
                    c.problems.add("local shadows a remapped name " + rel + ":" + (i + 1));
                }
            }
        }
        for (String key : files.keySet()) {
            if (!key.startsWith("shaders/")) {
                continue;
            }
            if (!(key.endsWith(".glsl") || key.endsWith(".vsh") || key.endsWith(".fsh") || key.endsWith(".csh") || key.endsWith(".gsh"))) {
                continue;
            }
            String text = texts.containsKey(key) ? texts.get(key)
                    : normalize(new String(files.get(key), StandardCharsets.UTF_8));
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (INCLUDE_LINE_RX.matcher(lines[i]).find() && !INCLUDE_OK_RX.matcher(lines[i]).matches()) {
                    c.problems.add("bad #include line " + key.substring("shaders/".length()) + ":" + (i + 1));
                }
            }
        }
        int at = props.indexOf(MARK);
        String inBlock = at >= 0 ? props.substring(Math.max(0, at - 2)) : "";
        for (String line : inBlock.split("\n", -1)) {
            if (line.startsWith("# ")) {
                String word = line.substring(2).split(" ", 2)[0];
                if (DIRECTIVE_RX.matcher("#" + word).find() && !DIRECTIVE_RX.matcher(line).find()) {
                    c.problems.add("properties comment reads as a directive: " + line);
                }
            }
        }
        for (String w : WORLDS) {
            for (String key : files.keySet()) {
                String prefix = "shaders/" + w + "/";
                if (key.startsWith(prefix) && key.endsWith(".vsh") && key.indexOf('/', prefix.length()) < 0) {
                    String name = key.substring(prefix.length());
                    if (startsWithAny(name, HOOK_PROGRAM_PREFIXES) && textOf(key).contains("srVertexPost.glsl")) {
                        c.hooks++;
                    }
                }
            }
        }
        return c;
    }

    /** Helper for callers that only have a stream (unused by the mod itself). */
    static byte[] readAll(InputStream in) throws IOException {
        return in.readAllBytes();
    }

    /** Copies a settings file next to the generated pack if the pack had one and the copy is missing. */
    public static boolean copySettings(Path shaderpacks, String sourceName, String targetName) throws IOException {
        Path src = shaderpacks.resolve(sourceName + ".txt"), dst = shaderpacks.resolve(targetName + ".txt");
        if (Files.isRegularFile(src) && !Files.exists(dst)) {
            Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
            return true;
        }
        return false;
    }
}
