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
- `jsonInfrastructureApi()` — `reloadAll()`, `reloadModelDefinitions()`,
  `reloadMultiBlocks()`, `reloadRecipes()`.
- `animationApi()` — selector-based animation control (`AnimationApi`, see §10a).
- `moduleSupport()` — the module toolkit (`ModuleSupport`, see §7a).

Supporting Core utilities every module should use instead of hand-rolling:

- `com.mineplus.infrastructure.core.util.ModelPoints` — CENTER-origin pixel→world transforms
  (`toWorld`, `toWorldOffset`, `direction`); identical to the renderer's math.
- `com.mineplus.infrastructure.core.state.TypedState` — typed view over persisted `stateData`
  (`of(instance).getInt/setInt/getLong/getDouble/getBoolean/...`).
- `com.mineplus.infrastructure.core.util.Cooldowns` — self-pruning per-key cooldown map
  (`tryAcquire`, `isReady`, `remove`, `prune`); the standard interact-pair dedupe.
- `com.mineplus.infrastructure.core.gui.AbstractMachineGui` — machine-GUI base class (see §8).

## 4. Interaction & lifecycle mechanics (verified facts)

The Core's right-click flow, in order (`MultiBlockLifecycleManager.interact`):

1. The instance is resolved from the clicked block — first by anchor location, then by
   rendered-model id (barrier collision cells map back to instances).
2. The `INTERACT` lifecycle event fires, then `type.hook().onInteract(instance, player)`.
3. **If the type's `guiKey` is non-blank, the Core opens that GUI automatically.**

Consequences:

- **Unconditional menu** (Juicer pattern): put `"gui": "<key>"` in the multiblock JSON.
- **Conditional interaction** (Cannon pattern: saddle mounts, torch fires, else opens the
  menu): register the JSON *without* a `gui` key and let the hook decide; the hook opens the
  menu itself via `context.infrastructureApi().openGui(key, player, instance)`.
- `PlayerInteractEvent` fires for **both hands** — main- and off-hand arrive as a near-simultaneous
  pair. A feature-level cooldown (~1s, keyed by instance id) is the standard dedupe.
- Hook dispatch on removal is **split**: `removeBlock(id, actor, destroy=true)` fires
  `onBreak` (block break, destroy), `destroy=false` fires `onRemove`. Cleanup logic (dropping
  stored items, clearing feature state) must be implemented in **both**.
- Direct hook dispatch and GUI callbacks are **exception-isolated** by the Core: a throwing
  module hook is logged (`Hook 'onTick' of type '...' threw; isolating and continuing.`) and
  skipped. Never rely on an exception escaping to cancel a Core operation — return values
  and cancelled events are the control surface.
- Persistence is **incremental** on hot paths: place/upgrade/remove and per-tick process
  advancement stage single-instance upserts/deletes; only reload/shutdown do a full replace.
  `stateData` writes from GUI captures are picked up by the write-behind cycle within ~1s.
- Hook overrides survive reloads: `registerHook` stores an override that
  `MultiBlockConfigLoader`/`clearTypes` re-applies to freshly loaded types. Registering the hook
  before or after `jsonInfrastructureApi().reloadAll()` works either way.
- Breaking any barrier cell of the structure removes the whole instance (Core listener).

### Upgrades (verified facts, `MultiBlockLifecycleManager.upgrade`)

- The consumed cost is the **target level's** `upgradeCost` (level N+1's map), read through
  `UpgradeManager`. Both `minecraft:*` vanilla keys and custom item keys work; `hasRequirements`
  counts matching stacks across the whole inventory.
- Creative-mode players **skip** the material check and consumption (vanilla convention).
- The instance must be `ACTIVE`; if the model swap fails after materials were consumed, the
  Core reverts the level and **refunds** the cost (inventory first, drops on overflow).
- `onUpgrade(instance, oldLevel, newLevel, actor)` fires on the hook — the right place for
  feature announcements or state migration.
