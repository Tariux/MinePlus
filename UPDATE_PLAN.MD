# UPDATE_PLAN — Texel Surface Baking (Pixel-Accurate Vanilla Texturing)

**Target:** Mineplus Core (`src/main/java/com/mineplus/`)
**Status:** Planned — no code written yet. This document is the implementation contract.
**Scope:** Core engine update. One new render strategy in the virtual-rendering pipeline.
**Constraints:** 100% vanilla server + vanilla clients. Zero resource pack. Zero client mod.
Zero heavyweight or non-standard dependencies (existing libs only: Gson, JOML, Bukkit API,
`javax.imageio` from the JDK).

---

## 1. Mission statement

Mineplus renders models as `BlockDisplay` entities. Today, a face's texture is rendered by
selecting **one** vanilla material per face (`TextureMaterialResolver` filename → material,
`DisplayEmitter` per-face plates). This is a one-color-per-face approximation: a model whose
Blockbench texture contains a gradient, stripes, lettering, or any multi-pixel artwork loses
all of it.

This update introduces **texel surface baking**: a load-time transformer that decomposes a
face's UV-mapped texture into per-pixel *texels*, quantizes each texel's color to the
closest visually-flat vanilla block, and emits one plate per merged run of same-color
texels. The player sees the model's real texture **reconstructed pixel-by-pixel out of
vanilla blocks** — the same Blockbench-authored image, rebuilt from the default palette.

Design in one sentence: *the model loader becomes a transformer pipeline*
(`import → bake → emit`) where baking turns texture pixels into geometry-colored plates,
and every expensive artifact is computed once at load and cached.

---

## 2. The core idea, precisely

A face's UV window (`BakedFace.u1/v1/u2/v2`) maps a texture image onto the face
rectangle. The **effective pixel grid** of the face is its physical size in model pixels
(`FaceUvAnalyzer.facePixelSize`), NOT the PNG's resolution:

```
texelW = facePixelWidth  / uvWindowWidth    (in texture pixels)
texelH = facePixelHeight / uvWindowHeight
```

- Texture 16×16 on a 16px face → 1 texel = 1 model pixel (1:1).
- Texture 4×4 on a 16px face → 1 texel = 4×4 model pixels (upscale; one plate covers a
  4×4 area — correct, the artist drew big pixels).
- Texture 32×32 on a 16px face → 1 texel = 0.5 model pixels (downsample: the 2×2 block of
  texture pixels behind each model pixel is **area-averaged** into one color; sub-pixel
  detail is invisible at render distance anyway, and emitting it would multiply entity
  count for nothing).

Each texel then:
1. Samples the source PNG (nearest or area average, see §6.3).
2. Quantizes to the nearest color in the **vanilla flat-block palette** (§5) using a
   perceptual distance metric.
3. Becomes a plate covering exactly its rectangle of the face.
4. Adjacent same-quantized-color texels merge into maximal rectangles **before** any
   entity is spawned (§7).

Uniform faces degrade automatically to exactly today's behavior: all texels quantize to
one color, merge into one plate. The new strategy is a strict generalization of the
existing `FULL` strategy in `FaceUvAnalyzer`.

---

## 3. Current architecture (verified against sources — do not re-derive)

Relevant pipeline stages, in load/render order:

| Stage | Class | Responsibility |
|---|---|---|
| Import | `BbModelImporter` | `.bbmodel` → `VirtualModel` (cubes, faces, UV windows, texture name table). Textures resolve by `path → relative_path → name` (last path segment, extension stripped). Base64 `source` PNGs are **skipped**, never stored. |
| Registry | `VirtualBlockManager.loadModelDefinitions()` | Scans `plugins/Mineplus/models/` for `*.bbmodel` (via `ModelImportCoordinator`), registers models + `ModelMeta`, builds texture-resolution reports. |
| Material resolve | `TextureMaterialResolver` | Tiered name→`Material` pipeline, JVM-lifetime cache, fallback `WHITE_CONCRETE`. |
| UV analysis | `FaceUvAnalyzer` | Classifies each face's UV window: `TILE` (wrapping), `CROP_HALF`, `FULL`. |
| Emission | `DisplayEmitter.emitCube(cube, perFaceRendering)` | Base display in majority material + thin per-face plates for minority materials. Plates: `PLATE_THICKNESS = 1/64`, `EPS_OUT = 1/1024`, exact edge coincidence. |
| Spawn | `VirtualBlockManager.spawnModel()` | Applies global rotation + pivot conjugation `t' = R·t + (C−DO) − R·(C−DO)` per cube/display, spawns `BlockDisplay` entities. |
| Collision | `VoxelOccupancyCalculator` | Unaffected by this update (geometry unchanged — plates add no barrier cells). |

