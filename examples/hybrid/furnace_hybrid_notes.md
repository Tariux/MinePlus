# Hybrid Workflow (JSON + Advanced API)

1. Register the machine shape/levels from JSON:
   - `plugins/Mineplus/multiblocks/furnace_upgradable_multiblock.json`
2. Register recipes from JSON:
   - `plugins/Mineplus/recipes/furnace_machine_recipes.json`
3. Attach behavior in code with `InfrastructureApi.registerHook(...)`.

Example hook attachment:

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

This is useful when balancing stays in JSON while behavior logic stays in code.
