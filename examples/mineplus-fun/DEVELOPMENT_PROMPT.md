# Development Prompt — Building Mineplus Core Modules

You are an AI coding assistant helping develop **modules** for the *Mineplus* Minecraft plugin
(CraftBukkit/Paper, Java 21, Minecraft 1.21+). Read this file fully before writing code.
It contains the architecture rules, the Core API surface, verified integration facts, and the
standard feature recipe. Anything not covered here must be verified against the Core sources
in `src/main/java/com/mineplus/` — never guess API behavior.

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

## 4. Interaction & lifecycle mechanics (verified facts)

The Core's right-click flow, in order (`MultiBlockLifecycleManager.interact`):

1. The instance is resolved from the clicked block — first by anchor location, then by
   rendered-model id (barrier collision cells map back to instances).
2. The `INTERACT` lifecycle event fires, then `type.hook().onInteract(instance, player)`.
3. **If the type's `guiKey` is non-blank, the Core opens that GUI automatically.**

Consequences:

- **Unconditional menu** (Juicer pattern): put `"gui": "<key>"` in the multiblock JSON.
- **Conditional interaction** (Cannon pattern: torch fires, anything else opens the menu):
  register the JSON *without* a `gui` key and let the hook decide; the hook opens the menu
  itself via `context.infrastructureApi().openGui(key, player, instance)`.
- `PlayerInteractEvent` fires for **both hands** — main- and off-hand arrive as a near-simultaneous
  pair. A feature-level cooldown (~1s, keyed by instance id) is the standard dedupe.
- Hook dispatch on removal is **split**: `removeBlock(id, actor, destroy=true)` fires
  `onBreak` (block break, destroy), `destroy=false` fires `onRemove`. Cleanup logic (dropping
  stored items, clearing feature state) must be implemented in **both**.
- Hook overrides survive reloads: `registerHook` stores an override that
  `MultiBlockConfigLoader`/`clearTypes` re-applies to freshly loaded types. Registering the hook
  before or after `jsonInfrastructureApi().reloadAll()` works either way.
- Breaking any barrier cell of the structure removes the whole instance (Core listener).

## 5. Rotation, placement & model-space math

- `VirtualBlockPlacementHelper.getPlacementData(player, distance)` ray-traces a face and returns
  `PlacementData(location, attachedFace, globalRotation)`. The rotation snaps yaw to 90°
  increments and orients the model's *front* **toward the player** (vanilla stairs/observer
  behavior: `180 − snappedYaw`).
- **Trap:** `basicInfrastructureApi().createAndPlace(...)` always uses identity rotation — fine
  for symmetric machines, wrong for facing-sensitive ones. For those, use
  `infrastructureApi().createMultiBlock(typeId, location, owner, creator, rotation)` followed by
  `placeMultiBlock(id, actor)`, passing your own rotation.
- If the feature's functional axis (muzzle, conveyor direction) sits 90° off the model's
  "front", pre-rotate the placement quaternion by ±90° around Y in the place command. The
  cannon's muzzle is at model −X, so its command applies `.rotateY(-Math.PI / 2)`.
- `instance.rotation()` is exactly what the renderer uses. To compute world positions from
  bbmodel pixels, replicate the renderer's CENTER-origin transform:

  ```
  world = anchorBlock + (0.5, 0.5, 0.5) + R · (p_pixels / 16 − (0, 0.5, 0))
  ```

  where `R = instance.rotation()`. This keeps feature geometry (muzzle position, launch axis)
  glued to the rendered model for every placement rotation.
- Origin modes: `free`-format models anchor CENTER (pixel (0,0,0) = anchor block center at base);
  `java_block`/`java_item` anchor GRID (corner), unless geometry is center-authored. Per-model
  overrides live in `models/<key>.meta.json`. Both example machines are CENTER.
- The Core parses a level's model file directly if its per-type model key is not preloaded, so a
  module only needs to place the `.bbmodel` file — no model-key registration juggling.

## 6. Analyzing a bbmodel before wiring behavior

- A `.bbmodel` is JSON. Inspect `elements[].from/to/origin/rotation`, `outliner`, `textures`,
  and `resolution` before hardcoding feature constants. On this Windows machine use PowerShell
  (`ConvertFrom-Json`) — the sandbox shell is PowerShell 5.1, so bash-isms like `find -type f`
  fail; prefer the Glob/Grep tools for file work.
- Determine functional axes **from geometry evidence, not the model name or first glance**. The
  cannon's muzzle is the ring at x = −15 (bore along −X at y = 6.5, z = 0); the decorative wheel
  assemblies sit at *both* X ends and the breech looks similar to the muzzle at a glance. A wrong
  sign here fired the TNT backwards — the most expensive bug of the cannon update. When direction
  matters, record the evidence (which elements, which coordinates) in the javadoc next to the
  constant.
- User-supplied models arrive in the repo `temp/` folder. Copy them into
  `src/main/resources/defaults/models/` byte-identical.
- Multiblock ids are snake_case (`cannon`, `juicer_machine`); the JSON `name` is display-only.

## 7. Adding a multiblock machine (standard recipe)

1. Ship `defaults/multiblocks/<id>.json` and `defaults/models/*.bbmodel` (plus optionally
   `defaults/recipes/<id>_recipes.json`) inside the module jar.
2. In `onEnable`, copy them into the **Core's** data folder
   (`context.plugin().getDataFolder()`), then call `context.jsonInfrastructureApi().reloadAll()`.
   Overwrite models (`overwrite=true`) but not JSON configs (`overwrite=false`) so server owners'
   config edits survive module updates.
