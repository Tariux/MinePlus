---
name: blockbench-kickstart
description: Fast path for authoring Blockbench models and animations through the live MCP at http://localhost:6960/bb-mcp. Use when the user asks for a new model, prop, rock, machine, item, block or mob rig and wants it built quickly. One JSON spec builds geometry, UVs, a single-file vanilla-style pixel atlas, textures and animation clips, exports the .bbmodel, renders a preview, and verifies UVs/palette/spec-drift in one command.
---

# Blockbench Kickstart

## Fast path

```bash
node skills/blockbench-kickstart/scripts/model-build.mjs specs/my.spec.json --preview
node skills/blockbench-kickstart/scripts/verify-model.mjs specs/my.spec.json --target <target>
```

Two commands: build, then verify. `verify-model.mjs` wraps the pre-flight and adds
the numeric checks (UV 1:1, PNG size, palette count, spec/model drift), so do not
spend turns decoding the PNG or dumping the `.bbmodel` by hand. Look at the
preview only to judge the silhouette.

Camera-only iteration, no rebuild (reuses the open project):

```bash
node skills/blockbench-kickstart/scripts/preview-shot.mjs specs/my.spec.json
```

Build a model with **one spec file and one command**. Do not drive tools one
call at a time from the shell: every `bb-mcp-call.mjs` invocation pays a fresh
handshake, and PowerShell mangles inline JSON.

`model-build.mjs` holds **one** MCP session and runs the whole pipeline:
`validate spec → save_checkpoint → create_project → create_texture → add_group →
place_cube → set_cube_uv → create_animation → list_outline → export → pre-flight → preview`.

## 1. Decide three things first

| Question | Answer | Consequence |
|---|---|---|
| Is there a resource pack? | No → `target: "virtual"`; yes → `pack-item` / `pack-block` | Virtual = one material per cube, full-face UVs only |
| Which coordinate space? | `virtual`/`pack-block` → `free`, CENTER (x/z −8..8, y 0..height); `pack-item` → `java_block`, 0..16; `mob` → `bedrock`, feet y=0, front −z | Wrong space = model swings out of its block or sits 8 px off |
| Animated? | Group-level rotation/position/scale only | Cubes outside a group cannot animate |

**Preview targets live in viewport space, not authored space.** Blockbench draws
the `java_block` 0..16 cell centred on the origin (x/z −8..8), so a preview
target of `[8, y, 8]` frames the model off-centre and clips it. `preview.mjs`
derives the target from the cube bounds minus that offset; omit
`preview.target` and `preview.position` unless you want an override.

Then pick the texturing route — it decides the format, not the other way round:

| Route | Format + target | Texture | Animated |
|---|---|---|---|
| Vanilla, one PNG | `java_block` + `pack-block` / `pack-item` | one `atlas` sheet, per-face UV windows | no (vanilla blocks and items are static) |
| Packless prop | `free` + `virtual` | one material per cube, full-face UVs | yes |
| Mob rig | `bedrock` + `mob` | one sheet, box UV | yes, needs `idle` + `walk` |

**Default to the vanilla route.** A single 32×32 or 64×64 sheet that ships as one
PNG beside the model is what vanilla blocks, items and mobs actually use.

Starting points by ask:

| Ask | Spec to copy |
|---|---|
| rock, pebble, boulder | `specs/boulder.spec.json` (vanilla block, stone atlas, baked shading) |
| flower, plant, small block decoration | `specs/cactus_flower.spec.json` (vanilla block, literal pixel art) |
| prop, machine, gem, animated item | `templates/crystal_rock.spec.json` (packless, CENTER, animations) |

## 2. Spec format

Start from `templates/crystal_rock.spec.json` (packless prop),
`specs/boulder.spec.json` (vanilla stone with per-face anchors and baked top/bottom
shading) or `specs/cactus_flower.spec.json` (vanilla single-atlas block) — copy,
rename, edit the numbers. Keep specs at `specs/<name>.spec.json` (BOM-free).
Every field except `name`, `cubes`, `out` is optional. The driver validates the
whole spec before it opens the MCP session, so a typo cannot cost the project
that `create_project` replaces.

