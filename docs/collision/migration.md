# Migration: legacy collision → Shape Atlas

What moves, what stays, and in which order. The legacy path is not removed in
this release: it keeps running until a model opts in, and every deprecated entry
point delegates to the new system so the two cannot drift apart.

## What the legacy system did

| Legacy piece | Where | Behaviour |
|---|---|---|
| `VirtualBoundingBox` | `infrastructure/virtual` | A whole-block AABB (or per-cube geometry) turned into a list of barrier cells around the model. |
| Barrier fill | `VirtualBlockManager` spawn path | Writes `BARRIER` blocks into the world at those cells; cleans them up when the instance is removed. |
| `VIRTUAL_RENDERING.COLLISION_MODE` | config | `GEOMETRY` / `SURFACE` / `AABB`, chosen for every model at once. |
| `VIRTUAL_RENDERING.COLLISION_EPSILON` | config | Contact epsilon for the geometry rasteriser, doubling as cell shrink. |

Limits that motivated the replacement: one block type for everything (no slabs,
no stairs, no walkable tops), barrier is invisible but also unfriendly to
click-routing, a whole-box fit cannot describe a chair, and every restart had to
re-derive the cells from the model.

## What replaces it

- `CollisionSpec` + `CollisionService` describe and execute a fit per model.
- Occupancy comes from `AUTO` / `BLUEPRINT` / `FILL` / `HYBRID` instead of the
  global `COLLISION_MODE`.
- `CollisionManager` owns placement, persistence (`collision_state.json`),
  reconciliation and removal, and applies parts under a per-tick budget.
- Backends pick real blocks, packet ghosts, scaled shulkers, interaction
  entities, passable outlines or plates per cell, so a chair gets a real
  standing surface and a lamp gets an outline.

## Delegates kept for compatibility

| Legacy call | Delegate |
|---|---|
| `VirtualBoundingBox` | `@Deprecated(since = "5.0")`; `toCollisionSpec(modelKey)` returns the equivalent `CollisionSpec` (same extent as a `FILL` box). Inset, profile and yaw come from the `COLLISION` section, since the legacy record carries none of them. |
| Legacy whole-box barrier fill | Still runs when `COLLISION.ENABLED` is `false`, and for any model whose meta has no `collision` block. It is the fallback, not the default path for new models. |
| `MineplusConfig.parseVirtualRendering` | Unchanged; the render switches it owns keep working. Only the collision keys are re-read by `parseCollision` and reported as deprecated. |

## Steps for an operator

1. **Update config.** Add the `COLLISION` section (the template writes it).
   Keep `VIRTUAL_RENDERING` for rotation snap, per-face rendering and origin
   mode. Remove `COLLISION_*` from it: once a `COLLISION` section exists those
   keys are ignored, and leaving them behind only produces a start-up warning.
2. **Start once and read the log.** Legacy keys that were mapped log one line
   each; nothing else about them is used.
3. **Run `/mineplus collision atlas stats`.** The first start builds the atlas
   in the background, in chunks. Do not rely on fits until it reports ready.
4. **Fit one model at a time.** `/mineplus collision fit report <model>` prints
   the plan and the per-cell choices without writing anything. Iterate the
   model's `collision` block (usually `HYBRID` with a `TOP_SURFACE` layer) until
   the report looks right, then `fit bake <model>` to store the result.
5. **Roll back cheaply.** `COLLISION.ENABLED: false` returns every model to the
   legacy barrier fill without touching meta files. Delete
   `plugins/<plugin>/collision/fits/` if you want the fits re-derived.

## Steps for an addon author

```java
CollisionSpec spec = CollisionSpec.builder("myplugin:bench")
        .hybrid(modelJson, blueprintJson)
        .profile("FURNITURE")
        .yaw(90)
        .build();
FitResult result = context.collisionService().fit(spec, resolver);   // cached
for (String line : CollisionService.describeFit(result)) {
    getLogger().info(line);
}
```

`PluginContext#collisionService()` is never null. A disabled service answers
`enabled() == false`, and `fit` then reports that collision is off instead of
throwing, so an addon can ship the same code with the feature switched off.

## What is still legacy in this release

The barrier-fill lattice is now the fallback, not the default: a model whose
`.meta.json` declares a `collision` block is fitted at spawn and placed through
`CollisionManager`, and the lattice is used only when the model declares nothing,
collision is disabled, or the fit produces no parts. Two consequences worth
knowing:

- The first spawn of a model fits it on the main thread; after that the fit comes
  from `collision/fits/`, so baking a heavily used model keeps spawn cheap.
- A fitted model still emits light: its occupancy cells are reused as light
  sources and tracked for cleanup with the instance.
