# 🧩 Developer API

> **Navigate:** [Docs Home](README.md) • [Configuration Reference](config-reference.md) • [Extension Workflows](extension-workflows.md) • [Examples](../examples/README.md)

Mineplus exposes three usage tiers. They stack: a machine defined through JSON (Tier 1) can be placed by a basic add-on (Tier 2) and driven by full framework code (Tier 3) — all at once.

| Tier | Interface | For |
|---|---|---|
| **1 — JSON** | `JsonInfrastructureApi` | Config-first servers; runtime reload control |
| **2 — Basic** | `BasicInfrastructureApi` | Simple place/remove/lookup add-ons |
| **3 — Advanced** | `InfrastructureApi` | Full hooks, lifecycle listeners, GUIs, links, signals, processes |

**Jump to:** [API Handles](#getting-api-handles) · [Tier 1](#tier-1-json-infrastructure-api) · [Tier 2](#tier-2-basic-infrastructure-api) · [Tier 3](#tier-3-advanced-infrastructure-api) · [Module Toolkit](#module-toolkit) · [Hooks](#multiblock-hooks) · [Animation Engine](#animation-engine) · [Combat Services](#combat-services) · [Mob Behavior API](#mob-behavior-api) · [Timed Processes](#timed-crafting-processes) · [Auto-Linking](#auto-linking-pipe-networks) · [Runtime Internals](#core-runtime-behavior)

---

## Getting API Handles

From your plugin (after Mineplus is enabled):

```java
MineplusPlugin mineplus = MineplusPlugin.getInstance();
BasicInfrastructureApi basicApi = mineplus.basicInfrastructureApi();
JsonInfrastructureApi jsonApi = mineplus.jsonInfrastructureApi();
InfrastructureApi advancedApi = mineplus.infrastructureApi();
AnimationApi animationApi = mineplus.animationApi();
```

Or through the full `PluginContext`, the recommended entry point for modules:

```java
Plugin core = Bukkit.getPluginManager().getPlugin("Mineplus");
if (!(core instanceof MineplusPlugin mineplus)) { /* disable with clear error */ }
PluginContext context = mineplus.getPluginContext();

context.plugin()               // the Core JavaPlugin (getDataFolder() → install JSON/models)
context.itemRegistry()         // custom item registration
context.virtualBlockManager()  // model registration / render queries
context.infrastructureEngine() // .registry() .lifecycleManager() .recipeManager() .guiManager()
context.basicInfrastructureApi()
context.infrastructureApi()
context.jsonInfrastructureApi()
context.animationApi()          // selector-based animation control (play/stop/pause/trigger/enable)
context.packApi()               // resource pack registration/delivery (never null)
context.renderRouter()          // unified render routing: policy + telemetry (never null)
context.moduleSupport()        // module toolkit: resource install, looked-at resolution, command registration
context.mobBehaviorApi()       // mob behavior engine: profiles, journeys, crews, world-edit grants
context.particleFx()           // budgeted particle + sound emission (shared, never throws)
context.ballistics()           // core projectile simulation
context.heldItemInput()        // held-item press/release/swap seam with vanilla-interaction freeze
```

Always perform the dependency check in `onEnable` and disable with a human-readable message if the core is absent — see the reference implementation in [`MineplusFunPlugin.java`](../examples/mineplus-fun/src/main/java/com/mineplus/fun/MineplusFunPlugin.java).

---

## Tier 1: JSON Infrastructure API

**Interface:** `JsonInfrastructureApi`

| Method | Description |
|---|---|
| `reloadAll()` | Reload models, multiblocks, and recipes |
| `reloadModelDefinitions()` | Reload `.bbmodel` files only |
| `reloadMultiBlocks()` | Reload multiblock type JSON only |
| `reloadRecipes()` | Reload recipe JSON only |

Use this tier when your workflow is config-first and you only need runtime reload control. The file formats are documented in the [Configuration Reference](config-reference.md).

---

## Tier 2: Basic Infrastructure API

**Interface:** `BasicInfrastructureApi`

| Method | Description |
|---|---|
| `Collection<String> getTypeIds()` | All registered machine type ids |
| `Collection<MultiBlockInstance> getLoadedInstances()` | All live instances |
| `MultiBlockInstance getAt(Location location)` | Instance anchored at a location |
| `MultiBlockInstance get(UUID id)` | Instance by id |
| `MultiBlockInstance createAndPlace(String typeId, Location location, UUID owner, Player actor)` | Create + place in one call |
| `boolean removeAt(Location location, Player actor, boolean destroy)` | Remove by location |

> ⚠️ **Rotation note:** `createAndPlace` always uses **identity rotation** — perfect for symmetric machines, wrong for facing-sensitive ones (cannons, conveyors). For those, use [`createMultiBlock` + `placeMultiBlock`](#tier-3-advanced-infrastructure-api) with your own rotation quaternion.

**Working example:** [`examples/code-based/BasicPlacementExample.java`](../examples/code-based/BasicPlacementExample.java)

---

## Tier 3: Advanced Infrastructure API

**Interface:** `InfrastructureApi`

| Capability | Methods |
|---|---|
| Register types | `registerMultiBlock(MultiBlockType)` |
| Lifecycle | `createMultiBlock`, `placeMultiBlock`, `upgradeBlock`, `removeBlock` |
| Hooking & events | `registerHook(typeId, MultiBlockHook)`, `registerLifecycleListener` |
| Recipes & GUIs | `createRecipe`, `registerGui`, `openGui` |
| Links & signals | `linkBlocks`, `unlinkBlocks`, `getLinkedBlocks`, `autoLinkNeighbors`, `sendSignal` |
| Timed processes | `startProcess`, `cancelProcess`, `getProcess` |
| Lookups | `getBlock`, `getBlockAt` |
| Persistence | `stagePersist(instanceId)` — stage an instance's state (incl. `stateData`) for the async write-behind queue; call after mutating `stateData` from hooks/GUI callbacks |
| Render routing | Not on `InfrastructureApi` itself — content declares a `renderMode` (JSON/`MultiBlockLevel`) and the engine routes it. Read telemetry/policy through `context.renderRouter()`. See [Render Modes](#render-modes) |

**Working examples:** [`examples/code-based/AdvancedHookedMachineExample.java`](../examples/code-based/AdvancedHookedMachineExample.java) • the complete [`mineplus-fun`](../examples/mineplus-fun/README.md) module (Juicer + Cannon)

### Interaction & GUI mechanics (verified behavior)

- On right-click, the core resolves the instance from the clicked block (anchor location **or** any barrier collision cell), fires `INTERACT`, calls your hook's `onInteract`, and — **if the type's JSON `gui` key is non-blank — opens that GUI automatically**.
- Conditional interactions (torch fires, empty hand opens menu): omit the `gui` key and call `openGui(key, player, instance)` yourself from the hook — exactly how the [Cannon](../examples/mineplus-fun/src/main/java/com/mineplus/fun/cannon/CannonFireHook.java) works.
- The core tracks **one open GUI session per player**; click/drag/close events are dispatched to your `InteractiveInfrastructureGui` only while the player's top inventory is the tracked one.
- `PlayerInteractEvent` fires for **both hands** (main + off-hand arrive as a near-simultaneous pair) — use the Core's `Cooldowns` utility (or a feature-level cooldown) to dedupe, like the Cannon's 1-second fire cooldown.
- Hook dispatch on removal is split: `removeBlock(id, actor, destroy=true)` fires `onBreak`, `destroy=false` fires `onRemove`. Implement cleanup in **both**.
- Hook overrides survive `/mineplus reload` — register before or after reloads, the registry re-applies them.
- Direct hook dispatch (and GUI callbacks) are **exception-isolated**: a throwing module hook is logged and skipped instead of aborting the lifecycle operation or the tick loop.

---

## Module Toolkit

`context.moduleSupport()` (`ModuleSupport`) absorbs the boilerplate every module used to duplicate:

| Method | Description |
|---|---|
| `installDefault(module, classpathResource, dataRelativePath, overwrite)` | Copies an embedded resource from your jar into the **Core's** data folder. Convention: `overwrite=true` for models, `false` for JSON configs (owner edits survive module updates). |
| `resolveLooked(player, range, typeId)` | Resolves the machine the player is looking at (anchor block first, then rendered-model id). `typeId` may be `null` for any type. |

```java
ModuleSupport support = context.moduleSupport();
support.installDefault(this, "defaults/models/cannon-3-1-1.bbmodel", "models/cannon-3-1-1.bbmodel", true);
support.installDefault(this, "defaults/multiblocks/cannon.json", "multiblocks/cannon.json", false);
context.jsonInfrastructureApi().reloadAll();
```

`registerCommand(module, label, SubCommand)` registers one of the Core's `SubCommand` implementations as a top-level Bukkit command at enable time — no `plugin.yml` command entries, no hand-written `onCommand`/`onTabComplete` dispatch. The subcommand's `permission()` is enforced by the wrapper.

### Supporting utilities

- **`ModelPoints`** (`core.util`) — CENTER-origin model→world math: `toWorld(instance, world, pixels)`, `toWorldOffset`, `direction(instance, modelAxis)`. Uses the exact transform the renderer applies, so muzzles/seats/mounts can never drift from the visuals.
- **`TypedState`** (`core.state`) — typed view over persisted `stateData`: `TypedState.of(instance).getInt/setInt/getLong/getDouble/getBoolean/...`. Replaces hand-rolled string parsing in every store class (see `CannonTntStore`).
- **`Cooldowns`** (`core.util`) — self-pruning per-key cooldown map: `tryAcquire(key, millis)`, `isReady`, `remove`, `prune`. The standard main/off-hand interact dedupe.
- **`AbstractMachineGui`** (`core.gui`) — base class for machine menus. The base owns all interaction guarding (cursor, hotbar swaps, drags, shift-clicks), take-only output slots, filler, and capture-on-next-tick; subclasses implement only `title`, `layout`, `containerSlots`, `accepts`, `onButtonClick`, and `capture` (see `JuicerGui`/`CannonGui`).

---

## MultiBlock Hooks

Register behavior per machine type. All methods are optional (default no-op):

```java
advancedApi.registerHook("cannon", new MultiBlockHook() {
    @Override public void onCreate(MultiBlockInstance i, Player actor) {}
    @Override public void onPlace(MultiBlockInstance i, Player actor) {}
    @Override public void onInteract(MultiBlockInstance i, Player actor) {}
    @Override public void onTick(MultiBlockInstance i) {}
    @Override public void onUpgrade(MultiBlockInstance i, int prevLevel, int nextLevel, Player actor) {}
    @Override public void onBreak(MultiBlockInstance i, Player actor) {}      // destroy=true removal
    @Override public void onRemove(MultiBlockInstance i, Player actor) {}     // destroy=false removal
    @Override public void onModelReload(MultiBlockInstance i) {}
    @Override public void onSignal(MultiBlockInstance i, MultiBlockSignal signal) {}
    @Override public void onProcessStart(MultiBlockInstance i, MachineRecipe recipe) {}
    @Override public void onProcessComplete(MultiBlockInstance i, MachineRecipe recipe) {}
    @Override public void onAnimationStart(MultiBlockInstance i, String animation) {}      // clip started (autoplay or API)
    @Override public void onAnimationComplete(MultiBlockInstance i, String animation) {}   // once/hold clip reached its end
});
```

### Persistent machine state

`instance.mutableStateData()` is a `Map<String, String>` that the core's snapshot layer persists — values survive restarts together with the instance. This is the right home for machine state like ammunition counts, fuel, or progress. Prefer the typed view `TypedState.of(instance)` (`getInt`, `setInt`, `getBoolean`, …) over raw string parsing (see [`CannonTntStore`](../examples/mineplus-fun/src/main/java/com/mineplus/fun/cannon/CannonTntStore.java)). Plain in-memory maps do **not** survive restarts.

### Model-space → world-space math

`instance.rotation()` is exactly the rotation the renderer applies. To compute world positions from bbmodel pixels (muzzle points, particle origins, direction logic), use the Core's `ModelPoints` helpers, which apply the renderer's CENTER-origin transform:

```
world = anchorBlock + (0.5, 0.5, 0.5) + R · (p_pixels / 16 − (0, 0.5, 0))
```

`ModelPoints.toWorld(instance, world, pixels)`, `toWorldOffset(...)`, and `direction(instance, axis)` keep feature geometry glued to the rendered model for every placement rotation — the technique used by the [Cannon's firing math](../examples/mineplus-fun/src/main/java/com/mineplus/fun/cannon/CannonFireHook.java).

---

## Animation Engine

Blockbench animations travel inside the `.bbmodel` file — the importer parses clips, bone animators, and keyframes in the same streaming pass as the geometry. The runtime samples the tracks server-side and pushes composed `BlockDisplay` transforms; the vanilla client interpolates between pushes, so motion renders at the client's own frame rate (the maximum a purely server-side renderer can achieve; the server cannot emit updates faster than its own tick loop).

The tick loop is Folia-aware (Folia runs it on the global region scheduler; Paper/Spigot use the standard scheduler), culls instances nobody is looking at (a pose push is skipped when no player is within the transport's full-LOD range and facing the model), and reuses scratch matrices so a steady-state tick allocates nothing.

**Two control interfaces:**

1. **Internal data interface** — clips play straight from the model file. Declare autoplay per multiblock level (`"animations": ["rotate_gear"]` in the level JSON) or per raw model (`"autoplay"` in the model's `.meta.json`). JSON-declared autoplay wins over meta.
2. **External hook interface** — `AnimationApi`, selector-based control from code. Selectors address whole clips, single bones, or everything:

```java
AnimationApi anim = context.animationApi();

// Play a whole clip (respects the authored loop mode; override via AnimationPlayback)
anim.playAnimation(instance.id(), "rotate_gear");
anim.playAnimation(instance.id(), "recoil", AnimationPlayback.speed(2.0f));

// One-shot trigger: restarts from t=0, forced to play once
anim.triggerAnimation(instance.id(), AnimationSelector.animation("recoil"));

// Granular: trigger only the named bone's tracks inside every clip animating it
anim.triggerAnimation(instance.id(), AnimationSelector.bone("turret"));

// Enable/disable whatever a selector addresses (pause/resume controllers,
// or gate a bone's contribution inside all matching clips)
anim.setAnimationEnabled(instance.id(), AnimationSelector.bone("wheel_front_left"), false);
anim.setAnimationEnabled(instance.id(), AnimationSelector.all(), true);

// Introspection
anim.getAnimations(instance.id());          // clip names in the model
anim.getBones(instance.id());               // bone (outliner group) names
anim.getAnimationState(instance.id(), "recoil");  // playback snapshot or null
```

**Selector kinds** (`AnimationSelector`):

| Selector | `playAnimation`-equivalent | `triggerAnimation` | `setAnimationEnabled` |
|---|---|---|---|
| `animation("name")` | — | one-shot that clip | pause/resume its controller |
| `bone("name")` | — | one-shot restricted to that bone's tracks | gate the bone inside every controller animating it |
| `all()` | — | one-shot every clip | pause/resume everything |

**Verified behavior:**

- **Bones are outliner groups.** A clip animating a parent group moves all nested child bones with it (the pose composes down the hierarchy). Cubes outside any group never animate.
- Clip values are **deltas relative to the Blockbench rest pose** — rotation in degrees, position in pixels, scale as a multiplier. Multiple concurrent clips on one bone compose additively.
- **Loop modes** (`loop` field): `once` plays and returns bones to rest (controller removed), `loop` wraps forever, `hold` freezes on the final frame.
- `stopAnimation` removes a controller and snaps its bones back to rest; `pauseAnimation`/`resumeAnimation` freeze/continue at the current time.
- Autoplay never overrides explicit control: it fires only the first time a rendered instance is seen, and a stopped clip stays stopped until the instance re-renders (upgrade swap, respawn, reload).
- `onAnimationStart`/`onAnimationComplete` fire on the type's hook (exception-isolated like all hook dispatch). Looping clips fire start once per start, never complete.
- All methods accept a multiblock instance id **or** — for raw `/mineplus model debugspawn`-ed models — the rendered model id itself.
- Models without animations cost nothing: binding capture, tick work, and controllers only exist when the model carries both bones and clips.
- Molang keyframe expressions fall back to `0` and are reported at import (`/mineplus model info` shows the parsed clip table).
- `/mineplus model info <key>` reports bones, clips with loop mode/length/per-bone track counts, and meta autoplay.

---

## Texel Surface Baking

Pixel-accurate vanilla texturing, zero resource pack: when a texture PNG sits next to the model file (`plugins/Mineplus/models/<textureName>.png`), a transformer decomposes each face's UV-mapped texture into per-pixel **texels**, quantizes each texel to the nearest visually-flat vanilla block (66-entry palette: concretes, concrete powders, terracottas, snow, and flat stones/minerals — polished granite excluded; Oklab matching with directional-shade compensation; only pure-flat concretes and snow merge into stretched rectangles, everything else stays 1×1), and emits one thin plate (1/1024 block, inset in-plane at every face edge so perpendicular plates at shared cube edges are geometrically disjoint — they touch exactly instead of interpenetrating) per merged run of same-color texels (greedy rectangle merging). Texels whose plate would land inside another cube's solid are occlusion-culled, so nested box-modeled geometry never spawns overlapping surface entities; texel plates sit flush at the cube surface so plate planes never z-fight the base display at grazing angles. Baking runs off the main thread (results land asynchronously; a reload or settings change discards in-flight bakes, and spawns wait — bounded — for a model's bake in flight), so model registration and `/mineplus reload models` never stall on PNG decoding.

- **Zero regression by default:** `MODE: AUTO` only upgrades faces that would otherwise render with the FULL strategy and have a resolvable PNG — models without adjacent PNGs render byte-identically. `TEXEL_BAKING.ENABLED: false` restores the pre-texel pipeline entirely.
- **Entity count scales with geometry, not texture resolution:** the effective grid is the face's own pixel grid (16px face → 16×16 texels); a 4×4 texture upscales, a 32×32 downsamples.
- **Budgets bound the cost:** `MAX_PLATES_PER_FACE` (96) and `MAX_PLATES_PER_INSTANCE` (150) bound the plate count, in deterministic face emission order. Per-model overrides: `"maxTexelPlatesPerFace"`/`"maxTexelPlatesPerInstance"` in the `.meta.json` (decorative pixel-art models legitimately want more plates than the global default).
- **Plate-count reductions (all default-on):** large **uniform regions** coalesce into one stretched plate (`UNIFORM_AREA_DETECTION`, `UNIFORM_AREA_MIN_SIZE`, `UNIFORM_AREA_OKLAB_THRESHOLD`); **adaptive budgeting** scales the default per-face ceiling with texel area (`ADAPTIVE_BUDGETING`; an explicit per-model `maxTexelPlatesPerFace` is never reduced); an over-budget face degrades to **one dominant-color plate** instead of the legacy render (unless it has cutout holes, which must stay see-through) via `BUDGET_FALLBACK_TO_SIMPLE_COLOR`; and **identical faces** share a single sampling pass (`REUSE_SYMMETRIC_FACES`). `/mineplus model info` reports `simplifiedFallbacks` and `reusedFaceBakes` alongside the budget verdict.
- **Authoring opt-in per model:** `"texelMode": "AUTO" | "ON" | "OFF"` and `"texelDetail": "FACE" | "SUPERSAMPLE_2X2" | "SUPERSAMPLE_4X4"` in the `.meta.json`; `"texelBrightness": 0-15` sets a minimum display light level so dark palette art stays readable outside full daylight.
- Plates bind to their cube's bone exactly like the existing minority plates, so `AnimationApi` control composes naturally; brightness follows the model's `light_emission`.
- `/mineplus model info <key>` reports the bake: faces baked, grids, palette-usage histogram, merged plate counts, budget verdict, bake time; texture entries show `[png]`/`[no png]`.

Best content: pixel art with flat regions (lettering, stripes, emblems). Noise/checkerboard textures hit the budgets — that is surfaced as an authoring error, not a silent slowdown.

---

## Resource Pack API (PackApi)

`context.packApi()` is the peer developer surface for the generated resource pack (the client-assisted rendering axis). It is never null: when the subsystem is disabled, identity registration still works and presentation falls back to the vanilla backing. See [the pack system contract](pack-system.md) for delivery, compilation and the full carrier design.

| Method | Description |
|---|---|
| `registerItem(PackItemDefinition)` | Custom item: PDC identity + auto model/texture assets + modern `item_model`/legacy `CustomModelData` presentation |
| `registerBlock(PackBlockDefinition)` | Custom block rendering: allocates a carrier state (`PackBlockCarrier.NOTE_BLOCK`) and attaches block-space geometry + textures |
| `registerModel(ns, path, modelKey)` | Standalone model asset from a loaded virtual model |
| `registerTexture(ns, path, file)` / `registerRawAsset(ns, path, file)` | Verbatim PNG / pack file assets |
| `createItem(ns, id)` | The registered item's `ItemStack` |
| `playerPackState(uuid)` / `deliverPack(player)` / `deliveryUrl(player)` | Delivery state and diagnostics |
| `recompile()` / `currentArtifact()` | Explicit compile / the current artifact |

```java
// Item (see mineplus-fun's PackShowcaseFeature)
context.packApi().registerItem(PackItemDefinition.builder(
        "fun", "strad_wine", Material.GLASS_BOTTLE, "strad-wine")
        .displayName("Strad Wine Bottle").build());

// Block (see mineplus-fun's AlchemyFeature): model key = <typeId>_lvl_<level>,
// geometry key = the model file's stem in models/ (what assets attach to)
context.packApi().registerBlock(PackBlockDefinition.builder(
        "fun", "alchemy_table", "alchemy_table_lvl_1")
        .geometryModelKey("alchemy-table")
        .displayName("Alchemy Table")
        .carrier(PackBlockCarrier.NOTE_BLOCK)
        .build());
```

A pack block is placed, collision-owned and persisted by the ordinary multiblock lifecycle (`createMultiBlock` / `placeMultiBlock` / `removeBlock`). Its multiblock JSON level selects the rendering:

```json
"1": { "model": "models/alchemy-table.bbmodel", "renderMode": "pack_block" }
```

Registration is order-insensitive — assets attach during the coordinated reload-driven recompile.

---

## Render Modes

Rendering selection is a single declarative vocabulary, `RenderMode`. One value names both the subsystem and the primitive, replacing the older `renderBackend` + `renderKind` pair (which remains fully supported and maps onto the same modes).

| `RenderMode` | Config key | Backend + kind | Meaning |
|---|---|---|---|
| `VIRTUAL` | `virtual` | `VIRTUAL` + `MODEL` | Packless virtual engine (default) |
| `PACK_ITEM` | `pack_item` (`pack`/`item`) | `PACK` + `MODEL` | Pack item model (`ItemDisplay`) |
| `PACK_BLOCK` | `pack_block` (`block`) | `PACK` + `BLOCK` | Pack block display (`BlockDisplay` + carrier state) |
| `HYBRID_ITEM` | `hybrid_item` (`hybrid`/`virtual+pack`) | `VIRTUAL_PLUS_PACK` + `MODEL` | Virtual render plus the pack item layered on top |
| `HYBRID_BLOCK` | `hybrid_block` | `VIRTUAL_PLUS_PACK` + `BLOCK` | Virtual render plus the pack block layered on top |

**Routing is centralized.** A multiblock level declares intent; `ModelRenderingManager` resolves it through the `RenderRouter` — the single choke point that applies subsystem availability and the global `RENDERING.POLICY` (whether a pack request may degrade to the virtual engine when the pack is unavailable or a pack display fails to attach). A `RenderMode` selects one primitive for the whole level; mixing backends or primitives per cube/face is **not** supported by the engine (the pack axis draws an entire model client-side as one model or block).

```java
// Telemetry + policy, exposed through every PluginContext:
RenderRouter router = context.renderRouter();
RenderRouter.RenderStats stats = router.snapshot();     // per-mode counts, degraded/unavailable, attach failures
RenderPolicy policy = router.policy();                  // the active routing policy
```

`/mineplus render stats` prints the same snapshot; `/mineplus render reset` zeroes the counters.

> **Migrating from the legacy pair:** replace `"renderBackend": "pack", "renderKind": "block"` with `"renderMode": "pack_block"`. Both forms parse today, but new content should use `renderMode`. See the [migration guide](migration-guide.md).

---

## Combat Services

Gameplay that fires, hits and reloads is a Core concern: the tick loop, the
particle budget and the damage bookkeeping are the parts every module would
otherwise re-implement badly. The pieces are:

| Handle | Type | Role |
|---|---|---|
| `context.ballistics()` | `Ballistics` | Simulated round engine: per-tick stepping, spread, gravity/drag, falloff, penetration, ricochet, headshot, tracer/impact FX |
| `context.heldItemInput()` | `HeldItemInput` | The held-item seam: routes presses/releases/swap-hands to the handler that owns the stack, and freezes vanilla interactions while it is held |
| `context.particleFx()` | `ParticleFx` | Budgeted particle emission (CRITICAL never starved by AMBIENT); `Sounds` resolves custom or vanilla events and never throws |
| `BallisticProfile` | value | One weapon's ballistics, authored once in code or config |
| `ProjectileHandler` | interface | Per-fire callbacks: targeting gate, launch/hit/expire notifications |
| `DamageTransactions` | static | Damage attribution that cannot double-count: `apply(victim, amount, attacker)` and the re-entrancy guard for melee |
| `ItemMagazine` / `ItemState` | registry | Countable item state (rounds) live in memory while held, persisted to PDC when the stack leaves the hand |
| `ItemFrames` / `ItemFrameWriter` | pack item | Idle / loaded / reload / fire frame math for `item_model` presentation, and the writer that puts one state on the stack |
| `TriggerState` | input | The per-player press state: one physical click resolves to one press, however many packets the client sent for it |

```java
// 1. Describe the round (WeaponDefinition in mineplus-gun does exactly this).
BallisticProfile profile = BallisticProfile.builder()
        .damage(7.0).velocity(2.6).gravity(0.08).drag(0.01).range(64)
        .spreadDegrees(1.2).pellets(1)
        .falloff(28, 0.06).minDamageFraction(0.35)
        .headshotMultiplier(1.8).penetration(1)
        .particles("minecraft: muzzle", "tracer", "impact")   // tolerant names
        .sparkParticle("SMALL_FLAME")                            // muzzle + impact sparks
        .sounds("gun.fire", "impact", "ENTITY_ARROW_SHOOT", 1.0f)
        .build();

// 2. Fire it. fireWeapon() launches profile.pellets() independent rounds.
int launched = context.ballistics().fireWeapon(player, muzzle, aim, profile,
        new ProjectileHandler() {
            @Override public boolean allowTarget(Entity e, Player shooter) {
                return e instanceof Monster;             // this weapon is non-lethal vs wildlife
            }
            @Override public void onBlockHit(ProjectileHit hit) {
                // default impact FX already ran; add module flavour here
            }
        });
if (launched == 0) { /* ballistics disabled or origin unusable — the module owns the fallback */ }

// 3. Damage the module computes itself (melee, grenades) goes through the
//    transaction so the swing is not counted twice.
DamageTransactions.apply(victim, 6.0, player);
```

**Verified behavior:**

- **Callbacks run on the server thread inside the simulation step** — a hit may
  damage, teleport or spawn directly; no scheduling, no read-then-write race
  against the round being resolved.
- **Over-budget shots degrade, never queue.** When `MAX_ACTIVE_ROUNDS` is
  reached, the shot resolves as one instant trace over its full range: same hit,
  same damage, one tick earlier than a stepped round would land. `degradedShots()`
  counts them.
- **`ParticleFx` tiers** keep a burst of tracers from eating the muzzle flash and
  impact puff that tell the player what their shot did; emission never throws, a
  refused particle is counted as dropped and the simulation continues.
- **`HeldItemInput` freezes nothing it does not own.** A handler's
  `owns(stack, holder)` is consulted per player, so a feature disabled for that
  world/player leaves the carrier fully vanilla (no frozen placement, no
  swallowed press). `suppressVanillaMelee` defaults to `true` but is never
  consulted for damage the module applied itself.
- **One physical click is one press.** A modern client answers a click on a
  block with two packets (use-on-block, then use-in-hand) and keeps sending
  use packets while the button is held, so the raw event stream is not a
  trigger. `TriggerState` folds it: a press inside the 150 ms click window of
  the previous one continues the same click, and a release always ends it.
  A handler therefore acts on a *gesture*, not on a packet — a semi-automatic
  weapon fires once per click, and an automatic one keeps firing while the
  trigger is held.
- **A frame state is one number on the stack.** The modern item model reads
  the `custom_model_data` component's float list, the legacy representation
  reads the integer, and `ItemFrameWriter` writes both (probing both accessor
  spellings — the API declares a primitive getter and a boxed setter). A
  framed item that wrote only the integer rendered its resting pose forever.
- **Magazines are not rewritten per shot.** Rewriting a held stack's components
  20 times a second re-triggers the client's equip animation and spams slot
  packets; `ItemMagazine` keeps the live count in memory and flushes one silent
  PDC write when the stack leaves the hand. Identity is a per-stack tag, so a
  magazine survives slot re-ordering, off-hand hops and re-logs; stacks of two or
  more are deliberately never tagged (they are clones and would share one
  magazine).
- **`DamageTransactions.inProgress()`** is the re-entrancy flag: a module whose
  own damage fires `EntityDamageByEntityEvent` back into its listener checks it
  (the gun's hit path stands down while a transaction runs).
- Telemetry: `context.ballistics().telemetrySummary()` (active/total rounds,
  hits, degraded shots) and `particleFx().emitted()` / `dropped()`.

**Complete working module:** [`examples/mineplus-gun`](../examples/mineplus-gun/README.md)
— firearms, the reload/pose state machine and a Vault shop, all on these
surfaces; the feature code owns only weapon definitions and HUD.

---

## Mob Behavior API

`context.mobBehaviorApi()` is a data-driven "vanilla-like" mob behavior engine:
a module says *which entity type does which action how often, under which
condition*, and the Core schedules it inside a hard time/world-edit/spawn
budget. Nothing runs until a profile is registered, and the whole subsystem is
free when no module uses it.

```java
MobBehaviorApi mob = context.mobBehaviorApi();

MobProfile cow = MobProfile.builder(EntityType.COW)
        .priority(10)                                  // higher wins a scenario conflict
        .action(new EatPlantsAction(), 12.0)           // once per ~12s of eligible checks
        .action(new GrowPlantsAction(), 30.0)
        .condition(BehaviorContext.Conditions.adultOnly())
        .build();
mob.register(this, cow);                               // main thread only
```

At most **one** action fires per entity evaluation, with the reference-mod odds
`1 / (intervalSeconds * checksPerSecond)`. Profiles for the same entity type
are dispatched **priority first** (higher first), then in registration order —
so the ordering of `register(...)` calls only breaks ties between equal
priorities.

`priority` is also the tie-breaker of the **scenario arbitration**: an activity
claim remembers the priority of the profile that took it, and a profile with a
strictly higher priority whose conditions pass may take the mob over — a
guardian answers an attack on a villager even while it is walking a pilgrimage.
A challenger still plays the odds gate and keeps the mob only by claiming it, so
a higher priority never steals a mob on its own.

### What `BehaviorContext` gives an action

| Group | Methods |
|---|---|
| Sensing | `entity()`, `world()`, `footBlock()`, `groundBlock()`, `biome()`, `random()`, `footBlockLight()`, `onGrassyGround()`, `onPlantableGround()`, `countNearby(...)`, `isChunkLoaded(...)` |
| World edits (budgeted) | `trySetBlock(Block, BlockData)`, `tryBreak(Block, boolean)`, `trySpawn(Location, Class, Consumer)` — all return `false` once the run's grant is spent, so an action degrades instead of throwing |
| Scenario arbitration | `claimActivity(id[, durationTicks])`, `claimActivity(id, anchor, anchorRadius, durationTicks)`, `releaseActivity`, `ownsActivity`, `activeActivityId`, `activityAnchor()` |
| Confinement | `confinementCenter()`, `confinementRadius()`, `withinConfinement()` — the claimed anchor is what keeps a scenario mob inside the area it belongs to |
| Navigation | `navigateTo(target, speed)`, `navigateToWithin(target, stopDistance, speed)`, `stopNavigation()` — through `MobNavigator`, which tries Paper's public `Pathfinder` API first and falls back to the reflective NMS/Brain channel on Spigot. It never teleports and never spawns: movement is always the entity's own vanilla walk, so the client's motion stays authoritative. |
| Journeys | `advanceJourney(...)`, `abandonJourney()`, `isJourneying()`, `journeyDestination()`, `journeys()` |
| Crews | `joinCrew(crew, leader, goal)`, `rallyCrew`, `crewRejoin`, `crewRally`, `setCrewRally`, `crewSpread`, `crewLeader`, `crewGoal`, `crewMembers`, `isCrewLeader`, `leaveCrew`, `disbandCrew` |
| Formations | `setCrewFormation(crew, Formation)`, `crewFormation(crew)`, `setCrewRole(crew, SquadRole)`, `crewRole(crew)` — a crew's shape around its formation point (wedge, column, pincer, ring) and each member's role in it |
| Structures | `nearestStructure(key, maxRadiusChunks)` — async-chunk-safe lookup through `StructureLookup`, cached per region |

`BehaviorContext.Conditions` is the shared predicate vocabulary: `inWorld`,
`adultOnly`, `maxBlockLight`, `belowHealth(fraction)`,
`nearbyEntity(radius, matcher)`, `visibleEntity(radius, matcher)` — module-side
conditions compose these instead of re-scanning entity lists.

### Bundled actions

`EatPlantsAction`, `GrowPlantsAction` (vanilla bonemeal), `PlantSaplingAction`
(biome-matched, spacing-checked), `TorchPlaceAction` (block-light gated), and
`JourneyToStructureAction` — the long-range one:

```java
BehaviorAction pilgrim = JourneyToStructureAction.builder()
        .id("village_journey").structure("village")
        .crewName("pilgrims")                 // group travel: leader + recruited stragglers
        .searchRadiusChunks(64).arrivalDistance(24)
        .cohesionRadius(8).recruitRadius(16)
        .marchLeg(24)                         // how far the formation point advances per step
        .gatherRadius(6)                      // how close the crew must be to camp
        .formation(Formation.WEDGE)           // the shape the crew walks in
        .restAtNight(true)
        .callbacks(new JourneyToStructureAction.Callbacks() {
            @Override public boolean onNight(BehaviorContext ctx, Location campSite) {
                // raise the camp; return false to retry later the same night
                return true;
            }
            @Override public boolean onDawn(BehaviorContext ctx, Location campSite) {
                // clear the camp; return false to retry on the next roll
                return true;
            }
            @Override public void onArrival(BehaviorContext ctx, Location destination) { }
            @Override public void onNotFound(BehaviorContext ctx) { }
        })
        .build();
```

The action itself owns the mechanics the modules used to hand-roll and get
wrong: the village (or temple, fortress, …) is found through the Core's
structure lookup on a search cooldown, the destination is normalised to a
standable cell before it is ever pathed to, the walk is executed leg by leg by
`JourneyRunner` with stall detection, detour retry and a leash, the crew is kept
cohesive by `MobCrewService` (stragglers are walked back **before** the crew
halts, which is why a night camp now has everyone beside it), and the journey
runs under an exclusive activity claim so no other profile can steer the same
mob mid-march. Journey and crew state is runtime-only inside the engine — PDC is
never used for it, so a restart cleanly forgets a half-finished march.

#### How a crew actually moves

A distant goal is not something a mob can walk to: vanilla pathing reaches
roughly 48 blocks and simply reports "no path" beyond it, so a villager handed
a village 300 blocks away stands still forever. The Core therefore never hands
a mob a distant target:

- The crew keeps a **formation point** (`crewRally`) — a place on the route,
  not a person. The leader advances it one `marchLeg` toward the goal, and only
  **once the whole crew has reached it** (a straggler is waited for, with a
  patience counter so one stuck member cannot freeze the march forever).
- Every member is handed **one bounded step** toward the formation point
  (`crewRejoin`), capped at its own path range — a waypoint it can actually
  path to. Chasing the leader's live position instead would mean the target
  moves as fast as the chaser and the gap never closes.
- At night the formation point is **pinned**: the crew closes ranks around it
  and the leader only raises the camp once everyone is gathered
  (`gatherRadius`). The camp callback receives the camp site, and returning
  `false` retries on the next roll of the same night instead of writing the
  night's camp off.
- Arrival is a **group** arrival: the leader waits at the goal for the members
  still on the road.

Day and night are read through `WorldTimes` (`isNight`, `dayIndex`,
`timeOfDay`), so every scenario agrees with the sky instead of each reading
`World#getTime()` / `getFullTime()` its own way. `JourneyRunner`'s internal
cooldowns use `World#getGameTime()`, which `/time set` cannot stall.

#### Route planning: legs that go around obstacles

Each leg is not a straight line. `JourneyRunner` asks `RoutePlanner` for the
next waypoint, and the planner first samples the straight line with a handful
of block reads:

- clear ground, no cliff, no water → the straight leg is returned unchanged,
  so an open-field march costs almost nothing;
- an obstacle → a bounded A\* search over a 25×25-cell window (2-block cells,
  ≤256 expansions) finds a walkable line around it. Water is crossable but
  priced, drops up to four blocks are allowed, cliffs and lava are blocked, and
  the result is normalised by `GroundProbe` before the mob is sent to it.

Planning is **segmented** (a rolling window, not a 2,000-block monolith): the
route ahead is replanned every leg, when its chunks are loaded, so the plan
never guesses at unloaded terrain. Searches share a wall-clock budget (24 per
50 ms across all mobs); past that the runner keeps its straight leg instead of
spending the tick on planning. A stall that survives the sideways detour earns
exactly one full reroute before the journey reports `STUCK`.

Two limits are deliberate, not missing features: the planner never teleports a
mob (movement is always the entity's own vanilla walk, so the client's motion
stays authoritative), and the whole mob subsystem is main-thread by design —
it is scheduler-agnostic in the sense that it never assumes a thread it was not
called on, but it does not claim Folia region-thread execution.

#### Formations, roles and flocking

A formation point says *where* the crew is; a formation says *how* it stands
around it. `Formation.NONE` (the default) keeps the plain point, so nothing
changes for existing crews. The others assign each member a slot in the crew's
own frame (forward along the direction of travel, lateral beside it):

| Formation | Shape |
|---|---|
| `COLUMN` | one behind the other — doorways, caves, bridges |
| `LINE` | shoulder to shoulder across the route |
| `WEDGE` | a V with the leader at its point — broad front |
| `RUSH` | a loose charge, members spread but all pressing forward |
| `PINCER` | two wings approaching the formation point from opposite sides |
| `SURROUND` | a ring around the formation point — encirclement |

`SquadRole` (`LEADER`, `TANK`, `BREAKER`, `FLANKER`, `SUPPORT`, `SNIPER`,
`DISTRACTOR`) nudges a member's slot and spacing, so a tank walks the front and
a sniper the back of the same wedge. Roles are optional and free when unused.

Separation comes from `FlockSteering`: a member with neighbours inside the
separation radius gets a small capped sideways push, so five villagers do not
stack on one block and a crew can actually pass a doorway. Cohesion is the
formation point and alignment is the shared route — nothing extra is simulated.

```java
// after the mob has joined its crew
context.setCrewFormation("pilgrims", Formation.WEDGE);
context.setCrewRole("pilgrims", SquadRole.SUPPORT);

mob.squads().setFormation(crewKey, Formation.PINCER);   // identity-keyed API
mob.squads().snapshot(10);                              // status output
```

### Settler pipelines: locate → travel → settle → build

`SettlerPipeline` assembles the four phases every "move there and live there"
scenario needs, leaving only the build step to the module:

```java
MobPipeline settle = SettlerPipeline.builder("pilgrim_settle")
        .locateVillage()                       // cached, bell-anchored lookup
        .march(JourneyRunner.Options.defaults())// legs, routing, stall recovery
        .settle(6.0, 40L)                      // home spot within 6 blocks, 2s pause
        .onSettled(context -> buildCamp(context)) // your build step, runs once
        .build();

mob.assignPipeline(this, villager, settle);
```

`SettleStage` picks a **standable home spot** within the radius of the anchor
(rather than standing on the structure's single feature block) and stores it on
the blackboard as `<destinationKey>#home`, so the build callback places blocks
around the same spot. Pipelines give you the state machine for free: the engine
ticks only the current stage, so the mob is in exactly one phase at a time, and
the blackboard carries the village between them.

### Pipelines: multi-stage scenarios

A single action is one decision; some scenarios are phases. A `MobPipeline` is
an immutable, shareable definition — find a village, march there, raid it —
that the engine attaches to one mob and advances every behavior pass. All
per-mob state lives in the `PipelineContext` blackboard, the pipeline holds an
exclusive activity claim while it runs (so no ordinary profile pulls the mob
mid-march), and each stage has its own success condition and timeout:

```java
MobPipeline raid = MobPipeline.builder("necromancer_village_raid")
        .stage(LocateStructureStage.village("find_village", "village"))
        .stage(new JourneyStage("march", "village", "village",
                JourneyRunner.Options.defaults()))
        .stage(new MyRaidStage())                 // your own PipelineStage
        .onComplete(ctx -> cooldown(ctx.mob()))
        .onFailure((ctx, reason) -> log(reason))
        .build();

mob.assignPipeline(this, witch, raid);            // main thread only
```

- `LocateStructureStage.village(id, key)` searches through the engine's cached
  structure lookup and writes a **walkable** centre onto the blackboard — a
  village's bell when one is loaded, else its bed centroid, else a standable
  cell beside the reported point.
- `JourneyStage` walks to a blackboard location through the shared
  `JourneyRunner` (follow-range legs, stall detours, chunk leases). Pass the
  structure type and it re-anchors the destination once the mob is close enough
  for the village's bell to be found.
- Custom stages implement `PipelineStage` (`id()`, `tick(ctx)` returning
  `StageResult.RUNNING|COMPLETE|FAILED`, optional `onEnter`/`onExit`/
  `timeoutTicks()`). `ctx` exposes the mob, its world, `nearestStructure(...)`,
  `advanceJourney(...)`, `navigateToWithin(...)`, `openEditSession(...)`,
  claims and the blackboard.
- The pipeline advances at most `MAX_PIPELINES_PER_RUN` at a time, shares the
  run's millisecond budget, skips unloaded mobs (resuming on load), and ends
  itself if a higher-priority action takes the mob over.
- `pipelineOf(entity)` reads the live handle; `pipelineSnapshot(maxLines)`
  reports live scenarios for status commands; `assignPipeline` on a mob that
  already runs one replaces it.

### Budgets, arbitration and testing

- The engine sweeps chunks around online players with **alternating parity**,
  dedupes entities through a fastutil long set, and staggers them into buckets,
  so an entity is evaluated once every `2 × INTERVAL_TICKS × STAGGER_BUCKETS`
  ticks. `MOB_BEHAVIOR.BUDGET_MILLIS_PER_RUN` is a soft cap: excess work is
  deferred to the next run instead of spiking the tick.
- Every run gets one `MobEditSession` grant: block writes and entity spawns are
  counted against `MAX_BLOCK_EDITS_PER_RUN` / `MAX_ENTITY_SPAWNS_PER_RUN`. A
  build that outlives the evaluation (a golem column raised tick by tick) asks
  for its own grant: `mob.openEditSession(editBudget, spawnBudget)` — the engine
  clamps both to its per-run caps, and `session.setBlock` / `breakBlock` /
  `spawnMob` refuse once spent.
- **Activity arbitration** is what stops scenarios conflicting: a mob already
  claimed by an action refuses other claims, an anchored claim drags the mob
  back toward its area (and abandons the scenario past the leash), and claims
  expire on their duration. `activeActivity(entity)` reads the current holder,
  `clearActivity(entity, actionId)` releases it. A claim also remembers the
  **priority** of the profile that took it: a strictly higher-priority profile
  whose conditions pass becomes a *challenger* that plays the odds gate and
  keeps the mob only by claiming it — so a guardian can pull a villager out of
  a pilgrimage, and a pilgrim can never pull a builder out of its column.
- `forceFire(plugin, entity, ignoreConditions)` runs one profile against a real
  entity right now and returns the per-action `BehaviorFireResult`
  (`FIRED` / `DECLINED` / `CONDITION_FAILED`) — the test hook behind
  `/mobfun spawn|fire <id> [force]`.
- `telemetrySummary()`, `journeySnapshot(maxLines)`, `crewSnapshot(maxLines)`
  and `pipelineSnapshot(maxLines)` are the operator-visible state;
  `unregisterAll(plugin)` runs on module disable.

**Complete working module:** [`examples/mineplus-mobfun`](../examples/mineplus-mobfun/README.md)
— grazers, a reforesting pig, a lamplighter villager, a florist bee, golem- and
snow-golem-summoning builders, a necromancer witch who locates a village,
marches there and raids it as a pipeline, and the pilgrim villagers above.

---

## Custom Mobs

`context.customMobs()` registers a **new mob**, not a new behavior on a vanilla
one. A custom mob is an ordinary vanilla base entity (`PIG`, `ZOMBIE`, …) that
carries a Mineplus identity, its own visual model, its own attributes and its
own behavior profile. Vanilla mobs of the same base type are untouched in both
directions: custom profiles are dispatched by custom id, never by entity type.

```java
CustomMobApi mobs = context.customMobs();

CustomMobDefinition evilPig = CustomMobDefinition.builder(
                "mymod:evil_pig", EntityType.PIG,
                CustomMobModel.vanilla(EntityType.PIG, texturePng))   // reuse the pig model
        .displayName("Evil Pig").showName(true)
        .attributes(CustomMobAttributes.builder()
                .maxHealth(12).movementSpeed(0.28).attackDamage(4).followRange(24).build())
        .behavior(profile -> profile
                .priority(50)
                .action(AttackNearestAction.builder().searchRadius(18).damage(4).build(), 1.0))
        .build();

mobs.register(this, evilPig);                        // main thread only
LivingEntity pig = mobs.spawn(location, "mymod:evil_pig");
```

### The isolation contract

- **No vanilla AI.** On spawn and on every chunk restore the Core clears the
  entity's goal and target selectors through `MobAiIsolation` while leaving AI
  enabled — the navigator still ticks the mob's own vanilla walk, but nothing
  in the mob decides anything. Every action comes from the definition's
  behavior profile, evaluated by the mob behavior engine.
- **Identity is persisted, not positional.** The id lives in PDC
  (`custom_mob_id`), with a scoreboard tag as the cheap discovery path and a
  live `UUID → id` cache for the engine sweep. A definition registered later
  picks its already-loaded mobs back up; a chunk reload re-applies identity,
  attributes (health is clamped, not refilled) and isolation.
- **Unknown ids never fall back to vanilla.** A tagged entity whose definition
  is not registered is still isolated, and — once at least one definition
  exists — removed on chunk load (`CUSTOM_MOBS.REMOVE_UNKNOWN_ON_LOAD`). This
  keeps a module's not-yet-registered mobs from being deleted by another
  module's startup order.
- **Owner-scoped registration.** One owner per id; a second owner claiming the
  same id is an error, re-registering the same definition is idempotent, and
  `unregisterAll(this)` on module disable removes definitions, profiles and
  live mobs.

### Rendering: a model is per-part displays, not a client entity

`CustomMobModel.vanilla(type, png)` reuses a published vanilla model and swaps
only the texture — the Evil Pig path. `CustomMobModel.builderFor(type, png)`
starts from the same descriptor and lets you replace or add `MobPart`s, and
`CustomMobModel.builder(w, h, png)` authors a silhouette from scratch. Each
part is a box set with an absolute pivot and a `MobPart.Role` (`HEAD`, `BODY`,
the four `LEG_*` roles, `STATIC`) that tells the rig which vanilla pose applies,
so head tracking and leg swing follow the entity while parts stay independently
animated.

The Core compiles the model into pack assets (`MobRigAssetWriter`): one texture
PNG, one item model per part, one item definition per part — the vanilla box-UV
cross layout is converted to element face windows exactly as Blockbench derives
it. `MobRigRenderer` then hides the base entity and presents every part as an
`ItemDisplay` carrying that part's `item_model`, driven on
`CUSTOM_MOBS.RENDER_INTERVAL_TICKS`. When the pack subsystem is unavailable (or
the server has no `item_model` support) the base entity simply stays visible:
behavior, identity and persistence are unaffected, so a custom mob is always
testable without the visual layer.

Rig cost is bounded by `RENDER_DISTANCE` (no pose pushes past it),
`MAX_RIGGED_PER_WORLD`, and a pose task that only exists while at least one rig
is attached. `refreshRendering()` re-evaluates every live custom mob after a
pack reload; `/mineplus pack recompile` and a settings reload call it for you.

### Attributes and spawning

`CustomMobAttributes` states only what differs from the base entity —
`maxHealth`, `movementSpeed`, `attackDamage`, `followRange`, `armor`,
`knockbackResistance`. Health is applied as a ceiling and filled on a fresh
spawn, clamped on restore. `spawn(location, id)` loads the chunk if needed and
returns `null` (never throws) when the subsystem is off, the id is unknown or
the server refused the entity. `spawnConfigurator(...)` runs last, after
identity, attributes and isolation, for module-specific setup.

### Dispatched by custom id

The profile a definition builds is registered under its custom id
(`MobProfile.builder(baseType, customId)`), and the engine resolves every
entity through `MobIdentityResolver` before dispatch. Consequences worth
knowing:

- A vanilla pig keeps running vanilla AI and never sees a custom pig's actions,
  and a custom pig never matches a `MobProfile.builder(EntityType.PIG)` —
  including `forceFire`, which resolves identity the same way.
- A custom mob with no behavior consumer gets an idle profile: it stands
  still. That is the correct default for a mob whose AI was cleared.
- Priorities, claims, pipelines, journeys, crews and edit budgets are the same
  engine primitives, so a custom mob can join a crew or run a pipeline exactly
  like a showcase villager.

### Bundled action: `AttackNearestAction`

The first gameplay action written against this surface — find the nearest
eligible player or villager, path to it, strike on reach with a cooldown:

```java
AttackNearestAction.builder()
        .searchRadius(18).reach(2.5).speed(1.2)
        .damage(4).cooldownTicks(20).knockback(0.4)
        .target(e -> e instanceof Player || e instanceof Villager)   // default
        .build();
```

It keeps a sticky target for 60 ticks so a mob does not flicker between two
players, skips creative/spectator players, re-paths on a throttle instead of
every evaluation, checks line of sight before striking, and applies damage
through `DamageTransactions` so a module's own suppression listener cannot eat
mob damage.

**Complete working module:** the Evil Pig in
[`examples/mineplus-mobfun`](../examples/mineplus-mobfun/README.md) is the
minimal custom mob — one vanilla model, one texture, one action.

Full guide: [Custom Mobs](custom-mobs.md).

---

## Timed Crafting Processes

`startProcess(instanceId, recipeId)` runs a recipe as a timed process on an ACTIVE machine:

- Duration comes from the recipe's `craftTimeTicks`, scaled by the machine level's `speed` multiplier (`speed: 2.0` = twice as fast; upgrading mid-process applies immediately).
- Process state is stored in the instance's `stateData` (`mp.process.*` keys), which is persisted — processes survive restarts and resume where they stopped.
- Processes on machines in unloaded chunks are paused, not cancelled (vanilla furnace parity).
- Completion fires `PROCESS_COMPLETE` lifecycle events and the `onProcessComplete(instance, recipe)` hook; start fires `PROCESS_START` / `onProcessStart`.
- The engine tracks only time. Consuming inputs and producing outputs is your feature's responsibility, typically inside the hooks.

**Example — a smelter that takes 30 seconds and speeds up with level:**

```java
advancedApi.createRecipe(new MachineRecipe("smelt_iron", "smelter", 1, 600,
        Map.of("raw_iron", 1), Map.of("iron_ingot", 1)));

// In your interact hook: verify + consume inputs yourself, then:
advancedApi.startProcess(instance.id(), "smelt_iron");

// In your MultiBlockHook:
@Override
public void onProcessComplete(MultiBlockInstance instance, MachineRecipe recipe) {
    // grant recipe.output() to the machine's inventory/owner
}
```

**Progress for GUIs:** `advancedApi.getProcess(instanceId).progressRatio()` returns `[0.0, 1.0]`.

---

## Auto-Linking (Pipe Networks)

`autoLinkNeighbors(sourceId, radius)` links an instance to every other instance within a Chebyshev cube of `radius` around it, returning the number of new links. Use in `onPlace` hooks to build pipe-style networks without tracking UUIDs. Links are directed (source → neighbor) and combine freely with manual `linkBlocks` and signal propagation.

### Spatial Lookup

`MultiBlockRegistry.getNearby(BlockCoordinate, radius)` returns instances within a cube — the primitive backing auto-linking, also usable directly for proximity gameplay logic.

---

## Core Runtime Behavior

What runs underneath your feature code:

| Component | Role |
|---|---|
| `MultiBlockLifecycleManager` | Drives create/place/interact/tick/upgrade/remove |
| `ModelRenderingManager` | Maps machine level → `.bbmodel` rendering through `VirtualBlockManager` |
| `ModelAnimationManager` | Animation runtime: samples clip keyframes, composes bone-hierarchy deltas, pushes display transforms (attached/cleaned automatically off the live render map; Folia-aware scheduling, viewer-culled, allocation-free ticks) |
| `VirtualBlockManager` | Session-local mapping + automatic cleanup of orphaned `BlockDisplay` "ghost" entities on chunk loads; texel bakes run asynchronously off the main thread |
| `MachineProcessManager` | Timed crafting processes; state in per-instance `stateData`, restart-safe |
| `PersistenceFacade` | SQLite persistence (`plugins/Mineplus/infrastructure.db`, HikariCP-pooled single connection) via an asynchronous write-behind queue with **incremental writes**: hot paths (place/upgrade/remove, process advancement) stage single-instance upserts/deletes, so one mutation no longer rewrites every row; bulk paths (reload, shutdown) stage a full replace. A background task flushes off-thread, and a synchronous flush runs on shutdown/reload. Failed flushes are re-queued and retried. (`MultiBlockStorageEngine` is deprecated, retained only for legacy JSON migration) |
| `HookBus` | Publishes lifecycle events to registered listeners |
| `MultiBlockLinkingSystem` | Directed links and signal propagation |
| `MobBehaviorEngine` | Budgeted, distance-gated mob profile evaluation with activity arbitration, journey and crew upkeep, and per-run edit/spawn grants (lazy: no task exists before the first registration) |
| `Ballistics` | Shared round simulation loop: one stepped pass per configured interval over all live rounds, instant-trace degradation over the round cap |
| `HeldItemInput` | The single held-item listener set every module shares: owner routing, press/release/swap delivery, vanilla-interaction freeze, punch-animation suppression |

**Ticking is chunk-aware:** instances in unloaded chunks are fully skipped (no hooks, heartbeats, renders, or process advancement) until their chunk loads again — vanilla tile-entity parity.

**Crash semantics:** gameplay actions stage snapshots in memory and a background task flushes them roughly once per second. After a hard crash, at most ~1 second of multiblock changes roll back to the last persisted state.

---

## Zero-Content Policy

Mineplus registers no gameplay content by default:

- No default blocks/items/machines.
- No test/demo spawns.
- No automatic world injections.

Anything in-game comes only from your JSON or API calls.

---

> ➡️ **Next:** step-by-step paths for each tier in [Extension Workflows](extension-workflows.md), or study the complete [`mineplus-fun` reference module](../examples/mineplus-fun/README.md). For the canonical module-building recipe (structure, JSON installation, hook wiring), read [`mineplus-fun/DEVELOPMENT_PROMPT.md`](../examples/mineplus-fun/DEVELOPMENT_PROMPT.md).
