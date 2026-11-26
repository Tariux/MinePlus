# 🧪 Mineplus Examples

> **Navigate:** [Project README](../README.md) • [Docs Home](../docs/README.md) • [Config Reference](../docs/config-reference.md) • [Developer API](../docs/developer-api.md)

Everything in this folder is a **working, copyable example** for Mineplus' three-tier usage model. Each file is referenced from the documentation — pick your tier and start from a real file, not a blank page.

**New here?** Follow the [Step-by-Step Setup Guide](STEP_BY_STEP_FUN_GUIDE.md) for the JSON tier, or jump straight to [`mineplus-fun`](mineplus-fun/README.md) for a complete plugin module.

---

## 📋 The Examples

### Tier 1 — JSON (no coding)

| File | Shows |
|---|---|
| [`config-based/furnace_upgradable_multiblock.json`](config-based/furnace_upgradable_multiblock.json) | An upgradable multiblock with three levels, upgrade costs, and GUI options |
| [`config-based/furnace_machine_recipes.json`](config-based/furnace_machine_recipes.json) | Timed machine recipes with `craftTimeTicks` |
| [`lightweight/charcoal_mini_furnace.json`](lightweight/charcoal_mini_furnace.json) | A complete machine definition in a single minimal file |

Copy these into your server's `plugins/Mineplus/` data folders and run `/mineplus reload all`. File formats are documented in the [Configuration Reference](../docs/config-reference.md).

### Tier 2 — Basic add-on developers

| File | Shows |
|---|---|
| [`code-based/BasicPlacementExample.java`](code-based/BasicPlacementExample.java) | Create / place / query / remove machines via `BasicInfrastructureApi` |
| [`code-based/DeveloperApi2Example.java`](code-based/DeveloperApi2Example.java) | The Developer API 2.0 primitives via `context().dev()`: placement options (22.5° yaw lattice, air-target check), virtual-model spawn/removal/lookup with collision materials, custom-mob clips, the Rendering 2.0 census, and Multiblock 2.0 event-driven process ticking |
| [`code-based/ToolkitExample.java`](code-based/ToolkitExample.java) | The pure-Java toolkit subsystems: research trees with budget-gated unlocks, the minigame state machine + map + Bukkit adapter, the message bus with the Redis transport, energy grids with post-tick balancing, boss-fight phases, hot reload and the asset pipeline |

### Tier 3 — Advanced framework developers

| File | Shows |
|---|---|
| [`code-based/AdvancedHookedMachineExample.java`](code-based/AdvancedHookedMachineExample.java) | Hooks, lifecycle events, and signal workflows via `InfrastructureApi` |
| [`hybrid/furnace_hybrid_notes.md`](hybrid/furnace_hybrid_notes.md) | The hybrid pattern: JSON-defined data + code-defined behavior |

### 🏆 The reference module

| Folder | Shows |
|---|---|
| [`mineplus-fun/`](mineplus-fun/README.md) | A **complete, buildable plugin** — the Juicer (recipes, GUI, custom items, upgrades), the Cannon (conditional interaction, torch firing, TNT ballistics, persistent ammo), the Wine flight (texel baking), the Cabinet (both render axes A/B) and the Alchemy Table (pack blocks + declarative animation triggers via `/alchemy trigger <event>`) — built entirely outside the core |
| [`mineplus-gun/`](mineplus-gun/README.md) | A **complete, buildable plugin** on the combat axes — 12 firearms whose bbmodel clips bake into frame files at install (any-resolution, multi-texture Blockbench projects supported), server-clock animation, scope GUI, Vault shop |

### 🐄 The mob behavior showcase

| Folder | Shows |
|---|---|
| [`mineplus-mobfun/`](mineplus-mobfun/) | A **complete, buildable plugin** built on the Core's mob behavior engine: nine vanilla mobs (grazers, reforesting pig, lamplighter villager, florist bee, block-by-block golem guardians, necromancer witch with synced horseman raids, pilgrim villagers journeying to villages with night camps) driven by interval-gated, budget-capped, condition-gated world actions, with live status/toggles plus a spawn-and-force test bench via `/mobfun` |
| [`mineplus-machine/`](mineplus-machine/README.md) | A **complete, buildable plugin** on the Core's rotation networks (Create-style kinetic power): water wheels in three strengths (weak/regular/strong SU), shafts, three cogwheel orientations that visibly counter-rotate when meshed, and a brake lever that stalls its whole network — all config-driven via `rotation.*` guiOptions, rendered resourcepack-side with spin driven per tick, test bench via `/machine` |

## 🗺️ Where to go next

- **Setting up the furnace examples?** → [Step-by-Step Setup Guide](STEP_BY_STEP_FUN_GUIDE.md)
- **Building your own module?** → [`mineplus-fun/DEVELOPMENT_PROMPT.md`](mineplus-fun/DEVELOPMENT_PROMPT.md) (the canonical module recipe)
- **Want the full API picture?** → [Developer API](../docs/developer-api.md)

## 📁 Expected runtime data paths

```
plugins/Mineplus/
├── models/*.bbmodel        ← drop example/custom models here
├── multiblocks/*.json      ← machine type definitions
└── recipes/*.json          ← machine recipes
```
