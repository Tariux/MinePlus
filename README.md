# Mineplus

Mineplus is a dependency-free Paper/Spigot **core infrastructure plugin** for server-side machine systems with Blockbench `.bbmodel` rendering and collision handling.

It follows a strict zero-content policy:
- no default blocks/items/machines,
- no test world injections,
- no gameplay changes unless JSON or API usage is provided.

## Runtime Architecture

Boot flow:
1. `MineplusPlugin` starts.
2. `VirtualBlockManager` loads `.bbmodel` definitions from `plugins/Mineplus/models` (including nested folders like `plugins/Mineplus/models/debug`).
3. `PluginContext` boots `InfrastructureEngine`.
4. `InfrastructureEngine` loads JSON (`multiblocks`, `recipes`) and restores persisted instances.

Core internals:
- `MultiBlockRegistry`
- `MultiBlockLifecycleManager`
- `ModelRenderingManager`
- `MultiBlockLinkingSystem`
- `RecipeManager`
- `MultiBlockStorageEngine`

## 3 Usage Tiers

1. **JSON Tier** (`JsonInfrastructureApi`) for config-first servers.
2. **Basic Tier** (`BasicInfrastructureApi`) for simple add-ons.
3. **Advanced Tier** (`InfrastructureApi`) for full hook/event/link/GUI integrations.

## Admin CLI

- `/mineplus status`
- `/mineplus reload [all|models|multiblocks|recipes]`
- `/mineplus model <list|inspect|remove|respawn|setlevel> ...`

## Configuration

- `plugins/Mineplus/settings.mp.yml` is auto-generated on first start.
- Set `ADDITIONAL_DEBUG_LOGS: true` to enable verbose debug output across multiblock lifecycle, rendering, persistence, and linking systems. Default: `false`.

## Docs

- `docs/config-reference.md`
- `docs/developer-api.md`
- `docs/extension-workflows.md`
- `examples/README.md`
