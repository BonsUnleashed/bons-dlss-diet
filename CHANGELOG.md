# Changelog

## 1.0.0 - 2026-10-15

First release.

- Writes `<pack> + DLSS` from Complementary Unbound r5.7.1 + Euphoria Patches 1.8.6, for Super Resolution's shader pack interface v3 (DLSS; FSR 2 and 3 and XeSS use the same interface).
- Patch version 3: render-size buffers and jitter before the upscale, camera motion vectors, DLSS in place of TAA and FXAA, full-resolution texture detail, depth of field focused on the scene centre, screen-space reflections that find nearby objects.
- The generated folder is rebuilt when the source pack changes and left alone when it is up to date; the original pack and its settings file are never touched.
