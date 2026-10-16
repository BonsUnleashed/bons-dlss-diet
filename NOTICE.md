# Notices

Bons DLSS Diet's own code (the Java classes in `src/` and the native library built from `native/bons_dlss.cpp`) is released under the MIT License, see LICENSE. The jar also carries the NVIDIA components listed below, which are not under MIT.

## NVIDIA DLSS

This mod uses NVIDIA DLSS (NVIDIA DLSS SDK, https://github.com/NVIDIA/DLSS, commit 3749594). The jar contains NVIDIA's DLSS library `nvngx_dlss.dll` (signed by NVIDIA Corporation), and the mod's native library contains NVIDIA's NGX SDK static library. These components are NVIDIA's and are distributed under the NVIDIA RTX SDKs license, included in the jar as `natives/windows-x64/NVIDIA_RTX_SDK_LICENSE.txt`: they are distributed only as part of this mod, may not be reverse engineered and keep NVIDIA's notices. NVIDIA, DLSS, GeForce RTX and NGX are trademarks of NVIDIA Corporation. This repository does not contain NVIDIA's files; the build script fetches them from NVIDIA's repository at the pinned commit.

## Complementary Shaders

Complementary Shaders (Unbound) is by EminGT, https://www.complementary.dev, under the Complementary License Agreement (version 1.6 at the time of writing). It is not included. The mod reads the copy you installed and writes a modified copy for your own use, in the way Euphoria Patches works. The generated copy keeps Complementary's licence files. Do not redistribute the generated folder.

## Euphoria Patches

Euphoria Patches is by SpacEagle17, https://www.euphoriapatches.com, under the Euphoria Patches License (version 1.1 at the time of writing), which allows modified packs through a patching mechanism. It is not included; the Euphoria Patcher mod is required and produces the pack this mod builds on.

## Oculus and Iris

Oculus (Iris for Forge) is required at run time and is not included. The mod hooks three places in it with mixins (the shader pack's defines, its uniforms, the composite pass that reads the upscaled frame).

## Khronos Vulkan headers

The native library is compiled against the Khronos Vulkan headers (Apache-2.0 / MIT), fetched at build time; nothing of them ships in the jar.

## Minecraft Forge

Minecraft Forge is LGPL-2.1; the mod is built against its published API.
