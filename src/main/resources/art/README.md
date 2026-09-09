# StarChem art resources

Renderer-owned authored art lives under this classpath root so assets work the same from the IDE and from packaged JARs.

Recommended layout:

- `ships/` — authored ship sprites and later masks/decals.
- `stations/` — station architecture, modules and static layers.
- `planets/` — surface/cloud/crater/atmosphere masks and textures.
- `backgrounds/` — nebula, dust and system-environment source imagery.
- `effects/` — reusable particle, impact and explosion sprites.
- `decals/` — faction/industrial markings and warning graphics.
- `materials/` — reusable panel, metal, ceramic and wear source textures.

## Ship convention

A ship type `Heavy Freighter Mk2` resolves by default to:

`/art/ships/heavy-freighter-mk2.png`

The conventional asset must:

- use transparency around the hull;
- face +X (right) in local renderer space;
- be centered in the image;
- include neutral physical hull materials rather than full-player-color paint;
- fit inside the existing 144x144 medium-LOD sprite-cache canvas after its configured scale is applied.

If the asset is absent or cannot be decoded, `ShipVisualRenderer` uses the deterministic procedural fallback. The fallback is intentionally neutral-hulled with player color restricted to an identification accent.

## Runtime rules

Always load images through `ArtAssetManager`; do not use filesystem-relative paths or call `ImageIO.read` from a per-frame renderer. The manager loads through the classpath, bounds successful and negative caches, and exposes basic cache statistics for graphics profiling.

Visual content is cosmetic. Assets and visual definitions must not alter hitboxes, HP, weapons, economy, production, networking or authoritative simulation state.
