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

### Tier 3 — Advanced framework developers

| File | Shows |
|---|---|
| [`code-based/AdvancedHookedMachineExample.java`](code-based/AdvancedHookedMachineExample.java) | Hooks, lifecycle events, and signal workflows via `InfrastructureApi` |
| [`hybrid/furnace_hybrid_notes.md`](hybrid/furnace_hybrid_notes.md) | The hybrid pattern: JSON-defined data + code-defined behavior |

### 🏆 The reference module

| Folder | Shows |
|---|---|
| [`mineplus-fun/`](mineplus-fun/README.md) | A **complete, buildable plugin** — the Juicer (recipes, GUI, custom items, upgrades) and the Cannon (conditional interaction, torch firing, TNT ballistics, persistent ammo) — built entirely outside the core |

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
