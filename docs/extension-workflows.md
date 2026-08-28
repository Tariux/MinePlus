# 🛠️ Extension Workflows

> **Navigate:** [Docs Home](README.md) • [Configuration Reference](config-reference.md) • [Developer API](developer-api.md) • [Examples](../examples/README.md)

Pick the workflow that matches your role. Each one references working sample files from the [`examples/`](../examples/README.md) folder.

| Workflow | You are a... | You need |
|---|---|---|
| **[A — JSON-Only](#a-json-only-servers-no-coding)** | Server operator | Zero coding |
| **[B — Basic Add-on](#b-basic-add-on-developers)** | Light plugin dev | Place/remove/query only |
| **[C — Advanced Framework](#c-advanced-framework-developers)** | Framework dev | Hooks, GUIs, signals, processes |
| **[Runtime Administration](#runtime-administration)** | Admin | Live server control |

---

## A) JSON-Only Servers (No Coding)

The datapack-style workflow — best for operators. Everything lives in config files:

1. **Add models** — drop `.bbmodel` files into `plugins/Mineplus/models/`.
2. **Add multiblocks** — drop machine type files into `plugins/Mineplus/multiblocks/`.
3. **Add recipes** — drop machine recipe files into `plugins/Mineplus/recipes/`.
4. **Reload** — run `/mineplus reload all`.
5. **Validate** — `/mineplus status` and `/mineplus model list`.

> 📁 **Copy-ready samples:** [`examples/config-based/`](../examples/config-based/) (upgradable furnace + recipes) and [`examples/lightweight/charcoal_mini_furnace.json`](../examples/lightweight/charcoal_mini_furnace.json) (a complete machine in one file).
>
> 📖 **File formats:** the [Configuration Reference](config-reference.md) documents every field — including the [Blockbench designer guidelines](config-reference.md#blockbench-designer-guidelines) and the [texture-name catalog](config-reference.md#allowed-texture-names).

**Placing machines without code:** models can be previewed and spawned with `/mineplus model debugspawn <modelKey>` on the looked-at face. For player-facing placement, upgrades, and menus, you need a module — see workflow C.

---

## B) Basic Add-on Developers

Use `BasicInfrastructureApi` when you only need to:

- place/remove machines,
- query by location/id,
- enumerate loaded types/instances.

**Typical pattern:**

1. Ensure the target type exists (from JSON or advanced registration).
2. Call `createAndPlace(...)`.
3. Store the returned instance id in your plugin state.
4. Call `removeAt(...)` when needed.

```java
BasicInfrastructureApi basic = mineplus.basicInfrastructureApi();

MultiBlockInstance machine = basic.createAndPlace(
        "charcoal_mini_furnace", targetLocation, player.getUniqueId(), player);
if (machine != null) {
    // machine.id() is your handle for later removal
}

// Later:
basic.removeAt(targetLocation, player, true);
```

> ⚠️ `createAndPlace` places with **identity rotation**. For facing-sensitive machines use the [advanced tier's](developer-api.md#tier-3-advanced-infrastructure-api) `createMultiBlock(typeId, location, owner, creator, rotation)` + `placeMultiBlock(id, actor)` instead.
>
> 📁 **Working sample:** [`examples/code-based/BasicPlacementExample.java`](../examples/code-based/BasicPlacementExample.java)

---

## C) Advanced Framework Developers

Use `InfrastructureApi` for full control:

1. **Build the machine** — `MultiBlockType` + a `MultiBlockLevel` map in code, or load it from JSON and only attach behavior.
2. **Register behavior** — `registerHook(typeId, hook)` for interaction/tick/process callbacks, plus lifecycle listeners where needed.
3. **Register GUI bindings** — `registerGui(key, gui)` + `openGui`.
4. **Register recipes and linking channels** — `createRecipe`, `linkBlocks` / `autoLinkNeighbors`, `sendSignal`.
5. **Drive instances through the API only** — `createMultiBlock` / `placeMultiBlock` / `upgradeBlock` / `removeBlock`.

**The module pattern (recommended):**

Rather than extending the core, build a *separate plugin* that `depend: [Mineplus]`, fetches `PluginContext`, ships its models/JSON inside its own jar, installs them into the core's data folder on enable, and calls `reloadAll()`. The core stays pristine; your feature is fully self-contained.

The canonical module is [`examples/mineplus-fun`](../examples/mineplus-fun/README.md) — two complete machines:

| Machine | Demonstrates |
|---|---|
| **Juicer** | Unconditional GUI (JSON `gui` key), recipes, custom items, upgrade button |
| **Cannon** | Conditional interaction (torch = fire, else menu — no `gui` key, hook-driven `openGui`), persistent `stateData` ammo, rotation-aware placement, `TNTPrimed` ballistics |

For the full module-building recipe (project layout, resource installation, hook wiring, API traps to avoid), read [`mineplus-fun/DEVELOPMENT_PROMPT.md`](../examples/mineplus-fun/DEVELOPMENT_PROMPT.md) — it is the standard onboarding document for new module work.

> 📁 **Code samples:** [`examples/code-based/AdvancedHookedMachineExample.java`](../examples/code-based/AdvancedHookedMachineExample.java) • [`examples/hybrid/furnace_hybrid_notes.md`](../examples/hybrid/furnace_hybrid_notes.md) (JSON data + code behavior combined)

---

## Runtime Administration

Use the Mineplus admin CLI instead of debug tricks — it keeps production operations clean and predictable:

| Command | Purpose |
|---|---|
| `/mineplus status` | Types, instances, processes at a glance |
| `/mineplus reload [all\|models\|multiblocks\|recipes]` | Hot-reload without a restart |
| `/mineplus model list [limit]` | List active instances |
| `/mineplus model inspect [look\|uuid]` | Cube count, texture table, occupancy cells, cache status |
| `/mineplus model respawn [look\|uuid]` | Respawn a broken rendering |

### Debug model helpers

- `/mineplus model models` — all loaded model keys (from `plugins/Mineplus/models/**`).
- `/mineplus model debugspawn <modelKey>` — place a raw model on the looked-at face.
- Keep throwaway validation models in `plugins/Mineplus/models/debug/` so production model keys stay clean.

> 📖 **Every command and permission:** [Admin Command Reference](config-reference.md#admin-command-reference)
