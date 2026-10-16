![Bons DLSS Diet cover - AI-generated promotional concept art](https://github.com/BonsUnleashed/bons-dlss-diet/releases/download/v1.1.0/bons-dlss-diet-cover.png)

# Bons DLSS Diet

**Same picture. A third more frames.**

NVIDIA DLSS for Complementary Unbound shaders on Minecraft 1.20.1 Forge. Everything is in one jar.

**[Download 1.1.0](https://github.com/BonsUnleashed/bons-dlss-diet/releases/tag/v1.1.0)** for Minecraft 1.20.1 Forge · [Changelog](CHANGELOG.md) · [Build from source](BUILDING.md)

Requires Oculus with Embeddium, Complementary Unbound r5.7.1 with Euphoria Patches 1.8.6, Windows and an NVIDIA RTX card. Client only. Free, MIT licensed. Also on Modrinth.

## A third more frames per second

At 4K with shaders on, the graphics card does almost all of the work per pixel. DLSS Quality draws the world at two thirds of the screen size, less than half the pixels, and builds the full frame from them with NVIDIA's AI model. On an RTX 3080 that was 35 to 44 percent more frames per second in the scenes we measured, with the same detail.

| Scene | Without DLSS | Bons DLSS Diet | More frames |
|---|---:|---:|---:|
| Ocean view from the shore | 55 | 79 | +44% |
| High view over the sea | 70 | 94 | +35% |
| Forest from above | 41 | 59 | +44% |

RTX 3080, 3840x2066, Complementary Unbound r5.7.1 + Euphoria Patches 1.8.6 with Distant Horizons, in a 550-mod pack. Same saved world and settings, measured the same night, 16 October 2026; medians of the per-second frame rate over three runs. Your numbers depend on your card, resolution and settings. DLSS pays off when the graphics card is the limit; a scene limited by the processor gains little.

![Forest from above: left half without DLSS, right half with Bons DLSS Diet](media/forest-split.webp)

Want more? DLSS Quality is the default because it looks like the native frame. Balanced, Performance and Ultra Performance draw fewer pixels for more frames; DLAA keeps the full resolution and uses DLSS for anti-aliasing only.

## How it works

On the first start the mod writes a copy of the shader pack you already have, named `<your pack> + DLSS`, next to the original. Pick that copy in the shader menu. The original stays untouched and your shader settings carry over. The jar contains none of Complementary's code; it works like Euphoria Patches does.

With the copy selected, the shaders draw everything before the final passes at the render size, jittered, and write camera motion vectors. Bons DLSS Diet hands colour, depth and motion to NVIDIA DLSS through a small native library (Vulkan, sharing the frame with OpenGL on the graphics card, no copy through the processor) and puts the full-size result back where the shaders read it.

- **Full-resolution textures.** Roof tiles, leaves, water ripples and generated normals keep the detail of a native frame; the shaders pick the texture level for your real screen size.
- **Everything else keeps working.** Reflections, depth of field, volumetric light, clouds, Distant Horizons LODs, the End and the Nether.
- **DLSS replaces the shader's TAA and FXAA.** Moving mobs, turning Create gears and a sailing Valkyrien Skies deck stay crisp.
- **Light.** About 2 ms of graphics card time per frame for DLSS itself at 4K on an RTX 3080, and well under 1 ms of the game's render thread. It runs in the game's own OpenGL context: no second window, no restart dialog.
- **Safe.** No RTX card, an older driver, no 64-bit Windows: the pack simply runs without DLSS and the log says why. The game never crashes over it.

## Gets along with your other mods

Four small hooks, nothing else: three in Oculus (the shader pack's settings, its jitter uniform, and the step right before the pass that reads the upscaled frame) and one in Minecraft's window creation (it asks for OpenGL 4.6). Nothing is changed in any other mod.

We test it in a 550-mod pack, scene by scene, with DLSS on:

- **Create** cogs, fans and shafts turning (Flywheel instancing through Colorwheel) and **Valkyrien Skies** ships under sail.
- **Distant Horizons** LOD terrain to the horizon, **Alex's Caves** cave biomes, **Cosmic Horizons** space, the Nether and the End.
- Mobs, name tags, glowing and enchanted armour, the hand, signs and item frames, tinted-glass mirrors, portals, rain and night lights.
- Mid-game actions: window resize, shader reload, shaders off and on, resource reload, third person.
- Alongside Embeddium, Fancy World Animations, Entity Model and Texture Features, Fusion connected textures, Mekanism, Immersive Engineering, Alex's Mobs, Supplementaries and 500 more.

## What you need

| | Version | Why |
|---|---|---|
| Minecraft Java 1.20.1 with Forge | 47.4 or newer | |
| Oculus with Embeddium | 1.8.0 | runs the shaders |
| Complementary Unbound with Euphoria Patches | r5.7.1 with Euphoria Patches 1.8.6 (the Euphoria Patcher mod) | the shaders being patched; this exact pair is the one we test |
| An NVIDIA RTX graphics card | 20 series or newer, a current driver | DLSS runs on its tensor cores |
| Windows | 64-bit | DLSS's library is for Windows |

NVIDIA's DLSS library is included in the jar.

## Install

1. Put `bons_dlss_diet-1.1.0.jar` in your mods folder with the mods above and start the game.
2. Options, Video Settings, Shader Packs: choose `ComplementaryUnbound_r5.7.1 + EuphoriaPatches_1.8.6 + DLSS`.
3. Play. DLSS is on. Restart the game once when convenient for the full frame rate (see below).

Client only. Nothing is needed on a server.

## Settings

`config/bons_dlss_diet.properties`, read whenever the shader pack loads (shader menu or the reload key):

- `mode`: `quality` (render at 67 %, the default), `balanced` (58 %), `performance` (50 %), `ultra_performance` (33 %), `dlaa` or `off`.
- `preset`: the DLSS model, `K` by default (the one that looked best on our RTX 3080), or `J`, `L`, `M`, `default`.
- `forge_early_window`: `off` (the default) or `keep`, see below.
- `timing_log_seconds`: log the GPU time of the DLSS passes every N seconds (0 = off).

## Good to know

- **Forge's early loading window costs about 15 % FPS** with Embeddium on NVIDIA cards: it opens the OpenGL context before Embeddium's driver workaround can apply. We measured this without DLSS too (forest 36 FPS with it, 42 without). Once DLSS has run, Bons DLSS Diet switches it off in `config/fml.toml` (`earlyWindowControl=false`) and tells you in chat; the next start is faster. You then see Minecraft's own loading screen instead of Forge's early one. Set `forge_early_window=keep` to leave it.
- The log says what happened: `Bons DLSS Diet: wrote "... + DLSS" from ...`, `DLSS on for ...`, or why DLSS stayed off.
- Other Complementary versions, Complementary without Euphoria Patches and Complementary Reimagined are left untouched for now; a pack the mod does not recognise is never changed.
- After a Complementary or Euphoria Patches update the generated folder is rebuilt on the next start as soon as the mod supports the new pair; until then it stays as it is.
- Iris on 1.20.1 has no per-object motion vectors, so fast-moving mobs and ships carry only the camera's motion. DLSS's own history checks cover them; we saw no ghosting in our scenes.
- With the Euphoria Patcher mod 1.8.6 and Oculus 1.8.0, Euphoria's "update available" notice can stop the plain Euphoria pack from compiling (final.fsh, "undefined variable _10"). The `+ DLSS` copy is not affected: Bons DLSS Diet leaves that notice out of it.
- To use a newer NVIDIA DLSS library, put its `nvngx_dlss.dll` in `config/bons_dlss_diet/`.

## Credits and licence

Complementary Shaders by EminGT ([complementary.dev](https://www.complementary.dev)) and Euphoria Patches by SpacEagle17 ([euphoriapatches.com](https://www.euphoriapatches.com)) are not included. The mod patches the copies you installed, in the way Euphoria Patches itself works, and the patched copy keeps their licence files.

Uses NVIDIA DLSS. NVIDIA's DLSS library (`nvngx_dlss.dll`) and the NGX SDK code in the native library are NVIDIA's, under the NVIDIA RTX SDKs licence included in the jar; NVIDIA and DLSS are trademarks of NVIDIA Corporation. See [NOTICE.md](NOTICE.md).

Created by BonsUnleashed. Developed with AI assistance. The mod's own code is released under the [MIT License](LICENSE); the source and build script come with every release.

Cover art: AI-generated promotional concept art. Modrinth icon: detail from Frank R. Paul's cover of Amazing Stories, April 1928, public domain in the United States (publication term expired); scan from the Internet Archive, cropped and resized, original colour.
