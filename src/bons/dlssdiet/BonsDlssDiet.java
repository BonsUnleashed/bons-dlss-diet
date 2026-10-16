package bons.dlssdiet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Bons DLSS Diet (client): finds Complementary Unbound r5.7.1 + Euphoria Patches 1.8.6 in the shaderpacks folder and
 * writes "&lt;pack&gt; + DLSS" next to it, built from that copy (DietPatcher). Passes: at mod construction, when mod
 * loading completes (the Euphoria patcher may have written its pack by then), at the first screen that opens (title
 * screen, or the world when the game was started with quick play) and whenever the shader pack screen opens. Oculus
 * loads its pack before any mod constructs, so when the selected pack is one this start wrote, Oculus is asked to
 * reload once (reflection).
 * <p>
 * Since 1.1.0 the mod runs DLSS itself (bons.dlssdiet.runtime + three Oculus hooks in bons.dlssdiet.mixin, native code
 * in natives/windows-x64): no other mod is needed. With the Super Resolution mod installed the hooks stay off and that
 * mod serves the same shader-pack interface, as in 1.0.0.
 */
@Mod(BonsDlssDiet.MOD_ID)
public final class BonsDlssDiet {
    public static final String MOD_ID = "bons_dlss_diet";
    static final Logger LOG = LogManager.getLogger("Bons DLSS Diet");
    private static final Pattern SHADER_PACK_RX = Pattern.compile("(?m)^shaderPack=(.*)$");

    private final List<String> generatedThisStart = new ArrayList<>();
    private boolean firstScreenSeen = false;
    private boolean reloadDone = false;

