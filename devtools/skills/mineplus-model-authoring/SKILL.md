---
name: mineplus-model-authoring
description: Design, texture, rig, animate, validate and deliver Blockbench models for the Mineplus Spigot/Paper plugin through the live Blockbench MCP at http://localhost:6960/bb-mcp. Use when the user states a model idea or asks to build, port, fix or inspect a .bbmodel for a Mineplus custom item, pack item, managed block, pack block, multiblock machine, gun frame, or custom mob rig (including .meta.json, multiblock JSON, PackItemDefinition/PackBlockDefinition and CustomMobDefinition integration).
---

# Mineplus Model Authoring

Drive the open Blockbench desktop project through its MCP server, author to the rules the
Mineplus Core importer actually enforces, validate statically, then deliver the `.bbmodel`
and its PNGs into the runtime folder and wire them into a multiblock JSON, pack registration
or custom mob definition.

**Fixed locations**

| What | Absolute path |
|---|---|
| Mineplus plugin root (source of truth) | `E:/The Backup/Backups Main/16 July 2026/mineplus` |
| Model/importer/pack docs | `<plugin>/docs/config-reference.md`, `pack-system.md`, `custom-mobs.md`, `developer-api.md` |
| Blockbench MCP project | `C:/Users/Tariux/Desktop/blockbench-mcp-project` |
| MCP endpoint (Blockbench desktop plugin) | `http://localhost:6960/bb-mcp` |
| This skill | `C:/Users/Tariux/Desktop/blockbench-mcp-project/skills/mineplus-model-authoring` |

## 1. Connect before anything else

1. Prefer native tools `mcp__blockbench__*`. Verify with `get_capabilities {include_tools:false}`;
   the live surface is Blockbench 5.2.2 + MCP plugin 1.10.1 (147 enabled tools).
2. If those tools are absent from the current chat, the server is not bound to this session.
   Register it once with `mcp__qoder-settings__manage_app_mcp` (`add`, `{type:"http", url:"http://localhost:6960/bb-mcp"}`),
   then open a **new chat** — Qoder fixes the tool list at session start.
3. Fallback that always works while Blockbench is running:
   ```bash
   cd "C:/Users/Tariux/Desktop/blockbench-mcp-project/skills/mineplus-model-authoring"
   node scripts/bb-mcp-call.mjs get_capabilities '{"include_tools":false}'
   node scripts/bb-mcp-call.mjs --list
   node scripts/bb-mcp-call.mjs place_cube '<json args>'
   ```
4. This build has **no display-transform tools**; `activate_texture`/`apply_texture` are
   disabled. Apply textures with `place_cube {texture}` or `set_cube_uv {faces:{...texture}}`,
   and author display poses in `.meta.json` (see [target recipes](references/target-recipes.md)).
5. The MCP edits the **currently open project**. `create_project` replaces it: save or export
   first. Never create a project over unsaved user work.

## 2. Pick the target before modelling

| Target | Blockbench format & space | Textures | Runtime |
|---|---|---|---|
| Virtual multiblock/machine | `free`, CENTER: x/z -8..8, y 0..16 | `<vanilla_material>.png` (e.g. `bookshelf.png`) | `plugins/Mineplus/models/<key>.bbmodel`, multiblock JSON `renderMode: virtual` |
| Pack item (high fidelity) | `java_block` (or `free`), 0..16 px | one PNG, unique last filename segment | `models/<key>.bbmodel` + `PackItemDefinition` |
| Pack block (one display) | `free`, CENTER block space, y 0..height | one PNG, unique last segment | `models/<key>.bbmodel` + `renderMode: pack_block` + `note_block` carrier |
| Custom mob rig | `bedrock` (or `modded_entity`), feet y=0, front −z | one PNG sheet | `.bbmodel` in module resources, `BedrockMobModel` |
| Texture-only mob | none — a vanilla-shaped PNG sheet (e.g. 64×32 pig) | PNG only | `CustomMobModel.vanilla(type, png)` |
| Gun frame | raw item JSON, not `.bbmodel`, 0..16 | one PNG per gun | gun folder + config (frames `idle`, `loaded`, `fire`, `reloadN`) |
| Debug experiment | `free`, CENTER | any | `plugins/Mineplus/models/debug/` |

