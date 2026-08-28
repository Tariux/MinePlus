# Development Prompt — Building Mineplus Core Modules

You are an AI coding assistant helping develop **modules** for the *Mineplus* Minecraft plugin
(CraftBukkit/Paper, Java 21, Minecraft 1.21+). Read this file fully before writing code.

## 1. Architecture (non-negotiable)

Mineplus is split into two layers:

- **Core (`Mineplus`)** — a *dependency-only engine*. It contains the framework and a few admin
  commands. It contains **no game content**. It must never reference a specific feature.
- **Module** — a *separate plugin* (its own jar, own `plugin.yml`, own build) that depends on
  the Core and adds game logic (machines, items, mobs, quests, etc.).

A module is a normal Bukkit plugin whose `plugin.yml` declares `depend: [Mineplus]`. It obtains
the Core API at runtime and registers content through it. The Core must not be modified to add a
feature — features live in modules.

## 2. Obtaining the Core API

```java
import com.mineplus.MineplusPlugin;
import com.mineplus.infrastructure.PluginContext;

Plugin core = Bukkit.getPluginManager().getPlugin("Mineplus");
if (!(core instanceof MineplusPlugin mineplus)) { /* disable with clear error */ }
PluginContext context = mineplus.getPluginContext();
```

Always perform this dependency check in `onEnable` and disable with a human-readable message if
the Core is absent.

## 3. The `PluginContext` API surface (what you can call)

`PluginContext` (package `com.mineplus.infrastructure`) exposes:

- `plugin()` — the Core `JavaPlugin` (use its `getDataFolder()` to install your JSON/models).
- `itemRegistry()` — register/get custom items (`ItemDefinition`).
- `virtualBlockManager()` — model registration / render queries.
- `infrastructureEngine()` — `.registry()`, `.lifecycleManager()`, `.recipeManager()`, `.guiManager()`.
- `infrastructureApi()` — `registerMultiBlock`, `createMultiBlock`, `placeMultiBlock`,
  `upgradeBlock`, `removeBlock`, `registerHook`, `registerGui`, `openGui`, `linkBlocks`,
  `sendSignal`, `startProcess`, `cancelProcess`, `getProcess`, `getBlock`, `getBlockAt`.
- `basicInfrastructureApi()` — lightweight creation/placement/lookup helpers.
- `jsonInfrastructureApi()` — `reloadAll()`, `reloadModelDefinitions()`, `reloadMultiBlocks()`,
  `reloadRecipes()`.

## 4. Adding a multiblock machine (pattern)

1. Ship `defaults/multiblocks/<id>.json` and `defaults/recipes/<id>_recipes.json` inside your
   module jar.
2. In `onEnable`, copy those files into the **Core's** data folder
   (`context.plugin().getDataFolder()`), then call `context.jsonInfrastructureApi().reloadAll()`.
   The Core's loaders read from its own data folder.
3. Register any custom GUI via `context.infrastructureApi().registerGui(key, gui)` where `gui`
   implements `InfrastructureGui` / `InteractiveInfrastructureGui`.
4. Attach behavior with `context.infrastructureApi().registerHook(typeId, new MultiBlockHook() {…})`.
5. Register items with `context.itemRegistry().register(new MyItemDefinition())`.

## 5. Packaging

- Module `build.gradle` must include the Core jar (`compileOnly files("../../build/libs/mineplus-1.0.0.jar")`)
  plus the same library stubs the Core uses (paper-api, spigot-api, adventure-*, joml, gson,
  item-nbt-api, sqlite-jdbc). Mirror the Core's `build.gradle` dependency block.
- Build the Core first, then the module.
- Do **not** shade the Core into a module. The Core must be a standalone server plugin.

## 6. Conventions to follow

- Package modules under `com.mineplus.<module>` (e.g. `com.mineplus.fun`).
- Keep the Core pristine: no feature code, no feature resources, no feature commands.
- Prefer the Core's public APIs over reflection or internal classes.
- Resource/model keys, GUI keys, hook keys, and item keys should be namespaced constants
  (a `*Keys` class) to avoid collisions between modules.

## 7. Reference module

`example/mineplus-fun` is the canonical reference: it implements a Juicer machine entirely outside
the Core, with a dependency check, JSON/recipe/model installation, a GUI, items, and a command.
Copy its structure when starting a new module.
