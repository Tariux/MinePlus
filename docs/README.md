# 📚 Mineplus Documentation

Welcome to the Mineplus documentation hub. Start here, then follow the path that matches what you're building.

> **New to Mineplus?** Read the [main README](../README.md) first for a 2-minute overview and the quick start.

---

## 🗺️ Choose Your Path

| I want to... | Read this |
|---|---|
| Add machines **without writing code** | [Configuration Reference](config-reference.md) → [JSON-Only Workflow](extension-workflows.md#a-json-only-servers-no-coding) |
| Author models in **Blockbench** | [Configuration Reference → Blockbench Guide](config-reference.md#blockbench-designer-guidelines) |
| Build a **small add-on plugin** | [Developer API](developer-api.md#tier-2-basic-infrastructure-api) → [Basic Add-on Workflow](extension-workflows.md#b-basic-add-on-developers) |
| Build a **full machine framework** | [Developer API](developer-api.md) → [Advanced Workflow](extension-workflows.md#c-advanced-framework-developers) |
| See a **complete working module** | [MineplusFun (Juicer + Cannon)](../examples/mineplus-fun/README.md) |
| Tune the **rendering engine** | [Configuration Reference → settings.mp.yml](config-reference.md#settingsmpyml-rendering-engine) |
| Just **administer a server** | [Command Reference](config-reference.md#admin-command-reference) |

---

## 📖 The Documents

### [Configuration Reference](config-reference.md)
The complete data-format encyclopedia: multiblock JSON schema, machine recipe JSON schema, `.bbmodel` model handling, per-model `.meta.json` overrides, the `settings.mp.yml` rendering-engine keys, the full texture-name catalog, and the Blockbench designer guidelines.

### [Developer API](developer-api.md)
The programmatic surface: the three API tiers, hook & lifecycle mechanics, timed crafting processes, auto-linking pipe networks, spatial lookups, and how the core runtime behaves under your feature code.

### [Extension Workflows](extension-workflows.md)
The recipes: three step-by-step workflows (JSON-only, basic add-on, advanced framework) plus runtime administration and model-debugging tips.

---

## 🧪 Working Examples

The [`examples/`](../examples/README.md) folder is part of the documentation — every concept above exists there as a real, copyable file:

| Example | Tier | Shows |
|---|---|---|
| [`config-based/furnace_upgradable_multiblock.json`](../examples/config-based/furnace_upgradable_multiblock.json) | JSON | Upgradable multiblock with two levels |
| [`config-based/furnace_machine_recipes.json`](../examples/config-based/furnace_machine_recipes.json) | JSON | Timed machine recipes |
| [`lightweight/charcoal_mini_furnace.json`](../examples/lightweight/charcoal_mini_furnace.json) | JSON | Minimal single-file machine |
| [`code-based/BasicPlacementExample.java`](../examples/code-based/BasicPlacementExample.java) | Basic | Create / place / query / remove via API |
| [`code-based/AdvancedHookedMachineExample.java`](../examples/code-based/AdvancedHookedMachineExample.java) | Advanced | Hooks, lifecycle, signals |
| [`hybrid/furnace_hybrid_notes.md`](../examples/hybrid/furnace_hybrid_notes.md) | Hybrid | JSON data + code behavior combined |
| [`mineplus-fun/`](../examples/mineplus-fun/README.md) | Module | Complete reference plugin (Juicer + Cannon) |

For building your own module, follow the canonical recipe in
[`mineplus-fun/DEVELOPMENT_PROMPT.md`](../examples/mineplus-fun/DEVELOPMENT_PROMPT.md).