Choose by constraint, not taste: no pack delivery → virtual; exact texture fidelity or
rotated/many-face geometry → pack; bone animation on an entity → custom mob; 16-cube
vanilla aesthetic → virtual is usually enough.

## 3. Author with MCP (ordered, batch where possible)

1. `save_checkpoint {name:"before <idea>"}` before large edits.
2. `create_project {name:"<key>", format:"free"}` (`bedrock` for mobs, `java_block` for pack items).
3. `add_group` first: pivot = joint. Short snake_case names; these become bone names.
4. `place_cube {elements:[{name,from,to,origin,rotation,inflate}], texture:"<texture>", group:"<group>", faces:true}`
   in batches. One cube per material on the virtual axis. **No meshes** — the importer keeps cubes only.
5. `create_texture {name, width, height, data:"<absolute png path>"}` to import a real texture;
   for virtual models the texture name must be the vanilla material (`oak_planks`, `stone_bricks`).
6. `set_cube_uv {id, faces:{north:{uv:[0,0,16,16],texture:"<name>"}, ...}}` for per-face materials
   or partial windows. Virtual full-face windows keep material mapping predictable.
7. `create_animation {name:"idle", loop:true, animation_length:2, bones:{"<group>":[{time,rotation},...]}}`
   — keys are **group names**, channels are rotation/position/scale, degrees, seconds.
8. Inspect: `list_outline {include_cubes:true}`, `get_cube_uv`, `capture_screenshot` or
   `create_offscreen_view` + `set_camera_angle`.
9. Export and pre-flight in one step:
   ```bash
   node scripts/export-to-mineplus.mjs --out "E:/.../plugins/Mineplus/models" --name <key> --target virtual
   ```
   It writes `<key>.bbmodel`, every texture as `<name>.png` beside it, optional `--meta`, then
   runs `check-bbmodel.mjs`.
10. Wire the content (multiblock JSON / pack item / mob) and reload — see
    [integration](references/mineplus-integration.md).

## 4. Validate

```bash
node scripts/check-bbmodel.mjs <model.bbmodel> --target virtual|pack-item|pack-block|mob \
  [--definition blocks/<namespace>/<id>.json]
```

Exit 0 pass, 1 warnings, 2 errors. It mirrors the importer's texture-name priority, outliner
pivots and bone mapping, anchor resolution, footprint cells, UV windows, pack serialization
limits, mob part rules and animation channels. Treat every **error** as blocking; read
**warnings** as things that render wrong or degrade silently.

`--definition` gates the model against its block JSON: `visuals.model` must resolve to this
model, `occupancy` must equal the measured footprint (cells the geometry meaningfully fills;
a sub-pixel overhang under 1 px per side is reported as a note, not a mismatch), every
`visuals.animations.autoplay` clip must exist, `collision.mode` must be one of the loader's
modes, and `breaking.hardness` must be a finite 0..3600.

## 4b. Block delivery gate

A block model alone is not deliverable. Before handing it over, the model must
pass `check-bbmodel.mjs --target pack-block --definition <blocks/…json>` with
exit 0 and the box below, and the delivery must include the same
`blocks/<namespace>/<id>.json` definition — the `--definition` gate proves the
two agree. The contract and schema are in
`docs/design/2026-11-26-custom-block-system-design.md`; the checklist is
`docs/model-authoring-guide.md` §9.

- Free space, feet on y=0, no cube below y=0, origin in the anchor cell.
- Footprint in whole cells; cubes are the collision source (no filler cubes in
  walkways, real gaps where the space must be open).
- `fillBox` for every cell that must stay open (water through a wheel ring).
- `snake_case` clips; autoplay clips listed in the definition.
- Embedded texture or sibling PNG; angles inside the 1.21.6 snap grid unless
  the version matrix excludes it.
