# 📚 Mineplus Documentation

Welcome to the Mineplus documentation hub. Start here, then follow the path that matches what you're building.

> **New to Mineplus?** Read the [main README](../README.md) first for a 2-minute overview and the quick start.

---

## 🗺️ Choose Your Path

| I want to... | Read this |
|---|---|
| Add machines **without writing code** | [Configuration Reference](config-reference.md) → [JSON-Only Workflow](extension-workflows.md#a-json-only-servers-no-coding) |
| Author models in **Blockbench** | [Model Authoring Guide](model-authoring-guide.md) |
| Build a **small add-on plugin** | [Developer API](developer-api.md#tier-2-basic-infrastructure-api) → [Basic Add-on Workflow](extension-workflows.md#b-basic-add-on-developers) |
| Build a **full machine framework** | [Developer API](developer-api.md) → [Advanced Workflow](extension-workflows.md#c-advanced-framework-developers) |
| See a **complete working module** | [MineplusFun (Juicer, Cannon, Wine, Cabinet, Alchemy)](../examples/mineplus-fun/README.md) · [MineplusGun (firearms)](../examples/mineplus-gun/README.md) |
| Tune the **rendering engine** | [Configuration Reference → settings.mp.yml](config-reference.md#settingsmpyml-engine--runtime-services) |
| Add **pack-based custom content** | [Resource Pack System](pack-system.md) |
| Give weapons **real ballistics** | [Developer API → Combat Services](developer-api.md#combat-services) |
| Make mobs **do things** | [Developer API → Mob Behavior API](developer-api.md#mob-behavior-api) |
| Build **research trees, minigames, cross-server messaging or boss fights** | [Developer API → Toolkit Subsystems](developer-api.md#toolkit-subsystems-pure-java) |
| Add a **fully custom mob** | [Custom Mobs](custom-mobs.md) |
| **Upgrade** from an older release | [Migration Guide](migration-guide.md) |
| Just **administer a server** | [Command Reference](config-reference.md#admin-command-reference) |

---

## 📖 The Documents

### [Configuration Reference](config-reference.md)
The complete data-format encyclopedia: multiblock JSON schema, machine recipe JSON schema, `.bbmodel` model handling, per-model `.meta.json` overrides, the `settings.mp.yml` rendering-engine keys, the full texture-name catalog, and the Blockbench designer guidelines.

### [Developer API](developer-api.md)
The programmatic surface: the three API tiers, hook & lifecycle mechanics, timed crafting processes, auto-linking pipe networks, spatial lookups, the shared combat services (ballistics, held-item input, magazines, particle budgets), the mob behavior engine (profiles, conditions, journeys, crews, world-edit grants), custom mobs (identity, the two-mind contract — a Core profile or the base type's own engine AI — and per-part display rigs), how the core runtime behaves under your feature code, and the **pure-Java toolkit subsystems** (research trees, minigames, cross-server message bus, energy/kinetic grids, boss fights, hot reload, asset pipeline).

### [Custom Mobs](custom-mobs.md)
Adding new mobs without touching vanilla ones: the definition/model/attributes contract, the texture-extended phase (Evil Pig) and the fully custom model phase, **whose mind runs the mob** (a Core behavior profile or the base type's own engine AI), why a rigged mob is still a real entity (hitbox, damage, push, shadow, death), identity across restarts, the cost controls behind a crowd, and the bundled `AttackNearestAction`.

### [Extension Workflows](extension-workflows.md)
The recipes: three step-by-step workflows (JSON-only, basic add-on, advanced framework) plus runtime administration and model-debugging tips.

### [Resource Pack System](pack-system.md)
The **primary rendering axis**: custom items, models, textures and baked item animations through a resource pack the plugin compiles, hosts and delivers itself — architecture, render-mode selection (`virtual` / `pack_item` / `pack_block` / `pack_bones` / `hybrid_item` / `hybrid_block` / `hybrid_bones`), lighting, the `PackApi`, compilation/delivery model, configuration, inherited items (vanilla-parent re-skins from a single PNG), uniform fitting of any-size pack blocks, and the current limitations. The virtual/texel engine (below) is the built-in option and the automatic fallback.

### [Migration Guide](migration-guide.md)
Upgrading across a major release: renamed/added configuration keys, the unified `renderMode` vocabulary (and the legacy `renderBackend`/`renderKind` mapping), and how to update custom modules. The change list lives in the [changelog](../CHANGELOG.md).

---

## 🧪 Working Examples

The [`examples/`](../examples/README.md) folder is part of the documentation — every concept above exists there as a real, copyable file:

| Example | Tier | Shows |
|---|---|---|
| [`config-based/furnace_upgradable_multiblock.json`](../examples/config-based/furnace_upgradable_multiblock.json) | JSON | Upgradable multiblock with two levels |
| [`config-based/furnace_machine_recipes.json`](../examples/config-based/furnace_machine_recipes.json) | JSON | Timed machine recipes |
| [`lightweight/charcoal_mini_furnace.json`](../examples/lightweight/charcoal_mini_furnace.json) | JSON | Minimal single-file machine |
| [`code-based/BasicPlacementExample.java`](../examples/code-based/BasicPlacementExample.java) | Basic | Create / place / query / remove via API |
| [`code-based/DeveloperApi2Example.java`](../examples/code-based/DeveloperApi2Example.java) | 2.0 | Placement options, virtual models, custom-mob clips, render census, Multiblock 2.0 |
| [`code-based/ToolkitExample.java`](../examples/code-based/ToolkitExample.java) | Toolkit | Research trees, minigame machine/map/adapter, message bus + Redis transport, energy grids, boss fights, hot reload, asset pipeline |
| [`hybrid/furnace_hybrid_notes.md`](../examples/hybrid/furnace_hybrid_notes.md) | Hybrid | JSON data + code behavior combined |
| [`mineplus-fun/`](../examples/mineplus-fun/README.md) | Module | Complete reference plugin (Juicer + Cannon + Gear + Wine + Cabinet + pack showcases) |
| [`mineplus-gun/`](../examples/mineplus-gun/README.md) | Module | Combat module: additive firearms with file-discovered frame animations, right-click shooting, left-click scopes, ammunition economy and a Vault shop on the Core's ballistics, held-item input and magazine services |
| [`mineplus-mobfun/`](../examples/mineplus-mobfun/README.md) | Module | Smart-mob module: grazers, reforesting pig, lamplighter, florist bee, golem builders, necromancer witch, pilgrim villagers, plus the three custom mobs — the Core-steered Evil Pig, the engine-owned Villager Soldier (an iron golem wearing a model) and Woodland Trickster (a zombie wearing one) — on the Core's mob behavior and custom-mob APIs |

For building your own module, follow the canonical recipe in
[`mineplus-fun/DEVELOPMENT_PROMPT.md`](../examples/mineplus-fun/DEVELOPMENT_PROMPT.md).

---

## 🛠️ Mineplus Studio

The bundled dev tool at [`devtools/studio/`](../devtools/studio/README.md): one browser app over the real pipeline classes — models, mobs, items, blocks, textures, **texel baking** and the compiled pack — plus `agent.mjs`, a terminal CLI with exit codes and `--json` output so **AI agents verify assets while coding** without launching a server. Load any extra model directory with `--models <dir>`.
