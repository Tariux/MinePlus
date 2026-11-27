# Shape Atlas migration map (internal)

Written before implementation: what exists today, what the new collision system
touches, and what it replaces. This is the working map for the migration; the
user-facing version lives in `docs/collision/migration-guide.md`.

## Repository layout (relevant parts)

| Area | Location | Role |
|---|---|---|
| Plugin entry | `src/main/java/com/mineplus/MineplusPlugin.java` | Builds `PluginContext`, registers commands/listeners, owns `VirtualBlockManager`. |
| Config | `com/mineplus/config/ConfigManager.java`, `MineplusConfig.java`, `settings.mp.yml` template | Section-per-subsystem parse into immutable settings records. |
| Model metadata | `com/mineplus/infrastructure/virtual/ModelMeta.java` (`*.meta.json`) | Per-model origin, anchor, light, display, and the legacy `collisionMode` / `collisionMaterial` / `fillBox`. |
| Renderer / placement | `com/mineplus/infrastructure/virtual/VirtualBlockManager.java` | Spawns ItemDisplay/BlockDisplay models; the legacy path fills a whole-model AABB with barriers via `VirtualBoundingBox`. |
| Legacy collision | `VirtualBoundingBox.java`, `GeometryOccupancyCalculator.java`, `OrientableBlockStates.java`, barrier fill in `VirtualBlockManager` | Whole-box barrier fill, occupancy cell maths, orientation helpers. |
| Custom blocks | `com/mineplus/infrastructure/core/block/` (`BlockDefinition*`, `BlockStore`, `BlockPlacementService`) | JSON block definitions with PDC-tagged items and persisted placements — the closest existing "real block" lifecycle to reuse as a pattern (not as a store). |
| Multiblocks | `com/mineplus/infrastructure/core/multiblock/` | Formation/lifecycle; consumes virtual models. |
| Commands | `com/mineplus/infrastructure/command/CommandRouter` + `command/sub/*` | `/mineplus <sub>` with permissions and tab completion. |
| Events | `com/mineplus/infrastructure/api/event/*` | `MultiBlockInteractEvent`, `VirtualBlockClearAreaEvent`, etc. |
| Public API | `com/mineplus/infrastructure/DeveloperApi2`, `api/MineplusServices`, `PluginContext` | Stable facades, ServicesManager registration. |
| Scheduling | `com/mineplus/util/SchedulerCompat` | Folia/Paper/Bukkit scheduler seam. |
| Tests | `src/test/java/com/mineplus/test/*Test.java` (plain `main` runners registered in `build.gradle`) | No JUnit; each runner prints `all checks passed` or fails. |
| Samples | `examples/mineplus-fun`, `mineplus-gun`, `mineplus-mobfun` (separate Gradle projects) | Compile against `libs/spigot-api-1.21.1.jar` + built core jar. |

## What this feature replaces / touches

| Existing piece | Decision |
|---|---|
| Whole-model barrier fill (`VirtualBoundingBox` + `VirtualBlockManager` barrier loop) | **Superseded.** Kept as a `@Deprecated` delegate that routes to `CollisionService` FILL plan for callers that still ask for `collisionMode: FULL/SURFACE`; documented in the migration guide. |
| `GeometryOccupancyCalculator` | **Superseded** by `OccupancyMask` (same 1/16 cell contract, plus rotation/combinators). Kept for the legacy call site, marked deprecated. |
| `OrientableBlockStates` | **Reused** as the legacy orientation helper; the new fitter bakes orientations into the atlas instead of resolving states at spawn time. |
| `ModelMeta.collisionMode` / `collisionMaterial` / `fillBox` | **Kept readable**; new optional `collision` object (profile, source, blueprint, backends, options) takes precedence; one-time deprecation warning with the mapped new key. |
| `settings.mp.yml` `VIRTUAL_RENDERING.COLLISION_*` keys | **Kept readable**; mapped into the new `COLLISION` section with a one-time warning. |
| Manual shulker/Interaction hitbox helpers in samples | **Removed from samples**; samples call `CollisionService`. |
| `MultiBlockInteractEvent` / `VirtualBlock*Event` | **Kept**; collision interaction routing fires `ModelCollisionInteractEvent` and forwards hits to the owning instance. |
| `BlockStore` | Pattern reused; collision uses its own `collision_state.json` because block records are per-block while a fit is per-model. |

## New seams and version notes

- `BlockData#getCollisionShape(Location)` exists on Paper but not in the Spigot
  1.21.1 stubs; `PaperShapeReader` probes for it at runtime (API reflection only).
- `Block#getCollisionShape()` exists on both and powers `ScratchShapeReader`.
- `Attribute.SCALE` (1.20.5+) vs `Attribute.GENERIC_SCALE` older spelling is
  resolved through the existing `MobAttributes` seam.
- `Chunk#getPersistentDataContainer()` is Paper-only, so persistence uses the
  project's own store (works on Spigot and across 1.21–26.2).
- `Interaction`, `ItemDisplay`, `BlockDisplay`, `Player#sendBlockChange` all exist
  in the pinned stubs and need no adapter.

## Verification plan

Compilation and the plain-Java runners (`./gradlew testRunners`) cover the pure
core: mask conversion, atlas cache/invalidation, voxelisation, blueprint parsing,
scoring, ledge smoothing, planner determinism, fail-safety. Everything that needs a
world (probing, ghost refresh, shulker scale, protection) is compiled against the
pinned Spigot API and listed in `docs/testing/collision.md` for a live run.
