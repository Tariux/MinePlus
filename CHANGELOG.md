# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] — 2026-08-29

Core engine hardening and a new module toolkit: the architecture pass that made
the engine faster under load, fault-isolated against misbehaving modules, and
removed the boilerplate every module previously duplicated.

### Core engine (`Mineplus`)

#### Added

- **Module toolkit** (`context.moduleSupport()`, `ModuleSupport`):
  - `installDefault(module, resource, target, overwrite)` — installs embedded
    jar resources into the Core's data folder (models overwrite, configs don't).
  - `resolveLooked(player, range, typeId)` — resolves the machine a player is
    looking at (anchor block first, then rendered-model id); replaces every
    module's hand-rolled `findLooked`.
  - `registerCommand(module, label, SubCommand)` — dynamic top-level command
    registration on the server command map; no `plugin.yml` command entries,
    permission enforced by the wrapper.
- **`ModelPoints`** — CENTER-origin pixel→world transform helpers
  (`toWorld`, `toWorldOffset`, `direction`) using the exact math the renderer
  applies, so feature geometry (muzzles, seats, mounts) can never drift from
  the rendered model.
- **`TypedState`** — typed view over persisted `stateData`
  (`getInt/setInt/getLong/getDouble/getBoolean/...`); replaces hand-rolled
  string parsing in module state stores.
- **`Cooldowns`** — self-pruning per-key cooldown map; the standard
  main/off-hand interact-pair dedupe (leak-free, unlike raw maps).
- **`AbstractMachineGui`** — base class for machine GUIs owning all
  interaction guarding: top/bottom raw-slot routing, shift-click
  cancellation, cursor/hotbar-swap/drag validation via `accepts(slot, item)`,
  take-only output slots, filler, and capture-on-next-tick scheduling.
  Subclasses implement only layout, container topology, buttons, and capture.
- **`stagePersist(instanceId)`** (InfrastructureApi) — stages an instance's
  state for the async persistence queue after `stateData` mutations from
  hooks/GUI callbacks.

#### Changed

- **Incremental persistence** — hot lifecycle paths (place/upgrade/remove,
  process advancement) now stage single-instance upserts/deletes instead of
  rewriting every row per mutation; full-replace remains for bulk paths
  (reload, shutdown, migration). A single right-click no longer rewrites the
  whole table.
- **Chunk-aware tick fixed** — `TICK` events and `onTick` hooks are no longer
  dispatched for instances in unloaded chunks (previously only heartbeats and
  deferred renders were skipped, despite the docs claiming a full skip).
- **Heartbeat pass no longer writes** — it only refreshes timestamps; the
  full-dataset restage every 5 seconds (pure write amplification) is gone.
- **`MultiBlockRegistry.getInstances()`** returns a snapshot copy — hooks that
  remove instances mid-tick can no longer cause
  `ConcurrentModificationException` in the tick loop.

#### Fixed

- **Hook exception isolation** — all direct per-type hook dispatches (interact,
  upgrade, break/remove, tick, craft, signal, model reload, process
  start/complete) are wrapped: a throwing module hook is logged and skipped
  instead of aborting the lifecycle operation or killing the tick loop.
- **GUI callback isolation** — a throwing module GUI callback no longer breaks
  the inventory-event pipeline for other modules.
- **GUI session leak on quit** — open sessions are dropped on
  `PlayerQuitEvent` (previously only `InventoryCloseEvent` cleaned up).

### Reference module (`MineplusFun`)

#### Changed

- Both features install resources through `context.moduleSupport()`; the
  duplicated `installDefaultResource` helpers are gone.
- Both GUIs extend `AbstractMachineGui` — all slot/drag/hotbar guard code,
  capture scheduling, filler, and named-item helpers removed (~60% of each
  GUI class was boilerplate).
- Both subcommands resolve targets via `resolveLooked`; the duplicated
  `findLooked` helpers are gone.
- `/juicer` and `/cannon` are registered dynamically via
  `registerCommand` — no `plugin.yml` command entries, no dispatch in the
  plugin main class.
- Cannon: single `isHolding(actor, material)` helper replaces the duplicated
  torch/saddle variants; `Cooldowns` replaces the hand-rolled cooldown map;
  `ModelPoints` replaces the three duplicated CENTER-origin transform copies
  (level-1 muzzle, level-2 muzzle, seat).