```jsonc
{
  "name": "cactus_flower",         // .bbmodel stem, also the model key
  "format": "java_block",          // free | java_block | bedrock
  "target": "pack-block",          // virtual | pack-item | pack-block | mob
  "out": "E:/Vesktop/blockbench-mcp-project/art/props",

  "atlas": {                       // the whole texture, drawn in spec
    "name": "cactus_flower",       // one PNG, same stem as the model
    "width": 32, "height": 32,     // <= 64; sets the project resolution
    "palette": {                   // <= 12 colours, referenced by name
      "leaf": "#568f3c", "leafLite": "#6fae4e", "leafDark": "#3d6b2c"
    },
    "art": [
      { "rect": [0, 0, 16, 16], "fill": "leaf" },              // flat region
      { "rect": [3, 0, 1, 16], "fill": "leafDark" },           // 1 px rib
      { "rect": [0, 16, 8, 8], "outline": "leafDark" },        // inner 1 px border
      { "px": [[10, 18], [13, 18]], "fill": "leafDark" }       // single pixels
    ]
  },

  "groups": [
    { "name": "cactus", "origin": [8, 0, 8] },
    { "name": "flower", "origin": [8, 14, 8], "parent": "cactus" }
  ],

  "cubes": [
    { "name": "trunk", "group": "cactus", "from": [4, 0, 4], "to": [12, 12, 12],
      "origin": [8, 0, 8],
      "uv": [0, 0, 16, 16],                       // all six faces
      "faces": { "up": [16, 0, 32, 16] } },       // per-face override
    { "name": "petal", "group": "flower", "from": [6, 14, 3], "to": [10, 16, 6],
      "origin": [8, 15, 8], "rotation": [0, 45, 0], "uv": [0, 16, 8, 24] }
  ],

  "meta": { "originMode": "GRID" },               // written as <name>.meta.json
  "preview": { "position": [26, 26, -38], "target": [0, 8, 0], "fov": 38 }  // viewport space
}
```

Atlas art ops — all coordinates are atlas pixels, origin top-left:

| Op | Effect |
|---|---|
| `{ "at": [x,y], "pattern": [rows], "map": {char: colour} }` | literal pixel art, one char per texel, `.` empty |
| `{ "rect": [x,y,w,h], "fill": colour }` | solid region |
| `{ "rect": [...], "dither": [colour, colour], "size": 2 }` | checkerboard, for large flat areas only |
| `{ "rect": [...], "outline": colour }` | 1 px inner border |
| `{ "rect": [...], "noise": 0.02, "seed": 3 }` | tone jitter on painted pixels |
| `{ "px": [[x,y], ...], "fill": colour }` | individual pixels |

`colour` is a palette key or `#rrggbb`. Transparent atlas space is untouched.

Flags: `--preview` (write `<name>-preview.png`), `--no-preview` (suppress the
preview even when the spec sets one), `--no-export` (build only), `--out <dir>`
(override `spec.out`).

**Real palettes beat invented ones.** Fetch the vanilla block (see
[blockbench-vanilla-textures](../blockbench-vanilla-textures/SKILL.md)) and
transcribe the 16×16 tile into one `pattern` op — one char per texel is diffable
and exact. Mojang stone is four greys: `#8f8f8f #7f7f7f #747474 #686868`.
Duplicating that pattern with each char shifted one palette step gives a lit top
and a dark underside for free, so four colours still shade in three directions.
`specs/boulder.spec.json` does exactly that: side at `(0,0)`, lighter top at
`(16,0)` wired to `faces.up`, darker base at `(0,16)` wired to `faces.down`.

**What the script enforces for you** — these are the things that silently break models:

- `uv: "fit"` sizes every face window to **that face's own pixel size**, so one
  texel covers one Blockbench unit. This is the vanilla scale rule; without it a
  16×16 window on an 8 px face renders at double density and looks wrong next to
  real blocks. `uvFrom: [x, y]` is the atlas anchor; `faces: { up: [x, y] }`
  overrides the anchor for one face (use it to point the top at a separate region).
- An `atlas` model must declare `uv` (or `faces`) on every cube; there is no
  default window, and the validator says so instead of guessing.
