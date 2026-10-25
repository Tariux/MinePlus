# MobFun

A **Mob behavior showcase** module for the [Mineplus Core](https://github.com/Tariux/MinePlus) — the reference example for the Core's `MOB_BEHAVIOR` subsystem (`com.mineplus.infrastructure.mob`).

## What it shows

Three vanilla mobs, each driven by the Core's **mob behavior engine** — a budgeted, distance-gated scheduler that executes interval-gated world actions around moving mobs (the MinePlus evolution of the Regrowth reference project's per-tick AI event handling):

| Showcase | Mob | Actions | Mechanics axis |
|---|---|---|---|
| **Grazer Cow** | `minecraft:cow` | `eat_plants` (45s), `grow_plants` (300s) | Plant consumption with baby-growth/healing side effects; vanilla bonemeal-like blooming |
| **Reforesting Pig** | `minecraft:pig` | `plant_sapling` (450s) | Biome-matched planting with spacing/light checks |
| **Lamplighter Villager** | `minecraft:villager` | `place_torch` (40s) | Block-light-gated placement |

Every world write funnels through `BehaviorContext.trySetBlock`/`tryBreak`, so the engine's per-run **block edit budget** caps changes, and the per-run **nanotime budget** defers work instead of spiking tick time. Entities outside players' scan radius are never evaluated.

## Building

```
gradle build          # in the Mineplus root first (produces build/libs/mineplus-1.3.0.jar)
cd examples/mineplus-mobfun
gradle build
```

## Runtime

1. Install `Mineplus-1.3.0.jar` (Core) and `mineplus-mobfun-1.0.0.jar` in `plugins/`.
2. Start the server — the behaviors are live immediately (module `depend: [Mineplus]`).
3. `/mobfun status` — showcase states, engine settings, live telemetry (runs, fired actions, block edits, budget deferrals).
4. `/mobfun <grazer|reforest|lamplighter> <on|off|toggle>` — flip one showcase without touching the others.

Permission: `mineplusmobfun.admin` (default: op). Engine tuning lives in the Core's `settings.mp.yml` under `MOB_BEHAVIOR` (interval, stagger, scan radius, budgets).

## Module API quick reference

```java
MobBehaviorApi api = mineplusPlugin.getPluginContext().mobBehaviorApi();

api.register(this, MobProfile.builder(EntityType.COW)
        .action(new EatPlantsAction(), 45.0)     // expected fire ~1/45s per mob
        .action(new GrowPlantsAction(), 300.0)   // first eligible action wins per event
        .condition(ctx -> !(ctx.entity() instanceof Ageable a) || a.isAdult())
        .build());

api.unregister(this, profile);                    // per-profile toggle
api.unregisterAll(this);                          // module shutdown
```

Custom behavior = implement `BehaviorAction` (`id()` + `execute(BehaviorContext)`); reuse the Core's bundled actions from `com.mineplus.infrastructure.mob.action`.
