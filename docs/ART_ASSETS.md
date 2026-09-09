# StarChem Art Assets

StarChem repository-backed art lives under `src/main/resources/art/` and is loaded through `ArtAssetCache`. Do not load gameplay art through filesystem paths such as `src/main/resources/...`; those paths do not exist once the application is packaged into a JAR.

## Directory layout

Use these categories unless an asset clearly belongs elsewhere:

- `art/ships/` — ship-specific overlays, masks, decals, and reusable hull components.
- `art/stations/` — station surface components and station-specific decals.
- `art/planets/` — crater, terrain, cloud, atmosphere, and surface masks.
- `art/backgrounds/` — starfield, nebula, dust, and other tiling/background elements.
- `art/effects/` — reusable particles, flashes, beams, exhaust, and impact sprites.
- `art/decals/` — warning marks, faction-neutral signage, stripes, hazard patterns, and labels.
- `art/materials/` — small reusable surface textures such as metal panels, wear, scratches, plating, and windows.

Keep reusable components small. Prefer a 32–256 px tile/mask/decal that can be composed in Java2D over a large one-off texture when the smaller asset produces the same result.

## Supported formats

`ArtAssetCache` currently accepts formats decoded by the JDK ImageIO stack that are appropriate for repository art:

- PNG (`.png`) — preferred; supports lossless RGBA and is the default for textures, masks, decals, and sprites.
- GIF (`.gif`) — permitted for legacy/special-purpose static assets; animation is not part of the art pipeline.
- JPEG (`.jpg`, `.jpeg`) — use only for opaque imagery where compression artifacts are acceptable.
- BMP (`.bmp`) — supported by the loader but normally avoid it because PNG is smaller and supports alpha.

Do not add a new format by bypassing the cache. Extend `ArtAssetCache` and this document together.

## Naming

Use lowercase kebab-case names with a numeric variant when useful:

- `hull-panels-01.png`
- `warning-stripes-02.png`
- `crater-mask-03.png`
- `engine-glow-small.png`

Paths passed to the cache are relative to `art/`, for example:

```java
BufferedImage panels = ArtAssetCache.image("materials/hull-panels-01.png");
```

Absolute paths, parent traversal (`..`), empty path segments, and unsafe characters are rejected.

## Alpha and color

For PNG assets:

- Preserve straight RGBA transparency; transparent pixels should have alpha 0.
- Avoid large fully transparent borders around small sprites or decals.
- Surface masks and overlays should generally be neutral enough to compose over player/faction colors.
- Do not bake player colors into reusable material assets unless the image is intentionally faction-specific.
- Keep texture contrast restrained; tactical silhouettes, selection state, and gameplay readability take priority over surface detail.

## Size and memory guidelines

`ArtAssetCache` caches decoded source images, not every rotation/tint/zoom result. Transformed render products belong in renderer-specific caches such as `ShipSpriteCache`.

The default source-art budget is 24 MiB and 256 entries. It can be adjusted for diagnostics with:

- `-Dstarchem.artCacheBytes=<bytes>`
- `-Dstarchem.artCacheEntries=<count>`

Any single decoded asset larger than the configured byte budget is rejected to the deterministic fallback. Missing and failed assets are negative-cached so a render loop does not repeatedly hit JAR/resource I/O.

Recommended starting sizes:

- tiling materials/masks: 32–128 px
- decals/icons/effect sprites: 32–256 px
- ship/station overlays: normally <= 512 px unless there is a measured need
- backgrounds: compose smaller tiles/layers where possible rather than shipping giant unique images

## Failure behavior

Missing, corrupt, unsupported, oversized, or unsafe assets never throw through gameplay rendering. `ArtAssetCache` logs a warning once for the failed key and returns a deterministic magenta/dark checker fallback.

Renderers that can safely preserve an existing procedural/vector appearance should check `ArtAssetCache.isFallback(...)` and skip the authored-art layer. `ShipSurfaceArt` follows this rule, so a missing hull material cannot make ships disappear or crash rendering.

## Adding an asset

1. Put the file in the appropriate `src/main/resources/art/<category>/` directory.
2. Keep the file small and reusable where practical.
3. Load it only through `ArtAssetCache`.
4. Preserve the renderer's deterministic procedural fallback when possible.
5. Verify both unpackaged and JAR-backed loading before merging.

The included smoke validator can be run after compiling resources:

```text
java -cp build/classes/java/main:build/resources/main com.tndmadman.rts.ArtAssetValidator
```

To explicitly prove packaged-JAR resolution:

```text
./gradlew jar
java -cp build/libs/StarChem.jar com.tndmadman.rts.ArtAssetValidator --require-jar
```

On Windows, use `;` instead of `:` in the first command's classpath.

## Current production consumer

`ShipSurfaceArt` loads `art/materials/hull-panels-01.png`, clips it to the deterministic `ShipShape` hull, and applies it as a small tiling surface layer. Both the detailed `UnitRenderer` path and the medium-LOD `ShipSpriteCache` path consume that same repository asset. Far-zoom markers remain intentionally texture-free.