- An atlas forces a pack target: the packless `virtual` engine renders one
  material per cube with full-face UVs and cannot sample a sheet.
- The project resolution is set from the atlas size. `create_texture` alone
  leaves it at 16, and the pack writer scales every UV by `16/resolution`.
- Texture paths are rewritten to the bare `<name>.png` the exporter writes, so
  the `.bbmodel` never points at a temp file.
- Formats without animation support (`java_block`) reject clips in the validator
  instead of failing halfway through a build.
- `meta` is handed to the exporter, which writes `<name>.meta.json` beside the
  model. That is the only place `originMode`, `autoplay`, `collisionMode` and
  `itemParent` can be set.
- Cubes are batched per `group|texture`, so one `place_cube` call per material.
- Without an atlas, `uv: "full"` writes a full 0..16 window, `"half"` an exact
  half, `[u1, v1, u2, v2]` that window verbatim. `uv: "auto"` on a virtual target
  warns: Blockbench's auto-UV produces sub-pixel windows the engine stretches.
- Legacy multi-texture specs still work: `textures` with `fill`, `top`/`bottom`
  (painted ramp) or `png`. Use them for packless props, not for vanilla art.

## 3. Vanilla Minecraft style

Vanilla art is small, hard-edged and cheap to read at a distance. These are the
rules that make a model sit inside the vanilla world instead of next to it.

**Scale — the rule that matters most**
- **One texel covers one Blockbench unit.** A 12 px wide face gets a 12 px wide
  UV window, not 16. Use `uv: "fit"` and let the driver compute it. Mapping a
  16×16 window onto an 8 px face doubles the pixel density and the model stops
  matching the blocks beside it.
- 16 px = one block. A block model fills one 16×16×16 cell; a mob or item sheet is
  16, 32 or 64 px per side. Never scale a texture up.
- One sheet per model, sized 16–64 px, exported as a single PNG. Unused atlas
  space stays transparent.

**Palette — copy the real one**
- Do not invent greens and pinks. Fetch the actual block and read its colours;
  [blockbench-vanilla-textures](../blockbench-vanilla-textures/SKILL.md) resolves
  a block ID to Mojang's file, and the palette is then a fact rather than a guess.
- Vanilla cactus, measured from `blocks/cactus_side` and `cactus_top`:
  `#649832` `#5b8c2b` `#527d26` `#497122` `#426520` `#39581a` `#41641e`, with
  `#9fa76d` and `#babf95` as the pale spine highlights. That is a muted olive,
  not a saturated green — the saturation is the usual tell of a fake texture.
- Vanilla pink flower (`blocks/cactus_flower`): `#ffb0ab` `#f88584` `#d27088`
  `#a15779`. Vanilla yellow (`dandelion`): `#ffec4f` `#e8d13c` `#b89a28`.
- 3–5 shades per material, at most 12 colours for the whole model. The validator
  rejects more than 16.
- Shade by hue, not only brightness. Pure `#000000` and `#ffffff` do not appear
  in vanilla textures.
- Pick the palette first, then paint. Named palette entries keep the model
  consistent and make a stray colour obvious.

**Shading**
- One light direction, from above and slightly front. Top faces lightest, sides
  mid, bottom darkest — baked into the texture, not into separate materials.
- Silhouette edges get a 1 px border of a *darker shade of the same hue*, never
  a black outline.
- Vanilla surface textures are noisy, not striped: scattered 1 px highlights read
  as stone, cactus spines or wood grain. Clean vertical stripes read as plastic.

**Pixels**
- Hard edges only: no anti-aliasing, no gradients, no soft brushes, no scaling
  filters. Every pixel is a deliberate decision.
- Detail lives in 1 px lines and 2×2 clusters. A 2×2 checkerboard is the only
  dithering vanilla uses, and only on large flat areas.
- Keep symmetric details symmetric; put the asymmetry in the silhouette, not in
  the texture.
- Write literal art with the `pattern` op — one char per texel is readable,
  diffable and exact. Copying Mojang's own rows guarantees the look.

**Forms**
- Blocky, axis-aligned, chunky. Rounded shapes are 1–2 px steps, not bevels.
- Cute reads as: big simple masses, few details, high-contrast focal feature
  (flower, eye, gem) and a small number of colours.
