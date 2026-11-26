# Blockbench MCP Playbook (live build)

Verified against **Blockbench 5.2.2**, **MCP plugin 1.10.1**, endpoint
`http://localhost:6960/bb-mcp`, project format `free`. Tool lists change with the
open format and editor mode — always re-read `get_capabilities` when a call is refused.

## Connection bootstrap

Native tools appear as `mcp__blockbench__<name>` and are bound when the chat starts.
If they are missing:

1. `mcp__qoder-settings__manage_app_mcp` → `add` → `{ "type": "http", "url": "http://localhost:6960/bb-mcp" }`.
2. Open a new chat (Qoder does not add tools to a live session).
3. Meanwhile use the HTTP fallback in this skill's `scripts/`.

The transport is streamable HTTP: `initialize` returns an `Mcp-Session-Id` header,
then `notifications/initialized`, then `tools/call`. Responses arrive as JSON or SSE;
`scripts/bb-mcp-call.mjs` handles both and exposes `--list`, `--list --schemas`,
`--schema <tool>`.

## Live tool surface (147 enabled)

### Session, project, inspection
| Tool | Notes |
|---|---|
| `get_capabilities` | Version, format features, and every registered tool with `enabled` state |
| `get_project_info` | Name, UUID, format, counts |
| `list_outline` | `{include_cubes, include_meshes, max_depth}`; the source of truth for bone names |
| `list_textures` | Textures in the editor |
| `get_cube_uv` / `set_cube_uv` | Inspect or write box/per-face UVs and face texture assignment |
| `get_mesh_info` | Paged vertices/faces/normals (not usable for Mineplus; cubes only) |
| `get_selection`, `get_undo_stack` | State before an edit |
| `list_modes` / `set_mode` | `edit`, `paint`, `animate`, `display`, … |
| `list_export_formats` | Codec ids; `project` is always available |
| `list_views` | Offscreen views created this session |
| `save_checkpoint` | Named undo-history marker; use before large reworks |

### Project and elements
| Tool | Required | Notes |
|---|---|---|
| `create_project` | `name` | `format` defaults to `bedrock_block`; pass `free`, `java_block` or `bedrock`. **Replaces the open project.** |
| `place_cube` | `elements` | Top-level `texture`, `group`, `faces:true`; element fields `name,from,to,origin,rotation,inflate,uv_offset,mirror_uv`; unknown fields rejected |
| `modify_cube` | — | By `id`/name; supports `from,to,origin,rotation,inflate,light_emission,visibility,uv_offset,mirror_uv` |
| `add_group` | `name` | `origin` is the pivot, `parent` a group UUID/name or `root` |
| `add_locator` | `name, position` | Empty pivot for FX/attachment |
| `duplicate_element` | `id` | Optional `offset`, `newName` |
| `rename_element` | `id,new_name` | Rename before animating: names are bone keys |
| `remove_element` | `id` | |
| `find_elements_by_criteria` | — | Regex/substring name, type, parent, size, selection |
| `select_all_of_type` | `type` | `cube`, `mesh`, `group` |
| `filter_by_material` | `texture` | Find every cube/face using a texture |
| `from_geo_json` | `geojson` | Bedrock 1.12+ geometry, inline JSON or absolute path — fastest bulk blockout |
| `create_sphere` / `create_cylinder` / `create_polyhedron` | `elements` | Meshes/polyhedra; **not importable by Mineplus** |
| `knife_cut_cube` | `cubes, axis, positions` | Headless cube cuts, one undo entry |
| `inspect_block_bounds` / `slice_cubes_to_block_grid` | — | Only for Bedrock 30×30×30 oversized-block limits; irrelevant to Mineplus pack blocks |

### Textures, UV and materials
| Tool | Required | Notes |
|---|---|---|
| `create_texture` | `name` | `width,height` 16–4096; `data` accepts an absolute PNG path or data URL; `fill_color`, `pbr_channel`, `render_mode`, `render_sides` |
| `set_cube_uv` | `id` | Per-face patch: `faces:{north:{uv:[u1,v1,u2,v2], rotation:0|90|180|270, texture:"name"|false|null}}`; omit `texture` to preserve, `null` disables the face |
| `auto_uv_mesh`, `set_mesh_uv`, `rotate_mesh_uv` | mesh | Mesh-only; not used by Mineplus |
| `list_materials`, `configure_material`, `create_pbr_material` | — | PBR is a glTF/Bedrock concern; Mineplus reads the colour PNG |
| `import_texture_set` | `path` | `.texture_set.json`; no Mineplus consumer |
| `paint_settings`, `create_brush_preset`, `load_brush_preset` | — | Paint mode helpers |

`activate_texture`, `apply_texture`, `bulk_set_material_instances`,
`clear_material_instances`, `color_picker_tool`, `copy_brush_tool` are registered but
**disabled** in this build. Assign textures with `place_cube.texture` or
`set_cube_uv.faces.*.texture` instead.

