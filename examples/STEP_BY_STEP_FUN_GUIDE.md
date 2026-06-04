# Step-by-Step Setup Guide

## 1) Add model files
Put your Blockbench models here:
- `plugins/Mineplus/models/furnace_lv1.bbmodel`
- `plugins/Mineplus/models/furnace_lv2.bbmodel`
- `plugins/Mineplus/models/furnace_lv3.bbmodel`

## 2) Add multiblock JSON
Copy:
- `examples/config-based/furnace_upgradable_multiblock.json`

To:
- `plugins/Mineplus/multiblocks/furnace_upgradable_multiblock.json`

## 3) Add recipe JSON
Copy:
- `examples/config-based/furnace_machine_recipes.json`

To:
- `plugins/Mineplus/recipes/furnace_machine_recipes.json`

## 4) Reload
Run:
- `/mineplus reload all`

## 5) Validate runtime
Run:
- `/mineplus status`
- `/mineplus model list`

## 6) Optional code integration
- Use `BasicPlacementExample.java` if you only need simple placement/removal.
- Use `AdvancedHookedMachineExample.java` if you need hooks/events/signals.