- Cubes align to the grid; rotations are multiples of 45° on one axis only. The
  pack writer degrades anything else to a bounding box.
- Reuse one UV window across repeated parts (petals, legs, teeth) — that is what
  keeps the sheet small.

## 4. Geometry that reads as a model, not a box

- **Silhouette first**: one big core, then 3–6 smaller lumps intersecting it by
  0.5–1 px. Overlap hides seams; gaps read as broken.
- **Radial rings**: author one cube offset along −z, then repeat it with
  `rotation: [0, 45, 0]`, `[0, 90, 0]`, … about the *same* origin. Every copy lands
  on the same radius, so petals, spikes and fins cost one line each. Verify by
  reading the exported centres, not by eye.
- **Taper organic stacks**: a body plus a slightly narrower cap reads as a plant,
  mushroom or cactus; two stacked boxes of equal width read as a crate.
- **Tilt with yaw only** (`rotation: [0, ±5..30, 0]`) on anything touching the
  floor. Yaw keeps the base flat at y=0; pitch/roll lifts corners off the ground.
- Vanilla/pack models use whole pixels in 0..16 (GRID) and fill exactly one block
  cell. Packless props use CENTER, x/z inside −8..8. A lump that pokes past the
  cell claims an extra collision block.
- Asymmetry sells it: different sizes, different yaw, one shard leaning up.
- `inflate: 0.25` fattens a cube without touching `from`/`to` — good for pebbles,
  but it breaks whole-pixel alignment, so skip it on vanilla block models.

**Rock/boulder recipe that reads** (measured, `specs/boulder.spec.json`): one low
wide base, two differently sized shoulders on opposite sides topping out at
different heights, two 45°-yawed 6×6×6 crowns offset from centre, one smaller
peak, a front lump and a corner pebble. Centred tiers of shrinking boxes read as
a cake or a cairn; offset crowns at unequal heights read as a boulder. Keep the
yaw at 45° so the pack writer keeps the rotation, and put each rotated cube's
`origin` at its own centre so it cannot swing out of the cell.

## 5. Animations

Only formats with `animation_mode` animate: `free` and `bedrock`. Vanilla blocks
and items (`java_block`) are static — the validator rejects clips for them rather
than failing at `create_animation`.

Clips key **outliner group names**, in degrees, in seconds. Linear only —
`catmullrom`/`bezier` are sampled linear by the importer, and Molang evaluates to 0.

| Motion | Keys on the group |
|---|---|
| Float / bob | `position` `[0,0,0] → [0,0.4,0] → [0,0,0]` over 2 s, loop |
| Slow spin | `rotation` `[0,0,0] → [0,360,0]` over 4 s, loop |
| Sway | `rotation` `[0,-6,0] → [0,6,0] → [0,-6,0]` over 3 s, loop |
| Pulse | `scale` `[1,1,1] → [1.08,1.08,1.08] → [1,1,1]` over 1.2 s, loop |
| Machine cycle | two groups, opposite rotation, 2 s loop |
| Mob walk | legs `rotation.x` `+25 → −25 → +25` over 1 s, opposite phase per leg |

Rules: the group must exist before `create_animation`; animation names get an
`animation.` prefix automatically; a virtual machine needs the clip listed in the
multiblock level's `animations` array (or `.meta.json` `autoplay`) to start.
Mobs need `idle` **and** `walk`, or every part stays STATIC and the rig T-poses.

## 6. Tool cheatsheet (when the spec is not enough)

All calls are one JSON object; run them through
`node skills/mineplus-model-authoring/scripts/bb-mcp-call.mjs <tool> '<json>'`
or add them to the script.

