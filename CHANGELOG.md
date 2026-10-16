# Changelog

## 1.1.0 - 2026-10-16

- Runs NVIDIA DLSS itself. Everything is in one jar, NVIDIA's DLSS library included; no other upscaler mod is needed.
- Writes `<pack> + DLSS` from Complementary Unbound r5.7.1 + Euphoria Patches 1.8.6: render-size buffers and jitter before the upscale, camera motion vectors, DLSS in place of TAA and FXAA, full-resolution texture detail, depth of field focused on the scene centre, screen-space reflections that find nearby objects.
- 35 to 44 percent more frames per second than without DLSS on an RTX 3080 at 4K (DLSS Quality, preset K).
- Light: one GPU pass in, DLSS, one pass out; about 2 ms of GPU time for DLSS at 4K and well under 1 ms of the render thread per frame. No GPU timing queries unless the timing log is on.
- Switches Forge's early loading window off once DLSS has run (about 15 % more FPS with Embeddium on NVIDIA cards, from the next start); `forge_early_window=keep` leaves it.
- The `+ DLSS` copy compiles with the Euphoria Patcher mod's update notice present (the notice is left out of it).
- Settings in `config/bons_dlss_diet.properties`: mode (quality, balanced, performance, ultra_performance, dlaa, off) and DLSS preset.
- Without an RTX card, a current driver or 64-bit Windows, the pack runs without DLSS and the log says why.
