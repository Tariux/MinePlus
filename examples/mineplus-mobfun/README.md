# MobFun

A **Mob behavior showcase** module for the [Mineplus Core](https://github.com/Tariux/MinePlus) — the reference example for the Core's `MOB_BEHAVIOR` subsystem (`com.mineplus.infrastructure.mob`).

## What it shows

Nine vanilla mobs, each driven by the Core's **mob behavior engine** — a budgeted, distance-gated scheduler that executes interval-gated world actions around moving mobs (the MinePlus evolution of the Regrowth reference project's per-tick AI event handling):

| Showcase | Mob | Actions | Mechanics axis |
|---|---|---|---|
| **Grazer Cow** | `minecraft:cow` | `eat_plants` (10s), `grow_plants` (60s) | Plant consumption with baby-growth/healing side effects; vanilla bonemeal-like blooming |
| **Grazing Sheep** | `minecraft:sheep` | `eat_plants` (10s), `grow_plants` (60s) | The same Core actions on a second mob type — profiles are per-type and freely reusable |
| **Reforesting Pig** | `minecraft:pig` | `plant_sapling` (45s) | Biome-matched planting with spacing/light checks |
| **Lamplighter Villager** | `minecraft:villager` | `place_torch` (10s) | Block-light-gated placement |
| **Florist Bee** | `minecraft:bee` | `plant_flower` (45s) | Module-side `BehaviorAction` implementation — the action interface is fully open to modules |
| **Golem Guardian Villager** | `minecraft:villager` | `summon_iron_golem` (30s) + `inDanger(8)` | When endangered: raises an iron-block + carved-pumpkin column **block by block** (one block every few ticks, sound on each) that assembles into a real golem which immediately charges the menace. Per-builder PDC cooldown (45s, restart-safe) and a one-golem cap keep raids spam-free |
| **Frost Builder Villager** | `minecraft:villager` | `summon_snow_golem` (30s) + `inDanger(8)` | Same staged guardian pattern with snow blocks → snow golem |
| **Necromancer Witch** | `minecraft:witch` | `summon_minions` (12s) + `threatOrPrey(12, 10)` | Adaptive-raid-space mixed horde: biome-matched melee/flanker/archer (husk/stray/drowned/cave spider), a tamed **skeleton horseman archer** roughly every fifth raid, whole-horde target sync, potion retaliation against close golems, then a retreat-hop **behind her own screen**; per-witch summon cooldown + horde cap (5) |
| **Pilgrim Villager** | `minecraft:villager` | `village_journey` (2s) | Villagers in the wastelands locate the nearest village (64-chunk search, retried every ~30-60s until found) and **journey there as a synced crew** (leader + followers that regroup, self-promoting if the leader dies; step-aware hop bursts); between dusk and late night they halt and the leader raises **one shared camp — a single campfire plus a side-by-side row of beds, one per crew member** (once per night; nearby villagers will even path over and sleep); arrival clears the journey with a celebration; `/mobfun status` lists each pilgrim's journey state (target, distance, leader, day/night mode) |

### The two sides

Enable `necromancer` plus the guardians (`golem`, `snowgolem`) and `pilgrim` together and the module plays a survival skirmish:

1. Pilgrim villagers journey toward a village in a crew, camping at night.
2. The necromancer spots a villager → adaptive-space mixed horde charges as one pack; the witch hops behind her minions.
3. The threatened villagers raise golems block by block — slowly, once, and never more than one each — and the golems charge the horde and the witch.
4. The witch counter-fights: potion volleys at close golems, occasional fresh summons, retreats behind her crew.
5. Neither side can spam: builders carry a 45s personal cooldown + one-golem cap; the witch carries a 25s summon cooldown + five-minion cap. Fights resolve in the open where you can watch and intervene.

Intervals are tuned **for demonstration**. For natural pacing, pass larger interval seconds when building the profile.

Every world write funnels through `BehaviorContext.trySetBlock`/`tryBreak`, so the engine's per-run **block edit budget** caps changes, and the per-run **nanotime budget** defers work instead of spiking tick time. Entities outside players' scan radius are never evaluated. Staged work (golem assembly, retreat hops, march bursts) runs via the vanilla scheduler — bounded, pre-verified, and self-canceling when the world changed underneath.