- An in-GUI upgrade button calls `lifecycleManager().upgrade(instanceId, player)` (see
  `CannonGui`/`JuicerGui`), never the raw `UpgradeManager`.

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
  bbmodel pixels, use `ModelPoints` (`core.util`) — `toWorld(instance, world, pixels)` for
  points, `direction(instance, axis)` for model-space axes. It applies the renderer's
  CENTER-origin transform (`world = anchor + (0.5,0.5,0.5) + R·(p/16 − (0,0.5,0))`), keeping
  feature geometry (muzzle position, launch axis, seats) glued to the rendered model for
  every placement rotation. Do **not** re-derive the formula per feature — the three copies
  the cannon used to carry were a drift risk.
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
- **Texture gotcha:** models whose textures are linked from an external Blockbench library
  export `"path": null` on every texture entry. The Core importer falls back to
  `relative_path` → `name` (filename minus extension), which resolves the same vanilla
  materials — but older Core builds NPE'd on the null. If `/mineplus reload` warns
  `Cannot invoke "String.replace(char, char)"`, the deployed Core jar predates the fix
  (see §12, deploy discipline).
- Determine functional axes **from geometry evidence, not the model name or first glance**. The
  cannon's muzzle is the ring at x = −15 (bore along −X at y = 6.5, z = 0); the decorative wheel
  assemblies sit at *both* X ends and the breech looks similar to the muzzle at a glance. A wrong
  sign here fired the TNT backwards — the most expensive bug of the cannon update. When direction
  matters, record the evidence (which elements, which coordinates) in the javadoc next to the
  constant.
- User-supplied models arrive in the repo `temp/` folder. Copy them into
  `src/main/resources/defaults/models/` byte-identical (verify the SHA256 after copying).
- Multiblock ids are snake_case (`cannon`, `juicer_machine`); the JSON `name` is display-only.
- **Texture-gradient trap (texel budgets):** a 16x16 sprite with 60+ distinct colors
  (smooth wood grain, anti-aliased gradients) greedy-merges past the Core's default
  96-plate per-face budget. Over-budget faces lose their texel bake and fall back —
  for custom texture names that fallback used to be white concrete, so "only the
  front face has color" is the signature symptom. The Core now degrades such faces
  to the cube's dominant palette color, but full fidelity needs a per-model
  `models/<key>.meta.json` with raised budgets (`maxTexelPlatesPerFace: 512`,
  `maxTexelPlatesPerInstance: 2048` covers any 16x16 sprite) — see the Cabinet and
  Wine metas. `/mineplus model info <key>` reports the per-face budget verdict.
- The Core parses a level's model file directly if its per-type model key is not preloaded, so a
  module only needs to place the `.bbmodel` file — no model-key registration juggling.

## 7. Adding a multiblock machine (standard recipe)

1. Ship `defaults/multiblocks/<id>.json` and `defaults/models/*.bbmodel` (plus optionally
   `defaults/recipes/<id>_recipes.json`) inside the module jar.
2. In `onEnable()`, install them into the **Core's** data folder with
   `context.moduleSupport().installDefault(plugin, resource, target, overwrite)`.
   Overwrite models (`true`) but not JSON configs (`false`) so server owners' config
   edits survive module updates. Do **not** call `reloadAll()` from a feature — the
   module bootstrap runs one coordinated `reloadAll()` after all features started, so
   N features cost one model/registry reload instead of N.
3. Register GUIs via `registerGui(key, gui)` and behavior via `registerHook(typeId, hook)`.
4. Feature classes extend `com.mineplus.<module>.ModuleFeature` (the module-internal
   lifecycle contract: `id()`, `onEnable()`, optional `onDisable()`/`command()`).
   Bootstrap is exception-isolated per feature — one broken feature logs severe and
   the rest still boot. Package layout (mirror the juicer/cannon/gear):

   ```
   com.mineplus.<module>/
     ModuleFeature.java           — feature lifecycle contract (start/command/stop)
     <Module>Plugin.java          — feature list + coordinated reload + teardown
     <module>.<feature>/
      <Feature>Keys.java        — namespaced String constants (machine id, gui key, state keys)
      <Feature>Feature.java     — extends ModuleFeature: install resources, register gui/hook/listeners
      <Feature>Hook.java        — MultiBlockHook with the game behavior
      gui/<Feature>Gui.java     — extends AbstractMachineGui (Core base class)
      <Feature>SubCommand.java  — implements com.mineplus.infrastructure.command.SubCommand
   ```

   The cannon additionally splits behavior into collaborators — copy this shape when a feature
   outgrows one hook: `CannonMountManager` (seat entities + session state),
   `CannonAimListener` (bow-release firing), `CannonProjectiles` (projectile launch +
   explosion calibration), `CannonTntStore` (persistent ammo, on `TypedState`).
