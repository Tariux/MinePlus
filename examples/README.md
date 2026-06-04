# Mineplus Examples

This folder matches Mineplus' 3-tier model.

## Tier 1 - JSON Users
- `config-based/furnace_upgradable_multiblock.json`
- `config-based/furnace_machine_recipes.json`
- `lightweight/charcoal_mini_furnace.json`

Copy these into your server's `plugins/Mineplus/` data folders and run `/mineplus reload all`.

## Tier 2 - Basic Developers
- `code-based/BasicPlacementExample.java`

Uses `BasicInfrastructureApi` for create/place/remove/get patterns.

## Tier 3 - Advanced Developers
- `code-based/AdvancedHookedMachineExample.java`
- `hybrid/furnace_hybrid_notes.md`

Uses `InfrastructureApi`, lifecycle hooks, and signal/event workflows.

## Expected Runtime Data Paths
- `plugins/Mineplus/models/*.bbmodel`
- `plugins/Mineplus/multiblocks/*.json`
- `plugins/Mineplus/recipes/*.json`