    public BonsDlssDiet() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        boolean sr = net.minecraftforge.fml.ModList.get().isLoaded("super_resolution");
        LOG.info("Bons DLSS Diet: DLSS runs {}", sr ? "through the Super Resolution mod (installed)" : "in this mod (built-in runtime)");
        run("start");
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onLoadComplete);
        MinecraftForge.EVENT_BUS.addListener(this::onScreen);
    }

    private void onLoadComplete(FMLLoadCompleteEvent event) {
        run("load complete");
        event.enqueueWork(this::maybeReloadOculus);
    }

    private void onScreen(ScreenEvent.Opening event) {
        Object screen = event.getNewScreen();
        if (screen == null) {
            return;
        }
        if (!firstScreenSeen) {
            firstScreenSeen = true;
            run("first screen");
            maybeReloadOculus();
        } else if (screen.getClass().getName().endsWith(".ShaderPackScreen")) {
            run("shader pack screen");
        }
    }

    /** One pass over the shaderpacks folder. Never throws out of the mod. */
    synchronized void run(String when) {
        try {
            Path shaderpacks = FMLPaths.GAMEDIR.get().resolve("shaderpacks");
            if (!Files.isDirectory(shaderpacks)) {
                return;
            }
            List<Path> entries = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(shaderpacks)) {
                for (Path p : ds) {
                    entries.add(p);
                }
            }
            for (Path p : entries) {
                String file = p.getFileName().toString();
                boolean zip = file.toLowerCase().endsWith(".zip");
                if (!(Files.isDirectory(p) || zip)) {
                    continue;
                }
                String name = zip ? file.substring(0, file.length() - 4) : file;
                if (!name.startsWith("ComplementaryUnbound_") || name.endsWith(DietPatcher.SUFFIX)
                        || name.contains(DietPatcher.SUFFIX + " ") || name.endsWith(".tmp")) {
                    continue;
                }
                handle(shaderpacks, p, name, when);
            }
        } catch (Exception e) {
            LOG.warn("Bons DLSS Diet: could not look at the shaderpacks folder ({})", when, e);
        }
    }

    private void handle(Path shaderpacks, Path source, String name, String when) {
        String targetName = name + DietPatcher.SUFFIX;
        Path target = shaderpacks.resolve(targetName);
        try {
            LinkedHashMap<String, byte[]> pack = DietPatcher.readPack(source);
            String why = DietPatcher.unsupportedReason(name, pack);
            if (why != null) {
                if ("start".equals(when)) {
                    LOG.info("Bons DLSS Diet: left \"{}\" alone: {}", name, why);
                }
                return;
            }
            String fingerprint = DietPatcher.fingerprint(pack);
            String marker = marker(name, fingerprint);
            if (Files.isDirectory(target)) {
                Path m = target.resolve(DietPatcher.MARKER_FILE);
                if (!Files.isRegularFile(m)) {
                    if ("start".equals(when)) {
                        LOG.warn("Bons DLSS Diet: \"{}\" exists but was not written by this mod; left alone", targetName);
                    }
                    return;
                }
                if (marker.equals(Files.readString(m, StandardCharsets.UTF_8))) {
                    if ("start".equals(when)) {
                        LOG.info("Bons DLSS Diet: \"{}\" is up to date", targetName);
                    }
                    return;
                }
                LOG.info("Bons DLSS Diet: \"{}\" is out of date (the source pack or the patch changed), rewriting it", targetName);
            }
            DietPatcher.Result r = DietPatcher.patch(pack, DietPatcher.DEFAULT_SHARPEN, DietPatcher.DEFAULT_MIP_EXTRA);
            Path tmp = shaderpacks.resolve(targetName + ".tmp");
            deleteTree(tmp);
            DietPatcher.writePack(r.files, tmp);
            Files.writeString(tmp.resolve(DietPatcher.MARKER_FILE), marker, StandardCharsets.UTF_8);
            if (Files.isDirectory(target)) {
                deleteTree(target);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            boolean settings = DietPatcher.copySettings(shaderpacks, name, targetName);
            generatedThisStart.add(targetName);
            LOG.info("Bons DLSS Diet: wrote \"{}\" from \"{}\" at {} ({} files, {} shader files patched, {} vertex hooks{})",
                    targetName, name, when, r.files.size(), r.written, r.hooks, settings ? ", settings copied" : "");
        } catch (DietPatcher.PatchException e) {
            LOG.warn("Bons DLSS Diet: cannot patch \"{}\": {}", name, e.getMessage());
        } catch (IOException e) {
            LOG.warn("Bons DLSS Diet: could not write \"{}\": {}", targetName, e.toString());
        }
    }

    private static String marker(String source, String fingerprint) {
        return "{\n \"mod\": \"bons_dlss_diet\",\n \"patcher\": \"" + DietPatcher.PATCHER_VERSION + "\",\n \"patch_version\": "
                + DietPatcher.PATCH_VERSION + ",\n \"source\": \"" + source.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\n \"source_fingerprint\": \"" + fingerprint
                + "\",\n \"note\": \"Generated from the pack named above, which you installed; delete this folder to have it rebuilt. Not for redistribution.\"\n}\n";
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Oculus loaded its pack before this mod ran; if the selected pack is one this start wrote, load it now (once). */
    private synchronized void maybeReloadOculus() {
        if (reloadDone || generatedThisStart.isEmpty()) {
            return;
        }
        try {
            Path cfg = FMLPaths.CONFIGDIR.get().resolve("oculus.properties");
            if (!Files.isRegularFile(cfg)) {
                return;
            }
            Matcher m = SHADER_PACK_RX.matcher(Files.readString(cfg, StandardCharsets.UTF_8));
            if (!m.find()) {
                return;
            }
            String selected = m.group(1).trim();
            if (!generatedThisStart.contains(selected)) {
                return;
            }
            reloadDone = true;
            Class.forName("net.irisshaders.iris.Iris").getMethod("reload").invoke(null);
            LOG.info("Bons DLSS Diet: asked Oculus to reload \"{}\" (it was written after Oculus had loaded)", selected);
        } catch (Exception e) {
            LOG.warn("Bons DLSS Diet: Oculus reload not done: {}", e.toString());
        }
    }
}
