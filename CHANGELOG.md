# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