- Report pixel bounds, footprint, collision cell count, break time, and an
  `F3+G` lattice screenshot.

## 5. Hard rules that fail silently

- **One cube = one material** on the virtual axis. Split parts; the engine uses one material per cube.
- Virtual texture filenames must resolve to a Bukkit material. Never name a texture `texture`.
- Mineplus ignores embedded Base64 PNGs: always ship `<name>.png` next to the `.bbmodel`.
- CENTER (free) vs GRID (java_block) anchors are not interchangeable; a wrong anchor swings
  the model out of its block on rotation. AUTO resolves java_block to GRID unless geometry is centered.
- Animations only reach **outliner groups**, never raw cubes. Molang evaluates to 0;
  spline/bezier keys sample linear; effect animators are dropped.
- Pack cubes may carry at most **one axis, ±45°** rotation; anything else serializes as its AABB.
- Pack item coordinates are serialized **unshifted**: author 0..16 px or the item is off-centre.
- Pack textures are keyed by the **last filename segment**; duplicate segments collide.
- Custom mobs: every group becomes a bone, feet at y=0, model front is −z, and
  `partsFromOutliner()` marks every part **STATIC** — clip mode ignores roles, procedural mode
  needs a role override (`legRoleMap` in `mobs/<id>.model.json` or code `.role(...)`).
- Texel baking is budgeted (96 plates/face, 150/instance, 64 grid edge). Over-budget faces fall
  back to one dominant colour plate; gradient-heavy art needs `.meta.json` budget raises.
- A UTF-8 BOM breaks JSON tooling; keep `.bbmodel`/`meta.json` BOM-free.

## 6. Reference map

| File | Use it for |
|---|---|
| [references/mcp-playbook.md](references/mcp-playbook.md) | Live tool catalog, exact schemas, batching, gaps, `risky_eval` recipes |
| [references/pipeline-and-limits.md](references/pipeline-and-limits.md) | What the importer, texel baker, pack writer and mob rig actually do |
| [references/target-recipes.md](references/target-recipes.md) | Step-by-step build recipes per target with exact numbers |
| [references/mineplus-integration.md](references/mineplus-integration.md) | Folder layout, `.meta.json`, multiblock JSON, Java registration templates, commands |
| [references/troubleshooting.md](references/troubleshooting.md) | Symptom → cause → fix matrix |

Upstream Blockbench skills (load only what the task needs):
[blockbench-use](<C:/Users/Tariux/Desktop/blockbench-mcp-project/skills/blockbench-use/SKILL.md>),
[modeling](<C:/Users/Tariux/Desktop/blockbench-mcp-project/skills/blockbench-modeling/SKILL.md>),
[texturing](<C:/Users/Tariux/Desktop/blockbench-mcp-project/skills/blockbench-texturing/SKILL.md>),
[animation](<C:/Users/Tariux/Desktop/blockbench-mcp-project/skills/blockbench-animation/SKILL.md>),
[vanilla textures](<C:/Users/Tariux/Desktop/blockbench-mcp-project/skills/blockbench-vanilla-textures/SKILL.md>).

## 7. Quick triage

| Symptom | First thing to check |
|---|---|
| White model | texture filename does not resolve to a material, or no PNG beside the model |
| Model sunk / floating / swings out of the block | CENTER vs GRID anchor, y origin, `.meta.json originMode` |
| Open cube / holes | faces omitted in Blockbench (`set_cube_uv texture:null` disables a face) |
| Animation does nothing | animator targets an element, not an outliner group; name mismatch |
| Pack item off-centre or tiny | pixels not authored 0..16; wrong `display`/`itemParent` |
| Pack shows note block | pack not applied, or the carrier state was not allocated |
| Mob T-poses | no `idle`/`walk` clips and every part is STATIC |
| Mob faces the wrong way | model front must be −z; set `yawOffsetDegrees` if it was authored +z |

Start with [troubleshooting](references/troubleshooting.md) for the full matrix.
