# 🧩 Developer API

> **Navigate:** [Docs Home](README.md) • [Configuration Reference](config-reference.md) • [Extension Workflows](extension-workflows.md) • [Examples](../examples/README.md)

Mineplus exposes three usage tiers. They stack: a machine defined through JSON (Tier 1) can be placed by a basic add-on (Tier 2) and driven by full framework code (Tier 3) — all at once.

| Tier | Interface | For |
|---|---|---|
| **1 — JSON** | `JsonInfrastructureApi` | Config-first servers; runtime reload control |
| **2 — Basic** | `BasicInfrastructureApi` | Simple place/remove/lookup add-ons |
| **3 — Advanced** | `InfrastructureApi` | Full hooks, lifecycle listeners, GUIs, links, signals, processes |

**Jump to:** [API Handles](#getting-api-handles) · [Tier 1](#tier-1-json-infrastructure-api) · [Tier 2](#tier-2-basic-infrastructure-api) · [Tier 3](#tier-3-advanced-infrastructure-api) · [Hooks](#multiblock-hooks) · [Timed Processes](#timed-crafting-processes) · [Auto-Linking](#auto-linking-pipe-networks) · [Runtime Internals](#core-runtime-behavior)

---

## Getting API Handles

From your plugin (after Mineplus is enabled):

```java
MineplusPlugin mineplus = MineplusPlugin.getInstance();
BasicInfrastructureApi basicApi = mineplus.basicInfrastructureApi();
JsonInfrastructureApi jsonApi = mineplus.jsonInfrastructureApi();
InfrastructureApi advancedApi = mineplus.infrastructureApi();
```

Or through the full `PluginContext`, the recommended entry point for modules:

```java
Plugin core = Bukkit.getPluginManager().getPlugin("Mineplus");
if (!(core instanceof MineplusPlugin mineplus)) { /* disable with clear error */ }
PluginContext context = mineplus.getPluginContext();

context.plugin()               // the Core JavaPlugin (getDataFolder() → install JSON/models)
context.itemRegistry()         // custom item registration
context.virtualBlockManager()  // model registration / render queries
context.infrastructureEngine() // .registry() .lifecycleManager() .recipeManager() .guiManager()
context.basicInfrastructureApi()
context.infrastructureApi()
context.jsonInfrastructureApi()
```

Always perform the dependency check in `onEnable` and disable with a human-readable message if the core is absent — see the reference implementation in [`MineplusFunPlugin.java`](../examples/mineplus-fun/src/main/java/com/mineplus/fun/MineplusFunPlugin.java).

---

## Tier 1: JSON Infrastructure API

**Interface:** `JsonInfrastructureApi`

| Method | Description |
|---|---|
| `reloadAll()` | Reload models, multiblocks, and recipes |
| `reloadModelDefinitions()` | Reload `.bbmodel` files only |
| `reloadMultiBlocks()` | Reload multiblock type JSON only |
| `reloadRecipes()` | Reload recipe JSON only |

Use this tier when your workflow is config-first and you only need runtime reload control. The file formats are documented in the [Configuration Reference](config-reference.md).

---

## Tier 2: Basic Infrastructure API

**Interface:** `BasicInfrastructureApi`

| Method | Description |
|---|---|
| `Collection<String> getTypeIds()` | All registered machine type ids |
| `Collection<MultiBlockInstance> getLoadedInstances()` | All live instances |
| `MultiBlockInstance getAt(Location location)` | Instance anchored at a location |
| `MultiBlockInstance get(UUID id)` | Instance by id |
| `MultiBlockInstance createAndPlace(String typeId, Location location, UUID owner, Player actor)` | Create + place in one call |
| `boolean removeAt(Location location, Player actor, boolean destroy)` | Remove by location |

> ⚠️ **Rotation note:** `createAndPlace` always uses **identity rotation** — perfect for symmetric machines, wrong for facing-sensitive ones (cannons, conveyors). For those, use [`createMultiBlock` + `placeMultiBlock`](#tier-3-advanced-infrastructure-api) with your own rotation quaternion.

**Working example:** [`examples/code-based/BasicPlacementExample.java`](../examples/code-based/BasicPlacementExample.java)

---

## Tier 3: Advanced Infrastructure API

**Interface:** `InfrastructureApi`

| Capability | Methods |
|---|---|
| Register types | `registerMultiBlock(MultiBlockType)` |
| Lifecycle | `createMultiBlock`, `placeMultiBlock`, `upgradeBlock`, `removeBlock` |
| Hooking & events | `registerHook(typeId, MultiBlockHook)`, `registerLifecycleListener` |
| Recipes & GUIs | `createRecipe`, `registerGui`, `openGui` |
| Links & signals | `linkBlocks`, `unlinkBlocks`, `getLinkedBlocks`, `autoLinkNeighbors`, `sendSignal` |
| Timed processes | `startProcess`, `cancelProcess`, `getProcess` |
| Lookups | `getBlock`, `getBlockAt` |

**Working examples:** [`examples/code-based/AdvancedHookedMachineExample.java`](../examples/code-based/AdvancedHookedMachineExample.java) • the complete [`mineplus-fun`](../examples/mineplus-fun/README.md) module (Juicer + Cannon)

### Interaction & GUI mechanics (verified behavior)

- On right-click, the core resolves the instance from the clicked block (anchor location **or** any barrier collision cell), fires `INTERACT`, calls your hook's `onInteract`, and — **if the type's JSON `gui` key is non-blank — opens that GUI automatically**.
- Conditional interactions (torch fires, empty hand opens menu): omit the `gui` key and call `openGui(key, player, instance)` yourself from the hook — exactly how the [Cannon](../examples/mineplus-fun/src/main/java/com/mineplus/fun/cannon/CannonFireHook.java) works.
- The core tracks **one open GUI session per player**; click/drag/close events are dispatched to your `InteractiveInfrastructureGui` only while the player's top inventory is the tracked one.
- `PlayerInteractEvent` fires for **both hands** (main + off-hand arrive as a near-simultaneous pair) — use a feature-level cooldown to dedupe, like the Cannon's 1-second fire cooldown.
- Hook dispatch on removal is split: `removeBlock(id, actor, destroy=true)` fires `onBreak`, `destroy=false` fires `onRemove`. Implement cleanup in **both**.
- Hook overrides survive `/mineplus reload` — register before or after reloads, the registry re-applies them.

---

## MultiBlock Hooks

Register behavior per machine type. All methods are optional (default no-op):

```java
advancedApi.registerHook("cannon", new MultiBlockHook() {
    @Override public void onCreate(MultiBlockInstance i, Player actor) {}
    @Override public void onPlace(MultiBlockInstance i, Player actor) {}
    @Override public void onInteract(MultiBlockInstance i, Player actor) {}
    @Override public void onTick(MultiBlockInstance i) {}
    @Override public void onUpgrade(MultiBlockInstance i, int prevLevel, int nextLevel, Player actor) {}
    @Override public void onBreak(MultiBlockInstance i, Player actor) {}      // destroy=true removal
    @Override public void onRemove(MultiBlockInstance i, Player actor) {}     // destroy=false removal
    @Override public void onModelReload(MultiBlockInstance i) {}
    @Override public void onSignal(MultiBlockInstance i, MultiBlockSignal signal) {}
    @Override public void onProcessStart(MultiBlockInstance i, MachineRecipe recipe) {}
    @Override public void onProcessComplete(MultiBlockInstance i, MachineRecipe recipe) {}
});
```

### Persistent machine state

`instance.mutableStateData()` is a `Map<String, String>` that the core's snapshot layer persists — values survive restarts together with the instance. This is the right home for machine state like ammunition counts, fuel, or progress (see [`CannonTntStore`](../examples/mineplus-fun/src/main/java/com/mineplus/fun/cannon/CannonTntStore.java)). Plain in-memory maps do **not** survive restarts.

### Model-space → world-space math

`instance.rotation()` is exactly the rotation the renderer applies. To compute world positions from bbmodel pixels (muzzle points, particle origins, direction logic), replicate the renderer's CENTER-origin transform:

```
world = anchorBlock + (0.5, 0.5, 0.5) + R · (p_pixels / 16 − (0, 0.5, 0))
```

This keeps feature geometry glued to the rendered model for every placement rotation — the technique used by the [Cannon's firing math](../examples/mineplus-fun/src/main/java/com/mineplus/fun/cannon/CannonFireHook.java).

---

## Timed Crafting Processes

`startProcess(instanceId, recipeId)` runs a recipe as a timed process on an ACTIVE machine:

- Duration comes from the recipe's `craftTimeTicks`, scaled by the machine level's `speed` multiplier (`speed: 2.0` = twice as fast; upgrading mid-process applies immediately).
- Process state is stored in the instance's `stateData` (`mp.process.*` keys), which is persisted — processes survive restarts and resume where they stopped.
- Processes on machines in unloaded chunks are paused, not cancelled (vanilla furnace parity).
- Completion fires `PROCESS_COMPLETE` lifecycle events and the `onProcessComplete(instance, recipe)` hook; start fires `PROCESS_START` / `onProcessStart`.
- The engine tracks only time. Consuming inputs and producing outputs is your feature's responsibility, typically inside the hooks.

**Example — a smelter that takes 30 seconds and speeds up with level:**

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

**Progress for GUIs:** `advancedApi.getProcess(instanceId).progressRatio()` returns `[0.0, 1.0]`.

---

## Auto-Linking (Pipe Networks)

`autoLinkNeighbors(sourceId, radius)` links an instance to every other instance within a Chebyshev cube of `radius` around it, returning the number of new links. Use in `onPlace` hooks to build pipe-style networks without tracking UUIDs. Links are directed (source → neighbor) and combine freely with manual `linkBlocks` and signal propagation.

### Spatial Lookup

`MultiBlockRegistry.getNearby(BlockCoordinate, radius)` returns instances within a cube — the primitive backing auto-linking, also usable directly for proximity gameplay logic.

---

## Core Runtime Behavior

What runs underneath your feature code:

| Component | Role |
|---|---|
| `MultiBlockLifecycleManager` | Drives create/place/interact/tick/upgrade/remove |
| `ModelRenderingManager` | Maps machine level → `.bbmodel` rendering through `VirtualBlockManager` |
| `VirtualBlockManager` | Session-local mapping + automatic cleanup of orphaned `BlockDisplay` "ghost" entities on chunk loads |
| `MachineProcessManager` | Timed crafting processes; state in per-instance `stateData`, restart-safe |
| `PersistenceFacade` | SQLite persistence (`plugins/Mineplus/infrastructure.db`) via an asynchronous write-behind queue: gameplay calls stage snapshots in memory, a background task flushes off-thread, and a synchronous flush runs on shutdown/reload. Failed flushes are re-queued and retried. (`MultiBlockStorageEngine` is deprecated, retained only for legacy JSON migration) |
| `HookBus` | Publishes lifecycle events to registered listeners |
| `MultiBlockLinkingSystem` | Directed links and signal propagation |

**Ticking is chunk-aware:** instances in unloaded chunks are fully skipped (no hooks, heartbeats, renders, or process advancement) until their chunk loads again — vanilla tile-entity parity.

**Crash semantics:** gameplay actions stage snapshots in memory and a background task flushes them roughly once per second. After a hard crash, at most ~1 second of multiblock changes roll back to the last persisted state.

---

## Zero-Content Policy

Mineplus registers no gameplay content by default:

- No default blocks/items/machines.
- No test/demo spawns.
- No automatic world injections.

Anything in-game comes only from your JSON or API calls.

---

> ➡️ **Next:** step-by-step paths for each tier in [Extension Workflows](extension-workflows.md), or study the complete [`mineplus-fun` reference module](../examples/mineplus-fun/README.md). For the canonical module-building recipe (structure, JSON installation, hook wiring), read [`mineplus-fun/DEVELOPMENT_PROMPT.md`](../examples/mineplus-fun/DEVELOPMENT_PROMPT.md).