## Building

```
gradle build          # in the Mineplus root first (produces build/libs/mineplus-1.3.0.jar)
cd examples/mineplus-mobfun
gradle build
```

## Runtime

1. Install `Mineplus-1.3.0.jar` (Core) and `mineplus-mobfun-1.0.0.jar` in `plugins/`.
2. Start the server — the behaviors are live immediately (module `depend: [Mineplus]`).
3. Commands (`mineplusmobfun.admin`, default op):
   - `/mobfun status` — showcase states, engine settings, live telemetry (runs, fired actions, block edits, budget deferrals).
   - `/mobfun <grazer|sheep|reforest|lamplighter|florist|golem|snowgolem|necromancer|pilgrim> <on|off|toggle>` — flip one showcase without touching the others.
   - `/mobfun spawn <id> [force]` — spawn the showcase mob 2 blocks in front of you, name it, and immediately **force-fire** its actions: one forced pass with the odds gate bypassed, printing a per-action report (`FIRED`, `DECLINED` for a failed context check, `BLOCKED` for failed profile conditions). With `force`, profile conditions are ignored too.
   - `/mobfun fire <id> [force]` — same forced pass on the **nearest** mob of that type within 10 blocks.
   - `/mobfun despawn` — remove every test mob spawned via `/mobfun spawn` (also removed on disable). Golems, minions and journeys launched into the world are ordinary world state — kill/capture as usual.

### Scenario recipes

- **Raid test**: `/mobfun spawn necromancer` beside a villager → a biome-matched, terrain-fitting mixed horde charges as one crew; the witch hops back behind it. Spawn a golem guardian nearby (`/mobfun spawn golem`) → the threatened villagers build a golem block by block; the golem charges, the witch potions and retreats. Watch, don't let either side die, or jump in.
- **Journey test**: `/mobfun spawn pilgrim` a few times in the open (or let natural villagers far from villages do it) → they rally into one crew and march to the nearest village (`/mobfun status` shows each pilgrim's target, distance, and march mode; debug logging names every search failure); at dusk they halt and the leader lights **one campfire with a row of beds, one per crew member**; at dawn they resume; arrival celebrates.
- **Horseman**: villagers near a witch for several summons → roughly every fifth raid, a tamed skeleton horse with an archer in the saddle joins the charge.
- The guardian profiles ride the `villager` type together - when both golem builders are enabled they compete for the same evaluation (registration order wins). Builders tagged by a successful build skip pilgrim journeys and keep their posts.

## Module API quick reference

```java
MobBehaviorApi api = mineplusPlugin.getPluginContext().mobBehaviorApi();

api.register(this, MobProfile.builder(EntityType.VILLAGER)
        .action(new GolemSummonAction(plugin, "summon_iron_golem", "Summoned Iron Guardian",
                Material.IRON_BLOCK, EntityType.IRON_GOLEM,
                Sound.BLOCK_METAL_PLACE, Sound.ENTITY_IRON_GOLEM_REPAIR), 30.0)
        .condition(BehaviorConditions.inDanger(8.0))   // module-side situational gate
        .build());

api.unregister(this, profile);                    // per-profile toggle
api.unregisterAll(this);                          // module shutdown

// Direct testing: odds-bypassed firing pass with per-action diagnostics
BehaviorFireResult result = api.forceFire(this, entity, false);  // true = ignore conditions
result.attempts();                                // (actionId, FIRED|DECLINED|CONDITION_FAILED)
```

Custom behavior = implement `BehaviorAction` (`id()` + `execute(BehaviorContext)`) — see this module's `PlantFlowerAction`, `GolemSummonAction` (staged build + PDC cooldown), `SummonMinionHordeAction` (adaptive space, mixed pool, retaliation) and `JourneyAction` (PDC-carried state machine) for the module-side pattern; reuse the Core's bundled actions from `com.mineplus.infrastructure.mob.action`. Situational gates like `inDanger`/`threatOrPrey` (`BehaviorConditions`) compose entity searches on top of the block-level `BehaviorContext`.
