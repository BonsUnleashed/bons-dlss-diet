# Building Bons DLSS Diet

No Gradle. The mod is two Java files compiled against the jars of an installed Forge 1.20.1 client.

You need:

- A JDK 17 or newer (`javac` on the PATH, or pass `--jdk`).
- Python 3.11 or newer for the build script.
- A Minecraft launcher installation with Forge 1.20.1-47.4.16 installed (the script reads `libraries/` from it). Any 47.4.x works; pass `--forge` for another version.

Build:

```
py build.py --minecraft "%APPDATA%\.minecraft"
```

Output: `dist/bons_dlss_diet-<version>.jar` and `build/manifest.json` with the SHA-256 of the jar and the list of entries. The jar carries only the compiled classes, `META-INF/mods.toml`, `pack.mcmeta` and the licence.

The patcher (`src/bons/dlssdiet/DietPatcher.java`) is free of Minecraft classes. It can patch a pack offline:

```
java -cp build/classes bons.dlssdiet.DietPatcher "<shaderpacks>/ComplementaryUnbound_r5.7.1 + EuphoriaPatches_1.8.6" "<target folder>"
```

Before each release the offline output is compared byte for byte with the reference patch the authors run on their own pack, on two different Complementary copies; the mod is released only when every file matches.
