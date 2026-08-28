# MineplusFun — Example Module for the Mineplus Core

`mineplus-fun` is a **reference implementation** of a *module plugin* built on top of the
Mineplus Core engine. It demonstrates the intended architecture:

- **Core (`Mineplus`)** is a dependency-only engine. It ships no game content — only the
  framework (virtual Blockbench rendering, multiblock registry/lifecycle, recipes, GUIs,
  item registry, linking/signals, persistence) and a few admin commands.
- **Module (`MineplusFun`)** is a completely separate plugin that depends on the Core and
  adds *game logic* — in this case, the Juicer machine (place/upgrade/recipe GUI/consumables).

The Juicer logic that used to live inside the Core has been **moved out entirely** into this
module. The two plugins are decoupled: the Core knows nothing about the Juicer.

---

## Project layout

```
mineplus/                         # Core engine (dependency-only)
  build.gradle
  src/main/java/com/mineplus/...
  src/main/resources/plugin.yml

example/
  mineplus-fun/                  # Example module (separate plugin)
    build.gradle
    settings.gradle
    README.md
    DEVELOPMENT_PROMPT.md        # AI onboarding prompt for building new modules
    src/main/java/com/mineplus/fun/
      MineplusFunPlugin.java     # entry point + Core dependency check
      juicer/
        JuicerFeature.java       # wires Juicer into the Core API
        JuicerKeys.java
        JuicerSubCommand.java
        JuiceConsumeListener.java
        gui/JuicerGui.java
        items/CarrotJuiceItemDefinition.java
        items/MelonJuiceItemDefinition.java
    src/main/resources/
      plugin.yml
      defaults/...               # bbmodels + multiblock/recipe JSON (shipped in this jar)
```

## How the module talks to the Core

The Core exposes its API through `PluginContext`, obtained from the Core plugin instance:

```java
MineplusPlugin core = (MineplusPlugin) Bukkit.getPluginManager().getPlugin("Mineplus");
PluginContext context = core.getPluginContext();

context.itemRegistry().register(new CarrotJuiceItemDefinition());
context.infrastructureApi().registerHook("juicer_machine", hook);
context.infrastructureApi().registerGui("juicer_gui", gui);
context.jsonInfrastructureApi().reloadAll(); // load shipped JSON definitions
```

Module content (models, multiblocks, recipes) is shipped *inside the module jar* and copied
into the **Core's** data folder at enable time, then loaded via `reloadAll()`.

## Building

Build order matters — the module depends on the Core jar:

```bash
# 1. Build the Core first (produces build/libs/mineplus-1.0.0.jar)
cd mineplus
gradle build

# 2. Build the example module
cd example/mineplus-fun
gradle build   # produces build/libs/mineplus-fun-1.0.0.jar
```

> The module's `build.gradle` points at `../../build/libs/mineplus-1.0.0.jar`. If you change
> the Core version, update both `version` fields.

## Running (production-like)

Drop **both** jars into your server's `plugins/` folder:

```
plugins/
  Mineplus.jar        # Core engine  (this must load first)
  MineplusFun.jar     # Example module (depend: [Mineplus])
```

On startup, `MineplusFun` checks that the Core is present. If it is missing it prints a clear
error and disables itself instead of crashing:

```
[MineplusFun] FATAL: Mineplus Core plugin was not found.
[MineplusFun] Install 'Mineplus.jar' (Core) into your plugins/ folder first.
```

## Commands (provided by the module)

`/juicer <place|remove|upgrade|give>`
- `place` — place a Juicer where you are looking
- `upgrade` — upgrade the looked-at Juicer
- `remove` — remove the looked-at Juicer
- `give <carrot|melon>` — give yourself a juice item
