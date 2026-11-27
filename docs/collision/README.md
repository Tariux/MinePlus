# Shape Atlas + Hybrid Fitter

How a Mineplus model becomes the blocks, entities and hitboxes a vanilla client
collides with. Everything here runs on the Paper/Spigot API only: no NMS, no
client mod, no required resource pack beyond the one the server already sends.

- [Schema](schema.md) — the `collision` block in a model's `.meta.json`, the
  blueprint format, the `COLLISION` config section, and the commands.
- [Migration](migration.md) — moving from a hand-written `virtualBoundingBox`
  or the legacy barrier fill.
- [Testing checklist](testing-checklist.md) — what to check before shipping a
  model, and how to reproduce a fit locally.
- [Internal design note](../internal/shape-atlas-migration.md) — what existed
  before and what this system replaces.

## The three pieces

**Shape atlas.** At start-up the plugin samples the server's real block
collision shapes (`BlockData#getCollisionShape` on Paper, a scratch-location
probe on plain Spigot) into occupancy masks and caches them on disk under
`plugins/<plugin>/collision/atlas.bin`. The cache is keyed by server version,
shape source and the candidate block list, so a version bump or a config change
invalidates it instead of mixing old masks into a new fit. `atlas rebuild`
rescans in the background, in chunks, without blocking the main thread.

**Occupancy source.** The model says what is allowed to collide, not which block
to use:

| Source | Meaning |
|---|---|
| `AUTO` | Rasterise the model's own elements (rotated by `yaw`) into occupied cells. |
| `BLUEPRINT` | A hand-authored JSON grid of cell directives wins over the model. |
| `FILL` | A solid box of the given size — cheap stand-in for a closed shape. |
| `HYBRID` | `AUTO` occupancy, with `BLUEPRINT` cells overriding where both are given. |

Every source produces the same `OccupancyMask` the fitter scores against, so the
choice changes accuracy and authoring effort, never the runtime contract.

**Hybrid fitter.** For each occupied cell it scores the atlas candidates on
sub-voxel mismatch (extra volume and missing volume are weighted separately),
top-surface flatness, walkability and the backend's own costs, then applies a
profile's weights and a walkability/ledge pass. The result is deterministic for
a given atlas, spec and profile, which is what makes `fit bake` reusable and
cheap to diff in review.

## Entry points

- `/mineplus collision atlas stats|dump|rebuild`
- `/mineplus collision fit debug <x> <y> <z> [profile]`
- `/mineplus collision fit report <model> [profile]`
- `/mineplus collision fit bake <model> [profile]`

In code: `PluginContext#collisionService()` returns the `CollisionService`
(always non-null; a disabled service answers `enabled() == false` and every
convenience method is safe to call). `CollisionSpec.builder(modelKey)` is the
way to describe a fit; the builder validates what the chosen source needs and
fails with a message naming the missing piece.

## Runtime cost

Fits are cached per model, atlas key and profile in
`plugins/<plugin>/collision/fits/`. A placed model replays a cached
`BackendPlan`; nothing re-fits at placement time. The manager applies a bounded
number of parts per tick (`COLLISION.PARTS_PER_TICK`) so a large furniture piece
never spikes the tick, and reconciles cell by cell on chunk restore, teleport
and disable.
