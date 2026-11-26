# Troubleshooting

Symptom → cause → fix. Verify with
`node scripts/check-bbmodel.mjs <model> --target <virtual|pack-item|pack-block|mob>`
first; then `/mineplus model info <key>`, `/mineplus pack status`,
`/mineplus render stats`.

## MCP and tooling

| Symptom | Cause | Fix |
|---|---|---|
| `mcp__blockbench__*` tools absent | Qoder binds MCP tools at session start | Register once via `manage_app_mcp` (`{type:"http",url:"http://localhost:6960/bb-mcp"}`), open a new chat; meanwhile use `scripts/bb-mcp-call.mjs` |
| Calls fail after a format/mode change | The tool surface is per-format | Re-read `get_capabilities {include_tools:true}` |
| `activate_texture` / `apply_texture` refused | Registered but disabled in this build | Use `place_cube {texture}` or `set_cube_uv {faces:{...texture}}` |
| No display-transform tools | Not implemented in this MCP build | Author the pose in Blockbench's Display tab, or write `display` in `.meta.json` |
| `create_project` destroyed work | It replaces the open project | `save_checkpoint` / export first; use `Undo` immediately if it just happened |
| `export_model` refuses the path | Blockbench `fs` permission | Enable filesystem permission in the MCP plugin settings, or export to the project folder and move the file |
| `risky_eval` edit cannot be undone | It bypasses the undo stack | Prefer dedicated tools; snapshot first |
| Export script warns "no embedded bitmap" | Texture was referenced by path only | Export the PNG manually as `<name>.png` next to the model |
| Checker reports odd footprint | Wrong `--target` | Use `virtual` for machines, `pack-item`/`pack-block` for pack content, `mob` for rigs |

## Virtual / texel models

| Symptom | Cause | Fix |
|---|---|---|
| Whole model white concrete | Texture name resolves to no material | Name the texture after a vanilla material (`stone_bricks`); check `/mineplus model info` |
| One cube white, rest correct | That cube's primary face texture name is wrong | Assign the correct texture to all six faces or split the cube |
| Model sinks, floats, or pivots out of the block on rotation | CENTER vs GRID mismatch | Author `free` CENTER (−8..8 x/z, 0..16 y) or set `originMode` in the meta file |
| Multi-block structure appears offset by 8 px | Corner-authored geometry treated as CENTER | Set `"originMode": "GRID"` |
| Holes in a cube | Faces disabled by `set_cube_uv {texture:null}` | Re-enable with `faces:true` or assign a texture |
| Texture stretched on one face | UV window is partial or rotated | Use a full 0..16 window, or author the sub-window deliberately; the resolver supports halves/wraps/tiles but not arbitrary sub-pixel crops |
| Texture mirrored | Negative UV or flipped window | Unflip the face UV in Blockbench |
| Faces z-fight or flicker | Overlapping coplanar cubes, or ghost displays from a previous spawn | Offset by ≥0.01 px; `/mineplus model cleanup` |
| Gradient art collapses to one flat colour | Plate budget exceeded; budget-fallback face plates the dominant palette colour | Raise `maxTexelPlatesPerFace` / `maxTexelPlatesPerInstance` in the meta file |
| Texel colours look wrong | Palette has no near match or brightness override | Expect Oklab-mapped vanilla palette colours; set `texelBrightness` only when needed |
| PNG ignored | No PNG beside the model and none in the models root, or name mismatch | Place `<texture-name>.png` next to the `.bbmodel` |
| Model loads but is absent from `/mineplus model models` | File outside `models/`, wrong extension, or parse failure | Check the server log; key is the relative path without extension |
| Animation does nothing | Animator targets an element, not an outliner group; name typo; clip not autoplayed | Re-target the group, check `list_outline`, add the clip to the level's `animations` or call `AnimationApi` |
| Animation rotates the wrong way | Euler order is Rz·Ry·Rx; pivot is the group origin | Author rotations with the correct pivot and test one axis at a time |
| Motion looks stepped | Server tick rate vs client interpolation | Leave `ANIMATION.INTERPOLATION_TICKS` default; `TICK_INTERVAL_TICKS: 1` is the fastest supported |
| Molang expression has no effect | Molang values import as 0 | Bake the motion into keyframes |
| Effect/sound animation missing | Effect animators are dropped | Trigger sounds/particles from code |

## Pack items

| Symptom | Cause | Fix |
|---|---|---|
| Vanilla backing item in hand | Player has not applied the pack, or declined it | `/mineplus pack status`, `/mineplus pack push <player>` |
| Purple/black checkerboard | Missing sprite, or one face references an undefined texture variable | Ensure `textures/item/<name>.png` exists with the exact last-segment name; remove `#missing` faces |
| Item tiny, huge, or off-centre | Authored in CENTER space, or missing display transforms | Author `java_block` 0..16 px; set `display` contexts |
| Right in GUI, wrong in first person | Missing `firstperson_*` display entries | Author all contexts or set them in the meta file |
| Two items share a look | Texture stems collide in the pack (`texture.png` twice) | Give every texture a unique filename |
| Item pose differs from the authored one | `.meta.json` `display` overrides the model | Delete or merge the override |
| Rotated part collapsed into a box | Multi-axis or non-45° rotation | Restrict pack rotations to one axis, multiples of 45° |
| Model has holes only in the pack | UV window outside 0..16 (clamped) | Keep UVs inside the sheet; pad the texture |
| Item missing on 1.21.3 and older | Legacy path uses `CustomModelData` host files | Prefer modern servers; check `/mineplus pack audit` for host conflicts |