Key existing facts the plan builds on:
- `BakedFace` already carries the exact UV window, rotation, texture reference and name.
- `DisplayEmitter` already knows how to build a plate hugging a face rectangle with
  anti-z-fight offset — the texel baker reuses that math with a **sub-rectangle** of the
  face, not the full rectangle.
- `VirtualModel.Resolution` (bbmodel `resolution.width/height`) is already parsed — it
  defines the artist's intended texture pixel density (default 16×16).
- `settings.mp.yml` → `VirtualRenderingSettings` already threads per-model overrides via
  `ModelMeta` (`textureMode`, `collisionMode`, `originMode`, `autoplay` exist; we add
  `texelMode`).

---

## 4. New pipeline stage: `TexelSurfaceBaker`

Position: **between import and emission**, executed at load time only.

```
BbModelImporter
      │  VirtualModel (cubes + faces + texture names)
      ▼
TexelSurfaceBaker            ← NEW (this update)
      │  per-(model, face) TexelSurfacePlan:
      │    - effective grid dimensions
      │    - per-texel quantized palette index
      │    - merged rectangle list [(gridX, gridY, gridW, gridH, paletteIndex)]
      │    - emissive texel set (from light_emission or palette entry)
      ▼
DisplayEmitter (extended)
      │  emitCube consumes TexelSurfacePlan when strategy = TEXEL
      ▼
VirtualBlockManager.spawnModel()   (unchanged spawn path)
```

New classes (package `com.mineplus.infrastructure.virtual.texel`):

| Class | Responsibility |
|---|---|
| `TexelPalette` | The curated vanilla flat-block palette + perceptual color matching (§5). Immutable singleton; load-time only. |
| `TexelSurfaceBaker` | Stateless: `BakedFace + BakedCube + BufferedImage → TexelSurfacePlan`. Runs at model load. |
| `TexelSurfacePlan` | Immutable record: grid size, merged rectangles, per-rect palette entry, emissive flags. Serializable-ish (recomputed on reload; not persisted). |
| `TextureImageStore` | Loads/decodes/caches PNGs from the models folder by texture name (§6.1). |
| `TexelMerge` | 2D greedy rectangle merging over the quantized grid (§7). |

Modified classes:

| Class | Change |
|---|---|
| `DisplayEmitter` | New emission path: when the face's plan exists and strategy is `TEXEL`, emit one plate per merged rectangle using the existing plate math parameterized by sub-rectangle. Plate count guard (§9). |
| `FaceUvAnalyzer` | New `Strategy.TEXEL` + the decision rule (§6.4). |
| `VirtualBlockManager` | After `registerModel`, invoke the baker for models whose meta enables texel mode; cache plans per model key; invalidate on reload (already cleared with `loadedModels`). |
| `ModelMeta` | New optional field `texelMode` (`AUTO`/`ON`/`OFF`) + `texelDetail` (§6.3). |
| `VirtualRenderingSettings` | New global `texelMode` (default `AUTO`) + `maxTexelPlatesPerInstance` budget (§9). |
| `ConfigManager` + `settings.mp.yml` | New `TEXEL_BAKING` section (§11). |
| `/mineplus model info` | Report texel plan stats: grid, palette usage histogram, merged plate count, budget verdict (§12). |

---

## 5. `TexelPalette` — the color-linking subsystem