| Need | Tool | Args |
|---|---|---|
| Check what is loaded | `get_capabilities` | `{include_tools:false}` |
| Current project state | `get_project_info` | `{}` |
| Undo marker before big edits | `save_checkpoint` | `{name}` |
| Replace project (destroys work) | `create_project` | `{name, format}` |
| Group / bone | `add_group` | `{name, origin:[x,y,z], parent:"root"}` |
| Cubes | `place_cube` | `{elements:[{name,from,to,origin,rotation,inflate}], texture, group, faces:true}` |
| Resize / move one cube | `modify_cube` | `{id, from, to, origin, rotation}` |
| Per-face UV or material | `set_cube_uv` | `{id, faces:{north:{uv:[u1,v1,u2,v2], texture}}}` |
| Read UVs back | `get_cube_uv` | `{id}` |
| Texture from PNG or colour | `create_texture` | `{name, width, height, data:"<abs png>", fill_color:"#rrggbb"}` |
| Animation | `create_animation` | `{name, loop, animation_length, bones:{group:[{time,rotation,position,scale}]}}` |
| Edit keys later | `manage_keyframes` | `{action, bone_name, channel, keyframes}` |
| Verify hierarchy | `list_outline` | `{include_cubes:true}` |
| Verify textures | `list_textures` | `{}` |
| Render a view | `create_offscreen_view` + `set_camera_angle` | `{id,width,height,copy_view:"none"}` then `{view, position, target, projection, fov}` |
| Dump the project | `export_model` | `{codec_id:"project", path, overwrite:true, max_content_length:0}` |
| Bulk geometry from JSON | `from_geo_json` | `{geojson}` — Bedrock 1.12+ |
| Arbitrary JS (no undo) | `risky_eval` | `{code}` — return plain data, never objects |

Disabled in this build: `activate_texture`, `apply_texture`, and there are no
display-transform tools. Assign textures via `place_cube.texture` /
`set_cube_uv.faces.*.texture`; author display poses in `.meta.json`.

### Script shortcuts

| Need | Script |
|---|---|
| Build + export + preview from a spec | `scripts/model-build.mjs <spec> --preview` |
| Camera-only re-render of the open project | `scripts/preview-shot.mjs <spec> [--position x,y,z] [--target x,y,z] [--fov n]` |
| Verify UVs, palette, resolution, spec drift | `scripts/verify-model.mjs <spec.json\|model.bbmodel> [--spec <spec>]` |
| Raw MCP tool call | `skills/mineplus-model-authoring/scripts/bb-mcp-call.mjs <tool> '<json>'` |

**PowerShell quoting.** PowerShell strips the double quotes from inline JSON
passed to a native exe, so `bb-mcp-call.mjs place_cube '{"elements":[]}'` arrives
as `{elements:[]}` and fails to parse. Write the arguments to a `.json` file and
read them, or use a script that takes positional arguments. Windows PowerShell's
`Set-Content -Encoding utf8` also writes a BOM; strip `^\uFEFF` before parsing.

## 7. Validate and deliver

**Verify in one command.** `verify-model.mjs` wraps `check-bbmodel.mjs --json`
and adds the checks that used to need ad-hoc scripts: every face window vs its
own pixel size (1 texel per unit), PNG size vs project resolution, distinct
colours vs the declared palette, and spec/model drift (a stale build). Four
lines, exit 0/1/2.

```bash
node skills/blockbench-kickstart/scripts/verify-model.mjs \
  specs/boulder.spec.json --target pack-block
```

The preview PNG is a deliverable, not proof: judge the silhouette, not the
numbers. For repeated features (petal rings, legs, teeth) recompute each cube
centre about its origin and assert one shared radius; `--json` exposes
`bounds`/`anchor` for that.

Under it, the pieces (use directly only when exporting to a runtime folder):

```bash
node skills/mineplus-model-authoring/scripts/check-bbmodel.mjs <model.bbmodel> --target virtual|pack-item|pack-block|mob
node skills/mineplus-model-authoring/scripts/export-to-mineplus.mjs --out "<server>/plugins/Mineplus/models" --name <key> --target pack-block --overwrite
```

Exit 0 = pass, 1 = warnings (renders wrong), 2 = errors (blocking). The export
script writes `<key>.bbmodel` plus every texture as `<name>.png` beside it, then
runs the check — that is why the driver calls it instead of `export_model`. An
atlas model delivers `<key>.bbmodel` + `<name>.png`, plus `<name>.meta.json` and
`<name>-preview.png` when the spec asks for them.

Things the scripts already handle, in case you call tools by hand:

