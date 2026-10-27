# 🛠️ Extension Workflows

> **Navigate:** [Docs Home](README.md) • [Configuration Reference](config-reference.md) • [Developer API](developer-api.md) • [Examples](../examples/README.md)

Pick the workflow that matches your role. Each one references working sample files from the [`examples/`](../examples/README.md) folder.

| Workflow | You are a... | You need |
|---|---|---|
| **[A — JSON-Only](#a-json-only-servers-no-coding)** | Server operator | Zero coding |
| **[B — Basic Add-on](#b-basic-add-on-developers)** | Light plugin dev | Place/remove/query only |
| **[C — Advanced Framework](#c-advanced-framework-developers)** | Framework dev | Hooks, GUIs, signals, processes |
| **[D — Combat Module](#d-combat-modules)** | Gameplay dev | Weapons, projectiles, held-item state |
| **[E — Smart-Mob Module](#e-smart-mob-modules)** | Gameplay dev | Entity behavior, journeys, groups |
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

## D) Combat Modules

A weapon module ships definitions and presentation; the Core owns the shot. Same module shape as workflow C, with these four seams:

1. **Define the round** — build a `BallisticProfile` per weapon (damage, velocity, gravity/drag, range, spread, pellet count, falloff, penetration, ricochet, headshot, particles, sounds). Keep it derivable from your config so a balance tweak is a file edit.
2. **Own the item** — implement `HeldItemInput.HeldItemHandler` (`owns`, `onPress`, `onRelease`, `onSwapHands`) and attach it once: `context.heldItemInput().addHandler(runtime)`. Do not register your own `PlayerInteractEvent` listener for the same items — the Core seam routes presses, suppresses the vanilla swing and freezes place/mine/trade while the item is held.
3. **Fire it** — on a press, spend a round and call `context.ballistics().fireWeapon(player, origin, aim, profile, handler)`. Return value is the number of rounds launched, so `0` is your own dry/no-op path.
4. **Track the ammo** — `ItemMagazine.open(stack, capacity)` gives a live count; `spend()`/`add()`/`refill()` it in memory and `flush(player, live)` when the stack leaves the hand. Never rewrite the held stack per shot.

Damage the Core's ballistics applies needs nothing from you (`ProjectileHandler.STANDARD`); damage you compute yourself (melee, explosions) goes through `DamageTransactions.apply(victim, amount, attacker)` so your own listener does not double-count it.

> 📁 **Working module:** [`examples/mineplus-gun`](../examples/mineplus-gun/README.md) — firearms, melee, grenades, reload frame animation, scopes and a Vault shop.
>
> 📖 **API detail:** [Combat Services](developer-api.md#combat-services)

---

## E) Smart-Mob Modules

Behavior profiles, not listeners. One `MobProfile` per entity type, registered on the main thread at enable and released with `unregisterAll(this)` at disable:

1. **Pick the actions** — use a bundled one (`EatPlantsAction`, `GrowPlantsAction`, `PlantSaplingAction`, `TorchPlaceAction`, `JourneyToStructureAction`) or implement `BehaviorAction` for module-specific content.
2. **Set the pace** — `action(myAction, intervalSeconds)`; the engine turns that into the reference-mod odds `1/(intervalSeconds × checksPerSecond)`, so declare the interval you want observed, not a tick count.
3. **Gate the situation** — compose `BehaviorContext.Conditions` (`adultOnly`, `maxBlockLight`, `belowHealth`, `nearbyEntity`, `visibleEntity`) instead of writing your own entity scans.
4. **Work through the context** — `trySetBlock` / `tryBreak` / `trySpawn`, `navigateTo`, `claimActivity`, `joinCrew`, `advanceJourney`, `nearestStructure`. Everything is budgeted and confined: a claim with an anchor keeps the mob inside the area it belongs to and stops two profiles steering the same entity.
5. **Write back only what must survive a restart** — PDC for a builder tag or a summon cooldown; journeys, crews and activity claims are runtime state owned by the engine.
6. **Test it** — `/mineplus`-side: `forceFire(plugin, entity, true)` returns a per-action `BehaviorFireResult`; the mob-side: `journeySnapshot()` / `crewSnapshot()` / `telemetrySummary()`.

> 📁 **Working module:** [`examples/mineplus-mobfun`](../examples/mineplus-mobfun/README.md) — grazers, reforesting pig, lamplighter villager, florist bee, iron/snow golem builders, a necromancer witch with a terrain-adaptive minion horde, and pilgrim villagers that march to the nearest village and camp overnight. Its `/mobfun spawn|fire|status|despawn` bench is the pattern for testing behavior without waiting for the odds.
>
> 📖 **API detail:** [Mob Behavior API](developer-api.md#mob-behavior-api)

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
