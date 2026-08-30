# ⚙️ Configuration Reference

> **Navigate:** [Docs Home](README.md) • [Developer API](developer-api.md) • [Extension Workflows](extension-workflows.md) • [Examples](../examples/README.md)

Mineplus is a **zero-content core**: nothing is added to gameplay unless you provide JSON files or call the API. This reference documents every file format and setting you can use to fill that canvas.

**Runtime folders** (inside `plugins/Mineplus/`):

```
plugins/Mineplus/
├── settings.mp.yml        ← engine settings (auto-generated)
├── models/*.bbmodel       ← Blockbench models (nested folders supported)
├── multiblocks/*.json     ← machine type definitions
├── recipes/*.json         ← machine recipe definitions
└── infrastructure.db      ← SQLite persistence (auto-managed)
```

Reload everything from game with `/mineplus reload all`.

**Contents:** [Multiblock JSON](#1-multiblock-type-json) · [Recipe JSON](#2-machine-recipe-json) · [Models](#3-model-files-bbmodel) · [settings.mp.yml](#settingsmpyml-rendering-engine) · [Commands](#admin-command-reference)

---

## 1) Multiblock Type JSON

**Path:** `plugins/Mineplus/multiblocks/<file>.json`
**Live example:** [`examples/config-based/furnace_upgradable_multiblock.json`](../examples/config-based/furnace_upgradable_multiblock.json) • the [Cannon definition](../examples/mineplus-fun/src/main/resources/defaults/multiblocks/cannon.json)

### Top-level fields

| Field | Required | Description |
|---|---|---|
| `id` | ✅ | Unique machine type id (snake_case convention, e.g. `cannon`, `juicer_machine`) |
| `name` | optional | Display name; defaults to `id` |
| `gui` | optional | GUI key opened automatically on right-click (see note below) |
| `levels` | ✅ | Object where each key is an integer level |

> **When to set `gui`:** if present, the core opens that GUI on *every* right-click. For conditional interactions (e.g. the Cannon: torch fires, empty hand opens the menu) omit `gui` and let a registered hook decide — see the [Developer API](developer-api.md) and the [Cannon hook](../examples/mineplus-fun/src/main/java/com/mineplus/fun/cannon/CannonFireHook.java).

### Per-level fields

| Field | Default | Description |
|---|---|---|
| `model` | — | Relative (from `plugins/Mineplus/`) or absolute path to a `.bbmodel` |
| `speed` | `1.0` | Crafting speed multiplier. `2.0` halves recipe time; a mid-process upgrade speeds the running process immediately |
| `durability` | `1.0` | Reserved for future use — not yet consumed by the engine |
| `upgradeCost` | `{}` | Object of `itemKey -> amount`, charged on `upgradeBlock` |
| `guiOptions` | `{}` | Free-form string map for custom GUI data (e.g. `title`) |
| `animations` | `[]` | Clip names to auto-play on this level's rendered model (see [Animation Engine](developer-api.md#animation-engine)) |

### Example

```json
{
  "id": "crusher",
  "name": "Crusher",
  "gui": "crusher_gui",
  "levels": {
    "1": {
      "model": "models/crusher_lv1.bbmodel",
      "speed": 1.0,
      "durability": 100.0,
      "upgradeCost": { "core_plate": 8 },
      "guiOptions": { "title": "Crusher I" },
      "animations": ["crush_loop"]
    },
    "2": {
      "model": "models/crusher_lv2.bbmodel",
      "speed": 1.35,
      "durability": 160.0,
      "upgradeCost": { "core_plate": 16 },
      "animations": ["crush_loop", "steam_hiss"]
    }
  }
}
```

---

## 2) Machine Recipe JSON

**Path:** `plugins/Mineplus/recipes/<file>.json`
**Live example:** [`examples/config-based/furnace_machine_recipes.json`](../examples/config-based/furnace_machine_recipes.json)

The loader accepts either a single recipe object or a `{ "recipes": [ ... ] }` wrapper.

### Recipe fields

| Field | Default | Description |
|---|---|---|
| `id` | ✅ required | Unique recipe id |
| `machine` | ✅ required | Target multiblock type id |
| `level` | `1` | Minimum machine level |
| `craftTimeTicks` | `20` | Base duration in ticks when run as a timed process (scaled by the level's `speed`; processes pause in unloaded chunks and survive restarts — see [`startProcess`](developer-api.md#timed-crafting-processes)) |
| `input` | `{}` | Object `key -> amount` |
| `output` | `{}` | Object `key -> amount` |

### Example

```json
{
  "recipes": [
    {
      "id": "crusher_iron",
      "machine": "crusher",
      "level": 1,
      "craftTimeTicks": 100,
      "input": { "raw_iron": 1 },
      "output": { "iron_dust": 2 }
    }
  ]
}
```

---

## 3) Model Files (`.bbmodel`)

**Path:** `plugins/Mineplus/models/*.bbmodel` (scanned recursively — nested folders supported)

- Relative `model` paths in multiblock JSON are resolved from `plugins/Mineplus/`.
- Keep model file names stable; active instances rely on type/level model resolution.
- Use `/mineplus reload models` after edits.

### What the importer supports

| Feature | Status |
|---|---|
| Cube rotation around each cube's `origin` (pivot) | ✅ |
| Outliner group hierarchy transforms | ✅ |
| `inflate` | ✅ included in final cube dimensions |
| Per-face `uv` + `rotation` (all six directions) | ✅ parsed & used for material orientation |
| Negative-coordinate geometry | ✅ preserved |
| `light_emission` | ✅ per-cube display brightness |
| Animations (clips, bone animators, keyframes) | ✅ parsed and playable (see below) |
| Embedded texture bitmaps (`source` Base64 PNGs) / timeline setups | ❌ skipped (dead branches are never even allocated) |

**Barrier occupancy is computed from transformed cube volumes** (union per cube), not from one full model bounding box — empty internal spaces stay free, so hollow structures are genuinely walkable.

### Animations

Blockbench animations live in the same `.bbmodel` file and are imported in the same streaming pass:

| Animation feature | Status |
|---|---|
| Bone animators (outliner groups; parents drag nested children) | ✅ |
| `rotation` / `position` / `scale` channels (deltas from rest) | ✅ |
| `linear` / `step` keyframe interpolation | ✅ |
| `catmullrom` / `bezier` / `ease` | ⚠️ sampled as linear (spline handles are not exported in keyframes) |
| Loop modes `once` / `loop` / `hold` | ✅ |
| Molang keyframe values (e.g. `math.sin(q.anim_time * 90)`) | ⚠️ fall back to `0` and are reported at import |
| Effect animators (sounds, particles, timelines) | ❌ skipped — no client mod to play them |

Autoplay is declared per multiblock level (`animations` key) or per raw model (`autoplay` in `.meta.json`); programmatic control goes through the `AnimationApi` (selectors for clips, bones, or the whole model). Full behavior: [Developer API → Animation Engine](developer-api.md#animation-engine).

---

### Texture Engine Architecture

Mineplus renders models using vanilla `BlockDisplay` entities. Each cube becomes one `BlockDisplay`, which can only show a single Minecraft block material — this is the fundamental constraint of the "completely vanilla, no resource pack" approach, and everything below follows from it.

**How a cube gets its material:**

1. The importer reads the `.bbmodel` `textures` array, extracting each texture's filename (from `path`, `relative_path`, or `name` fields, in that priority order).
2. For each cube, it reads the face `texture` reference — the **integer array index** into the `textures` array (standard Blockbench format).
3. The **primary texture** is chosen from the first resolved face texture, checked in order: north → south → east → west → up → down.
4. The primary texture name is mapped to a Minecraft `Material` via `TextureMaterialResolver` (curated map → direct match → suffix-strip → aliases → fuzzy → fallback).
5. Unmatched names fall back to `WHITE_CONCRETE` and are reported per model via `/mineplus model info`.

**Beyond one material per face:** the UV alignment layer renders wrapping windows as native-density tile grids and half windows as slab-type crops, and — when a texture PNG sits next to the model file — **texel surface baking** reconstructs the actual texture pixel-by-pixel out of flat vanilla palette blocks (see [Texel Surface Baking](#texel-surface-baking)). Arbitrary sub-pixel UV cropping without a resource pack remains out of scope.

---

### Blockbench Designer Guidelines

Follow these rules when creating `.bbmodel` files for Mineplus:

1. **Use "Free Model" format** in Blockbench (not Java Block/Entity). Box UV or Per-face UV mode both work.

2. **Texture assignment is per-cube.** Assign the same Minecraft block texture to **all six faces** of each cube. The engine picks the first face with a valid texture and uses that one material for the entire cube.

3. **Name your texture files after the Minecraft block texture.** Only the final filename matters — `C:\...\blocks\bookshelf.png` and `minecraft-textures/blocks/bookshelf.png` both resolve as `bookshelf`. The `.png` extension is stripped automatically.
   - ✅ `bookshelf.png`, `purpur_pillar.png`, `concrete_brown.png`

4. **Use actual Minecraft block textures** extracted from the game's resource pack. Import them into Blockbench from anywhere on disk — Mineplus reads the path/filename metadata.

5. **Different texture on a different part? Split it into a separate cube.** One cube = one material.

6. **Avoid the generic "texture" name.** A file named just `texture` or `texture.png` won't resolve to any block material and falls back to `WHITE_CONCRETE`.

7. **Model geometry is fully free.** Position, scale, rotation, pivots, inflate, outliner groups — all supported. Only the texture → material mapping has restrictions.

---

### Allowed Texture Names

The following texture filenames are recognized and mapped to Minecraft block materials (extension optional). Any valid Minecraft block material name also works directly (`oak_planks`, `white_concrete`, `diamond_block`) — matched case-insensitively against the Bukkit `Material` enum.

<details>
<summary><strong>📋 Click to expand the full texture catalog</strong></summary>

#### Stone & Variants
`stone`, `granite`, `polished_granite`, `diorite`, `polished_diorite`, `andesite`, `polished_andesite`, `deepslate`, `cobbled_deepslate`, `polished_deepslate`, `calcite`, `tuff`, `dripstone_block`, `cobblestone`, `mossy_cobblestone`, `smooth_stone`

#### Dirt & Soil
`dirt`, `coarse_dirt`, `rooted_dirt`, `grass_block_top`, `grass_block_side`, `grass_block_side_overlay`, `podzol_top`, `podzol_side`, `mud`, `muddy_mangrove_roots_top`, `muddy_mangrove_roots_side`, `packed_mud`, `mycelium_top`, `mycelium_side`, `soul_sand`, `soul_soil`

#### Sand & Gravel
`sand`, `red_sand`, `gravel`, `clay`

#### Sandstone
`sandstone`, `sandstone_top`, `sandstone_bottom`, `chiseled_sandstone`, `cut_sandstone`, `red_sandstone`, `red_sandstone_top`, `red_sandstone_bottom`, `chiseled_red_sandstone`, `cut_red_sandstone`

#### Wood (all types: oak, spruce, birch, jungle, acacia, dark_oak, mangrove, cherry, bamboo, crimson, warped)
`{wood}_planks`, `{wood}_log`, `{wood}_log_top`, `stripped_{wood}_log`, `stripped_{wood}_log_top`, `{wood}_stem`, `{wood}_stem_top`

#### Ores
`coal_ore`, `iron_ore`, `gold_ore`, `diamond_ore`, `emerald_ore`, `lapis_ore`, `redstone_ore`, `copper_ore`, `nether_gold_ore`, `nether_quartz_ore`, `deepslate_coal_ore`, `deepslate_iron_ore`, `deepslate_gold_ore`, `deepslate_diamond_ore`, `deepslate_emerald_ore`, `deepslate_lapis_ore`, `deepslate_redstone_ore`, `deepslate_copper_ore`, `ancient_debris_top`, `ancient_debris_side`

#### Metal & Mineral Blocks
`gold_block`, `iron_block`, `diamond_block`, `netherite_block`, `emerald_block`, `lapis_block`, `redstone_block`, `coal_block`, `copper_block`, `raw_gold_block`, `raw_iron_block`, `raw_copper_block`, `exposed_copper`, `weathered_copper`, `oxidized_copper`, `cut_copper`, `exposed_cut_copper`, `weathered_cut_copper`, `oxidized_cut_copper`, `amethyst_block`

#### Colored Blocks (all 16 colors: white, orange, magenta, light_blue, yellow, lime, pink, gray, light_gray, cyan, purple, blue, brown, green, red, black)
`{color}_wool`, `{color}_concrete`, `concrete_{color}` (alias), `{color}_concrete_powder`, `concrete_powder_{color}` (alias), `{color}_terracotta`, `{color}_glazed_terracotta`, `{color}_stained_glass`, `terracotta`

#### Bricks & Stone Bricks
`bricks`, `stone_bricks`, `mossy_stone_bricks`, `cracked_stone_bricks`, `chiseled_stone_bricks`, `mud_bricks`

#### Nether
`netherrack`, `nether_bricks`, `red_nether_bricks`, `chiseled_nether_bricks`, `cracked_nether_bricks`, `basalt_top`, `basalt_side`, `polished_basalt_top`, `polished_basalt_side`, `smooth_basalt`, `blackstone`, `blackstone_top`, `polished_blackstone`, `chiseled_polished_blackstone`, `polished_blackstone_bricks`, `cracked_polished_blackstone_bricks`, `gilded_blackstone`, `warped_nylium_top`, `warped_nylium_side`, `crimson_nylium_top`, `crimson_nylium_side`, `warped_wart_block`, `nether_wart_block`, `shroomlight`, `glowstone`

#### End
`end_stone`, `end_stone_bricks`, `purpur_block`, `purpur_pillar`, `purpur_pillar_top`

#### Prismarine & Ocean
`prismarine`, `prismarine_bricks`, `dark_prismarine`, `sea_lantern`

#### Quartz
`quartz_block_top`, `quartz_block_bottom`, `quartz_block_side`, `quartz_pillar`, `quartz_pillar_top`, `chiseled_quartz_block`, `chiseled_quartz_block_top`, `smooth_quartz`, `quartz_bricks`

#### Ice & Snow
`ice`, `packed_ice`, `blue_ice`, `snow`, `snow_block`

#### Organic & Farm
`hay_block_top`, `hay_block_side`, `melon_top`, `melon_side`, `pumpkin_top`, `pumpkin_side`, `carved_pumpkin`, `jack_o_lantern`, `bone_block_top`, `bone_block_side`, `dried_kelp_top`, `dried_kelp_side`, `dried_kelp_block`, `honeycomb_block`, `honey_block_top`, `honey_block_side`, `honey_block_bottom`, `slime_block`, `moss_block`

#### Functional Blocks
`bookshelf`, `obsidian`, `crying_obsidian`, `bedrock`, `sponge`, `wet_sponge`, `glass`, `spawner`, `dragon_egg`

#### Crafting & Utility
`furnace_front`, `furnace_front_on`, `furnace_side`, `furnace_top`, `blast_furnace_front`, `blast_furnace_front_on`, `blast_furnace_side`, `blast_furnace_top`, `smoker_front`, `smoker_front_on`, `smoker_side`, `smoker_top`, `smoker_bottom`, `crafting_table_front`, `crafting_table_side`, `crafting_table_top`, `smithing_table_front`, `smithing_table_side`, `smithing_table_top`, `smithing_table_bottom`, `fletching_table_front`, `fletching_table_top`, `cartography_table_top`, `cartography_table_side1`, `cartography_table_side2`, `cartography_table_side3`, `loom_front`, `loom_side`, `loom_top`, `loom_bottom`, `barrel_top`, `barrel_top_open`, `barrel_side`, `barrel_bottom`, `enchanting_table_top`, `enchanting_table_side`, `enchanting_table_bottom`, `end_portal_frame_top`, `end_portal_frame_side`

#### Redstone
`dispenser_front`, `dispenser_front_vertical`, `dropper_front`, `dropper_front_vertical`, `observer_front`, `observer_back`, `observer_back_on`, `observer_side`, `observer_top`, `piston_top`, `piston_side`, `piston_bottom`, `piston_inner`, `piston_top_sticky`, `tnt_top`, `tnt_side`, `tnt_bottom`, `target_top`, `target_side`, `redstone_lamp`, `redstone_lamp_on`, `note_block`, `jukebox_top`, `jukebox_side`

#### Mushroom
`mushroom_block_inside`, `mushroom_stem`, `red_mushroom_block`, `brown_mushroom_block`

#### Sculk
`sculk`, `sculk_catalyst_top`, `sculk_catalyst_side`, `sculk_catalyst_bottom`

#### Froglight
`ochre_froglight_top`, `ochre_froglight_side`, `verdant_froglight_top`, `verdant_froglight_side`, `pearlescent_froglight_top`, `pearlescent_froglight_side`

#### Misc
`lodestone_top`, `lodestone_side`, `respawn_anchor_top`, `respawn_anchor_side0`

</details>

---

### Per-Model Overrides (`.meta.json`)

Any global rendering setting can be overridden per model. Place a `models/<key>.meta.json` next to the model file:

```json
{
  "originMode": "GRID",
  "collisionMode": "SURFACE",
  "autoplay": ["rotate_gear"],
  "texelMode": "AUTO",
  "texelDetail": "FACE",
  "maxTexelPlatesPerFace": 96,
  "maxTexelPlatesPerInstance": 150,
  "texelBrightness": 15,
  "voxelMode": "AUTO",
  "maxVoxelDisplays": 1024
}
```

Omitted fields fall back to the global `settings.mp.yml` values. `autoplay` lists clip names to auto-start when a raw (non-multiblock) model spawns — multiblock levels use their own `animations` key instead, which takes precedence. `texelMode`/`texelDetail` override the global `TEXEL_BAKING` settings for this model (see [Texel Surface Baking](#texel-surface-baking)); `maxTexelPlatesPerFace`/`maxTexelPlatesPerInstance` raise (or lower) the plate budgets per model — decorative pixel-art models legitimately want more plates than the conservative global default; `texelBrightness` (0-15) sets a minimum display light level for texel-baked models so dark palette art stays readable in shade and at night. `voxelMode`/`maxVoxelDisplays` override `VOXEL_RENDERING` for this model (see [Voxel Reconstruction](#voxel-reconstruction)); pin `"voxelMode": "OFF"` on texel-plate showcase models so the AUTO strategy cannot take them over.

---

### Anchor Conventions (Origin Modes)

| Mode | Meaning |
|---|---|
| `AUTO` | Detect from the model's `meta.model_format` and geometry extent — the default |
| `CENTER` | Blockbench free-format: pixel (0,0,0) is the **center** of the anchor block at its base; a full block spans [-8..8] horizontally, [0..16] vertically |
| `GRID` | Vanilla `java_block` convention: pixel (0,0,0) is the **north-west-bottom corner**; a full block spans [0..16] on every axis |

`AUTO` resolves `java_block`/`java_item`/`modded_block` formats to `GRID` (unless the geometry is center-authored) and everything else to `CENTER`.

---

### Internal Debug Models

- Use `plugins/Mineplus/models/debug/` for throwaway importer/transform experiments — keeps production model keys clean.
- The loader scans `plugins/Mineplus/models/` recursively; model keys include the folder path (e.g. `models/debug/test_rotation.bbmodel` → key `debug/test_rotation`).
- `/mineplus model models` lists loaded keys; `/mineplus model debugspawn <modelKey>` spawns one on the looked-at face.

---

## `settings.mp.yml` (Rendering Engine)

Auto-generated on first start at `plugins/Mineplus/settings.mp.yml`. Controls the global bbmodel → BlockDisplay pipeline; per-model `.meta.json` overrides take precedence.

```yaml
# Toggle additional detailed debug logs across multiblock lifecycle,
# rendering pipeline, persistence transactions, and linking events.
ADDITIONAL_DEBUG_LOGS: false

# Update checker: compares the installed version against the SpigotMC
# resource page. 0 disables the check entirely.
UPDATE_CHECKER:
  RESOURCE_ID: 0

VIRTUAL_RENDERING:
  # Collision proxy voxelization: AABB | GEOMETRY | SURFACE
  COLLISION_MODE: GEOMETRY
  # Cell shrink epsilon for geometry contact tests.
  COLLISION_EPSILON: 0.001
  # Behavior when a collision cell is not air: SKIP | STRICT
  COLLISION_NON_AIR_POLICY: SKIP
  # Snap placement rotations to the 24 grid orientations.
  ROTATION_SNAP: true
  # Max deviation from the nearest grid orientation before a warning is logged (degrees).
  ROTATION_SNAP_THRESHOLD_DEGREES: 5
  # Emit per-face material plates for mixed-material cubes.
  PER_FACE_RENDERING: true
  # Anchor convention: AUTO (detect) | CENTER | GRID
  ORIGIN_MODE: AUTO

ANIMATION:
  # Master switch for the animation runtime.
  ENABLED: true
  # Server ticks between transform pushes (1 = every tick, the smoothest
  # a purely server-side renderer can update).
  TICK_INTERVAL_TICKS: 1
  # Client interpolation window in ticks; 0 = match TICK_INTERVAL_TICKS.
  INTERPOLATION_TICKS: 1
  # Auto-start animations declared in multiblock levels ("animations")
  # or model meta files ("autoplay").
  AUTOPLAY: true

TEXEL_BAKING:
  # Global enable (false = rendering pipeline identical to before).
  ENABLED: true
  # AUTO | ON | OFF (per-model .meta.json overrides)
  MODE: AUTO
  # FACE | SUPERSAMPLE_2X2 | SUPERSAMPLE_4X4
  DETAIL: FACE
  # Merged-plate ceiling per face; above it the face falls back to the
  # single-material plate.
  MAX_PLATES_PER_FACE: 96
  # Whole-instance plate budget; faces overflow in emission order.
  MAX_PLATES_PER_INSTANCE: 150
  # Hard grid edge cap per face (max texels per axis pre-merge).
  MAX_GRID_EDGE: 64

VOXEL_RENDERING:
  # Global enable (false = the legacy cube/plate pipeline for every model).
  ENABLED: true
  # AUTO | ON | OFF (per-model .meta.json overrides)
  MODE: AUTO
  # Whole-model display-run budget; a model exceeding it keeps the legacy
  # pipeline instead of a partial reconstruction.
  MAX_DISPLAYS: 1024
```

| Key | Values | Effect |
|---|---|---|
| `ADDITIONAL_DEBUG_LOGS` | `true` / `false` | Verbose lifecycle, rendering, persistence, and linking logs. Off by default; persistence errors are always logged regardless |
| `UPDATE_CHECKER.RESOURCE_ID` | numeric | SpigotMC resource id for the optional version check on startup; `0` (default) disables it |
| `COLLISION_MODE` | `GEOMETRY` / `SURFACE` / `AABB` | Barrier voxelization: per-cube SAT (default), interior hollowing for walk-in structures, or legacy full-AABB fill |
| `COLLISION_EPSILON` | float | Shrink factor for geometry contact tests |
| `COLLISION_NON_AIR_POLICY` | `SKIP` / `STRICT` | When a collision cell isn't air: skip that cell, or abort the whole spawn |
| `ROTATION_SNAP` | `true` / `false` | Snap placement rotations to the 24 orientation-preserving axis permutations |
| `ROTATION_SNAP_THRESHOLD_DEGREES` | degrees | Deviation beyond this logs a `DebugLogger` warning |
| `PER_FACE_RENDERING` | `true` / `false` | Emit per-face material plates for mixed-material cubes (better texture fidelity, more display entities) |
| `ORIGIN_MODE` | `AUTO` / `CENTER` / `GRID` | Default anchor convention — see [Origin Modes](#anchor-conventions-origin-modes) |
| `ANIMATION.ENABLED` | `true` / `false` | Master switch for the animation runtime |
| `ANIMATION.TICK_INTERVAL_TICKS` | ticks ≥ 1 | Server ticks between transform pushes; `1` (default) pushes every tick and lets the client interpolate to its own frame rate |
| `ANIMATION.INTERPOLATION_TICKS` | ticks ≥ 0 | Client interpolation window between pushes; `0` (default) matches the tick interval |
| `ANIMATION.AUTOPLAY` | `true` / `false` | Auto-start clips declared in multiblock level `animations` keys or model `.meta.json` `autoplay` lists |
| `TEXEL_BAKING.ENABLED` | `true` / `false` | Global texel baking switch; `false` restores the pre-texel pipeline entirely |
| `TEXEL_BAKING.MODE` | `AUTO` / `ON` / `OFF` | `AUTO` (default) bakes only faces that would use the FULL strategy and have a resolvable PNG; `ON` bakes every face with a resolvable PNG; `OFF` never bakes |
| `TEXEL_BAKING.DETAIL` | `FACE` / `SUPERSAMPLE_2X2` / `SUPERSAMPLE_4X4` | Samples per texel: one center sample, or 4/16 area-averaged samples for close-up models |
| `TEXEL_BAKING.MAX_PLATES_PER_FACE` | plates ≥ 1 | Merged-plate ceiling per face (default 96); above it the face falls back to the single-material plate |
| `TEXEL_BAKING.MAX_PLATES_PER_INSTANCE` | plates ≥ 1 | Whole-instance texel plate budget (default 150); faces overflow in emission order |
| `TEXEL_BAKING.MAX_GRID_EDGE` | cells ≥ 1 | Hard grid edge cap per face (default 64) — entity count scales with geometry, never with texture resolution |
| `VOXEL_RENDERING.ENABLED` | `true` / `false` | Global voxel reconstruction switch; `false` keeps every model on the legacy pipeline |
| `VOXEL_RENDERING.MODE` | `AUTO` / `ON` / `OFF` | `AUTO` (default) voxelizes only non-animated, axis-aligned, lattice-snapped, texture-backed models; `ON` attempts any non-animated model (off-lattice geometry approximated); `OFF` never voxelizes |
| `VOXEL_RENDERING.MAX_DISPLAYS` | displays ≥ 1 | Whole-model merged-run ceiling (default 1024); a model exceeding it keeps the legacy pipeline |

---

### Texel Surface Baking

Texel baking reconstructs a face's texture **pixel-by-pixel out of flat vanilla blocks** (16 concretes, 16 concrete powders, 16 terracottas, plus snow block, obsidian and warped wart covering the dark desaturated band — 51 entries, all visually flat): each texel's color is matched to the nearest palette entry by redmean perceptual distance, and adjacent same-color texels merge into single thin `BlockDisplay` plates (greedy rectangle merging, like vanilla chunk meshing).

- **Opt-in gesture:** place the texture PNG next to the model file (`models/<textureName>.png`) — models without adjacent PNGs render byte-identically to before. Bake never fails model load; unresolvable faces keep their legacy tier.
- **Stretch safety by construction:** every palette entry is a visually flat block, so plates of any size render as a solid color — a stretched patterned block can never appear, because patterned blocks (wool weave, glazed terracotta, glowstone mottle, grained wood…) are excluded from the palette outright. Colors are always the source texture's own, sampled 1:1 per texel; only the block choice is a nearest-flat-color match.
- **Occlusion culling (no overlapping entities):** texels whose plate would sit inside another cube's solid — the buried midsection of a band-wrapped body, a cork's hidden base — emit no plate. Every rendered plate occupies its own distinct, visible space; box-modeled nested geometry no longer produces layered surface artifacts. Culled-cell counts show in `/mineplus model info`.
- **Effective grid:** the face's own pixel grid (a 16px face yields a 16×16 grid regardless of PNG resolution) — a 4×4 texture upscales to ≤16 plates, a 32×32 texture downsamples.
- **Cutout transparency:** texels with alpha < 128 emit no plate; the underlying base display shows through. On texel-baked cubes the base display is colored with the cube's **dominant baked palette color** (largest world-area entry across its faces) instead of the filename-resolved material — so holes reveal a matching local tone rather than the resolver's white fallback, and untextured faces inherit it.
- **Readability floor:** vanilla's directional face shading crushes near-black palette materials into one unreadable mass outside full daylight. A per-model `"texelBrightness": 0-15` meta override keeps every display at a minimum light level (the top/side/bottom shading still separates faces); unset follows the model's `light_emission` as before.
- **Dithered art:** dithered gradient shading (checkerboard-blended shades, common in pixel art) reconstructs as alternating flat palette entries — a faithful but higher-contrast rendering of the dither. Prefer art with flat regions for clean results.
- **Best content:** pixel art with flat regions; lettering, stripes, and gradients reconstruct as stair-stepped rectangles at 1px scale.
- **Diagnostics:** `/mineplus model info <key>` reports faces baked, texel grids, palette usage histogram, merged plate counts, and budget verdicts; texture entries show `[png]`/`[no png]`.

---

### Voxel Reconstruction

Voxel reconstruction replaces a model's whole display list with a **texel-aware 1×1×1 block lattice**: every occupied lattice voxel becomes one palette-quantized block (sampled area-weighted from the textures its faces map onto it), fully enclosed voxels are culled, and same-color voxels greedy-merge into XZ rectangles per Y level — a uniform 16×16 floor is a single `BlockDisplay`.

- **When it activates (`AUTO`, default):** non-animated, all cubes axis-aligned, all cube bounds snapped to the voxel lattice of the origin mode, and at least one resolvable texture PNG — i.e. genuinely blocky, texture-backed models where one-block voxels are a *better* representation than cube plates. Everything else (rotated cubes, sub-block geometry, animated models) keeps the legacy pipeline untouched. `ON` attempts any non-animated model, approximating off-lattice geometry; `OFF` (or a per-model `"voxelMode": "OFF"` meta) never voxelizes — the recommended pin for texel-plate showcase models, which are otherwise AUTO-eligible.
- **Color fidelity:** voxel colors come from the same `TexelSampler` the texel plates use (window mapping, in-plane rotation, cutout alpha), so the two representations can never disagree; buried seam faces between abutting cubes are gated out exactly like the texel baker's occlusion culling.
- **Guards:** a probed-cell ceiling aborts pathological models and the display budget (`MAX_DISPLAYS`, per-model `maxVoxelDisplays`) falls back to the legacy pipeline — a model is never partially reconstructed.
- **Diagnostics:** `/mineplus model info <key>` reports the strategy decision with its rationale, occupied/surface/culled voxel counts, merged display runs against the budget, palette usage, and bake time.

---

## Admin Command Reference

| Command | Description |
|---|---|
| `/mineplus status` | Runtime overview: types, instances, processes |
| `/mineplus reload [all\|models\|multiblocks\|recipes]` | Hot-reload content without a restart |
| `/mineplus model list [limit]` | List active instances |
| `/mineplus model inspect [look\|uuid]` | Full diagnostics: cubes, textures, occupancy layers, cache status |
| `/mineplus model remove [look\|uuid]` | Remove an instance |
| `/mineplus model respawn [look\|uuid]` | Respawn an instance's rendering |
| `/mineplus model setlevel <look\|uuid> <level>` | Force an instance's level |
| `/mineplus model models` | List all loaded model keys |
| `/mineplus model debugspawn <modelKey>` | Spawn a raw model on the looked-at face |
| `/mineplus model cleanup` | Sweep loaded chunks for ghost `BlockDisplay` entities (stale renders from a previous session or crashed swap) and remove them; also runs automatically on startup after instance reconcile |

**Permissions:** `mineplus.admin.status`, `mineplus.admin.reload`, `mineplus.admin.model` — all default to op.

---

> ➡️ **Next:** wiring behavior to these definitions — the [Developer API](developer-api.md), or pick a ready-made path in [Extension Workflows](extension-workflows.md).
