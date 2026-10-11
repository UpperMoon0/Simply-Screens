# Unreleased: shader image colors

- Preserve fully opaque image source colors with a depth-tested post-shader pass
  on the Iris/Oculus rendering path, while retaining the original world draw's
  surrounding bloom.
- Preserve shader-processed semi-transparent/edge samples without applying alpha
  twice. Arbitrary depth rewriting, refraction and later postprocessing remain
  shader-pack limitations; see the precise contract in `tools/shader/README.md`.
- Add checksum-pinned, opt-in Sodium/Embeddium, Iris/Oculus, Complementary and
  supported Extra development stacks, plus actual shader framebuffer regression
  controls. Runtime validation is required before release.
