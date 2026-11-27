# Collision samples

Six copy-paste samples for the Shape Atlas + Hybrid Fitter. Each one is a small
static helper: call it from your plugin's enable step, or adapt it into a
command. They compile against the framework classes (`PluginContext`,
`CollisionSpec`, `CollisionService`), so drop the folder into a plugin source
tree or compile it next to the examples.

| Sample | Source | Profile | Shows |
|---|---|---|---|
| `ChairFurnitureSample` | `AUTO` | `FURNITURE` | The default path: fit the model's own geometry, bake the plan. |
| `BenchBlueprintSample` | `HYBRID` | `WALKABLE` | Model volume plus a `TOP_SURFACE` seat row. |
| `ThinSignSample` | `FILL` | `THIN` | A one-block plate where extra volume is expensive. |
| `CrateScaledSample` | `AUTO` | `DEFAULT` | A display transform folded into the fit. |
| `WalkableDeckSample` | `BLUEPRINT` | `WALKABLE` | `STEP` and `TOP_SURFACE` cells for walk-up steps. |
| `GateHitboxSample` | `BLUEPRINT` | `FURNITURE` | `FORCE_BLOCK` posts and a `PASSABLE_HITBOX` opening. |

`ModelFiles` is the shared helper that resolves an installed model's JSON and
returns a `ModelResolver`, the same way `/mineplus collision fit report` does.

## Before you copy

- `bake` writes into `plugins/<plugin>/collision/fits/`. During authoring use
  `fit(spec, resolver)` and print `CollisionService.describeFit(result)`
  instead, so a bad blueprint does not leave a cached plan behind.
- A blueprint is validated when the spec is built: syntax errors and unknown
  directives throw `IllegalArgumentException` (or `BlueprintException` from the
  parser) with the file path and cell, so fail loudly at enable rather than
  silently at placement.
- Collision only turns on when `COLLISION.ENABLED: true`; a disabled service
  answers `enabled() == false` and every sample above is still safe to call.

See `docs/collision/` for the schema, the migration steps and the test
checklist.
