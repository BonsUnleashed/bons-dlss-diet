# Building Bons DLSS Diet

No Gradle. One Python script fetches NVIDIA's DLSS SDK files and the Vulkan headers, compiles the native library with MSVC and the Java code against the jars of an installed Forge 1.20.1 client, and packages the jar.

You need:

- Windows 64-bit with the Visual Studio Build Tools (the "Desktop development with C++" workload, MSVC x64).
- A JDK 17 or newer (`javac` on the PATH, or pass `--jdk` with the JDK's home folder).
- Python 3.11 or newer.
- A Minecraft launcher installation with Forge 1.20.1-47.4.16 and Oculus 1.8.0 installed (the script reads `libraries/` and `mods/oculus-mc1.20.1-1.8.0.jar` from it). Any 47.4.x works; pass `--forge` for another version.

Build:

```
py build.py all --minecraft "%APPDATA%\.minecraft"
```

The steps can also run one by one: `deps` (NVIDIA DLSS SDK files at commit 3749594 and Khronos Vulkan-Headers at vulkan-sdk-1.4.341.0 into `third_party/`, each file checked against the pinned commit), `native` (MSVC; from an x64 Native Tools Command Prompt, or the script finds `vcvars64.bat` through vswhere) and `jar`.

Output: `dist/bons_dlss_diet-<version>.jar` and `build/manifest.json` with the SHA-256 of the jar and of the native library and the list of entries. The jar carries the compiled classes, the mixin config, the native library, NVIDIA's `nvngx_dlss.dll` with its licence, `META-INF/mods.toml`, `pack.mcmeta`, the licence and the notices.

The shader patcher (`src/bons/dlssdiet/DietPatcher.java`) is free of Minecraft classes. It can patch a pack offline:

```
java -cp build/classes bons.dlssdiet.DietPatcher "<shaderpacks>/ComplementaryUnbound_r5.7.1 + EuphoriaPatches_1.8.6" "<target folder>"
```
