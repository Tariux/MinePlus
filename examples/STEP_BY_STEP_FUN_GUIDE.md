# 🪜 Step-by-Step Setup Guide (JSON Tier)

> **Navigate:** [Examples](README.md) • [Config Reference](../docs/config-reference.md) • [Extension Workflows](../docs/extension-workflows.md)

This guide takes the **config-based furnace example** from zero to a live machine on your server — no coding required. Total time: about five minutes.

> Prefer code? The [Basic](README.md#tier-2--basic-add-on-developers) and [Advanced](README.md#tier-3--advanced-framework-developers) examples cover that, and [`mineplus-fun`](mineplus-fun/README.md) is a complete plugin module.

---

## 1) Add model files

Put your Blockbench models into `plugins/Mineplus/models/`:

```
plugins/Mineplus/models/furnace_lv1.bbmodel
plugins/Mineplus/models/furnace_lv2.bbmodel
plugins/Mineplus/models/furnace_lv3.bbmodel
```

> Don't have models yet? Any `.bbmodel` works — see the [Blockbench designer guidelines](../docs/config-reference.md#blockbench-designer-guidelines) for the texture rules.

## 2) Add multiblock JSON

Copy [`examples/config-based/furnace_upgradable_multiblock.json`](config-based/furnace_upgradable_multiblock.json) to:

```
plugins/Mineplus/multiblocks/furnace_upgradable_multiblock.json
```

## 3) Add recipe JSON

Copy [`examples/config-based/furnace_machine_recipes.json`](config-based/furnace_machine_recipes.json) to:

```
plugins/Mineplus/recipes/furnace_machine_recipes.json
```

## 4) Reload

```
/mineplus reload all
```

## 5) Validate the runtime

```
/mineplus status        → types and instances should now list your furnace
/mineplus model list    → active instances
/mineplus model models  → confirm your model keys loaded
```

## 6) See it in-game

Spawn the model on the block face you are looking at:

```
/mineplus model debugspawn <modelKey>
```

Placing it *as a machine* (with lifecycle, upgrades, GUIs) requires either the API or a module — see the two options below.

## 7) Optional code integration

- Use [`code-based/BasicPlacementExample.java`](code-based/BasicPlacementExample.java) if you only need simple placement/removal.
- Use [`code-based/AdvancedHookedMachineExample.java`](code-based/AdvancedHookedMachineExample.java) if you need hooks, events, and signals.
- Or study [`mineplus-fun`](mineplus-fun/README.md) — a full reference module with two complete machines (Juicer + Cannon) and a [step-by-step module guide](mineplus-fun/DEVELOPMENT_PROMPT.md).

---

**Something didn't load?** Check `[Mineplus]` log lines — config parse failures and unresolved texture names are always reported, even with debug logs off. Then re-check your JSON against the [Configuration Reference](../docs/config-reference.md).