- `CannonTntStore` accesses `stateData` through `TypedState`.
- `plugin.yml` keeps only permissions; command declarations removed.

#### Compatibility

Module-facing API is additive only (`moduleSupport()`, `stagePersist`,
`AbstractMachineGui`, `ModelPoints`, `TypedState`, `Cooldowns`). Existing
`InfrastructureGui`/`MultiBlockHook` implementations compile unchanged.
Modules built against 1.0.0 run unchanged, but should migrate to the toolkit
at their leisure. **Core and module must be rebuilt and redeployed together**
with this release.

[1.1.0]: https://github.com/Tariux/MinePlus/releases/tag/v1.1.0

## [1.0.0] — 2026-08-29

First public release.

### Core engine (`Mineplus`)

#### Added

- **Virtual Blockbench rendering** — single-pass streaming `.bbmodel` importer:
  negative-coordinate geometry, outliner pivot-conjugation transforms, per-cube
  `light_emission`, per-face UV analysis, and per-face material plates for
  mixed-material cubes. Models render as vanilla `BlockDisplay` entities —
  **no client mods, no resource packs**.
- **Geometry-aware collision** — per-cube SAT voxelization with `GEOMETRY`
  (default), `SURFACE` (interior hollowing), and `AABB` (legacy) modes; barrier
  occupancy uses the exact same transform as the visuals, so collision and
  rendering can never drift apart.
- **Rotation snapping** — placements snap to the 24 orientation-preserving axis
  permutations for exact voxel-lattice transforms.
- **Multiblock lifecycle** — create / place / interact / upgrade / tick / remove
  with hooks (`MultiBlockHook`) and lifecycle events for every stage.
- **Timed crafting processes** — restart-safe, chunk-aware timed processes
  (paused in unloaded chunks, resumed on load — vanilla furnace parity), scaled
  by per-level `speed` multipliers.
- **Machine linking & signals** — directed block links, pipe-network style
  auto-linking (`autoLinkNeighbors`), and signal propagation between machines.
- **Three-tier API** — `JsonInfrastructureApi` (config-first),
  `BasicInfrastructureApi` (place/lookup), `InfrastructureApi` (full hooks,
  GUIs, signals, processes).
- **SQLite persistence** — asynchronous write-behind queue
  (`plugins/Mineplus/infrastructure.db`); gameplay never blocks on disk I/O,
  with synchronous flush on shutdown/reload and re-queued retries on failure.
- **Ghost cleanup** — orphaned `BlockDisplay` entities from crashes are
  detected and removed on chunk loads.
- **Texture material resolver** — filename-to-vanilla-material pipeline
  (curated map, direct match, aliases, fuzzy matching) with a JVM-lifetime cache.
- **Admin CLI** — `/mineplus status`, `/mineplus reload [all|models|multiblocks|recipes]`,
  and the `/mineplus model` suite (list, inspect, remove, respawn, setlevel,
  debugspawn) with per-instance diagnostics.
- **Update checker** — optional SpigotMC version check, configured via
  `UPDATE_CHECKER.RESOURCE_ID` in `settings.mp.yml` (disabled by default).
- **bStats metrics** — anonymous usage statistics
  ([bstats.org](https://bstats.org/plugin/bukkit/Mineplus/33702)); opt-out anytime
  via `plugins/bStats/config.yml`.

#### Zero-content policy

The core ships with **no gameplay content** — no default machines, items, or
world injections. Everything in-game comes from your JSON or API calls.

### Reference module (`MineplusFun`)

#### Added

- **Juicer** — recipe-driven machine with a GUI, custom juice items, and a
  level upgrade path; demonstrates the unconditional-GUI interaction pattern.
- **Cannon** — 3×1×1 Blockbench cannon with conditional interaction
  (torch fires, anything else opens the ammunition menu), persistent TNT ammo
  (survives restarts via `stateData`), model-accurate `TNTPrimed` ballistics,
  and rotation-aware placement.
- `/juicer` and `/cannon` admin commands with tab completion.
- `DEVELOPMENT_PROMPT.md` — the canonical module-building guide.

[1.0.0]: https://github.com/Tariux/MinePlus/releases/tag/v1.0.0
