Mineplus is a Spigot/Paper plugin focused on a data-driven "vanilla-like systems" framework: custom items, managed blocks, multiblocks/machines, recipes, GUIs, and virtual rendered models, all server-side (no client mod required).

Runtime starts in `MineplusPlugin`: it loads `settings.mp.yml` via `ConfigManager`, applies `VirtualRenderingSettings` to `VirtualBlockManager`, loads model assets from the plugin data folder, then bootstraps `PluginContext`. `PluginContext` wires `ItemRegistry` and the infrastructure engine + `InfrastructureApi`.

The modern extension core is `InfrastructureEngine`. It composes:
- `MultiBlockRegistry` (type registry + instance indices by UUID/location)
- `MultiBlockLifecycleManager` (create/place/interact/upgrade/remove/tick, model reload, save/restore)
- `ModelRenderingManager` (maps multiblock level -> virtual model spawn/swap/remove through `VirtualBlockManager`)
- `MultiBlockLinkingSystem` + `HookBus` (block links, signals, lifecycle event fanout)
- `RecipeManager`, `UpgradeManager`, `InfrastructureGuiManager`, and persistence/config loaders.

Feature definitions can come from code or JSON:
- Code: use `InfrastructureApi` (`registerMultiBlock`, `create/place/upgrade/remove`, `registerHook`, lifecycle listeners, recipe/gui registration, linking/signals).
- JSON multiblocks: `plugins/Mineplus/multiblocks/*.json` parsed by `MultiBlockConfigLoader` into `MultiBlockType` + `MultiBlockLevel` (`model`, `speed`, `durability`, `upgradeCost`, `guiOptions`, optional `gui` key).
- JSON machine recipes: `plugins/Mineplus/recipes/*.json` parsed by `RecipeConfigLoader` into `MachineRecipe`.

Virtual rendering pipeline (`infrastructure.virtual`):
- `BbModelImporter` single-pass streaming parser (Gson `JsonReader`): `.bbmodel` -> `VirtualModel`. Dead branches (texture `source` Base64 PNGs, animations, timeline setups) are skipped with `skipValue()` and never allocated. Captures `resolution` and per-element `light_emission`; preserves negative-coordinate geometry and outliner pivot-conjugation transforms.
- Anchor convention (`ORIGIN_MODE`, default `CENTER`): vanilla/Blockbench space — pixel (0,0,0) is the anchor block's center at its base (a full block spans pixels [-8..8] xz, [0..16] y). Single-block models occupy exactly one block and rotate about the block center. `GRID` (corner anchor) is available for multi-block structures; `LEGACY` is an alias of `CENTER`.
- `RotationSnapper` snaps placement rotations to the 24 orientation-preserving signed axis permutations, giving exact integer voxel-lattice transforms; deviations beyond the threshold are warned via `DebugLogger`.
- `VoxelOccupancyCalculator` geometry-aware collision: per-cube SAT voxelization (axis-aligned fast path), `GEOMETRY` (default) / `SURFACE` (interior hollowing) / `AABB` (legacy fill) modes, packed long cell set, per-(model, orientation, mode) cache. Voxelization consumes the exact same `T(anchorOffset)·R·M` transform the displays use, so barriers and visuals cannot drift.
- `TextureMaterialResolver` filename->vanilla-material pipeline (curated map -> direct match -> suffix-strip -> aliases -> fuzzy -> fallback) with a JVM-lifetime cache; unresolved names are reported per model (`/mineplus model info`).
- `DisplayEmitter` per-cube emission: fast path = one `BlockDisplay` per uniform cube; mixed-material cubes add thin per-face plates (box mapping parity, UV in-plane rotation, `light_emission` -> brightness).
- `ModelMeta` per-model overrides via `models/<key>.meta.json` (`textureMode`, `originMode`, `collisionMode`).
- `VirtualBlockManager` spawn/remove, barrier placement (`SKIP`/`STRICT` non-air policy), ghost `BlockDisplay` cleanup on chunk loads. `ModelRenderingManager` resolves per-level model paths and binds rendered model IDs back to instances.

Configuration and logging:
- `ConfigManager` auto-generates `plugins/Mineplus/settings.mp.yml` on first start; the `VIRTUAL_RENDERING` section controls collision mode/epsilon/non-air policy, rotation snapping, per-face rendering, texture mode, and origin mode.
- `DebugLogger` gates all additional lifecycle, rendering, persistence, and linking logs behind `ADDITIONAL_DEBUG_LOGS` (default `false`). When disabled, only essential plugin lifecycle logs are emitted.
- `/mineplus model info <key>` reports cube count, display estimate, resolved texture table, occupancy cells with ASCII layer dumps, and cache status.

Current architecture direction is stabilization/alignment of the infrastructure layer; prefer extending via `InfrastructureApi` + engine managers instead of ad-hoc listeners.