## Pack blocks

| Symptom | Cause | Fix |
|---|---|---|
| Renders a normal note block | Pack not applied, or the carrier state was not allocated | `/mineplus pack status`; `/mineplus render stats` |
| Nothing renders | Carrier has `INVISIBLE` render type | Use `note_block` only |
| Model floats/sinks in the block | Math in the wrong space | Author `free` CENTER (block base at y=0); block serialization adds the +8 x/z shift |
| Collision does not match the visual | Every occupied cube cell becomes a barrier | Author the silhouette you want players to collide with; use `SURFACE` collision for walk-in interiors |
| One state of the vanilla block changed | Allocated carrier state redirected | Expected; only the allocated rare state is touched |
| Pack block disappears when the pack is disabled | Level degrades to virtual | Expected; the virtual fallback renders instead |
| Assets compile but the block is untextured | Registered after the coordinated reload | Register before `reloadAll`/restart, then `/mineplus pack recompile` |
| Block renders in the wrong place | Model key does not match the derived `<typeId>_lvl_<level>` | Fix `modelKey`; `geometryModelKey` is the file stem, not the derived key |

## Custom mobs

| Symptom | Cause | Fix |
|---|---|---|
| Rig T-poses | No clips and every part is `STATIC` | Add `animation.idle`/`animation.walk`, or set `poseMode: "procedural"` + `legRoleMap` |
| Base mob visible, no rig | Pack unavailable, rendering disabled, beyond render distance, or rig cap reached | Check `CUSTOM_MOBS.RENDERING`, `RENDER_DISTANCE` (48), `MAX_RIGGED_PER_WORLD` (96) |
| Base mob visible inside the rig | `hideBaseWhenRigged` false, or pack presentation failed | Set `.hideBaseWhenRigged(true)`; verify pack |
| Mob faces the wrong way | Model authored toward +z | Rotate the model or set `yawOffsetDegrees: 180` in the sidecar |
| Parts detach or explode when walking | Pivots are not the authored joint pivots, or parent hierarchy is wrong | Rebuild groups with correct `origin`/`parent`; re-export |
| Legs sink or float | Feet not at y=0 | Shift the whole model so the lowest geometry sits on y=0 |
| Walk cycle too fast/slow | Authored tempo differs from movement | `animationScale`, `walkSpeedReference`, `strideBlocks` in `mobs/<id>.model.json` |
| Attack clip never plays | Clip name not `animation.attack`, or no melee hit | Rename the clip; the Core plays it on the mob's own damage event, or call `MobRigRenderer.playAttack` |
| Texture scrambled | Wrong sheet size or UV layout | Match the base entity sheet (64×32 pig, 64×64 villager) or re-export with box UV |
| Head does not track the player | Head group not detected (procedural mode with no HEAD role) | Map the head group in `legRoleMap` or use clips |
| Vanilla AI still runs | Identity not applied (definition not registered, or tag lost) | Re-register the definition; the Core isolates on spawn and chunk restore |
| Mob removed after restart | Definition gone and `REMOVE_UNKNOWN_ON_LOAD` true | Register the definition on enable, or set the flag to false |

## Guns

| Symptom | Cause | Fix |
|---|---|---|
| Purple/black gun model | A face references an undefined texture variable | Remove the face; `WeaponAssets` repairs known cases on enable |
| Gun never animates | Frame stems not recognized | Follow `<id>_idle`, `<id>_primed`, `<id>_loading_N`, `<id>_fire_N`, …; `/mpgun reload` |
| Fire animation missing but reload works | No `fire` frames and no generated fallback | Add `_fire_0`/`_fire_1`, or accept the generated kick |
| Gun sits wrong in the hand | Display transforms missing or wrong | Author display poses in Blockbench; they are copied verbatim |
| Reload plays at the wrong speed | Frame count/duration differs from weapon config | Frames are retimed to the weapon's reload duration; add or remove frames |
| New gun folder ignored | Texture not a flat `<id>.png`, or models not under `models/` | Match `defaults/guns/<id>/models/*.json` + `defaults/guns/<id>/<id>.png` |

## Pack delivery

| Symptom | Cause | Fix |
|---|---|---|
| Pack URL unreachable from another machine | LOCAL delivery resolves loopback for local players only | Use a reachable server IP; check `/mineplus pack status` |
| Player receives an old artifact | Cache not recompiled | `/mineplus pack recompile`, then push |
| Declined player never receives | Decline is respected | Player must accept the prompt / rejoin |
| Pack compiles but model is unchanged | Asset registered after the reload, or same entry different content conflict | Register before reload; `/mineplus pack audit` for conflicts |
| Everything breaks when the pack is off | Code depends on pack rendering | Use a hybrid render mode or keep identity/gameplay independent of visuals |

## JSON and files

| Symptom | Cause | Fix |
|---|---|---|
| Meta silently ignored | UTF-8 BOM at the start of the file | Re-save without BOM (the shipped Cabinet metas show the failure mode) |
| Multiblock not loaded | Invalid JSON, missing `levels`, duplicate `id` | Check the server log; `/mineplus reload multiblocks` |
| Model missing after reload | File renamed; active instances keep the old derived key | Restore the path or update instances |
| Same texture on two models overwritten | Last-segment pack key collision | Rename one texture file |
