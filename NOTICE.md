# Notices

Bons DLSS Diet ships only its own code (MIT License, see LICENSE). It contains no part of any shader pack or other mod.

## Complementary Shaders

Complementary Shaders (Unbound) is by EminGT, https://www.complementary.dev, under the Complementary License Agreement (version 1.6 at the time of writing). It is not included. The mod reads the copy you installed and writes a modified copy for your own use, in the way Euphoria Patches works. The generated copy keeps Complementary's licence files. Do not redistribute the generated folder.

## Euphoria Patches

Euphoria Patches is by SpacEagle17, https://www.euphoriapatches.com, under the Euphoria Patches License (version 1.1 at the time of writing), which allows modified packs through a patching mechanism. It is not included; the Euphoria Patcher mod is required and produces the pack this mod builds on.

## Super Resolution

Super Resolution is by 187J3X1 (https://modrinth.com/mod/superresolution), MIT licensed. It is not included and is required at run time; it provides the upscaler and fetches NVIDIA's DLSS library on first start. This mod only writes the shader pack files that use its interface (superresolution.v3.json and the uniforms it defines).

## Oculus and Iris

Oculus (Iris for Forge) is required at run time and is not included. The mod calls Iris's public reload method by reflection; it links against nothing from Oculus.

## NVIDIA DLSS

DLSS and the DLSS library are NVIDIA's. Nothing from NVIDIA is included in this mod.

## Minecraft Forge

Minecraft Forge is LGPL-2.1; the mod is built against its published API.