This is the accuracy-critical core (the "system that links colors to low-detail blocks
with high precision"). It must be a **standalone, testable unit**: input RGB, output
`Material`. Everything else only consumes it.

### 5.1 Palette membership (curated, ~50 entries)

Selection criteria — **visually flat under arbitrary scaling**, i.e. the block's texture
noise must average to its perceived color and never read as a pattern when a plate is
small:

- **IN — 16 concretes:** saturated, flat, hue-complete. The backbone.
- **IN — 16 concrete powders:** same hues, matte granular finish; perceived colors sit
  slightly off the concrete equivalents — that gap is *useful* (fills holes in the palette
  hull).
- **IN — 16 terracottas:** muted/desaturated band the concretes don't cover.
- **IN — gap fillers (verify visually, ~4–6):** `SNOW_BLOCK` (near-white),
  `SMOOTH_STONE` (neutral gray), `WHITE_WOOL`/`LIGHT_GRAY_WOOL` only if their weave noise
  is acceptable at small scale (test at 1×1 texel; exclude if it reads as texture),
  `GLOWSTONE` (emissive warm tone), `SEA_LANTERN` (emissive cool tone).
- **OUT — glazed terracottas** (directional 4-way pattern — reads as artifacts),
  **stained glass** (transparency/blend), **wool beyond the two candidates** (weave
  visible at small scale), **planks/logs/sandstone-family** (directional grain — see the
  neutral-band note below), anything animated or emissive-patterned.

Rationale for excluding grain materials: a plate smaller than ~4×4 model pixels shows a
*random slice* of a grainy texture — neighboring plates then look inconsistently shaded,
which reads as noise, not color. Flat materials have ≤ ±5 RGB noise around their mean, which
converges to the mean visually. (Optional §10.2 extension: allow grain materials only for
rectangles ≥ 4×4 texels, where the grain renders *complete*.)

### 5.2 Color profiles — measured, not documented

Palette entries must carry the **average RGB of the actual client texture**, not the wiki
nominal value. Two sourcing options, in priority order:

1. **Bundled profile table (default):** hardcoded float triples measured once from the
   vanilla resource pack textures (average of the 16×16 block texture, alpha-weighted).
   Maintain as a static table in `TexelPalette` with a comment per entry. This keeps the
   feature dependency-free and deterministic.
2. **Live re-measurement (optional, off by default):** if a vanilla client jar is present
   on the server (`libraries/` or a configured path), extract `assets/minecraft/textures/block/*.png`
   and average them at startup. Only worth doing if profile drift across MC versions
   becomes a real complaint; otherwise skip — vanilla block colors have been stable for
   years.

### 5.3 Perceptual distance metric

Nearest-neighbor by raw Euclidean RGB is not acceptable: the palette is small and every
mismatch is visible. Use a weighted RGB distance in the comparing space:

```
dist² = 2·Δr² + 4·Δg² + 1·Δb²        (integer-friendly "redmean-lite")
```

Better (still dependency-free, ~20 lines of math): **redmean** (from the classic
"low-cost approximation of perceptual color distance"):

```
r̄ = (r₁+r₂)/2
dist² = (2 + r̄/256)·Δr² + 4·Δg² + (2 + (255−r̄)/256)·Δb²
```

Redmean is the specified metric. Do NOT adopt full CIE/Oklab for v1 — the palette is
~50 points; exhaustive scan with redmean is both fast enough (branch-free, no allocation)
and perceptually adequate at this palette size. Revisit only if side-by-side comparisons
show systematic hue errors (e.g. oranges drifting to browns — if so, the fix is palette
entries, not a fancier metric).

Implementation notes:
- Palette stored as `float[]` triples in a fixed order + parallel `Material[]` — the
  matcher is a linear scan with no allocation, safe to call per-texel at load time
  (hundreds of thousands of calls worst case: 16×16×6 faces×N cubes — still trivial).
- Exact-match fast path: quantize incoming RGB to a 5-bit-per-channel lookup cache
  (32³ = 32768 entries, int-indexed) filled lazily with palette indices. Turns repeated
  quantization into an array read. This cache is the *only* mutable state and lives inside
  `TexelPalette`.

### 5.4 Emissive handling

`light_emission` from the bbmodel is already parsed per cube (`BakedCube.lightEmission`,
0–15). Rule:
- Texel plates inherit the cube's `lightEmission` → `Display.Brightness(emission, 15)`
  exactly like today's base display.
- Palette entries `GLOWSTONE`/`SEA_LANTERN` do NOT force brightness by themselves —
  brightness follows the model's emission data. Selecting glowstone for its *color* must
  not make a matte model glow.

---

## 6. `TextureImageStore` + sampling rules

### 6.1 Sourcing images

Textures are looked up **by name** next to the model file, mirroring the importer's
resolution chain:

```
plugins/Mineplus/models/<textureName>.png        (name = last path segment, ext stripped)
```

Rules:
- Cache decoded `BufferedImage`s by lowercase name for the JVM lifetime of a model set;
  cleared on `reloadModelDefinitions()` together with the model cache.
- Missing PNG → the face falls back to the current `FaceUvAnalyzer` strategy (per-face
  material). Bake failure must never break model load. Log once per model via
  `DebugLogger`.
- PNG decode via `javax.imageio.ImageIO` (JDK, zero dependencies). Guard: reject images
  > 4096×4096 (sanity; a 16×16 texture is the expected case).
- Future extension point (NOT in v1): `.bbmodel`-embedded Base64 `source` textures. The
  streaming importer skips them for performance; supporting them means a second,
  non-streaming parse pass of just the `textures` array. Only add if artists actually
  ship embedded textures — the file-next-to-model convention is the documented workflow.

### 6.2 UV window → texel grid mapping

For face `F` on cube `C` with UV window `[u1,v1,u2,v2]` (texture pixels) and face physical
size `(wPx, hPx)` model pixels (`FaceUvAnalyzer.facePixelSize`):

```
gridW = clamp(round(wPx · |u2−u1| / texW ), 1, 64)     texW = texture image width
gridH = clamp(round(hPx · |v2−v1| / texH ), 1, 64)
```

Wait — the effective grid is measured against the **UV window's span in texture pixels**,
not the whole texture, because that is what the window stretches across the face:

```
winW = |u2−u1|    (texture px)      winH = |v2−v1|
gridW = max(1, round(wPx))   ← NO. Correct formula below.
```

**Correct derivation (implement exactly this):** the window covers the face, so one model
pixel of the face corresponds to `winW / wPx` texture pixels horizontally. The texel grid
is the face's pixel grid:

```
gridW = round(wPx)      gridH = round(hPx)        (face resolution — the render ceiling)
```

and texel `(i, j)` (grid coords, `i` along U, `j` along V) samples the texture at:

```
u(i) = u1 + (i + 0.5) · winW / gridW        (nearest → floor; supersampled → average, §6.3)
v(j) = v1 + (j + 0.5) · winH / gridH
```

Consequences (all intended):
- 16px face, any texture resolution → 16×16 grid; a 4×4 texture simply makes each texel
  sample a 4-px-wide stretch (constant color → exact upscale). A 32×32 texture makes each
  texel average a 2×2 region (downsample).
- The grid is **bounded by the face's pixel size**, so entity count scales with geometry,
  never with texture resolution. This is the load-bearing performance invariant.
- Non-integer face sizes (fractional `from/to`) round to the nearest integer grid; a
  6.4px face → 6 texels, each slightly stretched. Acceptable and self-consistent.
- Grid caps at 64 (§9 budget will engage far earlier for big faces).

### 6.3 Sampling per texel (`texelDetail` policy)

- `texelDetail = FACE` (default): 1 sample per texel (center, nearest). The honest
  minimum: matches what a player can see at typical viewing distance.
- `texelDetail = SUPERSAMPLE_2X2` / `_4X4`: average 4/16 samples inside the texel's
  texture footprint. Use for models viewed up close (items, hand-held scale). Cost is
  linear and load-time only.
- Transparency: sample the average of **alpha ≥ 128** pixels only; a fully transparent
  texel emits **no plate** (the underlying base display / minority plate shows through —
  this is how sprites/decals with cutout alpha reconstruct correctly).
- In-plane UV rotation (`BakedFace.rotation`) rotates the *sampling* lookup, not the grid
  (same approach the map-canvas experiment validated): for rotation θ, sample at
  `(u,v)` rotated by −θ about the window center. 90°/180°/270° are exact integer remaps;
  arbitrary angles are quantized to the nearest 90° for v1 (bbmodel in-plane rotation is
  effectively always a multiple of 90; `FaceUvAnalyzer` already treats it as such).

### 6.4 Strategy decision (extends `FaceUvAnalyzer`)

```
strategy(face):
  if texelMode == OFF                                   → existing logic
  if texelMode == ON  and texture image resolvable      → TEXEL
  if texelMode == AUTO:
      existing = existing_logic(face)                   // TILE / CROP_HALF / FULL
      if existing == FULL and texture image resolvable  → TEXEL
      else                                              → existing   // TILE/CROP_HALF stay: they
                                                                   // are already exact
  unresolvable texture                                  → existing logic (never fail)
```

Rationale: `TILE` (wrapping windows) and `CROP_HALF` (slab crops) are *already exact*
vanilla renderings; texel baking would only add entities. `FULL` — today's "one material
for whatever the window shows" — is exactly the case texel baking replaces.

`ModelMeta.texelMode` overrides the global; `AUTO` default keeps every existing model
byte-identical in behavior unless a `.meta.json` opts in — **zero regression risk for
existing content**, and the global can be flipped per server once validated.

---

## 7. `TexelMerge` — greedy rectangle merging

Without merging, a 16×16 face is 256 entities; a 10-cube model ≈ 15k entities: dead on
arrival. With 2D greedy merging (the same idea as vanilla chunk greedy meshing):

```
grid: paletteIndex[h][w]   (or -1 for transparent/no-plate)
visited: boolean[h][w]
for each cell (x,y) in scan order, not visited:
    extend width  while grid[y][x..x+w] all equal idx and unvisited
    extend height while every row grid[y..y+h][x..x+w] equal idx and unvisited
    emit rectangle (x, y, w, h, idx); mark visited
```

Properties that make greedy the right choice here:
- O(w·h) with tiny constants; the grid is ≤ 64×64 = 4096 cells worst case.
- Pixel-art and stylized textures (the realistic content for this system) have large flat
  runs → 256 texels typically collapse to 20–60 rectangles.
- Worst case (noise/checkerboard) degrades to 256 — this is what the budget guard (§9)
  catches.

Merge operates on **quantized palette indices** (post-quantization), not raw colors: two
near-identical texels that quantize to the same palette entry merge, which both improves
compression and guarantees a merged rectangle is a single solid material (no seam risk).

Diagonal/non-rectangular detail is intentionally not modeled — rectangles are the only
shape that maps to a single axis-aligned `BlockDisplay` scale. Curved artwork resolves to
stair-stepped rectangles, which at 1px scale reads as the intended curve (this is exactly
how the pixel art is drawn in the first place).

---

## 8. `DisplayEmitter` extension — emitting texel plates

The existing plate math (`plateDisplay`) builds a plate covering the **whole** face
rectangle. Generalize it to a **sub-rectangle** (grid cell range), keeping every existing
invariant:

- Plate rectangle in face-local unit space: `x0 = i/gridW`, span `w = rw/gridW` (same for
  V; note V-flip: grid row 0 is the top of the UV window, plates build top-down — reuse
  the existing tile-grid code path from `tileDisplays`, which already does exactly this
  subdivision math for TILE strategy. **`tileDisplays` is the template to copy**; the only
  difference is that tile rectangles are uniform while texel rectangles are merged and
  irregular).
- Thickness: `PLATE_THICKNESS` (1/64) — same as today.
- Z-fight offset: `EPS_OUT` (1/1024) outward — same as today. Adjacent texel plates share
  the same plane and thickness, so they never fight each other, only the shared outward
  offset vs. the base display matters, and it is already proven.
- Edge coincidence: plate edges land exactly on texel boundaries (`i/gridW` fractions);
  floating-point exactness is preserved because grid sizes are small integers and the
  division happens once per rectangle edge.
- BlockData per rectangle: `paletteEntry.material.createBlockData()` — no orientation
  logic needed (flat palette materials have no meaningful facing). Cache the `BlockData`
  per palette entry (one instance, reused across ALL rectangles and models — `BlockData`
  is immutable value semantics in Bukkit).
- Brightness: cube `lightEmission` inherited by every texel plate, exactly like the base
  display does today.

**Emission ordering guarantee:** all texel plates of one face emit before the next face;
within a face, scan order. This keeps spawn order deterministic (matters for debugging
and for stable entity UUID ordering in `ActiveVirtualBlock.displayEntities`).

---

## 9. Performance envelope & budgets (the hard limits)

Entity count is the currency; everything below exists to bound it.

| Guard | Default | Rationale |
|---|---|---|
| `maxTexelGridEdge` | 64 | Hard cap per face (64×64 = 4096 texels worst case pre-merge). |
| `maxTexelPlatesPerFace` | 96 | Above this, the face falls back to majority-material single plate (computed pre-merge→post-merge check, warn once per model). |
| `maxTexelPlatesPerInstance` | 150 (configurable) | Whole-instance budget across all faces/cubes; overflow faces fall back to `FULL` strategy per face, in face emission order, until under budget. Faces earlier in emission order keep their texel detail — deterministic. |
| Bake time | load-time only, cached in `VirtualBlockManager` alongside `loadedModels` | Zero per-spawn cost: spawn only applies the already-computed transform per plate (same per-entity work as today). |
| Memory | plan ≈ grid ints + rectangle list ≈ a few KB per face | Negligible vs. the decoded PNGs, which are the real memory item (bounded by the 4096² sanity guard; typical 16×16 = 1 KB). |

Scaling math (for the record): a 10-cube model, all faces 16px, pixel-art textures merging
to ~40 rectangles/face → ≈ 2.4k texel entities + ~10 base displays ≈ within one chunk's
usual entity traffic on a decorated server, but 10× today's per-model count. This is why
`maxTexelPlatesPerInstance` defaults to 150 (≈ 3–4 faces at full detail, or all faces at
reduced merge quality — see §10.3 LOD). The budget is the feature's safety valve; treat
"model renders at budget" as a content-authoring bug, surfaced by `/mineplus model info`.

---

## 10. Deliberate non-goals / future extensions (explicitly out of v1)

1. **Animated texels** (flipbook) — would require per-tick `setBlock` swaps; rejected for
   packet cost. Model animation (`AnimationApi`) is unaffected and composes naturally
   (plates bind to the cube's bone like today's minority plates do via `AnimationBinding`).
2. **Grain-material rectangles** — allowing planks/logs for rectangles ≥ 4×4 texels would
   widen the palette enormously for floors/roofs. Deferred: needs visual validation, and
   the neutral band is already covered by terracotta/powder entries.
3. **LOD / distance-based plate collapsing** — the budget fallback is static. A dynamic
   per-viewer-distance re-merge is possible (players far away see merged plates, near
   players see full detail) but requires per-player entity culling that vanilla
   `setDisplay` visibility doesn't offer cheaply. Revisit only if large decorated builds
   actually hit the budget.
4. **Embedded bbmodel Base64 texture sources** — see §6.1.
5. **Non-90° in-plane UV rotation** — see §6.3.
6. **Dithering** (Bayer/FS) to soften palette banding — breaks rectangle merging by
   design; rejected unless a "decorative mode" is ever wanted at 4× budget.

---

## 11. Configuration surface

`settings.mp.yml`, new section (auto-generated by `ConfigManager`, following the existing
pattern):

```yaml
VIRTUAL_RENDERING:
  # ... existing keys unchanged ...

TEXEL_BAKING:
  ENABLED: true                  # global enable (false = pipeline identical to today)
  MODE: AUTO                     # AUTO | ON | OFF  (per-model .meta.json overrides)
  DETAIL: FACE                   # FACE | SUPERSAMPLE_2X2 | SUPERSAMPLE_4X4
  MAX_PLATES_PER_FACE: 96
  MAX_PLATES_PER_INSTANCE: 150
  MAX_GRID_EDGE: 64
```

`ModelMeta` additions (`models/<key>.meta.json`):

```json
{
  "texelMode": "AUTO",
  "texelDetail": "FACE"
}
```

Defaults chosen so that **a server that never touches the config sees zero behavior
change** (AUTO only activates for FULL-strategy faces with a resolvable PNG, and existing
shipped models' textures resolve through `TextureMaterialResolver` as today unless PNGs
are placed next to them — placing a PNG next to an existing model is therefore the opt-in
gesture for content authors).

---

## 12. Diagnostics (`/mineplus model info <key>`)

Extend the existing report (it already prints cube count, display estimate, texture
table, occupancy) with, when texel baking is active for the model:

```
Texel baking: ON (mode=AUTO, detail=FACE)
  faces baked: 41/54            (13 faces used TILE/CROP_HALF or had no PNG)
  texel grids: 16x16 (34), 6x6 (7)
  palette usage: OAK_PLANKS-free; top: white_concrete 1210 texels,
                 light_gray_concrete 402, ... (histogram, top 8)
  merged plates: 812 total (avg 19.8/face, max 61 on face #12)
  budget: 812/150 per instance → 6 faces fell back to FULL
  bake time: 14 ms, cache: HIT
```

Also surface in the per-model texture report (`getTextureReport`) whether each texture
name has a resolvable PNG, since that is now load-bearing for rendering fidelity.

---

## 13. Implementation order (milestones)

Each milestone is independently shippable and leaves the tree green.

**M1 — `TexelPalette` + matcher (pure, testable).**
Static palette table with measured profiles, redmean matcher, 5-bit LUT cache. Unit-test
surface: known RGB → expected material (golden tests against the table), cache hit/miss,
transparency passthrough. No pipeline integration yet.

**M2 — `TextureImageStore` + `TexelSurfaceBaker` (pure, testable).**
PNG loading by name, grid derivation (§6.2 — implement the corrected formula verbatim),
sampling incl. rotation remap and alpha cutout, quantized grid output. Unit tests with
synthetic `BufferedImage`s (solid color, gradient, checkerboard, 4×4 upscale, 32×32
downsample, transparent regions, rotated window). No pipeline integration yet.

**M3 — `TexelMerge` (pure, testable).**
Greedy merge over synthetic grids: solid grid → 1 rect; checkerboard → N² rects; stripes →
N rects; visited-marking correctness (no overlap, full coverage); determinism (same input
→ identical rect list order).

**M4 — `DisplayEmitter` sub-rectangle plates + `FaceUvAnalyzer.TEXEL` strategy.**
Copy the `tileDisplays` subdivision math; wire strategy decision (§6.4). Integration-test
by hand: opt one test model in via `.meta.json`, verify in-game (§15).

**M5 — `VirtualBlockManager` caching + budgets + `/mineplus model info` reporting.**
Plan cache lifecycle (reload clears), budget guards, diagnostics output.

**M6 — `ConfigManager` / `ModelMeta` / `settings.mp.yml` + docs.**
Config plumbing, `docs/config-reference.md` + `docs/developer-api.md` updates, and a short
authoring guide (which textures work best: pixel-art, flat regions, ≤32×32 effective
detail; placing PNGs next to models).

**M7 — mineplus-fun showcase (optional, after Core is stable).**
A wine-bottle or banner demo model in the reference module with a hand-drawn 16×16
texture, opted in via `.meta.json`, demonstrating palette reconstruction end-to-end.

Dependency chain: M1 → M2 → M3 → (M4, M5 parallel) → M6 → M7. M1–M3 are pure-function
work with no Bukkit dependency beyond `Material` — they can be written and verified with
plain JUnit before any in-game testing.

---

## 14. Risks & mitigations

| Risk | Likelihood | Mitigation |
|---|---|---|
| Entity count explodes on dense textures | High without guards | Budgets §9 are release-blocking; `/mineplus model info` surfaces budget exhaustion as an authoring error, not a silent slowdown. |
| Palette matches feel "off" (hue drift) | Medium | Redmean + measured profiles (§5.2–5.3); fix is palette entries, metric swap is last resort. Golden tests pin the palette so drift is caught in review. |
| Z-fighting between adjacent texel plates | Low | Plates share one plane/offset per face (same as TILE strategy today); edge fractions are exact small-integer divisions. If ever visible, raise plate `EPS_OUT` for texel plates only — it is a single constant. |
| Spawn latency for many-plate models | Medium | Everything is bake-time; spawn applies transforms only (same per-entity cost as today). Rollback path (`rollbackSpawn`) is entity-count-agnostic. If spawn ticks spike on huge models, batch by interleaving across ticks is a contained follow-up — NOT in v1. |
| Animation transforms break on texel plates | Low | Plates bind per-cube via the existing `AnimationBinding` matrix path — same as minority plates today; bone-relative math is plate-count-agnostic. Verify in M4 hand-test with an animated test model. |
| Existing models change appearance | Very Low | AUTO only engages for FULL faces with resolvable PNGs; shipped models have no adjacent PNGs → behavior byte-identical. Config kill-switch `TEXEL_BAKING.ENABLED: false` restores the old pipeline entirely. |
| PNG loading adds server startup cost | Low | Decode at model-load only, images ≤ 4096², cached; typical content is a handful of 16×16s (kilobytes). |

---

## 15. Verification protocol (in-game, after M4)

1. **Baseline:** existing models (juicer, cannon, gear) render byte-identically with
   `TEXEL_BAKING.ENABLED: true` and no adjacent PNGs. Screenshot-compare against
   `ENABLED: false`.
2. **Flat texture:** 16×16 solid-color PNG next to a test model → renders as exactly one
   plate per face in the palette-mapped color; `/mineplus model info` reports merged
   plates = faces count.
3. **Pixel-art:** hand-drawn 16×16 (e.g. a bottle with label + highlight) → distinct
   rectangles visible at reading distance, colors matching the drawn palette picks.
4. **Upscale:** same art saved as 4×4 → visibly blockier, exactly 16 plates max per face
   after merge; no crashes, no missing faces.
5. **Downsample:** same art at 32×32 → indistinguishable from the 16×16 render at 8
   blocks distance (the point of §6.2).
6. **Rotation:** place the model at all 4 snapped yaw orientations → texel art follows
   the face (no mirroring/turning per orientation; the sampling rotation remap is the
   suspect if it fails — it is a single sign).
7. **Animation:** run a bbmodel clip on a texel-baked model → plates move with their
   bone; no detachment, no per-plate flicker.
8. **Budget:** a noise texture (checkerboard) → `/mineplus model info` shows fallback
   faces; server tick graph stays flat during a `/mineplus reload` with the model placed.
9. **Restart restore:** placed texel model survives restart (`restoreForState` path) and
   re-renders identically.

---

## 16. Glossary

- **Texel** — one cell of the face's effective pixel grid (§6.2); the atomic unit of
  color reconstruction.
- **Plate** — a thin `BlockDisplay` rectangle hugging a face sub-rectangle in a palette
  material (existing concept from `DisplayEmitter`, generalized to sub-rectangles).
- **Palette** — the curated set of visually-flat vanilla materials with measured color
  profiles (§5).
- **Plan** — the immutable per-face bake output: grid + merged rectangles + palette
  indices + emissive flags (`TexelSurfacePlan`).
- **Budget** — the per-face/per-instance plate ceilings that bound entity count (§9).

---

*Prepared as the implementation contract for the next Core update. Everything above is
grounded in the current sources: pipeline classes in
`src/main/java/com/mineplus/infrastructure/virtual/`, lifecycle in
`core/multiblock/`, settings in `VirtualRenderingSettings`/`ConfigManager`. No code has
been written; milestones M1–M3 are pure-function work suitable for test-first
development.*
