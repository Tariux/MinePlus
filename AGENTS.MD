Mineplus is a Spigot/Paper plugin focused on a data-driven "vanilla-like systems" framework: custom items, managed blocks, multiblocks/machines, recipes, GUIs, and virtual rendered models, all server-side (no client mod required).

Runtime starts in `MineplusPlugin`: it builds `VirtualBlockManager`, loads model assets from the plugin data folder, then bootstraps `PluginContext`. `PluginContext` wires legacy registries/services (`ItemRegistry`, `BlockRegistry`, `MultiblockRegistry`, `RecipeRegistry`, `ManagedBlockService`, `ItemUseService`, GUI managers, limiter) and also initializes the newer infrastructure engine + `InfrastructureApi`.

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

Virtual rendering pipeline: Blockbench-like JSON -> `BbModelImporter` -> `VirtualModel` (`BakedCube` list) -> `VirtualBlockManager` spawn/remove and barrier/display tracking. `ModelRenderingManager` resolves per-level model paths and binds rendered model IDs back to instances. `VirtualBlockManager` also monitors chunk loads to remove orphaned `BlockDisplay` entities (ghosts) that persist across server reloads.

Configuration and logging:
- `ConfigManager` auto-generates `plugins/Mineplus/settings.mp.yml` on first start.
- `DebugLogger` gates all additional lifecycle, rendering, persistence, and linking logs behind `ADDITIONAL_DEBUG_LOGS` (default `false`). When disabled, only essential plugin lifecycle logs are emitted.

Current architecture direction (recent commits up to `e3e3ee5`) is stabilization/alignment of the infrastructure layer; prefer extending via `InfrastructureApi` + engine managers instead of ad-hoc listeners.
