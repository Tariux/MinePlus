# Model texture pipeline

Status: implemented (2026-11-27). Supersedes the ad-hoc resolution in
`BbModelImporter.materializeEmbeddedTextures` + `TextureImageStore`.

## The problem

Blockbench models reach the plugin in five shapes, and the old code handled
each with a different, partial mechanism:

| # | Shape | Example in this repo | What the old code did |
|---|-------|----------------------|-----------------------|
| 1 | External PNG referenced by `path` / `relative_path` | `examples/mineplus-gun/**/models/*.bbmodel` + sibling `<gun>.png` | Looked for a PNG only *beside the .bbmodel*, so gun art one folder up was never found |
| 2 | Base64 pixels in the texture entry's `source` | `water_wheel_weak/strong`, `shaft`, all guns, cannon | Decoded and wrote PNGs onto disk during import, then re-read them; models parsed from a stream lost the pixels entirely, and same-named textures from different models overwrote each other |
| 3 | Vanilla texture name, no pixels | any free model using `blackwool`, `concrete_black.png`, `minecraft:block/stone` | Material resolver handled most names but not concatenated legacy names (`blackwool`) or a `.png` suffix |
| 4 | Texel art (texture painted texel-by-texel for the model) | opt-in per model, e.g. `texelMode: AUTO` on the wine props | Baked only when a PNG was resolvable; embedded-only models fell back to vanilla block materials |
| 5 | Any mix of 1–4 in one model | cannon (27 embedded vanilla-named textures), cutout + solid mixes | Any single missing texture dropped the whole model to material rendering |

The visible symptom was models rendering as vanilla `oak_planks` / `stone` /
`WHITE_CONCRETE` even though the authored art existed inside the `.bbmodel`.

## Design

One seam: **`ModelTextureCatalog`**, built once per registered model. Everything
that needs a texture (texel baker, pack export, validator, diagnostics) asks the
catalog, never the filesystem directly.

### Source kinds and priority

For a texture *name* (`face.textureName()` after the importer resolved numeric
or uuid references), the catalog resolves the first available source:

1. **EMBEDDED** — base64 `source` bytes carried on the model itself.
   Highest priority: it is the art the author saw in Blockbench, independent of
   install layout, read-only jars, or another model embedding a same-named file.
2. **FILE** — PNG on disk, searched in order:
   `models/<name>.png`, beside the model, the model's parent folder, one level
   above that (bounded by the models root), `<model-stem>.png` in each of those,
   `textures/<name>.png` (pack layout). Case-insensitive.
3. *(no pixels)* **VANILLA** — the name maps to a Bukkit `Material` via
   `TextureMaterialResolver` (exact map → `matchMaterial` → suffix strip →
   aliases → fuzzy). The face renders with that block material.
4. **MISSING** — nothing matched; material fallback (`WHITE_CONCRETE`), recorded
   in the per-model report and logged once.

A source that yields pixels pins the texture's native size to the authored
`width`/`height` (or the PNG's own size). UV windows are interpreted against
that size, so a 64×64 gun map and a 16×16 wheel map both line up exactly as
authored.

### Per-face decision, not per-model

`TexelSurfaceBaker` decides per face:

- texture has pixels → texel plates (subject to the model's texel mode, budget and occlusion);
- texture has no pixels → that face only keeps its material.

**Vanilla names win under AUTO.** A block display can only show a vanilla
material or texel plates, so `AUTO` plates exactly the art it cannot otherwise
show: a face whose texture name is a vanilla block (`oak_planks`, `spruce_log`,
`blackwool`) keeps that block — the water wheel's embedded wood art renders as
wood — while custom-named art (gun maps) is plated so it appears exactly as
authored. `ON` plates every pixel face regardless, `OFF` never plates.

### Global default

`TEXEL_BAKING.ENABLED` defaults to `true` and `TEXEL_BAKING.MODE` to `AUTO`
(vanilla names keep their block, custom art is plated). `ON` forces every pixel
face to plates; `OFF` gives a flat-material look.

### Performance and memory

- Resolution, base64 decode and PNG decode happen once per model, on the
  existing async bake executor; results are cached by `(modelKey, textureName)`.
- Decoded rasters are held only while the model is registered; the catalog is
  discarded with it.
- No new per-tick work; plate counts obey the existing adaptive budget.

### Pack and validator

`VirtualBlockManager.textureBytes(modelKey, name)` exposes catalog bytes so the
resource-pack writer emits embedded textures straight from memory (no temp
files), and `ModelAssetValidator` no longer reports an embedded texture as
missing.

## Failure reporting

`/mineplus blocks diagnose <id>` prints each texture as
`name <- source` (`embedded`, `file:<path>`, `vanilla:<MATERIAL>`, `missing`),
so an author can see immediately which of the five shapes a model used and
what the pipeline chose. Warnings name the model and texture, once per model.

## Tests

`AssetPipelineTest` covers the pipeline rules: default texel mode and meta
overrides, global disable, vanilla-name resolution (`oak_planks`), legacy
concatenated names (`blackwool`, `orangeconcretepowder`), plus the existing
asset dependency/cycle/incremental cases. In-game checks (wheel as wood, gun
art exact, cannon vanilla-named faces) are listed in the test notes.
