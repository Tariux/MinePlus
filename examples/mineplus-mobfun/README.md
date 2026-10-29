# MobFun

A **Mob behavior showcase** module for the [Mineplus Core](https://github.com/Tariux/MinePlus) — the reference example for the Core's `MOB_BEHAVIOR` subsystem (`com.mineplus.infrastructure.mob`).

## What it shows

Nine vanilla-mob showcases driven by the Core's **mob behavior engine** — a budgeted, distance-gated scheduler that executes interval-gated world actions around moving mobs (the MinePlus evolution of the Regrowth reference project's per-tick AI event handling) — plus one **custom mob**, which is a new mob rather than a behavior on a vanilla one:

| Showcase | Mob | Actions | Mechanics axis |
|---|---|---|---|
| **Grazer Cow** | `minecraft:cow` | `eat_plants` (10s), `grow_plants` (60s) | Plant consumption with baby-growth/healing side effects; vanilla bonemeal-like blooming |
| **Grazing Sheep** | `minecraft:sheep` | `eat_plants` (10s), `grow_plants` (60s) | The same Core actions on a second mob type — profiles are per-type and freely reusable |
| **Reforesting Pig** | `minecraft:pig` | `plant_sapling` (45s) | Biome-matched planting with spacing/light checks |
| **Lamplighter Villager** | `minecraft:villager` | `place_torch` (10s) | Block-light-gated placement |
| **Florist Bee** | `minecraft:bee` | `plant_flower` (45s) | Module-side `BehaviorAction` implementation — the action interface is fully open to modules |
| **Golem Guardian Villager** | `minecraft:villager` | `summon_iron_golem` (30s) + `inDanger(8)` | When endangered: raises an iron-block + carved-pumpkin column **block by block** (one block every few ticks, sound on each) that assembles into a real golem which immediately charges the menace. Per-builder PDC cooldown (45s, restart-safe) and a one-golem cap keep raids spam-free |
| **Frost Builder Villager** | `minecraft:villager` | `summon_snow_golem` (30s) + `inDanger(8)` | Same staged guardian pattern with snow blocks → snow golem |
| **Necromancer Witch** | `minecraft:witch` | `summon_minions` (2s) + `threatOrPrey(20, 18)` | Adaptive-raid-space mixed horde: biome-matched melee/flanker/archer (husk/stray/drowned/cave spider), a tamed **skeleton horseman archer** roughly every fifth raid, whole-horde target sync, potion retaliation against close golems, then a retreat-hop **behind her own screen**. She never stops calling: a wave every **3 s** (every **1 s** while a golem is swinging at her), 3 minions per wave, a horde cap of **8** — the horde is what buys her the time to run from a golem she cannot kill |
| **Pilgrim Villager** | `minecraft:villager` | `village_journey` (2s) | The Core's `JourneyToStructureAction` with this module's `PilgrimCamp` callbacks: villagers in the wastelands locate the nearest village (64-chunk search, on the engine's search cooldown) and **journey there as a synced crew** — the crew keeps a shared formation point that advances along the route only once everybody has reached it, and each member is handed one bounded step toward it, so a village 300 blocks away is still a walk the mob's own pathing can make (leader self-promotes if killed); a member that falls more than **64 blocks** behind stops waiting and walks the goal on its own instead of stranding the whole crew; between dusk and late night the stragglers are walked back to the crew **before** the crew halts, then the leader raises **one shared camp — a single campfire plus a side-by-side row of beds, one per crew member** (only the crew's own beds count, so a village's beds are never mistaken for the camp's; both halves of every bed verified before either is written; a budget-refused camp is retried later the same night, not lost); arrival clears the journey with a celebration; `/mobfun status` prints the Core's live journey and crew snapshots plus the active activity claims |
| **Evil Pig** | `minecraft:pig` + custom id `mobfun:evil_pig` | `attack_nearest` (1s) | **The custom-mob showcase** — not a vanilla pig with extra behavior but a separate mob: the vanilla pig model with this module's own texture (`mobs/evil_pig.png`, embedded in the jar), its own attributes, its own behavior profile, and vanilla AI cleared by the Core on spawn and every chunk restore. It is invisible to vanilla pig profiles and never matches one; identity, AI isolation, persistence and the display rig are all Core-owned (`context.customMobs()`) |

### The two sides

Enable `necromancer` plus the guardians (`golem`, `snowgolem`) and `pilgrim` together and the module plays a survival skirmish:

1. Pilgrim villagers journey toward a village in a crew, camping at night.
2. The necromancer spots a villager → adaptive-space mixed horde charges as one pack; the witch hops behind her minions.
3. The threatened villagers raise golems block by block — slowly, once, and never more than one each — and the golems charge the horde and the witch.
4. The witch counter-fights: potion volleys at close golems, occasional fresh summons, retreats behind her crew.
5. Neither side can spam: builders carry a 45s personal cooldown + one-golem cap; the witch carries a 3s summon cooldown (1s when a golem is on her) + an eight-minion horde cap. Fights resolve in the open where you can watch and intervene.

Intervals are tuned **for demonstration**. For natural pacing, pass larger interval seconds when building the profile.

Every world write funnels through `BehaviorContext.trySetBlock`/`tryBreak`/`trySpawn`, so the engine's per-run **block-edit** and **entity-spawn** budgets cap what a scenario may change, and the per-run **nanotime budget** defers work instead of spiking tick time. Work that outlives the evaluation that started it (a golem column raised tick by tick) asks the engine for its own bounded grant with `openEditSession(edits, spawns)` — the grant is clamped to the same per-run caps, and each staged step writes under it, so no module edits or populates the world outside the subsystem's limits. Entities outside players' scan radius are never evaluated; a mob whose scenario claimed an activity holds inside its claim anchor and refuses other profiles, which is what keeps the four villager showcases from steering the same person — and because a claim remembers the priority of the profile that took it, a strictly higher-priority showcase (a guardian answering an attack) may take a villager over from a running pilgrimage, while the pilgrimage may never take a builder away from its post. Staged work (golem assembly, retreat hops) runs through the vanilla scheduler — bounded, pre-verified, and self-canceling when the world changed underneath.

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
   - `/mobfun status` — showcase states, engine settings, live telemetry (runs, fired actions, block edits, spawns, budget deferrals), the Core's journey and crew snapshots, and how many tracked mobs currently hold an activity claim.
   - `/mobfun <grazer|sheep|reforest|lamplighter|florist|golem|snowgolem|necromancer|pilgrim|evilpig> <on|off|toggle>` — flip one showcase without touching the others. `evilpig` additionally registers/unregisters the custom mob definition and removes its live mobs.
   - `/mobfun spawn <id> [force]` — spawn the showcase mob 2 blocks in front of you, name it, and immediately **force-fire** its actions: one forced pass with the odds gate bypassed, printing a per-action report (`FIRED`, `DECLINED` when the action's own checks refused, `CONDITION_FAILED` when a profile condition blocked it). `evilpig` spawns the real custom mob through `CustomMobApi` (identity, isolation and rig applied). With `force`, profile conditions are ignored too.
   - `/mobfun fire <id> [force]` — same forced pass on the **nearest** mob of that type within 10 blocks.
   - `/mobfun despawn` — remove every test mob spawned via `/mobfun spawn` (also removed on disable). Golems, minions and journeys launched into the world are ordinary world state — kill/capture as usual.

### Scenario recipes

- **Raid test**: `/mobfun spawn necromancer` beside a villager → a biome-matched, terrain-fitting mixed horde charges as one crew; the witch hops back behind it. Spawn a golem guardian nearby (`/mobfun spawn golem`) → the threatened villagers build a golem block by block; the golem charges, the witch potions and retreats. Watch, don't let either side die, or jump in.
- **Journey test**: `/mobfun spawn pilgrim` a few times in the open (or let natural villagers far from villages do it) → they rally into one crew and march to the nearest village (`/mobfun status` shows each pilgrim's target, distance, and march mode; debug logging names every search failure); at dusk they halt and the leader lights **one campfire with a row of beds, one per crew member**; at dawn they resume; arrival celebrates.
- **Horseman**: villagers near a witch for several summons → roughly every fifth raid, a tamed skeleton horse with an archer in the saddle joins the charge.
- **Evil Pig test**: `/mobfun spawn evilpig` in the open → the pig looks like a pig (with this module's texture) but does nothing a pig does: no wandering, no breeding, no panic. Approach it and it acquires you, paths to strike reach and hits for 4 with knockback every second; stand behind a wall and it does not hit through it. `/mobfun evilpig off` removes the definition and every live Evil Pig.
- The guardian profiles ride the `villager` type together - when both golem builders are enabled they compete for the same evaluation (registration order wins). Builders tagged by a successful build skip pilgrim journeys and keep their posts.

## Module API quick reference

```java
PluginContext context = mineplusPlugin.getPluginContext();
MobBehaviorApi api = context.mobBehaviorApi();
ParticleFx fx = context.particleFx();          // shared budgeted particles + Sounds

api.register(this, MobProfile.builder(EntityType.VILLAGER)
        .action(new GolemSummonAction(this, api, fx, "summon_iron_golem", "Summoned Iron Guardian",
                Material.IRON_BLOCK, EntityType.IRON_GOLEM, IronGolem.class,
                Sound.BLOCK_METAL_PLACE, Sound.ENTITY_IRON_GOLEM_REPAIR), 30.0)
        .condition(BehaviorConditions.inDanger(8.0))   // module-side gate on core conditions
        .build());

// A long-range scenario is the Core's bundled journey action; content is callbacks only.
api.register(this, MobProfile.builder(EntityType.VILLAGER)
        .action(JourneyToStructureAction.builder()
                .id("village_journey").structure("village").crewName("pilgrims")
                .searchRadiusChunks(64).arrivalDistance(24).cohesionRadius(8).recruitRadius(16)
                .marchLeg(24).gatherRadius(6)
                .callbacks(new PilgrimCamp(this, fx))
                .build(), 2.0)
        .condition(BehaviorConditions.notBuilder(this))
        .build());

api.unregister(this, profile);                    // per-profile toggle
api.unregisterAll(this);                          // module shutdown

// A custom mob is a new mob, registered once and spawned by id.
CustomMobApi customMobs = context.customMobs();
customMobs.register(this, CustomMobDefinition.builder("mobfun:evil_pig", EntityType.PIG,
                CustomMobModel.vanilla(EntityType.PIG, texturePng))
        .displayName("Evil Pig").showName(true)
        .attributes(CustomMobAttributes.builder().maxHealth(12).attackDamage(4).build())
        .behavior(profile -> profile
                .priority(50)
                .action(AttackNearestAction.builder().damage(4).cooldownTicks(20).build(), 1.0))
        .build());
customMobs.spawn(location, "mobfun:evil_pig");    // identity + isolation + rig applied by the Core
customMobs.unregisterAll(this);                   // removes definitions, profiles and live mobs

// Direct testing: odds-bypassed firing pass with per-action diagnostics
BehaviorFireResult result = api.forceFire(this, entity, false);  // true = ignore conditions
result.attempts();                                // (actionId, FIRED|DECLINED|CONDITION_FAILED)
api.journeySnapshot(12);                          // live marches: target, leg, distance, crew
api.crewSnapshot(12);                             // crews: leader, members, cohesion, goal
api.activeActivity(entity);                       // which scenario (if any) holds this mob
```

Custom behavior = implement `BehaviorAction` (`id()` + `execute(BehaviorContext)`) — see this module's `PlantFlowerAction` (a small FX-and-sound action), `GolemSummonAction` (a staged build under one `openEditSession` grant, plus PDC cooldown and golem cap) and `SummonMinionHordeAction` (a leashed, terrain-adaptive raid under the run's spawn budget) for the module-side pattern. Long-range movement does **not** need a custom action: `JourneyToStructureAction` already owns structure lookup, ground normalisation, leg-by-leg walking, stall recovery, crew cohesion and night rest, so a module supplies only the scenario's content through its `Callbacks` (`PilgrimCamp`). Situational gates like `inDanger`/`threatOrPrey` (`BehaviorConditions`) compose the Core's `BehaviorContext.Conditions` (`belowHealth`, `nearbyEntity`, `visibleEntity`, `adultOnly`, `maxBlockLight`) rather than re-scanning entities themselves.
