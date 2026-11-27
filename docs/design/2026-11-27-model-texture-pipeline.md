# Model texture pipeline

Status: implemented (2026-11-27). One seam — `ModelTextureCatalog` — serves every
consumer (pack writer, texel baker, validator, diagnostics).

## Rendering backends

`RenderMode` (per block definition, and per multiblock level via
`MultiBlockLevel`) picks the backend; the router falls back to VIRTUAL when the
pack is unavailable.

| Backend | How it draws | When |
|---------|--------------|------|
| `pack_block` / `pack_item` | resourcepack model generated from the bbmodel; the client draws the authored texture | **default** (`auto` with no autoplay, and multiblock levels) |
| `virtual` | block displays per face with a vanilla `Material` each | opt-in: `"renderMode": "virtual"`; required for models animated per-bone (kinetic machines) |
| virtual + texel | `virtual` plus plates rebuilt from texture pixels | opt-in: `TEXEL_BAKING` (`ENABLED`, `MODE`) or `"texelMode"` in the model meta |

`AUTO` texel mode never plates a face whose texture name is itself a vanilla
block (`oak_planks`, `blackwool`); `ON` plates every pixel face; `OFF` never.

## Texture sources and priority

For a face texture name, the catalog takes the first source that yields pixels:

1. **FILE** — PNG on disk: `models/<name>.png`, beside the model, its parent
   folder, one level above (bounded by the models root), `<model-stem>.png` in
   each, and `textures/<name>.png` (pack layout). Case-insensitive. An external
   file overrides the copy embedded in the model.
2. **EMBEDDED** — base64 in the model's texture `source`.
3. **VANILLA** — no pixels; the name resolves to a `Material` (exact map →
   `matchMaterial` → suffix strip → aliases → legacy concatenations →
   word-order → fuzzy). Faces use that block, with `minecraft:block/<texture>`
   references in generated models.
4. **MISSING** — `WHITE_CONCRETE`, reported once per model.

Both `file` and `embedded` pin the texture's native `width`/`height`; UV windows
are scaled by the referenced texture's size, so a 64px canvas on a 16×16 vanilla
texture covers the cube face exactly as authored.

## Pack export

Generated item/block models reference model-scoped ids
`<model>_<texture>.png` (`water_wheel` + `oak_planks` → `water_wheel_oak_planks`,
registered under `textures/item|block/`). Two models may therefore carry
different art under the same texture name. Textures with pixels are written from
the catalog bytes (no temp files); vanilla-only faces point at
`minecraft:block/<texture>` and add no texture asset.

`/mineplus model debugspawn <model>` registers a debug pack item and attaches
the pack display after the async recompile, falling back to the virtual/texel
path when the pack cannot take the model.

## Diagnostics

`/mineplus blocks diagnose <id>` prints each texture as `name <- source`
(`file:<path>`, `embedded`, `vanilla:<MATERIAL>`, `missing`).

## Tests

`AssetPipelineTest`: texel mode defaults and overrides, vanilla/legacy name
resolution, `AUTO` keeps vanilla-named faces while plating custom art, pack id
naming (`<model>_<texture>`, namespace strip, sanitising), byte-backed texture
export. `MachineWiringTest`: a dry water-driven source reports 0 SU and leaves
wheel and shaft still; a reported SU turns both.
