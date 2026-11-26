# Target Recipes

Concrete build recipes per delivery target. Every recipe assumes the live MCP is
connected (see [SKILL.md](../SKILL.md#1-connect-before-anything-else)) and that the
open Blockbench project is disposable — `create_project` replaces it.

Runtime paths below are written `<server>/plugins/Mineplus/...`; substitute the live
server root (the repo `E:/The Backup/Backups Main/16 July 2026/mineplus` is the
**source** tree, not necessarily the running server).

| Target | Blockbench format | Coordinate space | Export codec |
|---|---|---|---|
| Virtual machine / multiblock | `free` | CENTER: x/z −8..8, y 0..16 per block | `project` |
| Pack item | `java_block` | 0..16 px, y up | `project` |
| Pack block | `free` | CENTER, y 0..height | `project` |
| Custom mob rig | `bedrock` | feet y=0, front −z, pixels | `project` |
| Texture-only mob | none (PNG sheet) | vanilla entity texture layout | — |
| Gun frame | `java_block` | 0..16 px, y up | `java_block` |
| Debug model | `free` | CENTER | `project` |

---

## 1. Virtual multiblock / machine

The packless axis: one `BlockDisplay` per cube, textures resolved to vanilla
materials, optional texel reconstruction from a real PNG.

**Authoring rules**
- `free` format. Pixel (0,0,0) is the centre of the anchor block at its base:
  a single block spans x/z −8..8, y 0..16. Multi-block machines extend past those
  bounds; add `originMode: "GRID"` in the meta file if you author from a corner.
- One cube = one material. Split a two-material part into two cubes.
- Texture names must resolve to a Bukkit material: `stone_bricks`, `oak_planks`,
  `iron_block`, `polished_andesite`. Never `texture`.
- Name outliner groups after joints. They are the only animation targets.
- Optional `.meta.json` for collisions/texel budgets (see
  [integration](mineplus-integration.md#3-meta-json)).

**MCP sequence**
```jsonc
{"name":"crusher_lv1","format":"free"}

{"name":"body","origin":[0,8,0],"parent":"root"}
{"name":"gear","origin":[0,12,0],"parent":"body"}

{"elements":[
  {"name":"base","from":[-6,0,-6],"to":[6,3,6]},
  {"name":"column","from":[-2,3,-2],"to":[2,13,2],"origin":[0,3,0]}
],"texture":"stone_bricks","group":"body","faces":true}

{"elements":[{"name":"wheel","from":[-5,5,-7],"to":[5,13,-5]}],
 "texture":"iron_block","group":"gear","faces":true}

{"name":"idle","loop":true,"animation_length":2.0,
 "bones":{"gear":[{"time":0,"rotation":[0,0,0]},{"time":2,"rotation":[0,360,0]}]}}
```

**Export**
```bash
node scripts/export-to-mineplus.mjs \
  --out "<server>/plugins/Mineplus/models" --name crusher_lv1 --target virtual --overwrite
```
Ship the PNGs beside the `.bbmodel` (`bookshelf.png`, `Alchemy_Texture.png`, …).
Without a PNG the cube still renders its resolved material; with a PNG the texel
baker reconstructs the actual artwork from flat palette blocks.

**Wire** — `plugins/Mineplus/multiblocks/crusher.json`:
```json
{
  "id": "crusher",
  "name": "Crusher",
  "levels": {
    "1": {
      "model": "models/crusher_lv1.bbmodel",
      "speed": 1.0,
      "durability": 100.0,
      "upgradeCost": { "core_plate": 8 },
      "animations": ["idle"]
    }
  }
}
```
`/mineplus reload all`, then `/mineplus model info crusher_lvl1` and
`/mineplus model debugspawn crusher_lvl1`. Place real instances through a module
hook / `InfrastructureApi`; `debugspawn` is admin-only and clears the target area.

**Animation at runtime**: autoplay from the level's `animations` array, or
`AnimationApi.playAnimation(instanceId, "idle")`.

---

## 2. Pack item (full fidelity, pack required)

A vanilla-backed item whose real geometry and texture are delivered in the
generated resource pack. Identity, crafting and gameplay are unchanged; without
the pack the player sees the backing material.

**Authoring rules**
- `java_block` (or `java_item`) so coordinates are **0..16 px**, y up, corner
  origin. Pack item serialization does **not** shift the origin — a model authored
  in `free` CENTER space lands 8 px off.
- One texture per model. Its **last filename segment** is the pack asset key:
  `strad_wine.png` → `assets/fun/textures/item/strad_wine.png`. Two models with
  the same file stem collide.
- Author `display` contexts in Blockbench. They are copied into the item model;
  `.meta.json` `display` overrides them. Without them the item uses generic item
  poses and looks wrong in hand.
- Rotations here are real element rotations (the pack path serializes them), but
  multi-axis or non-45° rotations degrade to their AABB in the pack writer.
  Keep pack rotations on one axis, multiples of 45°.
- `light_emission` on cubes becomes pack brightness (`PACK.LIGHTING`); this is
  per-model, not per-face.

**MCP sequence**
```jsonc
{"name":"strad_wine","format":"java_block"}
{"add_group": {"name":"bottle","origin":[8,0,8]}}
{"place_cube": {"elements":[
  {"name":"body","from":[6,1,6],"to":[10,9,10]},
  {"name":"neck","from":[7,9,7],"to":[9,12,9]}
],"texture":"strad_wine","group":"bottle","faces":true}}
{"create_texture":{"name":"strad_wine","width":16,"height":16,
  "data":"E:/art/strad_wine.png"}}
```
Set the display pose in Blockbench's Display tab before export (the MCP build has
no display-transform tools), or write `display` in the meta file.

**Export**
```bash
node scripts/export-to-mineplus.mjs \
  --out "<server>/plugins/Mineplus/models" --name strad-wine --target pack-item \
  --meta '{"itemParent":"item/handheld"}' --overwrite
```
`itemParent` is optional (`item/handheld` for tools, `item/generated` is the
default). The script writes `strad-wine.bbmodel` + `strad_wine.png`.

**Register** (module `onEnable`, before the coordinated model reload):
```java
context.packApi().registerItem(PackItemDefinition.builder(
        "fun", "strad_wine", Material.GLASS_BOTTLE, "strad-wine")
    .displayName("Strad Wine Bottle")
    .category(ItemCategory.UTILITY)
    .descriptionLines(List.of("Rendered at full client fidelity."))
    .build());
```
The model key is the file stem (`strad-wine`). The item is obtained with
`packApi.createItem("fun", "strad_wine")` or `ItemRegistry`.

**Verify**: `/mineplus pack status` (artifact + URL), `/mineplus pack recompile`,
`/mineplus pack push <player>`, `/mineplus pack verify`. A missing sprite shows the
purple/black checkerboard; a missing model shows the vanilla backing item.

---

## 3. Pack block (one BlockDisplay, pack required)

A real block model delivered in the pack and drawn by one `BlockDisplay` carrying
an allocated `note_block` state. Placement, collision, breaking and persistence
stay with the ordinary multiblock lifecycle.

**Authoring rules**
- `free`, CENTER block space (x/z −8..8, y 0..height). Geometry may exceed one
  block, but every occupied cell becomes a collision barrier.
- Author the **full block silhouette** — the client draws this model in place of a
  vanilla block, so there is no invisible host cube to hide.
- Textures live under `textures/block/`: name them like a block texture
  (`alchemy_top.png`), one unique stem each.
- No rotation limits beyond the pack writer's AABB degradation; still prefer
  axis-aligned geometry.
- Model must be closed enough to look right from all six sides; players can walk
  around it.

**MCP sequence** is identical to the virtual recipe except the texture name (it
becomes `block/<name>` in the pack) and the multiblock JSON.

**Export**
```bash
node scripts/export-to-mineplus.mjs \
  --out "<server>/plugins/Mineplus/models" --name alchemy-table --target pack-block --overwrite
```

**Wire** — `plugins/Mineplus/multiblocks/alchemy_table.json`:
```json
{
  "id": "alchemy_table",
  "name": "Alchemy Table",
  "levels": {
    "1": {
      "model": "models/alchemy-table.bbmodel",
      "renderMode": "pack_block",
      "speed": 1.0,
      "durability": 10.0
    }
  }
}
```

**Register the render binding** (required for multiblock pack blocks):
```java
context.packApi().registerBlock(PackBlockDefinition.builder(
        "fun", "alchemy_table", "alchemy_table_lvl_1")
    .geometryModelKey("alchemy-table")
    .displayName("Alchemy Table")
    .carrier(PackBlockCarrier.NOTE_BLOCK)
    .build());
```
`modelKey` must equal `ModelRenderingManager.buildModelKey(typeId, level)` —
`<typeId>_lvl_<level>` lowercased. `geometryModelKey` is the loaded file stem.
Register before the reload; assets attach during the coordinated recompile.

**Verify**: place the machine, `/mineplus pack status`, `/mineplus render stats`.
Without the pack the level degrades to the virtual engine; with the pack but no
allocated carrier state you see a normal note block.

---

## 4. Custom mob rig (bone-animated entity)

A vanilla base mob carrying a Mineplus identity, hidden and replaced by per-part
`ItemDisplay`s driven by the model's own outliner and clips.

**Authoring rules**
- `bedrock` format (or `modded_entity`), entity space: **feet at y = 0**, model
  front is **−z**, pixels are 1/16 block (`modelHeightPx` 24 for a standard
  quadruped, 32 for a villager-scale humanoid).
- Every outliner group becomes a render bone: name groups meaningfully
  (`head`, `body`, `right_leg`, `left_arm`, `spear`). Pivots are the authored
  absolute pivots.
- Clips must be named `animation.idle`, `animation.walk`, optionally
  `animation.attack` — `create_animation {name:"idle"}` adds the prefix.
  Walk and idle blend by movement speed; attack is a one-shot overlay.
- If you skip clips, `partsFromOutliner()` marks every part **STATIC**, so the rig
  T-poses. Either add idle/walk clips or override roles in the sidecar.
- One texture sheet, mapped with vanilla-style box UV or per-face UV; keep the
  PNG next to the `.bbmodel` in module resources.
- No meshes, no armatures — cubes and outliner groups only.

**MCP sequence**
```jsonc
{"name":"soldier_villager","format":"bedrock"}
{"add_group":{"name":"head","origin":[0,24,0],"parent":"root"}}
{"add_group":{"name":"body","origin":[0,18,0],"parent":"root"}}
{"add_group":{"name":"right_leg","origin":[-2,12,0],"parent":"root"}}
{"add_group":{"name":"left_leg","origin":[2,12,0],"parent":"root"}}

{"place_cube":{"elements":[{"name":"head_geo","from":[-4,24,-4],"to":[4,32,4]}],
 "texture":"soldier_villager","group":"head","faces":true}}
{"place_cube":{"elements":[{"name":"body_geo","from":[-4,12,-2],"to":[4,24,2]}],
 "texture":"soldier_villager","group":"body","faces":true}}
{"place_cube":{"elements":[{"name":"leg_r","from":[-4,0,-2],"to":[-1,12,2]}],
 "texture":"soldier_villager","group":"right_leg","faces":true}}
{"place_cube":{"elements":[{"name":"leg_l","from":[1,0,-2],"to":[4,12,2]}],
 "texture":"soldier_villager","group":"left_leg","faces":true}}
```

```jsonc
{"name":"idle","loop":true,"animation_length":2.0,
 "bones":{"head":[{"time":0,"rotation":[0,0,0]},{"time":1,"rotation":[2,0,0]},
                  {"time":2,"rotation":[0,0,0]}]}}
{"name":"walk","loop":true,"animation_length":1.0,
 "bones":{"right_leg":[{"time":0,"rotation":[25,0,0]},{"time":0.5,"rotation":[-25,0,0]},
                       {"time":1,"rotation":[25,0,0]}],
          "left_leg":[{"time":0,"rotation":[-25,0,0]},{"time":0.5,"rotation":[25,0,0]},
                      {"time":1,"rotation":[-25,0,0]}]}}
```

**Export** into the module's resources:
```bash
node scripts/export-to-mineplus.mjs \
  --out "E:/The Backup/Backups Main/16 July 2026/mineplus/examples/mineplus-mobfun/src/main/resources/mobs" \
  --name soldier_villager --target mob --overwrite
```
The script writes `soldier_villager.bbmodel` and `soldier_villager.png`.

**Wire**:
```java
CustomMobModel model = BedrockMobModel.parse(modelBytes)
        .textureName("soldier_villager")
        .partsFromOutliner()
        .animationClips()
        .build(textureBytes);

context.customMobs().register(this, CustomMobDefinition
        .builder("mobfun:villager_soldier", EntityType.VILLAGER, model)
        .displayName("Villager Soldier")
        .attributes(CustomMobAttributes.builder()
                .maxHealth(60.0).movementSpeed(0.3).followRange(28.0).build())
        .behavior(profile -> profile.action(attackNearest, 1.0))
        .build());
```
For the procedural (role) path drop `.animationClips()` and supply roles:
```java
BedrockMobModel.parse(bytes).textureName("soldier")
        .part("head", MobPart.Role.HEAD, 0, 24, 0, "head_geo")
        .part("right_leg", MobPart.Role.LEG_BACK_RIGHT, -2, 12, 0, "leg_r")
        .build(png);
```

**Presentation sidecar** — `<server>/plugins/Mineplus/mobs/<id>.model.json`:
```json
{
  "poseMode": "auto",
  "legRoleMap": { "leg_r": "LEG_BACK_RIGHT", "leg_l": "LEG_BACK_LEFT" },
  "animationScale": 1.0,
  "walkSpeedReference": 2.0,
  "yawOffsetDegrees": 0,
  "strideBlocks": 1.05
}
```
`poseMode` is `auto` (clips if present), `clips`, or `procedural`. The file is read
once and cached; `/mineplus reload` re-reads it.

**Verify**: `/mobfun spawn soldier`, `/mobfun status`, `/mineplus pack status`.
Cost caps live under `CUSTOM_MOBS` (`RENDER_DISTANCE` 48, `MAX_RIGGED_PER_WORLD` 96).

---

## 5. Texture-only mob (vanilla shape, new texture)

No Blockbench model. Paint a sheet that matches the base entity's UV layout
(e.g. 64×32 for a pig, 64×64 for a villager) and register it with
`CustomMobModel.vanilla(EntityType.PIG, pngBytes)`.

- Export the PNG at the entity's exact sheet dimensions; stray pixels outside the
  used boxes are harmless but inflate the file.
- Keep alpha where the vanilla model expects cutouts.
- Identity, hitbox and pathing stay vanilla; only the texture changes.
- Use this when the silhouette is already right and the idea is "same mob, new
  skin". Anything with new geometry needs recipe 4.

---

## 6. Gun frame (raw item model, not `.bbmodel`)

The gun module ships **raw vanilla item JSON** per frame; the Core never imports a
`.bbmodel` here. Frame names classify themselves, so every animation except idle
is optional.

**Authoring rules**
- `java_block`, 0..16 px, y up. Author display transforms in Blockbench (they are
  part of the JSON and are what makes the gun sit in the hand).
- One texture per gun: `<id>.png`, referenced as
  `"textures": {"1": "mineplusgun:item/<id>"}`.
- Frame file names inside the gun folder:
  - rest: `<id>_idle`, `_rest`, `_base`, `_default`
  - loaded: `<id>_primed`, `_loaded`, `_ready`, `_cocked`
  - reload: `<id>_loading_N`, `_reload_N`, `_load_N` (numeric, retimed at runtime)
  - shot: `<id>_fire_N`, `_shoot_N`, `_shot_N`
  - scope: `<id>_scope_in`, `_scope_hold`, `_scope_out`
  - action: `<id>_cycle`, `_bolt`, `_pump`, `_rack`, `_rechamber`
  - equip: `<id>_equip`, `_draw`, `_raise`
- Missing reload/fire/equip/cycle/scope frames are generated procedurally from
  the idle/loaded pose (`WeaponAnimations`), so a folder with only `<id>_idle.json`
  is a valid weapon.

**Export**
```bash
node scripts/export-to-mineplus.mjs \
  --out "E:/The Backup/Backups Main/16 July 2026/mineplus/examples/mineplus-gun/src/main/resources/defaults/guns/<id>/models" \
  --name <id>_idle --codec java_block --target pack-item --overwrite
```
Then copy the PNG to `defaults/guns/<id>/<id>.png`. On enable, `WeaponAssets`
mirrors `models/*.json` → `pack/mineplusgun/models/item/<id>/` and the flat PNG →
`pack/mineplusgun/textures/item/<id>.png`, generates missing frames, repairs
unresolved face references, and registers every installed file as raw pack assets.

**Verify**: `/mpgun reload`, give the weapon, check idle/loaded in hand, fire and
reload. A single face referencing an undefined texture var makes the whole model
render as the missing-model checkerboard; remove the face.

---

## 7. Debug / silhouette check

Use this to test anchoring and orientation before committing a target:

1. `create_project {name:"debug", format:"free"}`.
2. Block out with one cube at `[-8,0,-8]..[8,16,8]` plus marked front/north detail.
3. `capture_screenshot` from north and up; or `create_offscreen_view` +
   `set_camera_angle {position:[32,24,-32], target:[0,8,0]}` for a deterministic
   render.
4. Export to `<server>/plugins/Mineplus/models/debug/` with `--target virtual`,
   `/mineplus reload models`, `/mineplus model debugspawn debug`.
5. Confirm the model sits on the block floor and its front faces **−z**. A model
   that pivots out of the block on rotation is anchored wrong — fix the space
   before authoring detail.

For pack-only testing, use a scratch namespace and `/mineplus pack recompile` +
`/mineplus pack push <player>`; do not reuse a production namespace.

---

## Common numbers

| Thing | Value |
|---|---|
| Block = pixels | 16 |
| CENTER single block | x/z −8..8, y 0..16 |
| GRID/java space | 0..16 per block |
| Rotation snap (virtual) | 5° threshold, 24 legal orientations |
| Pack rotation allowed | one axis, multiples of 45° |
| Texel plate budget | 96/face, 150/instance, grid edge 64 |
| Mob feet | y = 0, front −z, 1 unit = 1/16 block |
| Mob render distance | 48 blocks, 96 rigs/world, pose every 2 ticks |
| Note-block carrier slots | 175 allocatable states |
