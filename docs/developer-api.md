# Mineplus Developer API

Mineplus exposes three usage tiers:

1. **JSON Tier** (`JsonInfrastructureApi`): reload and manage data-driven definitions.
2. **Basic Tier** (`BasicInfrastructureApi`): simple create/place/remove/get operations.
3. **Advanced Tier** (`InfrastructureApi`): full hooks, lifecycle listeners, GUIs, links, and signals.

## Getting API Handles

From your plugin (after Mineplus is enabled):

```java
MineplusPlugin mineplus = MineplusPlugin.getInstance();
BasicInfrastructureApi basicApi = mineplus.basicInfrastructureApi();
JsonInfrastructureApi jsonApi = mineplus.jsonInfrastructureApi();
InfrastructureApi advancedApi = mineplus.infrastructureApi();
```

## Tier 1: JSON Infrastructure API

Interface: `JsonInfrastructureApi`

Methods:
- `reloadAll()`
- `reloadModelDefinitions()`
- `reloadMultiBlocks()`
- `reloadRecipes()`

Use this tier when your workflow is config-first and you only need runtime reload controls.

## Tier 2: Basic Infrastructure API

Interface: `BasicInfrastructureApi`

Methods:
- `Collection<String> getTypeIds()`
- `Collection<MultiBlockInstance> getLoadedInstances()`
- `MultiBlockInstance getAt(Location location)`
- `MultiBlockInstance get(UUID id)`
- `MultiBlockInstance createAndPlace(String typeId, Location location, UUID owner, Player actor)`
- `boolean removeAt(Location location, Player actor, boolean destroy)`

Use this tier for add-ons that need placement/removal and lookup, without implementing hook graphs.

## Tier 3: Advanced Infrastructure API

Interface: `InfrastructureApi`

Main capabilities:
- Register types: `registerMultiBlock(MultiBlockType)`
- Lifecycle: `createMultiBlock`, `placeMultiBlock`, `upgradeBlock`, `removeBlock`
- Hooking/events: `registerHook`, `registerLifecycleListener`
- Recipes/GUIs: `createRecipe`, `registerGui`, `openGui`
- Networks: `linkBlocks`, `unlinkBlocks`, `getLinkedBlocks`, `sendSignal`
- Lookup/signals: `getBlock`, `getBlockAt`, `createSignal`

Use this tier for full machine frameworks and advanced add-ons.

## Core Runtime Behavior

- `MultiBlockLifecycleManager` drives create/place/interact/tick/upgrade/remove.
- `ModelRenderingManager` maps machine level to `.bbmodel` rendering through `VirtualBlockManager`.
- `VirtualBlockManager` maintains session-local mapping and performs automatic cleanup of orphaned `BlockDisplay` "ghost" entities during chunk loading to ensure persistence synchronization.
- `MultiBlockStorageEngine` persists and restores active instances.
- `HookBus` publishes lifecycle events to registered listeners.
- `MultiBlockLinkingSystem` handles directed links and signal propagation.

## Zero-Content Policy

Mineplus registers no gameplay content by default:
- No default blocks/items/machines.
- No test/demo spawns.
- No automatic world injections.

Anything in-game comes only from your JSON or API calls.
