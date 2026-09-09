# Validation fixtures

Files in this directory exist only to exercise failure handling in `ArtAssetValidator`.
They are not production art and must not be referenced by gameplay renderers.

`corrupt-image.png` is intentionally invalid so CI verifies that a corrupt supported-format asset logs once, falls back deterministically, and does not crash rendering.
