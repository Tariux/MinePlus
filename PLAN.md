# Shape Atlas + Hybrid Fitter — build plan

## Goal and users
Give Mineplus custom models collision and interaction that a vanilla client respects
without client mods or NMS, by fitting the closest set of real/ghost vanilla block
states, scaled shulkers and Interaction entities to a model's volume.
Users: Mineplus operators and addon authors placing furniture, gates, platforms and
decorations through the new `CollisionService`.
Working means: a model placed through the service has walkable surfaces, side walls
that stop players, correct click routing, nothing orphaned after a restart, and
`/mineplus collision fit debug` shows the chosen cells per cell.

## Scope
In scope: version adapter seam, Shape Atlas (+disk cache), occupancy sources
(AUTO/BLUEPRINT/FILL/hybrid), hybrid fitter with profiles and ledge smoothing,
backends (real block, ghost block, shulker, interaction, passable outline, plates),
planner, persistence/reconciliation/protection/lifecycle, facade + config + model
`collision` block + events + commands + debug tooling, migration of the legacy
whole-box barrier helper, samples, tests and documentation.
Out of scope: new client-side rendering, NMS, per-voxel custom shapes (impossible on
vanilla), a graphical blueprint editor, and rewriting the existing render/texel
pipeline.

## Architecture
- **Pure-Java core** in `com.mineplus.infrastructure.collision.*` (atlas, masks,
  sources, fitter, planner). No Bukkit types, so every rule is unit-testable off
  server. Bukkit types appear only in adapters/backends/manager.
- **Version adapter seam**: `ShapeReader` reads a `BlockData`'s collision shape.
  `PaperShapeReader` uses the Paper-only `BlockData#getCollisionShape(Location)` when
  present (reflective API probe, never NMS); otherwise `ScratchShapeReader` sets the
  state in a scratch block, reads `Block#getCollisionShape()` and restores it.
  `ServerCapabilities` reports provider, `Attribute.SCALE` vs `GENERIC_SCALE`, and
  entity/ghost support. Chosen once at startup.
- **Shape Atlas**: candidate pool (config) expanded to BlockData states by the
  Bukkit builder, rasterised to a 16³ `VoxelMask`, keyed by server version +
  provider + pool hash + raster mode and cached as a binary file; rebuilt
  automatically when the key changes.
- **Occupancy**: `OccupancyMask` (sub-voxel bitset, yaw rotation exact at 90°,
  resampled otherwise). Sources: AUTO (model JSON cuboid voxelisation + item
  display transform), BLUEPRINT (versioned JSON directives), FILL, HYBRID
  (blueprint overrides AUTO).
- **Fitter**: deterministic per-cell scoring over sorted candidates, profile
  weights (`FURNITURE`, `NARROW_GATE`, `WALKABLE_SURFACE`, `DECORATIVE_PASSABLE`,
  `BALANCED`), walkability/ledge pass, interior pruning, per-cell backend
  assignment, serialisable `FitResult` cached per model/rotation/profile/atlas key.
- **Backends**: `REAL_BLOCK` (standing surfaces, always solid server-side),
  `GHOST_BLOCK` (`Player#sendBlockChange`, re-sent on join/teleport/respawn/chunk
  resend), `SHULKER` (scaled via the scale attribute, capped, culled), `INTERACTION`
  (click/hit), `PASSABLE_OUTLINE` (no-collision state + physics guard),
  `PLATE` (optional boat/minecart platform). `BackendPlanner` decides and records
  why.
- **Data/persistence**: no database needed; durable stores are the binary atlas
  cache, `collision_state.json` (one record per model instance: dimension, origin,
  blocks, entities, fit metrics) and the fit cache under
  `plugins/<plugin>/collision/fits/`. Chunk-PDC is not used because it is
  Paper-only; the project store works on Spigot. Startup reconciliation compares
  records with the world and repairs orphans.
- **Auth boundary**: operator-only commands guarded by the collision permission;
  no player data is stored.
- **Threading**: fitting/voxelisation run async on immutable copies; all world and
  entity access happens on the main/region thread via `SchedulerCompat`.

## Risks and assumptions
- Spigot 1.21.1 stubs have no `BlockData#getCollisionShape(Location)`; the scratch
  probe is the portable path and the Paper path is probed at runtime.
- Shulker hitboxes ignore `Attribute.SCALE` on some versions; the capability check
  falls back to REAL_BLOCK.
- Ghost blocks are client-only; the fitter never assigns them to standing surfaces
  (server-side flying/anti-cheat safety).
