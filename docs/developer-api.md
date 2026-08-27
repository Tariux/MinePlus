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
- Links/signals: `linkBlocks`, `unlinkBlocks`, `getLinkedBlocks`, `autoLinkNeighbors`, `sendSignal`
- Timed processes: `startProcess`, `cancelProcess`, `getProcess`

Use this tier for full machine frameworks and advanced add-ons.

### Timed Crafting Processes

`startProcess(instanceId, recipeId)` runs a recipe as a timed process on an ACTIVE machine:

- Duration comes from the recipe's `craftTimeTicks`, scaled by the machine level's `speed` multiplier (`speed: 2.0` = twice as fast; upgrading mid-process applies immediately).
- Process state is stored in the instance's `stateData` (`mp.process.*` keys), which is persisted — processes survive restarts and resume where they stopped.
- Processes on machines in unloaded chunks are paused, not cancelled (vanilla furnace parity).
- Completion fires `PROCESS_COMPLETE` lifecycle events and the `onProcessComplete(instance, recipe)` hook; start fires `PROCESS_START` / `onProcessStart`.
- The engine tracks only time. Consuming inputs and producing outputs is your feature's responsibility, typically inside the hooks.

Example — a smelter that takes 30 seconds and speeds up with level:

```java
advancedApi.createRecipe(new MachineRecipe("smelt_iron", "smelter", 1, 600,
        Map.of("raw_iron", 1), Map.of("iron_ingot", 1)));

// In your interact hook: verify + consume inputs yourself, then:
advancedApi.startProcess(instance.id(), "smelt_iron");

// In your MultiBlockHook:
@Override
public void onProcessComplete(MultiBlockInstance instance, MachineRecipe recipe) {
    // grant recipe.output() to the machine's inventory/owner
}
```

Progress for GUIs: `advancedApi.getProcess(instanceId).progressRatio()` returns `[0.0, 1.0]`.

### Auto-Linking (Pipe Networks)

`autoLinkNeighbors(sourceId, radius)` links an instance to every other instance within a Chebyshev cube of `radius` around it, returning the number of new links. Use in `onPlace` hooks to build pipe-style networks without tracking UUIDs. Links are directed (source -> neighbor) and combine freely with manual `linkBlocks` and signal propagation.

### Spatial Lookup

`MultiBlockRegistry.getNearby(BlockCoordinate, radius)` returns instances within a cube — the primitive backing auto-linking, also usable directly for proximity gameplay logic.

## Core Runtime Behavior

- `MultiBlockLifecycleManager` drives create/place/interact/tick/upgrade/remove.
- `ModelRenderingManager` maps machine level to `.bbmodel` rendering through `VirtualBlockManager`.
- `VirtualBlockManager` maintains session-local mapping and performs automatic cleanup of orphaned `BlockDisplay` "ghost" entities during chunk loading to ensure persistence synchronization.
- `MachineProcessManager` runs timed crafting processes on instances; process state lives in per-instance `stateData` and survives restarts.
- `PersistenceFacade` persists and restores active instances to SQLite (`plugins/Mineplus/infrastructure.db`) using an asynchronous write-behind queue: gameplay calls stage snapshots in memory, a background task flushes them off the main thread, and a synchronous flush runs on shutdown/reload. Failed flushes are re-queued and retried. (`MultiBlockStorageEngine` is deprecated and retained only for legacy JSON migration.)
- `HookBus` publishes lifecycle events to registered listeners.
- `MultiBlockLinkingSystem` handles directed links and signal propagation.
- Ticking is chunk-aware: instances in unloaded chunks are skipped (no hooks, heartbeats, renders, or process advancement) until their chunk loads again — vanilla tile-entity parity.

## Zero-Content Policy

Mineplus registers no gameplay content by default:
- No default blocks/items/machines.
- No test/demo spawns.
- No automatic world injections.

Anything in-game comes only from your JSON or API calls.
