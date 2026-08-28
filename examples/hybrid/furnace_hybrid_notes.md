# 🔀 Hybrid Workflow (JSON + Advanced API)

> **Navigate:** [Examples](../README.md) • [Developer API](../../docs/developer-api.md) • [Extension Workflows](../../docs/extension-workflows.md)

The hybrid pattern: **data stays in JSON, behavior stays in code.**

- Game designers balance the machine (levels, speed, upgrade costs, recipes) by editing JSON — hot-reloadable with `/mineplus reload`, no plugin rebuild.
- Developers attach behavior (interactions, processes, signals) through hooks — versioned, testable Java.

This split is ideal when balance changes ship frequently but logic changes rarely.

---

## The recipe

1. **Register the machine shape/levels from JSON:**
   - `plugins/Mineplus/multiblocks/furnace_upgradable_multiblock.json`
   - (see [`examples/config-based/`](../config-based/) for ready files)
2. **Register recipes from JSON:**
   - `plugins/Mineplus/recipes/furnace_machine_recipes.json`
3. **Attach behavior in code** with `InfrastructureApi.registerHook(...)`.

## Example hook attachment

```java
api.registerHook("upgradable_furnace", new MultiBlockHook() {
    @Override
    public void onInteract(MultiBlockInstance instance, Player actor) {
        actor.sendMessage("Hybrid machine level: " + instance.level());
    }

    @Override
    public void onSignal(MultiBlockInstance instance, MultiBlockSignal signal) {
        // react to linked-machine traffic
    }
});
```

## Key behaviors to know

- **GUI control:** if the JSON's top-level `gui` key is set, the Core opens that GUI on *every* right-click. Omit it and let your hook decide per interaction (the pattern the [Cannon](../mineplus-fun/src/main/java/com/mineplus/fun/cannon/CannonFireHook.java) uses for torch-firing).
- **Timed processes:** start them from your hook with `startProcess(instanceId, recipeId)`; the recipe's `craftTimeTicks` and the level's `speed` come straight from JSON. See [Timed Crafting Processes](../../docs/developer-api.md#timed-crafting-processes).
- **Reload safety:** hook registrations survive `/mineplus reload` — the Core re-applies them to freshly loaded JSON types.
- **Persistence:** anything your hook stores in `instance.mutableStateData()` survives restarts with the machine.

## The evolved version

This pattern scales up to a full standalone module: [`mineplus-fun`](../mineplus-fun/README.md) is the hybrid workflow as a complete, buildable plugin — JSON definitions shipped in the module jar, behavior in hooks, GUIs, and custom items. Its [Development Guide](../mineplus-fun/DEVELOPMENT_PROMPT.md) is the canonical recipe for building your own.