5. Expose the feature's command by overriding `command()` on the `ModuleFeature`
   (returns the `SubCommand`); the bootstrap registers it as a top-level command
   under the feature id via `context.moduleSupport().registerCommand(...)` —
   no `plugin.yml` command entry and no `onCommand`/`onTabComplete` dispatch in
   the plugin main. Declare only the permission in `plugin.yml`.
6. Recipes are optional — only features that consult `recipeManager().findMatch(...)` need a
   recipes JSON (the cannon has none).

### 7a. Module toolkit quick reference (`context.moduleSupport()`)

- `installDefault(module, classpathResource, dataRelativePath, overwrite)` — copies an
  embedded jar resource into the Core's data folder.
- `resolveLooked(player, range, typeId)` — "the machine I'm looking at": anchor block first,
  then rendered-model id. Pass `null` as `typeId` to accept any type. Replaces every module's
  hand-rolled `findLooked`.
- `registerCommand(module, label, subCommand)` — dynamic top-level command registration on
  the server command map; enforces the subcommand's `permission()`.

## 8. GUI patterns that work with the Core

- Extend `AbstractMachineGui` (Core, `core.gui`). The base class owns all interaction
  guarding: top/bottom raw-slot routing, shift-click cancellation from the player
  inventory, click cancellation on every non-container top slot, insertion guards on
  container slots (cursor, hotbar swaps — off-hand arrives as button 40 — and drags all
  route through `accepts(slot, item)`), take-only output slots, filler, and
  capture-on-next-tick. Subclasses implement only:
  - `title(instance)`, `layout(inventory, instance)`;
  - `containerSlots()` (+ `takeOnlySlots()` for outputs, `accepts(slot, item)` for
    validation);
  - `onButtonClick(player, instance, slot, event)` for buttons;
  - `capture(player, instance, inventory)` — persist contents; called on close and one
    tick after every accepted interaction, so slot contents are final;
  - `onClosed(player, instance)` — after the close-path `capture`, once the player
    actually shuts the menu (never on per-interaction captures); the hook for
    close-time side effects like model swaps (see the Cabinet).
  Helpers: `instance(id)`, `type(instance)`, `fill(...)`, `named(...)`, `fillerPane()`,
  `plugin()`, `captureLater(...)`.
- The Core tracks **one open GUI session per player** (`InfrastructureGuiManager`); opening a
  second GUI replaces the session, and click/drag/close are dispatched only while the event's
  top inventory is the tracked one. Sessions are dropped on player quit, and GUI callbacks
  are exception-isolated like hooks.
- State capture reads from the event's top inventory. If a foreign item somehow lands in a
  restricted slot, return it to the player (inventory/drop) instead of destroying it.

## 9. Persistent machine state

- `instance.mutableStateData()` is a `Map<String, String>` that the Core's snapshot layer
  persists — values survive restarts together with the instance. Access it through
  `TypedState.of(instance)` (`getInt/setInt/getLong/getDouble/getBoolean/...`) instead of
  hand-rolled parsing (see `CannonTntStore`). After mutating `stateData` outside a Core
  lifecycle path (hooks, GUI captures), call
  `context.infrastructureApi().stagePersist(instance.id())` so the change is queued for the
  async persistence flush.
