# 🎪 MineplusFun — Reference Module for the Mineplus Core

[![](https://img.shields.io/badge/tier-module%20plugin-purple)](../../README.md)
[![](https://img.shields.io/badge/machines-4-orange)](#-the-juicer)
[![](https://img.shields.io/badge/Java-21-blue)](https://adoptium.net/)

> **Navigate:** [Examples](../README.md) • [Developer API](../../docs/developer-api.md) • [Module Development Guide](DEVELOPMENT_PROMPT.md) • [Project README](../../README.md)

`mineplus-fun` is the **canonical reference implementation** of a *module plugin* built on top of the Mineplus Core engine. It demonstrates the intended architecture:

- **Core (`Mineplus`)** is a dependency-only engine. It ships no game content — only the framework (virtual Blockbench rendering, multiblock registry/lifecycle, recipes, GUIs, item registry, linking/signals, persistence) and a few admin commands.
- **Module (`MineplusFun`)** is a completely separate plugin that depends on the Core and adds *game logic* — in this case, four complete features:

| Machine | What it demonstrates |
|---|---|
| 🧃 **[Juicer](#-the-juicer)** | Unconditional GUI (JSON `gui` key), recipes, custom items, upgrade button |
| 💥 **[Cannon](#-the-cannon)** | Conditional interaction, `TNTPrimed` ballistics, persistent ammo, rotation-aware placement, mountable level 2 with vanilla-bow aiming |
| ⚙️ **[Gear](#%EF%B8%8F-the-gear)** | bbmodel clip animation, redstone activation, chain reaction through the `AnimationApi` |
| 🍷 **[Wine](#%EF%B8%8F-the-wine-tasting-flight)** | Texel surface baking — five 16x16 sprites reconstructed pixel-by-pixel out of vanilla palette blocks, compared side by side in a tasting flight |

The game logic lives **entirely** in this module — the Core knows nothing about the machines.

---

## 📁 Project layout

```
mineplus/                         # Core engine (dependency-only)
  build.gradle
  src/main/java/com/mineplus/...
  src/main/resources/plugin.yml

examples/mineplus-fun/            # Example module (separate plugin)
  build.gradle
  settings.gradle
  README.md
  DEVELOPMENT_PROMPT.md           # AI onboarding prompt for building new modules
  src/main/java/com/mineplus/fun/
    MineplusFunPlugin.java        # feature list + coordinated reload + reverse-order teardown
    ModuleFeature.java            # feature lifecycle contract (start/command/stop, isolated)
    juicer/
      JuicerFeature.java          # wires the Juicer into the Core API
      JuicerKeys.java
      JuicerSubCommand.java
      JuiceConsumeListener.java
      gui/JuicerGui.java
      items/CarrotJuiceItemDefinition.java
      items/MelonJuiceItemDefinition.java
    cannon/
      CannonFeature.java          # wires the Cannon into the Core API
      CannonKeys.java
      CannonFireHook.java         # level 1: torch = fire; level 2: saddle = mount, else menu
      CannonMountManager.java     # gunner's seat (armor stand), lanyard bow + match arrow
      CannonAimListener.java      # bow-draw force -> aimed TNT shot (level 2)
      CannonTntStore.java         # persistent ammo counter (stateData)
      CannonSubCommand.java
      gui/CannonGui.java          # TNT ammunition menu + gunner's seat button
    gear/
      GearFeature.java            # wires the Gear into the Core API
      GearKeys.java
      GearHook.java               # lifecycle re-evaluation of the redstone grid
      GearGrid.java               # flood-fill activation from powered seeds
      GearRedstoneListener.java   # instant response on BlockRedstoneEvent
      GearSubCommand.java
    wine/
      WineFeature.java            # installs 5 model+texture+meta triplets + multiblock JSONs
      WineKeys.java
      WineVariant.java            # the flight lineup (key -> type id -> display name)
      WineSubCommand.java         # /wine place|flight|remove|clear|status
   src/main/resources/
     plugin.yml
     defaults/                     # bbmodels + multiblock/recipe JSON (shipped in this jar)
       models/juicer-machine-level-1.bbmodel
       models/juicer-machine-level-2.bbmodel
       models/cannon-3-1-1.bbmodel
       models/cannon-3-1-1-bigger.bbmodel
       models/gear-1-1.bbmodel
       models/strad-wine.bbmodel  # + stal/red/chenet/solaris (with *_wine.png + .meta.json)
       multiblocks/juicer_machine.json
       multiblocks/cannon.json
       multiblocks/gear.json
       multiblocks/wine.json      # + wine-stal/red/chenet/solaris.json
       recipes/juicer_machine_recipes.json
```

Each feature is a self-contained package with the same shape: `*Keys` (constants), `*Feature` (wiring), a hook, a GUI, and a subcommand. Copy the package whose interaction model matches your idea — see the [Development Guide](DEVELOPMENT_PROMPT.md) for the full recipe.

---

## 🧃 The Juicer

A fruit-pressing machine with a recipe-driven GUI and custom juice items.

**Command:** `/juicer <place|remove|upgrade|give>`

| Action | Effect |
|---|---|
| `place` | Place a Juicer where you are looking |
| `upgrade` | Upgrade the looked-at Juicer (consumes the level's `upgradeCost`) |
| `remove` | Remove the looked-at Juicer |
| `give <carrot\|melon>` | Give yourself a juice item |

**In-game:** right-click the Juicer to open its menu — insert fruit in the input slot, press *Process*, and collect your juice from the output slot. Recipes are defined in [`defaults/recipes/juicer_machine_recipes.json`](src/main/resources/defaults/recipes/juicer_machine_recipes.json).

---

## 💥 The Cannon

A 3×1×1 muzzle-loading cannon modeled in Blockbench ([level 1](src/main/resources/defaults/models/cannon-3-1-1.bbmodel), [level 2](src/main/resources/defaults/models/cannon-3-1-1-bigger.bbmodel)) — rendered, collidable, and aimable, on completely vanilla clients.

**Command:** `/cannon <place|remove|upgrade>`

| Action | Effect |
|---|---|
| `place` | Place a Cannon where you are looking — **the muzzle automatically aims away from you** |
| `upgrade` | Upgrade the looked-at Cannon to **Cannon II** (8 iron ingots + 4 TNT) |
| `remove` | Remove the looked-at Cannon (loaded TNT drops back out) |

**Level 1 in-game interactions:**

| Interaction | Result |
|---|---|
| **Right-click holding TNT** (or empty hand / any item) | Opens the ammunition menu — a single slot that accepts one stack of TNT |
| **Right-click holding a torch** 🔥 | **Fires!** One TNT is consumed from the loaded stack and launched ~20 blocks along the barrel in a ballistic arc |
| **Break the cannon** | Leftover TNT drops naturally — nothing is silently destroyed |

**Level 2 (Cannon II) — the gunner's seat:**

| Interaction | Result |
|---|---|
| **Right-click holding a saddle** 🐴 (or the saddle button in the menu) | Takes the gunner's seat: the player rides an invisible seat pinned to the cannon (movement locked, view free for aiming) and automatically holds the **Cannon Lanyard** bow |
| **Draw the Cannon Lanyard** 🏹 | Aims and fires exactly like a vanilla bow — the draw force (0–100% over 20 ticks) scales the muzzle speed from a short lob (~0.7 b/t) to a full artillery shot (~2.8 b/t). No arrows are needed or consumed: the cannon's loaded TNT is the ammunition |
| **Sneak** | Dismounts and returns the lanyard |

The lanyard's draw works without real arrows because mounting also places a single **Cannon Match** arrow in the gunner's inventory (vanilla bows need one arrow to start a draw); the arrow launch is always intercepted and cancelled, so the match is never spent and is reclaimed on dismount. Aiming is clamped to a 60° cone around the bore so the stationary cannon never fires backwards through itself.

**Implementation highlights** (each is a reusable pattern, documented in the [Development Guide](DEVELOPMENT_PROMPT.md)):

- *Conditional interaction* — the multiblock JSON registers **no** `gui` key, so the [`CannonFireHook`](src/main/java/com/mineplus/fun/cannon/CannonFireHook.java) decides per click and per **level**: level 1 torch → fire; level 2 saddle → mount; anything else → `openGui(...)`.
- *Persistent ammo* — the loaded TNT count lives in the instance's `stateData` (via [`CannonTntStore`](src/main/java/com/mineplus/fun/cannon/CannonTntStore.java)), so it **survives server restarts**.
- *Model-accurate ballistics* — the muzzle position and firing axis are computed from the bbmodel's pixels through the same rotation transform the renderer uses, so the shot always leaves the visible barrel, for every placement orientation.
- *Rotation-aware placement* — `/cannon place` uses `createMultiBlock` + `placeMultiBlock` with a −90° compensation so the muzzle points away from the player.
- *Mounting via passenger seat* — [`CannonMountManager`](src/main/java/com/mineplus/fun/cannon/CannonMountManager.java) seats the gunner on an invisible marker armor stand (WASD is neutralised by riding; the seat clears the barrier collision layer), and [`CannonAimListener`](src/main/java/com/mineplus/fun/cannon/CannonAimListener.java) converts the vanilla bow release (`EntityShootBowEvent#getForce`) into the cannon shot.

---

## ⚙️ The Gear

A single-block gear modeled in Blockbench ([model](src/main/resources/defaults/models/gear-1-1.bbmodel)) whose `rotate_gear` animation (a 3-second looping Y-rotation authored in the model itself) is driven by redstone — the first reference feature for the Core's animation engine.

**Command:** `/gear <place|remove|status>`

| Action | Effect |
|---|---|
| `place` | Place a Gear where you are looking |
| `remove` | Remove the looked-at Gear |
| `status` | List all gears with their power/animation state |

**Activation rules:**

| Situation | Result |
|---|---|
| **Redstone adjacent to the gear** (torch, lever, wire, redstone block, repeater) | The gear starts rotating |
| **Gear placed next to a rotating gear** | It starts rotating too — the interlocking train, phase-synced to the neighbour |
| **Power cut** | The whole train coasts to a stop (gears return to their rest pose) |

**Implementation highlights:**

- *Clip from the model file* — the animation rides inside the `.bbmodel`; the multiblock JSON declares **no** `animations` autoplay because rotation is *state-driven*, not unconditional.
- *Grid evaluation* ([`GearGrid`](src/main/java/com/mineplus/fun/gear/GearGrid.java)) — every half second (plus instantly on `BlockRedstoneEvent` and lifecycle hooks) the module flood-fills the active set from redstone-powered seeds across face-adjacent gears. Reachability-based, so a ring of gears can never self-sustain after the power is cut.
- *Phase synchronization* — a gear joining a running train starts its clip at the neighbour's current animation time, so meshed gears rotate in lockstep; the whole train plays the same clip at the same rate.

---

## 🍷 The Wine Tasting Flight

Five vinery wine bottles — **Strad, Stal, Red, Chenet, Solaris** — each reconstructed from its own hand-drawn 16x16 sprite by the Core's **texel surface baker**: every face is decomposed into texels, each texel is quantized to the nearest visually-flat vanilla block, and merged same-color runs become thin plates. The result reads as pixel art built from concretes and terracottas — **zero resource pack, completely vanilla clients**.

**Command:** `/wine <place [variant]|flight|remove|clear|status>`

| Action | Effect |
|---|---|
| `place [variant]` | Place a single bottle (defaults to `strad`); variants: `strad`, `stal`, `red`, `chenet`, `solaris` |
| `flight` | Lay out the **tasting flight**: one bottle of every variant in a row on the surface you are looking at, perpendicular to your facing, each rotated toward you |
| `remove` | Remove the bottle you are looking at |
| `clear` | Remove every wine bottle within 24 blocks |
| `status` | List all placed bottles grouped by variant |

**Why five bottles?** Each sprite stresses the texel pipeline differently — flat two-tone labels, shaded glass gradients, rotated UV windows — so the side-by-side flight makes bake-quality differences visible at a glance. Verify any bake with `/mineplus model info <key>-wine` (grid histogram, palette usage, merged plate count, budget verdict).

**Implementation highlights:**

- *Per-model opt-in* — each [`<key>-wine.meta.json`](src/main/resources/defaults/models/strad-wine.meta.json) sets `"texelMode": "AUTO"` and raises the per-instance plate budget above the global default (384, or 768 for the gradient-heavy Stal sprite).
- *Vanilla-format imports* — the models were converted from the Vinery mod's `java_block` JSON (geometry kept in [0..16] corner space so the Core's AUTO origin detection anchors them GRID like any vanilla block model); the tall Stal bottle (18 pixels) simply occupies the block above its anchor.
- *Pruned visible cubes* — nested label-band cubes and zero-depth decals were dropped from the conversion (redundant in an opaque renderer); intentional nesting is handled by the Core's occlusion culling at bake time.
- *No hook, no GUI* — a pure showcase feature: resources + a subcommand, exactly the minimal `ModuleFeature` shape to copy for decorative content.

---

## 🔌 How the module talks to the Core

The Core exposes its API through `PluginContext`, obtained from the Core plugin instance:

```java
MineplusPlugin core = (MineplusPlugin) Bukkit.getPluginManager().getPlugin("Mineplus");
PluginContext context = core.getPluginContext();

// Install shipped resources into the Core's data folder (models overwrite, configs don't)
context.moduleSupport().installDefault(plugin, "defaults/models/cannon-3-1-1.bbmodel", "models/cannon-3-1-1.bbmodel", true);
context.moduleSupport().installDefault(plugin, "defaults/multiblocks/cannon.json", "multiblocks/cannon.json", false);

context.itemRegistry().register(new CarrotJuiceItemDefinition());
context.infrastructureApi().registerHook("cannon", new CannonFireHook(context, mounts));
context.infrastructureApi().registerGui("cannon_gui", new CannonGui(plugin, registry, mounts));
context.moduleSupport().registerCommand(plugin, "cannon", new CannonSubCommand(context)); // dynamic /cannon
context.jsonInfrastructureApi().reloadAll(); // load shipped JSON definitions
```

Module content (models, multiblocks, recipes) is shipped *inside the module jar* and installed into the **Core's** data folder at enable time through the Core's module toolkit (`context.moduleSupport()`), then loaded via `reloadAll()`. Commands are registered dynamically on the server command map — no `plugin.yml` command entries. Machine GUIs extend the Core's `AbstractMachineGui` base (slot guarding, drag/hotbar validation, and capture-on-next-tick are inherited); model geometry (muzzles, seats) resolves through `ModelPoints`, and persistent counters through `TypedState`.

## 🔨 Building

Build order matters — the module depends on the Core jar:

```bash
# 1. Build the Core first (produces build/libs/mineplus-1.0.0.jar)
cd mineplus
gradle build

# 2. Build the example module
cd examples/mineplus-fun
gradle build   # produces build/libs/mineplus-fun-1.0.0.jar
```

> The module's `build.gradle` points at `../../build/libs/mineplus-1.0.0.jar`. If you change the Core version, update both `version` fields.

## 🚀 Running (production-like)

Drop **both** jars into your server's `plugins/` folder:

```
plugins/
  Mineplus.jar        # Core engine  (this must load first)
  MineplusFun.jar     # Example module (depend: [Mineplus])
```

On startup, `MineplusFun` checks that the Core is present. If it is missing it prints a clear error and disables itself instead of crashing:

```
[MineplusFun] FATAL: Mineplus Core plugin was not found.
[MineplusFun] Install 'Mineplus.jar' (Core) into your plugins/ folder first.
```

## 📖 Going further

- **[DEVELOPMENT_PROMPT.md](DEVELOPMENT_PROMPT.md)** — the complete module-building recipe: architecture rules, verified Core behavior, GUI patterns, API traps, and both machines as reference features.
- **[Developer API](../../docs/developer-api.md)** — everything the Core exposes to module code.
- **[Extension Workflows](../../docs/extension-workflows.md)** — where the module pattern fits among the three usage tiers.