### Animation and rigging
| Tool | Required | Notes |
|---|---|---|
| `create_animation` | `name, bones` | `bones` = `{ "<group name>": [{time, rotation?, position?, scale?}, …] }`; seconds, degrees; `animation.` prefix added automatically; `loop`, `animation_length` |
| `manage_keyframes` | `action, bone_name, channel, keyframes` | `action` create/delete/edit/select; `channel` rotation/position/scale; optional `animation_id` |
| `animation_timeline` | — | Playback, scrub, length, FPS, loop, keyframe selection |
| `batch_keyframe_operations` | — | Atomic multi-key edits, bake, reverse |
| `animation_graph_editor` | — | Bezier/easing segment edits |
| `bone_rigging` | `action, bone_data` | create/parent/unparent/delete/rename/set_pivot/mirror |
| `add_armature`, `add_armature_bone`, `update_armature_bone(s)` | — | Freeform armature deformation; **Mineplus ignores armatures** |
| `variable_placeholders`, `list_molang_variables`, `list_particle_effects` | — | Preview helpers; Mineplus evaluates no Molang |

Only rotation/position/scale on outliner groups import. Effect animators, particle
keyframes and Molang expressions are dropped or zeroed by Mineplus.

### Views, capture, export
| Tool | Notes |
|---|---|
| `capture_screenshot`, `capture_app_screenshot` | Quick visual confirmation |
| `create_offscreen_view {id,width,height}`, `resize_offscreen_view`, `delete_offscreen_view` | Deterministic renders without touching the user's viewport |
| `set_camera_angle {position,projection, target?, view?, zoom?, fov?, locked_angle?}` | Returns an image; `format` png/jpeg/webp |
| `export_model` | See below |
| `trigger_action {action}`, `emulate_clicks` | Native action/mouse events; last resort |
| `risky_eval {code}` | Evaluates JS, returns JSON; **no undo entry** — read-only use preferred |

## Exact call patterns

```jsonc
// create a project
{"name":"crusher_lv1","format":"free"}

// groups first (pivot = joint, name = future bone)
{"name":"gear","origin":[0,12,0],"parent":"root"}
{"name":"body","origin":[0,8,0]}

// cube batch; texture is a material name for virtual models
{"elements":[
  {"name":"base","from":[-6,0,-6],"to":[6,3,6]},
  {"name":"column","from":[-2,3,-2],"to":[2,13,2],"origin":[0,3,0]}
],"texture":"stone_bricks","group":"body","faces":true}

// per-face material switch + exact window
{"id":"column","faces":{
  "north":{"uv":[0,0,16,16],"texture":"oak_planks"},
  "up":{"uv":[0,0,16,16],"texture":"iron_block"}
}}

// import a real PNG; for virtual models name = vanilla material
{"name":"stone_bricks","width":16,"height":16,"data":"E:/textures/stone_bricks.png"}

// animation keyed by GROUP name
{"name":"idle","loop":true,"animation_length":2.0,
 "bones":{"gear":[{"time":0,"rotation":[0,0,0]},{"time":2,"rotation":[0,360,0]}]}}

// export the editable project
{"codec_id":"project","path":"E:/.../models/crusher_lv1.bbmodel","overwrite":true,"max_content_length":0}
```

`export_model`: `path` is absolute and requires Blockbench `fs` permission; it
refuses to overwrite unless `overwrite:true`; `max_content_length:0` suppresses the
inline payload. `result_format:"embedded"` returns a resource block instead of text.
Only `project` (`.bbmodel`) matters for Mineplus delivery; glTF/OBJ exports are for
external renders and lose vanilla semantics.

## Useful read-only `risky_eval` snippets

```js
// counts and format
({format: Project.format.id, name: Project.name, cubes: Cube.all.length,
  groups: Group.all.length, textures: Texture.all.length, animations: Animation.all.length})

// every texture with its bitmap (used by export-to-mineplus.mjs)
Texture.all.map(function(t){return {name:t.name,path:t.path,
  relative_path:t.relative_path,source:t.source,width:t.width,height:t.height}})

// world-space cube bounds, to check anchoring before export
Cube.all.map(function(c){return {name:c.name,from:c.from,to:c.to,origin:c.origin,rotation:c.rotation,parent:c.parent&&c.parent.name}})
```

Mutating `risky_eval` code must open its own undo scope (`Undo.initEdit` /
`Undo.finishEdit`); prefer dedicated tools so history stays clean.

## Batching and safety

- One call = one undo entry for `place_cube` batches and `save_checkpoint`; group
  related cubes instead of 20 single calls.
- `add_group` before cubes: a cube's `group` must already exist.
- `create_project` and `mode` switches can interrupt the user's viewport; checkpoint first.
- Screenshots are the only way to confirm silhouette/UV orientation; take one after the
  blockout and one after texturing, from `north` and `up`.
- After any topology change, re-read `list_outline` before reusing names; duplicate names
  make later `modify_cube`/animation calls ambiguous.
