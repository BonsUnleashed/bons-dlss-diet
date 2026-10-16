package bons.dlssdiet.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * JNI surface of bons_dlss_diet.dll (natives/windows-x64 in the jar; source runtime/native/bons_dlss.cpp). Every call
 * except {@link #load} must come from the render thread with the OpenGL context current.
 */
final class NativeDlss {
    static final int ABI = 2;
    private static final String RESOURCE = "/natives/windows-x64/bons_dlss_diet.dll";
    private static boolean loaded;
    private static String loadError;

    private NativeDlss() {
    }

    static native int nAbi();

    static native int nInit(String dataDir, String dlssDir, int quality, int preset);

    static native int nSetMode(int quality, int preset);

    /** timing: GPU timestamp queries for the timing log (off = none issued); lean: hand-off without per-frame barriers. */
    static native void nSetOptions(boolean timing, boolean lean);

    /** dhDepthTex 0 = main depth only; otherwise main/DH projection m22, m32 and the combined depth's near/far. */
    static native int nDispatch(int colorTex, int depthTex, int motionTex, int targetTex, int renderW, int renderH,
                                int outW, int outH, float jitterX, float jitterY, boolean reset, float frameMs,
                                int dhDepthTex, float mainM22, float mainM32, float dhM22, float dhM32, float near, float far);

    static native void nReleaseFrameResources();

    static native void nShutdown();

    static native String nLastError();

    static native String nPollLog();

    static native String nDevice();

    static native double[] nTakeStats();

    /** Extracts the DLL (content-addressed, reused when already there) and loads it. Returns null or the reason. */
    static synchronized String load(Path dir) {
        if (loaded) {
            return null;
        }
        if (loadError != null) {
            return loadError;
        }
        try (InputStream in = NativeDlss.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return loadError = "the native library is missing from the jar";
            }
            byte[] data = in.readAllBytes();
            String sha = sha256(data);
            Path target = dir.resolve("bons_dlss_diet-" + sha.substring(0, 16) + ".dll");
            if (!Files.isRegularFile(target) || !sha.equals(sha256(Files.readAllBytes(target)))) {
                Files.createDirectories(dir);
                Path tmp = dir.resolve(target.getFileName() + ".tmp");
                Files.write(tmp, data);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            System.load(target.toAbsolutePath().toString());
            int abi = nAbi();
            if (abi != ABI) {
                return loadError = "native library ABI " + abi + ", expected " + ABI;
            }
            loaded = true;
            return null;
        } catch (IOException | UnsatisfiedLinkError | SecurityException e) {
            return loadError = "could not load the native library: " + e;
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
