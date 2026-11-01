# MobFun

A **Mob behavior showcase** module for the [Mineplus Core](https://github.com/Tariux/MinePlus) — the reference example for the Core's `MOB_BEHAVIOR` subsystem (`com.mineplus.infrastructure.mob`).

## What it shows

Nine vanilla-mob showcases driven by the Core's **mob behavior engine** — a budgeted, distance-gated scheduler that executes interval-gated world actions around moving mobs (the MinePlus evolution of the Regrowth reference project's per-tick AI event handling) — plus three **custom mobs**, which are new mobs rather than behaviors on a vanilla one. The custom mobs show the two minds a definition can have: the Evil Pig is steered by a Core profile, while the Villager Soldier and the Woodland Trickster keep the AI of the base type they wear (an iron golem and a zombie), so the game engine moves, attacks and kills them and the plugin only rigs and dresses them:

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
| **Evil Pig** | `minecraft:pig` + custom id `mobfun:evil_pig` | `attack_nearest` (1s) | **The texture-extended custom mob** — not a vanilla pig with extra behavior but a separate mob: the vanilla pig model with this module's own texture (`mobs/evil_pig.png`, embedded in the jar), its own attributes, its own behavior profile, and vanilla AI cleared by the Core on spawn and every chunk restore. It is invisible to vanilla pig profiles and never matches one; identity, AI isolation and persistence are all Core-owned (`context.customMobs()`); the restkin travels as a data-driven entity variant on 1.21.5+ and as a part rig compiled from the same PNG on older servers |
| **Villager Soldier** | `minecraft:iron_golem` + custom id `mobfun:villager_soldier` | none — the game engine owns its mind | **The engine-owned authored-model custom mob** — a Blockbench soldier (`mobs/soldier_villager.bbmodel`, 64×64 texture) handed to the Core as a `MobRenderSpec` of type `BBMODEL`; `BbModelParser` reads the outliner as the bone tree and `MobAnimator` binds the file's own clips by name, so the head, torso, both arms and both legs are posed from the model's own walk/idle animation, no part authoring in code. `vanillaAi()` keeps the **iron golem's** goals: the server pathfinds it, picks its targets, walks it, swings it and kills it, and no behavior profile is registered at all, so a hundred soldiers cost the plugin nothing beyond ordinary golem AI. It guards villages and hunts monsters exactly as a golem does; the plugin only dresses it up, poses its rig from the movement the engine produced and plays the attack clip when the golem's own swing lands a hit. `render.hitbox(0.7, 2.1)` keeps the silhouette honest and the base stays hittable and pushable — it is made invisible, not untracked |
| **Woodland Trickster** | `minecraft:zombie` + custom id `mobfun:woodland_trickster` | none — the game engine owns its mind | **The second engine-owned mob** — `mobs/woodland_trickster.bbmodel` on a **zombie** base with a zombie-shaped attribute template (health 30, speed 0.32, damage 4, armor 2), so it hunts players and villagers, chases, recoils and dies like a real zombie with no plugin actions involved. It adds a one-shot `animation.attack` clip: the Core plays it the moment the zombie's own melee damage lands, over the walk/idle clip the rig is already blending by measured speed. A reflective `setShouldBurnInDay(false)` probe (fire-resistance fallback on Spigot) keeps it daylight-safe |

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
   - `/mobfun <grazer|sheep|reforest|lamplighter|florist|golem|snowgolem|necromancer|pilgrim|evilpig|soldier|trickster> <on|off|toggle>` — flip one showcase without touching the others. The three custom mob ids additionally register/unregister the definition and remove its live mobs.
   - `/mobfun spawn <id> [force]` — spawn the showcase mob 2 blocks in front of you and name it. For a Core-steered showcase it then **force-fires** its actions: one forced pass with the odds gate bypassed, printing a per-action report (`FIRED`, `DECLINED` when the action's own checks refused, `CONDITION_FAILED` when a profile condition blocked it); with `force`, profile conditions are ignored too. Every custom mob id spawns the real custom mob through `CustomMobApi` (identity, mind and rig applied) — and for an **engine-owned** one (`soldier`, `trickster`) there is nothing to force, so the command says so instead: the server's own AI is running that mob, and you watch it behave.
   - `/mobfun fire <id> [force]` — same forced pass on the **nearest** mob of that type within 10 blocks; on an engine-owned mob it prints where to inspect the visual side instead (`/mobfun rig <id>`), because that mob has no plugin actions to fire.
   - `/mobfun rig <custom-id> [attack | yaw-offset <deg|auto>]` — inspect one custom mob definition: which render spec it carries, whether the model and texture compiled, the yaw offset the rig walks with, and per live mob its mind (engine-owned goals vs Core isolation verdict), rig heading vs the server's own body yaw, current animation state and whether the base entity is hidden. `… attack` plays the attack clip on the nearest one; `… yaw-offset <deg|auto>` re-probes the facing live without a restart.
   - `/mobfun pack [push]` — report the compiled resource pack that carries the mob rig assets (and push it to online players).
   - `/mobfun despawn` — remove every test mob spawned via `/mobfun spawn` (also removed on disable). Golems, minions and journeys launched into the world are ordinary world state — kill/capture as usual.

### Scenario recipes

- **Raid test**: `/mobfun spawn necromancer` beside a villager → a biome-matched, terrain-fitting mixed horde charges as one crew; the witch hops back behind it. Spawn a golem guardian nearby (`/mobfun spawn golem`) → the threatened villagers build a golem block by block; the golem charges, the witch potions and retreats. Watch, don't let either side die, or jump in.
- **Journey test**: `/mobfun spawn pilgrim` a few times in the open (or let natural villagers far from villages do it) → they rally into one crew and march to the nearest village (`/mobfun status` shows each pilgrim's target, distance, and march mode; debug logging names every search failure); at dusk they halt and the leader lights **one campfire with a row of beds, one per crew member**; at dawn they resume; arrival celebrates.
- **Horseman**: villagers near a witch for several summons → roughly every fifth raid, a tamed skeleton horse with an archer in the saddle joins the charge.
- **Evil Pig test**: `/mobfun spawn evilpig` in the open → the pig looks like a pig (with this module's texture) but does nothing a pig does: no wandering, no breeding, no panic. Approach it and it acquires you, paths to strike reach and hits for 4 with knockback every second; stand behind a wall and it does not hit through it. `/mobfun evilpig off` removes the definition and every live Evil Pig.
- **Villager Soldier test**: `/mobfun spawn soldier` in the open → an armored spearman walks and looks around under the **golem's own AI** (this is not a plugin action: nothing to force, `/mobfun fire soldier` says so). Spawn a zombie or a creeper beside it: it turns, closes on its own, swings — the attack clip lands with the golem's own hit — and kills it; players and villagers stay off its target list because a golem does. Hit it: it takes damage, flashes red particles, gets knocked back and you can body-block it, because the base entity is only invisible, never removed. Kill it: the death plays. `/mobfun soldier off` removes the definition and every live soldier. Spawn a crowd of them and check the whole server's cost with `/mineplus mob cost`.
- **Trickster test**: `/mobfun spawn trickster` a few blocks away → a real zombie wearing a trickster model: it spots you, groans, walks to you with its leg/arm swing playing and stops breathing-idle the moment it moves, hits you for its own melee damage and burns in nothing at noon (the module asks the server to keep it daylight-safe and falls back to fire resistance where that API is absent). It dies to your sword with the vanilla collapse, and if it faces the wrong way down its own path, `/mobfun rig trickster yaw-offset 180` flips the rig without a reload (`auto` measures it from the mob's movement).
- **Facing check for any rigged mob**: `/mobfun rig <id>` prints rig heading vs body yaw per live mob. Heading tracks the movement direction the model was authored to walk in; the offset is the one knob for a model whose front was authored on a different axis.
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
