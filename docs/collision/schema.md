# Collision schema

Everything an operator or addon author writes to get collision: the model
`collision` block, the blueprint format, the `COLLISION` config section and the
commands. Field names and defaults below are the ones the code reads
(`ModelCollision`, `Blueprint`, `CollisionSettings`, `CollisionSubCommand`).

## 1. Model metadata — `collision` block

In a model's `.meta.json`, next to `model` and `texture`:

```json
{
  "model": "chair.bbmodel",
  "collision": {
    "mode": "AUTO",
    "profile": "FURNITURE",
    "yaw": 22.5,
    "resolution": 16,
    "inset": 0.0,
    "enabled": true
  }
}
```

| Key | Type | Default | Meaning |
|---|---|---|---|
| `mode` | `AUTO` \| `BLUEPRINT` \| `FILL` \| `HYBRID` \| `NONE` | config `COLLISION.MODE` | Occupancy source. Aliased as `source` for older files. |
| `profile` | `DEFAULT` \| `FURNITURE` \| `WALKABLE` \| `THIN` (or a preset name) | config `COLLISION.PROFILE` | Fitter weight preset. Aliased as `fitProfile`. |
| `yaw` | degrees | config `COLLISION.DEFAULT_YAW` | Rotation applied to the model before rasterising. Multiples of 90 are exact; other angles are resampled. |
| `resolution` | 1–64 | 16 | Sub-voxels per block axis. Values outside the range are clamped. |
| `inset` | blocks ≥ 0 | config `COLLISION.INSET` | Shrinks the occupancy before fitting, so the collision never sticks out past a visible thin edge. |
| `enabled` | boolean | `true` | `false` disables collision for this model. Legacy `mode: "NONE"` does the same. |
| `blueprint` | object or JSON string | — | Required by `BLUEPRINT` and `HYBRID`; ignored otherwise. Must follow section 2. |

Unknown keys are ignored so a newer meta file still loads on an older build; a
malformed blueprint keeps its text and is rejected by the parser with the JSON
path, so the error names the file and the cell.

`AUTO` needs no blueprint. `BLUEPRINT` fits only what the blueprint declares.
`HYBRID` uses `AUTO` occupancy and lets blueprint cells override it, which is the
usual choice for furniture: model volume for the body, a `TOP_SURFACE` layer
where the player stands.

## 2. Blueprint format (schema version 1)

```json
{
  "schema": 1,
  "resolution": 16,
  "size": [2, 2, 1],
  "regions": [
    {"type": "fill",  "from": [0, 0, 0], "to": [1, 1, 0], "directive": "SOLID"},
    {"type": "layer", "y": 1, "directive": "TOP_SURFACE"},
    {"type": "box",   "from": [1, 0, 0], "to": [1, 0, 0], "directive": "STEP"}
  ],
  "cells": [
    {"x": 0, "y": 1, "z": 0, "directive": "FORCE_BLOCK:minecraft:oak_slab[type=top]"}
  ]
}
```

- `size` is `[x, y, z]` in blocks, 1–32 per axis; every coordinate is a block
  cell inside that box. Anything outside is clipped with a warning.
- Regions apply in order, then explicit `cells`, so a later entry wins.
- `fill` takes `from`/`to` (a bare `box` without `to` is a single cell); `layer`
  takes `y` and optionally narrows the layer with `from`/`to`.
- `resolution` defaults to 16 and is independent of the meta value only when the
  blueprint is used through `fit`; the model meta value wins at placement.

Directives:

| Directive | Effect |
|---|---|
| `SOLID` | The cell must collide. |
| `EMPTY` | The cell must not collide and is not clickable. |
| `FORCE_BLOCK:<state>` | Use this block state whatever the fitter scores (e.g. a specific slab type). |
| `PASSABLE_HITBOX` | No collision, but the cell is clickable (interaction entity or passable outline). |
| `TOP_SURFACE` | Standing surface: a real block chosen for a flat top. |
| `STEP` | Walk-up step: a real block whose top is at half height. |

A blueprint with no `regions` and no `cells` is valid but yields an empty
occupancy; run `fit report` before shipping it.

## 3. Config — `COLLISION`

Written into `settings.yml` by the config template:

```yaml
COLLISION:
  ENABLED: true            # false keeps the legacy barrier-fill path only
  RESOLUTION: 16           # sub-voxels per block edge, clamped to 8..32
  RASTER_MODE: CONSERVATIVE # CONSERVATIVE | CENTER
  PROFILE: DEFAULT         # DEFAULT | FURNITURE | WALKABLE | THIN
  MAX_BLOCKS: 64
  MAX_ENTITIES: 16
  ALLOW_VISIBLE_STATES: true
  VALIDATE_AT_STARTUP: false
  PARTS_PER_TICK: 64       # clamp floor is 16; 0 is not accepted
  BACKENDS:
    GHOST_BLOCK: true
    SHULKER_BOX_ENTITY: true
    INTERACTION_ONLY: true
    PASSABLE_OUTLINE: false
    PLATE: true
    PLATE_USE_MINECART: false
  SHULKER_SCALE: 0.5
  ATLAS:
    CACHE: true
    EXTRA_MATERIALS: []
    INVISIBLE_STATES: []
    EXCLUDED_MATERIALS: []
```

The legacy keys are read only when there is no `COLLISION` section at all, and
each one used is logged once at start-up as deprecated:

| Legacy key | Maps to |
|---|---|
| `VIRTUAL_RENDERING.COLLISION_MODE: NONE` | `COLLISION.ENABLED: false` |
| `VIRTUAL_RENDERING.COLLISION_EPSILON` | `COLLISION.RESOLUTION` (`round(1 / epsilon)`, clamped 1..32) |
| `VIRTUAL_RENDERING.COLLISION_NON_AIR_POLICY` | no equivalent; reported as deprecated only |

Once a `COLLISION` section exists the legacy block is not consulted, so keep
`VIRTUAL_RENDERING` only for the render switches it still owns. See
[migration.md](migration.md).

## 4. Commands and permissions

All under `/mineplus collision`, permission `mineplus.admin.collision`:

| Command | Does |
|---|---|
| `atlas stats` | Counts, key, build state of the loaded atlas. |
| `atlas dump [n]` | First `n` (default 50) entries with their masks. |
| `atlas rebuild` | Schedules a background rebuild; refuses when collision is disabled. |
| `fit debug <x> <y> <z> [profile]` | Fits a solid box of that size, prints the plan and draws it as dust at the block you are looking at (colour per backend). |
| `fit report <model> [profile]` | Fits an installed model and prints the plan; writes nothing. |
| `fit bake <model> [profile]` | Fits and writes the result to `collision/fits/`. |

Tab completion covers the sub-commands, actions and installed model names.