- `1.21`–`26.2` support is expressed as capability probes, not version switches.
- Build verification is compilation + unit tests; a live server is needed for the
  behaviours listed in `docs/collision/testing-checklist.md`.

## Phase 1: Shape Atlas and adapter seam
Goal: real collision data read from the server, cached, queryable.
- [x] `ShapeReader` adapter (Paper probe + scratch probe) and `ServerCapabilities`.
- [x] `VoxelMask` + rasteriser, `ShapeEntry`, `ShapeAtlas`, binary cache with
      version/provider/pool key invalidation.
- [x] Configurable `CandidatePool` (full blocks, slabs, stairs, trapdoors, walls,
      fences, panes, bars, chains, candles, snow, carpets, utility blocks,
      zero-collision states) with invisible-capable/visibility flags.
- [x] `CollisionService` facade skeleton, `atlas stats|rebuild|dump` command,
      `collision_state.json` round-trip.
- [x] Unit runners: mask conversion, cache save/load, invalidation, ordering.
- Acceptance: `/mineplus collision atlas stats` reports a cached atlas built from
  the live server; the cache reloads on restart; tests pass. (Runners pass; the
  live-server half needs the checklist run.)

## Phase 2: Occupancy sources
Goal: model JSON, blueprint and fill all produce the same mask type.
- [x] `OccupancyMask` + combinators (union/intersect/subtract/inset/dilate/mirror/rotate).
- [x] AUTO JSON voxeliser (elements, rotation, display transform, planes, conservative/
      centred modes, arbitrary yaw resampling).
- [x] BLUEPRINT schema, parser with line/column errors, directives and region fills;
      HYBRID override merge.
- [x] Tests: cube, stair-shaped model, rotated cuboid, thin plate, blueprint errors.
- Acceptance: each source produces expected occupancy for fixture models.

## Phase 3: Hybrid Fitter
Goal: pick per-cell backends with measured error.
- [x] Profiles/presets/overrides, scoring, orientation handling, options limits.
- [x] Ledge smoothing, interior pruning, walkability metrics, warnings.
- [x] Deterministic ordering + golden results + serialisable fit cache.
- [x] `BackendPlanner` with explainable decisions.
- Acceptance: golden fit tests pass; same inputs produce byte-identical plans.

## Phase 4: Backends, lifecycle and safety
Goal: a placed model exists, persists and cleans up.
- [x] Real block, ghost block (with refresh listener), shulker, interaction,
      passable outline, plate backends behind `CollisionBackend`.
- [x] `CollisionManager`: budgeted apply, removal, chunk load/unload, startup
      reconciliation, plugin-disable cleanup, metrics.
- [x] Protection listeners (break/explosion/piston/fluid/fire/physics/gravity/
      enderman/edit), no drops, no inventory leak.
- [x] Events `ModelCollisionBuiltEvent`, `ModelCollisionRemovedEvent`,
      `ModelCollisionInteractEvent`: fired from the manager; an
      Interaction-entity click resolves to its model instance and can be
      cancelled before the vanilla interaction.
- [ ] Acceptance: spawn → restart → still present and healthy; remove → no
      leftovers. Needs a live server (checklist §4/§5).
- [x] Placement: a model declaring a `collision` block is fitted at spawn and
      placed through the manager; every other model keeps the legacy lattice.
- Open: none; lights for fitted models are placed from the same occupancy cells.
## Phase 5: API, config, model format, commands, docs, samples
Goal: operators and addons use one documented entry point.
- [x] `CollisionSpec` builder, `CollisionService` in `PluginContext`,
      `DeveloperApi2.collision()` and `ServicesManager`.
- [x] `COLLISION` config section with legacy-key mapping/warning; `ModelMeta`
      `collision` block; profile keys accepting the config spellings.
- [x] `fit debug|report|bake`, `atlas dump|rebuild|stats`, tab completion,
      permissions.
- [x] Debug renderer: `fit debug` draws the plan as coloured dust at the block
      the player is looking at (shell senders get the text plan only).
- [x] Deprecate legacy `VirtualBoundingBox`/barrier fill with delegates + migration doc.
- [x] Six sample plugins, docs (`docs/collision/`, schema, testing checklist),
      README index entry, changelog, Javadoc.
- Acceptance: full build + all test runners green; samples compile; docs match code.

Assumed: resolution 16 sub-voxels per block, atlas pool default as listed, fit cache
under `plugins/<plugin>/collision/fits/`, Bedrock/Geyser degradation to REAL_BLOCK-only,
and that `1.21`–`26.2` differences are handled by capability probes.
