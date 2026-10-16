package bons.dlssdiet.runtime;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Where NVIDIA's DLSS library (nvngx_dlss.dll) is taken from, first hit wins: the folder named in the config, then
 * config/bons_dlss_diet/ (a newer DLL dropped in by the player), then the copy shipped inside this jar, unpacked to
 * bons_dlss_diet/dlss/<sha>/. NGX itself checks NVIDIA's signature before loading it.
 */
final class DlssDll {
    static final String NAME = "nvngx_dlss.dll";
    private static final String BUNDLED = "/natives/windows-x64/" + NAME;

    private DlssDll() {
    }

    static Path find(DietConfig config, Path base) {
        if (config != null && !config.dlssDllDir.isEmpty()) {
            Path dir = Path.of(config.dlssDllDir);
            if (Files.isRegularFile(dir.resolve(NAME))) {
                return dir;
            }
            DlssRuntime.LOG.warn("Bons DLSS Diet: dlss_dll_dir={} has no {}", dir, NAME);
        }
        Path gameConfig = FMLPaths.CONFIGDIR.get();
        Path own = gameConfig.resolve("bons_dlss_diet");
        if (Files.isRegularFile(own.resolve(NAME))) {
            return own;
        }
        try (InputStream in = DlssDll.class.getResourceAsStream(BUNDLED)) {
            if (in == null) {
                return null;
            }
            byte[] data = in.readAllBytes();
            Path dir = base.resolve("dlss").resolve(NativeDlss.sha256(data).substring(0, 16));
            Path target = dir.resolve(NAME);
            if (!Files.isRegularFile(target) || Files.size(target) != data.length) {
                Files.createDirectories(dir);
                Path tmp = dir.resolve(NAME + ".tmp");
                Files.write(tmp, data);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            return dir;
        } catch (Exception e) {
            DlssRuntime.LOG.warn("Bons DLSS Diet: could not unpack the bundled {}: {}", NAME, e.toString());
            return null;
        }
    }
}