- Plain in-memory maps (like the Juicer's `machineContents`) do **not** survive restarts; prefer
  `stateData` for anything the player would consider lost progress.
- Multi-material stores: give each material its own state key (`cannon_tnt_count`,
  `cannon_fireball_count`) and one accessor pair per material. Keep a single GUI slot by making
  it accept any of the store's materials and writing "one material set, others zeroed" on
  capture — the slot then always displays what fires next.

## 10. Projectiles, seats and aiming (cannon-proven patterns)

### Projectile physics quick reference

- `TNTPrimed`: velocity is blocks/tick; vanilla TNT uses ~0.04/tick gravity, ×0.98/tick drag,
  80-tick default fuse. `setSource(actor)` attributes the explosion.
- Calibrated speeds (natural feel, no cannon screenshake): level-1 fixed shot 0.95 b/t;
  level-2 bow-draw range 0.55–1.9 b/t (draw force 0.12–1.0). Spawn the entity ~0.5–0.75 blocks
  beyond the muzzle so it clears the barrel.
- **Explosion intensity calibration:** vanilla TNT power 4 is overkill. Tag the launched entity
  (`addScoreboardTag`), cancel its `EntityExplodeEvent`, and re-issue
  `world.createExplosion(loc, 2.2F, false, true)` — power 2.2 keeps a satisfying but gentle
  crater and knockback. Re-entrancy safe: the replacement explosion has no source entity, so it
  cannot re-enter the handler.
- **Conditional payload (fireball-first):** check the ammo store at fire time — fire charges
  fire a `LargeFireball` instead of TNT. Fireballs fly **straight** (no gravity) and detonate on
  impact; give them their own, lower speed factor (~0.45×) or they outrun every arc. Fireballs
  move by acceleration: `setDirection(vector × speed)` works, `setVelocity` alone does not.
  `setIsIncendiary(false)` avoids griefy fires; `setShooter(player)` attributes it.
- Verified constants in `libs/paper-api-1.21.jar`: `Sound.ENTITY_GENERIC_EXPLODE`,
  `Sound.ENTITY_TNT_PRIMED`, `Sound.ENTITY_GHAST_SHOOT`, `Particle.EXPLOSION`, `Particle.CLOUD`.

### Mounting a player to a stationary multiblock

- Seat = invisible **marker armor stand** (`setMarker(true)` so it has no collision/hitbox),
  `setSmall(true)`, `setGravity(false)`, `setInvulnerable(true)`, `setPersistent(false)`, tagged
  with the instance id in its PDC. `stand.addPassenger(player)` pins the player — riding
  neutralises WASD while leaving the camera free.
- Seat position comes from model pixels through the same CENTER-origin transform as the muzzle
  math (§5). One block *behind* the model reads better than on top of it: the level-2 cannon's
  rearmost geometry is x=23px, so the seat sits at x=39px (one full block past the rear), y=13px,
  z=0 on the bore centreline. Rider height is a sitting-posture constant, tuned in `SEAT_PIXELS`.
- **Aim bounds (API trap):** player camera rotation is client-authoritative —
  `Player#setRotation` throws `UnsupportedOperationException` on Spigot/Paper 1.21+
  ("Cannot set rotation of players"), and teleporting a rider dismounts them, so a
  per-tick camera clamp is impossible server-side. The cannon bounds *aiming at fire
  time* instead: `CannonAimListener` clamps the launch direction into a cone around
  the bore axis when the lanyard releases. Clamp what your feature controls (the
  shot), never the player's view.
- **Aiming tool without arrows:** mounting hands the player a tagged "Cannon Lanyard" bow plus a
  single tagged "Cannon Match" arrow — the vanilla client refuses to draw a bow without an arrow
  somewhere in the inventory, so the match exists purely to unlock the draw. Cancel
  `EntityShootBowEvent`, read `getForce()` (the vanilla 0–1 charge curve), and fire the cannon
  instead; the match is never consumed.
- Marker-item hygiene: block `PlayerDropItemEvent` for tagged tools, strip them from joining
  players (crash/kick leaks), and reclaim them on every dismount path (sneak-dismount via
  `EntityDismountEvent`, quit, instance break/remove, plugin disable).
- A seated gunner aiming down at their own cannon would trigger the multiblock's interact flow
  mid-draw — ignore interactions from any mounted session in `onInteract`.

## 10a. Model animations (bbmodel clips + selector hooks)

Blockbench animations ride inside the `.bbmodel` — animate **outliner groups**
(bones), not loose cubes; a clip animating a parent group drags all nested bones.
Clips carry deltas from rest (rotation in degrees, position in pixels, scale as
multiplier). Loop modes: `once` (returns to rest), `loop`, `hold` (freezes the
end frame).

Two control surfaces:

1. **Autoplay (internal data interface):** add `"animations": ["clip_name"]`
   to the multiblock level JSON — the clip starts whenever that level renders,
   including after restarts and upgrades (the new level's list replaces the old).
   Raw debug-spawned models use `"autoplay": [...]` in `models/<key>.meta.json`.
2. **Code hooks (external interface):** `context.animationApi()` with
   `AnimationSelector`:

```java
AnimationApi anim = context.animationApi();

// One-shot a whole clip from t=0 (forced once, then back to rest)
anim.triggerAnimation(instance.id(), AnimationSelector.animation("recoil"));

// Granular: only the named bone's tracks, inside every clip animating it
anim.triggerAnimation(instance.id(), AnimationSelector.bone("turret"));

// Continuous play / stop / pause / resume
anim.playAnimation(instance.id(), "rotate_gear");
anim.stopAnimation(instance.id(), "rotate_gear");

// Gate a bone inside all matching clips (its parent still animates around it)
anim.setAnimationEnabled(instance.id(), AnimationSelector.bone("wheel"), false);

// Introspection (clip/bone names come from the bbmodel)
anim.getAnimations(instance.id());
anim.getBones(instance.id());
anim.getAnimationState(instance.id(), "recoil");   // AnimationState or null
```

Verified facts / traps:

- All calls take the **multiblock instance id** (resolved through its rendered
  model). Raw `/mineplus model debugspawn` models accept the rendered model id.
- Autoplay never overrides explicit control — it fires only the first time a
  render appears; `stopAnimation` stays stopped until re-render (upgrade,
  respawn, reload).
- `onAnimationStart(instance, animation)` / `onAnimationComplete(instance,
  animation)` exist on `MultiBlockHook` (exception-isolated like all hook
  dispatch; looping clips fire start once and never complete).
- Splines (catmullrom/bezier) sample as linear; molang keyframe expressions
  fall back to 0 — keep keyframes numeric in Blockbench.
- Diagnose parsing with `/mineplus model info <modelKey>` (bones, clips, loop
  modes, per-bone track counts, meta autoplay).
- Performance: models without both bones and clips cost nothing; animation
  pushes are one `setTransformationMatrix` per bound display per tick with
  client-side interpolation (`ANIMATION.TICK_INTERVAL_TICKS: 1` default).

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

## 12. Packaging, build & deploy discipline

- Module `build.gradle` must include the Core jar
  (`compileOnly files("../../build/libs/mineplus-1.0.0.jar")`) plus the same library stubs the
  Core uses. Mirror the existing `examples/mineplus-fun/build.gradle` dependency block.
- Build the Core first, then the module. Do **not** shade the Core into a module.
- The assistant does **not** run Gradle — the user builds and reports compiler errors and
  in-game behavior. When implementing, state the expected observable outcome (e.g. "the TNT
  should spawn at the ring end and fly away from the player") so the user can verify it on the
  live server.
- **Deploy discipline (the most expensive non-bug of this cycle):** after changing *Core* code,
  rebuild and redeploy the Core jar too — a stale `Mineplus.jar` silently re-runs old behavior
  while the sources look fixed. Verify with jar timestamps vs. source edit times before
  debugging "nothing happens":
  `Get-Item build\libs\mineplus-1.0.0.jar` vs. the edited source's `LastWriteTime`.
- Server-side config files in the **Core's** data folder (`multiblocks/*.json`) are never
  overwritten on module update (`overwrite=false`); after editing a shipped JSON you must
  delete/reconcile the deployed copy by hand or edits will not appear.
- `/mineplus reload` re-reads models and multiblock JSON without a restart — use it to verify
  parse warnings quickly. Startup log order: Core enables first, module second; if the module
  reports "Mineplus Core is not initialized", read *upward* for the Core's real exception.

## 13. Conventions to follow

- Package modules under `com.mineplus.<module>` (e.g. `com.mineplus.fun`).
- Feature classes extend the module's `ModuleFeature` lifecycle contract; never call
  `reloadAll()` from inside a feature (the module's bootstrap owns the single
  coordinated reload) and never hand-wire enable/disable lists in the plugin main.
- Keep the Core pristine: no feature code, no feature resources, no feature commands.
- Prefer the Core's public APIs over reflection or internal classes.
- Resource/model keys, GUI keys, hook keys, and item keys should be namespaced constants
  (a `*Keys` class) to avoid collisions between modules.
- No code comments unless they carry non-obvious rationale (geometry evidence, API traps).

## 14. Reference features

All live in `examples/mineplus-fun` (see also `examples/STEP_BY_STEP_FUN_GUIDE.md`):

- **juicer** (`com.mineplus.fun.juicer`): unconditional GUI (JSON `gui` key), recipes, custom
  items, recipe-driven crafting, upgrade button.
- **cannon** (`com.mineplus.fun.cannon`): conditional interaction (level 1: torch fires, else
  opens the menu; level 2: saddle mounts — no `gui` key, hook-driven `openGui`), two-level
  upgrade with vanilla-key costs, `stateData` ammo store (TNT + fire charges, fireball-first
   firing) persisted across restarts, rotation-aware placement (`createMultiBlock` +
   `placeMultiBlock` with −90° compensation), CENTER-origin muzzle math, gunner's seat (marker
   armor stand one block behind the model, fire-time aim cone, lanyard bow + match arrow),
   `TNTPrimed` ballistics with calibrated explosion power, break/remove cleanup.
- **gear** (`com.mineplus.fun.gear`): bbmodel clip animation driven by game state — redstone
  adjacency activates the `rotate_gear` loop, face-adjacent gears chain-react
  (flood-fill from powered seeds, so trains never self-sustain), phase-synced starts via
  `AnimationPlayback.startTime`, `BlockRedstoneEvent` for instant response plus a periodic
  re-evaluation. The canonical `AnimationApi` consumer (§10a).
- **wine** (`com.mineplus.fun.wine`): pure showcase of the Core's texel surface baking —
  five vinery bottles (Strad/Stal/Red/Chenet/Solaris) whose 16x16 sprites are reconstructed
  pixel-by-pixel out of vanilla palette blocks with zero resource pack. Ships converted
  `java_block`-format bbmodels (GRID anchor via AUTO detection, pruned to visible cubes),
  one PNG per model, per-model `.meta.json` opt-ins (`texelMode: AUTO`, raised plate
  budgets), and one multiblock type per variant. `/wine flight` lays all five out side by
  side (row perpendicular to the player's snapped facing, shared placement rotation) for
  comparing bakes; `/wine place [variant]|remove|clear|status` manage them. The minimal
  resource-only feature shape: no hook, no GUI, no listeners.
- **cabinet** (`com.mineplus.fun.cabinet`): model-as-state storage — the multiblock's
  level *is* the visual state. Two levels: level 1 renders the closed acacia cabinet,
  level 2 the open one. Right-click (hook-driven, no JSON `gui` key) calls
  `openGui` then `lifecycleManager().setLevel(id, 2)`; the storage `CabinetGui`
  (18 container slots, `AbstractMachineGui`) overrides `onClosed` to
  `setLevel(id, 1)` — closing the menu closes the cabinet. Contents persist per slot
  as Base64 `ItemStack#serializeAsBytes` in `stateData` (persisted via
  `stagePersist` in `capture`), and `onBreak`/`onRemove` both drop everything at the
  anchor. The `setLevel` mechanism also fires no material cost — it is the raw
  model-swap path (`upgrade()` is the cost-charging one). Ships per-model
  `.meta.json` plate-budget opt-ins (`maxTexelPlatesPerFace: 512`,
  `maxTexelPlatesPerInstance: 2048`): the vinery wood sprite is gradient-heavy
  (60+ distinct colors per 16x16) and greedy-merges past the default 96-plate
  per-face ceiling, which makes faces silently fall back to white concrete —
  see the texture-gradient trap in §6.

Copy the reference whose interaction model matches your feature.