- A texture created from `fill_color` or a file exports with a `path` that the
  importer cannot resolve. The driver rewrites it to the bare `<name>.png` via
  `risky_eval` (inside an undo scope) before export.
- `create_offscreen_view` refuses an id that already exists. `preview.mjs` lists
  views first, reuses `kickstart_preview` (resizing it back to 640×640), and only
  falls back to the active viewport when no view can be had — restoring that
  viewport's camera afterwards.
- `delete_offscreen_view` throws `onContextMenu is not defined` in this build and
  the plugin allows four views at most. Never create a fresh id per shot.
- `create_project` replaces the open project. After a Blockbench restart the app
  may reopen a different model, so `preview-shot.mjs` checks the open project
  name and refuses to shoot a mismatch; rebuild instead.

## 8. Fast triage

| Symptom | Cause |
|---|---|
| Spec rejected before `connect` | `spec … is invalid` — every listed problem is a fix; the open project was not touched |
| `target "virtual" cannot sample an atlas` | atlas route needs `pack-item` or `pack-block` |
| `format "java_block" has no animation support` | vanilla blocks are static; drop the clip or use `bedrock` + `mob` |
| **Texture detail is too big or too small on the model** | UV window does not match the face pixel size. Use `uv: "fit"`; 1 texel must cover 1 unit |
| Texture reads as one flat colour | one `fill` and nothing else; add 1 px detail, a border or a shade step |
| Colours look fake next to real blocks | palette was invented; fetch the vanilla block and copy its hex values |
| Texture looks blurry or painted | gradient/ramp or noise on a vanilla model; use flat fills and hard pixels |
| More than ~12 colours in the sheet | palette was not declared, or `noise` was used on a vanilla model |
| White / concrete-coloured cube | texture name is not a Bukkit material, or that cube has no texture |
| Model sinks or floats | authored in the wrong space (CENTER vs GRID) |
| One face stretched | partial UV window on a virtual target, or a window that ignores the face aspect |
| Rotation swings the model out of the block | `origin` missing on the cube or group pivot not at the joint |
| Animation does nothing | keys target a cube, not a group, or clip not autoplayed |
| Preview empty | camera target above/below the model, or no textures assigned |
| Preview off-centre or clipped | target used authored centre `[8, y, 8]`; previews live in viewport space (`java_block` draws 0..16 centred on the origin). Let `preview.mjs` compute it |
| Preview shows another model | Blockbench restarted onto a different project; `preview-shot.mjs` now refuses a name mismatch — rebuild or open the right model |
| `create_offscreen_view: already exists` | do not create a fresh id per shot; reuse through `preview.mjs` / `preview-shot.mjs` |
| `bb-mcp-call.mjs`: `Invalid JSON arguments` | PowerShell ate the double quotes; pass args from a JSON file |
| `verify-model`: "Model does not match the spec" | stale build; rerun `model-build.mjs` before verifying |
| `create_project` ate the work | it replaces the open project — checkpoint first |

## 9. Deeper references

Scripts in this folder: `model-build.mjs` (spec → project → export → preview),
`preview.mjs` (shared viewport-space camera + offscreen-view reuse), `preview-shot.mjs`
(camera-only re-render), `verify-model.mjs` (post-build UV/palette/spec checks),
`png.mjs` (zero-dependency PNG writer and atlas renderer).

Read only what the task needs:

| File | Contents |
|---|---|
| `skills/mineplus-model-authoring/references/mcp-playbook.md` | Full 147-tool catalog, exact schemas, batching, `risky_eval` recipes |
| `skills/mineplus-model-authoring/references/target-recipes.md` | Per-target build recipes with numbers |
| `skills/mineplus-model-authoring/references/pipeline-and-limits.md` | What the importer, texel baker, pack writer and mob rig actually do |
| `skills/mineplus-model-authoring/references/mineplus-integration.md` | Folders, `.meta.json`, multiblock JSON, Java registration |
| `skills/mineplus-model-authoring/references/troubleshooting.md` | Symptom → cause → fix matrix |
| `skills/blockbench-vanilla-textures/SKILL.md` | Fetch Mojang's own block/entity textures, real palettes, tinting |
