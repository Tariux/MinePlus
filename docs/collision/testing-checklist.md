# Collision testing checklist

Run through this before shipping a model, and before shipping a change to the
fitter, atlas or backends. Each item is something a plain vanilla client can
check — no client mod is involved.

## 1. Unit and runner tests

```
./gradlew testRunners
```

The runners that cover this system:

| Runner | Covers |
|---|---|
| `ShapeAtlasTest` | Mask arithmetic, atlas key invalidation, binary cache round-trip, candidate pool ordering. |
| `OccupancySourceTest` | AUTO/BLUEPRINT/FILL/HYBRID, directives, parent chains, rotation, parse errors with JSON paths. |
| `HybridFitterTest` | Scoring, profiles, walkability pass, determinism, plan serialisation, fit cache, the `CollisionService` facade. |
| `CollisionStoreTest` | Atomic writes, reload, corrupt-file quarantine, removal. |
| `ApiShapeGuardTest` | Every Paper/Spigot seam still matches the compile-time stubs. |

`./gradlew compileJava` must be green before any of these mean anything; a test
task that silently skipped compilation proves nothing.

## 2. Atlas

1. Start a server with no existing `plugins/<plugin>/collision/atlas.bin`.
2. `/mineplus collision atlas stats` — the key is non-empty, the entry count is
   plausible for the candidate pool, the state says ready once the background
   build finishes.
3. Restart. The stats key must be identical; `atlas.bin` is reused.
4. Change `COLLISION.RESOLUTION` or `ATLAS.EXTRA_MATERIALS`, restart: the key
   changes and the atlas rebuilds instead of silently reusing stale masks.
5. `/mineplus collision atlas rebuild` on a live server: the main thread keeps
   ticking (no multi-second stall), and stats flip back to ready.

## 3. Fit quality per shape class

For each of these, `fit report <model>` and then walk into it in game:

- [ ] **Cube / crate** — solid on all sides, no gap at a corner, jumping onto it
      from a neighbouring block works.
- [ ] **Chair / bench** (`FURNITURE`, `HYBRID` with a `TOP_SURFACE` layer) — you
      can stand on the seat, the back and legs block, and you cannot walk
      through the seat diagonally.
- [ ] **Thin plate / sign** (`THIN`) — one block of thickness, passable from the
      side where the model is not, and the outline matches the visible plate.
- [ ] **Stair-shaped deck** (`WALKABLE`) — walk up without jumping, no invisible
      wall at the step edge.
- [ ] **Rotated model** (`yaw` 22.5 and 90) — the plan is identical across two
      runs (determinism), and the collision follows the visible rotation, not
      the unrotated model.
- [ ] **Large multiblock** — the part budget degrades gracefully: the model
      appears, collision fills in over consecutive ticks, nothing is left
      half-applied after a restart (state file reconciliation).

## 4. Client-side pass

- [ ] No floating barrier blocks visible, no ghost blocks left behind after
      breaking the furniture.
- [ ] Standing on, jumping onto, sliding along and walking off an edge all behave
      like the equivalent real blocks.
- [ ] Click routing: right-click the model opens its GUI/interaction rather than
      placing a block against an invisible barrier.
- [ ] With `ALLOW_VISIBLE_STATES: false`, only states the pack hides are used and
      the model still looks unchanged.
- [ ] Chunk unload/reload while standing on the model: no fall-through, no
      duplicate parts.

## 5. Failure behaviour

- [ ] A broken blueprint (bad JSON, out-of-range `size`, unknown directive) is
      rejected with the file and cell named, and the model still loads.
- [ ] A model with no `collision` block loads and uses the legacy path.
- [ ] Deleting `collision_state.json` while the server is stopped loses the
      placement record only; models are rebuilt on next spawn without an error
      spam loop.
- [ ] `COLLISION.ENABLED: false` disables the whole thing and leaves the legacy
      barrier fill working.

## 5. Runtime: placement and interaction

A model that declares a `collision` block is fitted at spawn and placed through
`CollisionManager`; every other model keeps the legacy lattice. `COLLISION.ENABLED:
false` puts every model back on the lattice.

Right-clicking an interaction hitbox fires `ModelCollisionInteractEvent`
(`getPlayer`, `getClickedEntity`, `getFurniture`, `getPart`,
`getClickedLocation`). Cancelling it also cancels the underlying
`PlayerInteractEntityEvent`, so no vanilla armature interaction reaches the
technical entity — open the model's GUI there.

## 6. Compatibility matrix

Do at least one pass per maintained API line (1.21.x on Paper and on Spigot, plus
the newest supported version). On Spigot, the shape source falls back to the
scratch-location probe: check `atlas stats` reports the fallback source and that
the entry count is not zero.