3. Register GUIs via `registerGui(key, gui)` and behavior via `registerHook(typeId, hook)`.
4. Feature package layout (mirror the juicer/cannon):

   ```
   com.mineplus.fun.<feature>/
     <Feature>Keys.java        — namespaced String constants (machine id, gui key, state keys)
     <Feature>Feature.java     — enable(): install resources, register gui/hook, reloadAll()
     <Feature>Hook.java        — MultiBlockHook with the game behavior
     gui/<Feature>Gui.java     — InfrastructureGui + InteractiveInfrastructureGui
     <Feature>SubCommand.java  — implements com.mineplus.infrastructure.command.SubCommand
   ```

5. Wire the feature into `MineplusFunPlugin.onEnable/onCommand/onTabComplete` and `plugin.yml`
   (command entry + `mineplusfun.admin.<feature>` permission).
6. Recipes are optional — only features that consult `recipeManager().findMatch(...)` need a
   recipes JSON (the cannon has none).

## 8. GUI patterns that work with the Core

- The Core tracks **one open GUI session per player** (`InfrastructureGuiManager`); opening a
  second GUI replaces the session, and click/drag/close are dispatched only while the event's
  top inventory is the tracked one.
- Layout: fill non-functional slots with named filler panes; leave functional slots `null`.
  Cancel clicks on non-functional top slots and cancel shift-clicks from the bottom inventory
  when the GUI cannot accept arbitrary items.
- Guard **every** insertion path into a restricted slot: the cursor
  (`event.getCursor()`), hotbar-number swaps (`event.getHotbarButton() >= 0` — the off-hand
  swap arrives as button 40; `InventoryAction.SWAP_WITH_OFFHAND` does **not** exist in
  spigot-api-1.21.1), and drags (`event.getOldCursor()`).
- Persist GUI contents on close *and* after each accepted click/drag. The result of a click is
  not yet applied when the event fires, so capture state on the next tick:
  `Bukkit.getScheduler().runTask(plugin, () -> capture(...))`.
- State capture reads from the event's top inventory. If a foreign item somehow lands in a
  restricted slot, return it to the player (inventory/drop) instead of destroying it.

## 9. Persistent machine state

- `instance.mutableStateData()` is a `Map<String, String>` that the Core's snapshot layer
  persists — values survive restarts together with the instance. This is the right place for
  machine state like ammunition counts (see `CannonTntStore`).
- Plain in-memory maps (like the Juicer's `machineContents`) do **not** survive restarts; prefer
  `stateData` for anything the player would consider lost progress.

## 10. Projectile physics quick reference (TNTPrimed)

- `TNTPrimed`: velocity is blocks/tick; vanilla TNT uses ~0.04/tick gravity, ×0.98/tick drag,
  80-tick default fuse. `setSource(actor)` attributes the explosion.
- A natural ~20-block arc from a horizontal barrel: speed 1.3 at 20° elevation, plus ±1.5° yaw
  spread. Spawn the entity ~0.5 blocks beyond the muzzle so it clears the barrel.
- Verified constants in `libs/paper-api-1.21.jar`: `Sound.ENTITY_GENERIC_EXPLODE`,
  `Sound.ENTITY_TNT_PRIMED`, `Particle.EXPLOSION`, `Particle.CLOUD`.

## 11. Verifying the API surface (stub jars)

Modules compile against **stub jars** in `libs/` (paper-api-1.21.jar, spigot-api-1.21.1.jar,
joml, gson, item-nbt-api, sqlite-jdbc) plus the built Core jar — these may lag the current
Bukkit API. Before using any enum constant or method you have not seen in this codebase, check
the stub:

```
javap -classpath "../../libs/paper-api-1.21.jar" org.bukkit.event.inventory.InventoryAction
```

Known traps: `InventoryAction.SWAP_WITH_OFFHAND` does not exist; `World.spawn(Location, Class)`
is inherited from `RegionAccessor`, not declared on `World`.

## 12. Packaging & build workflow

- Module `build.gradle` must include the Core jar
  (`compileOnly files("../../build/libs/mineplus-1.0.0.jar")`) plus the same library stubs the
  Core uses. Mirror the existing `examples/mineplus-fun/build.gradle` dependency block.
- Build the Core first, then the module. Do **not** shade the Core into a module.
- The assistant does **not** run Gradle — the user builds and reports compiler errors and
  in-game behavior. When implementing, state the expected observable outcome (e.g. "the TNT
  should spawn at the ring end and fly away from the player") so the user can verify it on the
  live server.

## 13. Conventions to follow

- Package modules under `com.mineplus.<module>` (e.g. `com.mineplus.fun`).
- Keep the Core pristine: no feature code, no feature resources, no feature commands.
- Prefer the Core's public APIs over reflection or internal classes.
- Resource/model keys, GUI keys, hook keys, and item keys should be namespaced constants
  (a `*Keys` class) to avoid collisions between modules.
- No code comments unless they carry non-obvious rationale (geometry evidence, API traps).

## 14. Reference features

Both live in `examples/mineplus-fun` (see also `examples/STEP_BY_STEP_FUN_GUIDE.md`):

- **juicer** (`com.mineplus.fun.juicer`): unconditional GUI (JSON `gui` key), recipes, custom
  items, recipe-driven crafting, upgrade button.
- **cannon** (`com.mineplus.fun.cannon`): conditional interaction (torch fires, else opens the
  menu — no `gui` key, hook-driven `openGui`), `stateData` ammo counter persisted across
  restarts, rotation-aware placement (`createMultiBlock` + `placeMultiBlock` with −90°
  compensation), CENTER-origin muzzle math, `TNTPrimed` ballistics, break/remove cleanup.

Copy the reference whose interaction model matches your feature.
