# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Core engine (`Mineplus`) — Composed visual impact units

#### Added

- **`VisualUnitService` + `VISUAL_UNITS`** — `context.visualUnits()` admits an
  ordered unit of components (`ParticleBurst`, `SoundCue`, `LightFlash`,
  `DecalDisplay`) with one lifetime, priority and anchor; one task advances
  them under `MAX_ACTIVE` / `MAX_SPAWN_PER_TICK` / `MAX_MS_PER_TICK`, dropping
  the lowest-priority unit first, routing particles and sounds through
  `ParticleFx`/`Sounds`, and returning every pooled decal display on expiry,
  drop and shutdown (`stats().liveDecals()` makes a leak visible). Block
  impacts in `Ballistics` use `impact(Location, ImpactProfile)` when enabled;
  disabled by default, where the service has no task, allocates nothing and the
  existing per-effect path is unchanged.

### Core engine (`Mineplus`) — Optional inbound input fast path

#### Added

- **`INPUT.FAST_PATH`** — when enabled, `HeldItemInput` installs one Netty
  handler (`mineplus_input`) per player that captures swing/use/interact
  packets at network arrival into a bounded queue and drains them at tick start
  through the same `TriggerState` fold, with a short per-gesture delivery claim
  so the Bukkit event that follows never fires the click twice; the handler is
  forwarded untouched, removed on quit/disable, and any failure logs once and
  permanently falls back to the event path. Off by default: no reflection, no
  channel access, no task.

### Core engine (`Mineplus`) — Deferred world-edit queue

#### Added

- **`DeferredEditQueue` + `WORLD_EDITS`** — `context.deferredEdits()` accepts
  per-world edit batches (HIGH/LOW FIFO, last-wins coalescing) and applies them
  on the main thread across ticks under a wall-clock (`MAX_MS_PER_TICK`) and
  block (`MAX_BLOCKS_PER_TICK`) budget, skipping unloaded chunks instead of
  forcing loads and reporting depth through `PerfRegistry`; `ENABLED: false`
  (default) applies every submission immediately on the main thread exactly as
  before, and shutdown flushes what is pending under a hard cap.

### Core engine (`Mineplus`) — Pack appearance modes and asset validation

#### Added

- **`PACK.APPEARANCE_MODE` + `PACK.VALIDATE_ASSETS`** — item definitions can
  present state through the modern `item_model` component (`AUTO`/`ITEM_MODEL`),
  a float `range_dispatch`, a `minecraft:select` on the
  `custom_model_data` string list (with an automatic range fallback when the
  server cannot write strings) or legacy predicates, while `ItemFrameWriter`
  follows the resolved channel with no module change; every compile now reports
  soft content budgets (bones, cubes, animations, source size, texture edges,
  unresolved textures, duplicate ids, entry collisions) through `DebugLogger`
  and `/mineplus pack status`, and a bad asset only warns — the pack still
  builds. `AUTO` keeps the previous artifact byte-identical.

### Core engine (`Mineplus`) — Performance telemetry

#### Added

- **`PerfRegistry` + `/mineplus perf`** — one context-owned, allocation-free
  telemetry registry (LongAdder counters, gauge atomics, fixed per-tick ring
  rows) fed by the display transport, packet optimizer, entity pool, animation
  runtime, texel baker, pack compiler and mob engine; the command prints a
  compact table, resets counters and dumps a JSON snapshot to
  `plugins/Mineplus/perf/`. The `PERFORMANCE.ADAPTIVE` valve raises the
  animation tick interval (up to 4 ticks) and halves the bundle weight limit
  when the pose slice stays over `POSE_BUDGET_MS`, restoring stepwise once the
  budget is met again.

### Core engine (`Mineplus`) — Adaptive pose pipeline

#### Changed

- **Off-tick pose evaluation with a bounded apply slice** — `ANIMATION.PIPELINE`
  picks the runtime's evaluation strategy: `TICK` keeps the old
  evaluate-and-apply-in-tick path, `ASYNC_COMPUTE` (default) samples clips and
  composes bone matrices on one bounded `Mineplus-Pose` thread while the main
  thread applies finished frames under `ANIMATION.MAX_APPLY_PER_TICK` (512) with
  ordered carry-over, and `ASYNC_SEND` adds a single shared flush pass.
  Controllers still advance on the main thread, a generation guard discards
  frames superseded by a stop/reload/settings change, a worker exception logs
  once and permanently degrades the session to `TICK`, the worker thread only
  exists while animated instances do, and `/mineplus perf` reports applied/
  dropped frames, the pose slice cost and a degraded flag.

### Core engine (`Mineplus`) — Weighted bundling and bounded sends

#### Changed

- **Weight-aware bundles and a per-tick send budget** — `PacketOptimizer` fills
  each bundle greedily against both `NETWORK.BUNDLE_LIMIT` and a new
  `NETWORK.BUNDLE_WEIGHT_LIMIT` (4096), where `NmsAdapter.packetWeight` counts a
  metadata/equipment packet's list entries instead of a flat 1 (resolved once by
  `ReflectionNmsAdapter`, never throwing). Flushing now honours
  `NETWORK.FLUSH_BUDGET_MS` (2 ms): players that do not fit keep their queue in
  arrival order for the next tick, at least one batch always goes out, and
  `PacketOptimizer.Stats` exposes packets, average/max weight and deferred
  players. Defaults preserve the previous one-bundle-per-player behaviour.

### Core engine (`Mineplus`) — Per-observer render state and equipment stances

#### Added

- **Per-viewer render state and an observer-only stance channel** — the display
  transport now stores a `RenderViewState` per observer instead of a bare LOD
  tier (`context`-side `viewerState(instanceId, viewerId)`; `forcedVisible` pins a
  model instead of hiding it, plus brightness/equipment/stance/animation
  override slots). `context.stances()` (`StanceService`, gated by
  `DISPLAY_TRANSPORT.STANCES.ENABLED`) lets one observer see an entity in a
  different stance through bundled `NmsAdapter.equipmentPacket` sends — two
  observers can hold different stances of the same entity, `clear`/`clearAll`
  restore the real equipment, nothing touches the target's inventory, the
  observer's own first-person hand is skipped, and the service registers no
  listeners and sends no packets when disabled or when the packet transport is
  not running.

### Core engine (`Mineplus`) — Animation priority stack and automatic states

#### Added

- **Per-bone animation priorities, crossfades and automatic state selection** —
  controllers now carry a priority plus fade-in/fade-out windows
  (`playAnimation(id, clip, playback, priority[, fadeIn, fadeOut])`). For every
  bone only the highest-priority level contributes, equal priorities still
  compose additively (default `0` behaves exactly as before), and a fading
  winner blends the next lower level in and back out, so a reload layered over a
  walk returns to the same walk phase instead of restarting it. With
  `ANIMATION.AUTO_STATE: true` the runtime derives `attack` > `airborne` >
  `swim` > `walk` > `idle` from the model's owning entity (walk phase from limb
  swing), switching `ANIMATION.TRANSITION` (`AFTER_CURRENT` by default) with
  `ANIMATION.TRANSITION_TICKS` crossfades at priority `-1` so explicit
  `AnimationApi` calls always win; `clearAutoState(uuid)` hands full control
  back. Controller snapshots are reused per instance, so steady-state ticks no
  longer allocate.

### Core engine (`Mineplus`) — Pack bone-rig backend

#### Added

- **`RIGS` + `renderKind: bones` (`renderMode: pack_bones`/`hybrid_bones`)** —
  `context.packApi().registerBoneRig(PackBoneRigDefinition)` declares a content
  level whose geometry renders as one `ItemDisplay` per bone instead of one per
  cube: `BoneRigAssetWriter` emits one item model + item definition per bone
  from the model's outliner hierarchy (`BoneRigDefinition` keeps bones in
  preorder, empty groups move children, `muzzle`/`attachment`/`hitbox` tags are
  understood), and `BoneRigRenderer` poses every bone from its parent matrix
  with client-side interpolation (`RIGS.INTERPOLATION_TICKS`).
  `RIGS.MAX_DISPLAYS_PER_INSTANCE` caps one rig's display count and
  `RIGS.HITBOX_BONES` opts into invisible hitbox displays; placement, collision
  and persistence stay with the ordinary multiblock lifecycle. `RIGS.ENABLED:
  false` (default) keeps every pack level on its per-cube path and leaves the
  virtual engine byte-identical.

### Core engine (`Mineplus`) — Custom mobs

#### Added

- **Custom mob API** — `context.customMobs()` (`CustomMobApi`) registers new
  mobs without touching vanilla ones: a `CustomMobDefinition` (base `Mob` type,
  `CustomMobModel`, `CustomMobAttributes`, display options and a behavior
  consumer) is owner-scoped, idempotent, and spawns through
  `spawn(location, id)`. Definitions, live mobs, chunk restore, death cleanup
  and pack assets are all owned by the Core.
- **Identity dispatch in the behavior engine** — `MobProfile.builder(type,
  customId)` registers a profile under a custom id; `MobIdentityResolver`
  (implemented by the custom-mob manager) routes evaluation through
  `profilesByCustomId`, so a custom mob never matches a vanilla-type profile and
  vanilla mobs never see custom actions. `forceFire` resolves identity the same
  way.
- **Vanilla-AI isolation** — spawn and every chunk restore clear the entity's
  goal/target selectors while leaving AI enabled, so all behavior comes from the
  profile and navigation still uses the mob's own vanilla walk. A definition
  with no behavior gets an idle profile; unknown tagged ids stay isolated and are
  removed on chunk load once any definition exists.
- **Texture-extended and fully custom models** — `CustomMobModel.vanilla(type,
  png)` reuses a published vanilla model with a replacement texture;
  `builderFor(type, png)` / `builder(w, h, png)` author `MobPart`s (absolute
  pivots, `Role`-driven head/leg posing) and `MobBox`es. `MobRigAssetWriter`
  compiles the model into pack assets (texture, per-part item model + item
  definition with vanilla box-UV windows), and `MobRigRenderer` renders each part
  as an `ItemDisplay` under a lazy, self-retiring task; without the pack the base
  entity stays visible and the mob remains fully playable.
- **`AttackNearestAction`** — Core hostile action: nearest eligible
  player/villager, sticky target, throttled re-path, line-of-sight strike
  through `DamageTransactions.apply`, knockback and sounds.
- **`CUSTOM_MOBS` settings** — `ENABLED`, `RENDERING`, `RENDER_INTERVAL_TICKS`,
  `RENDER_INTERPOLATION_TICKS`, `RENDER_DISTANCE`, `MAX_RIGGED_PER_WORLD`,
  `REMOVE_UNKNOWN_ON_LOAD`.

#### Fixed

- **Villager-based custom mobs were never isolated and could not be steered** —
  the reflective entity plan was built from the declared return type of
  `getHandle()`, which CraftBukkit declares as `Entity` for `CraftVillager`.
  No navigation, look control, Brain or goal selectors were found, so the
  villager kept its vanilla brain and every walk request failed. The plan now
  resolves against the live handle's runtime class; all three showcases report
  `goals+brain` isolation and accept paths after their spawn fall completes.
- **Mobs could look like they walked sideways or backwards** — with vanilla
  goals cleared nothing rotated the body. `MobNavigator` now points the vanilla
  look control at the destination on every accepted request, so body yaw
  follows the path and the rig's yaw frame follows body yaw. The rig's head
  pitch also falls back to the entity's own look pitch when the obfuscated NMS
  accessor is unavailable.
- **Oversized rig parts rendered as missing-texture cubes** — `MobRigAssetWriter`
  now keeps every element inside the client's −16..32 px item-model window:
  a small overshoot is shifted, a part spanning more than 48 px is split into
  deterministic pieces, and `BoneRigRenderer` compensates each piece's offset in
  the pose pass. The soldier's spear is two pieces instead of one corrupt cube.
- **Attack cadence** — `AttackNearestAction` keeps its chase state between
  evaluations (re-path every 10 ticks or when the target moves 1.5 blocks, stop
  at reach) instead of standing still between odds-gated fires; strikes are
  attributed through `DamageTransactions`, and a landed melee hit plays the
  rig's `animation.attack` overlay.
- **Walk clips barely moved while a mob walked** — the pose pass compared a
  velocity in blocks per tick with a walk reference in blocks per second, so a
  mob moving at 2 blocks/s produced a weight of 0.1 and the amplitude and clip
  tempo never left their minimums. The velocity is converted once to blocks per
  second, the stride phase stays per tick, and amplitude now reaches its ceiling
  at the reference speed. Measured on the final build: soldier 2.09 b/s with
  weight 1.00, trickster 2.37 b/s with weight 1.00, evil pig 1.81 b/s with
  weight 0.90, phase advancing every pass; `playAttack` returns `true` only for
  the trickster (the only authored attack clip) and its overlay runs one second
  then fades back to walk/idle.

### Modules (`mineplus-mobfun`)

#### Added

- **Evil Pig** — the first custom mob: `mobfun:evil_pig`, the vanilla pig model
  with the module's embedded texture (`mobs/evil_pig.png`), attribute overrides
  and a single `AttackNearestAction`. Exposed through the existing test bench
  (`/mobfun evilpig on|off`, `/mobfun spawn evilpig`, `/mobfun fire evilpig
  [force]`); the showcase list is now polymorphic (`MobFunShowcaseEntry`) so
  vanilla showcases and custom mobs share the command surface.

### Core engine (`Mineplus`) — Unified Rendering Engine

#### Added

- **Unified render vocabulary and router** — `RenderMode` (`virtual`,
  `pack_item`, `pack_block`, `hybrid_item`, `hybrid_block`) names both the
  subsystem and the primitive in one value; `RenderPlan`, `RenderPolicy`, and
  `RenderRouter` make routing a single choke point. `RenderMode.of(...)` maps the
  existing `RenderBackend` + `RenderKind` pair (still fully supported), and
  multiblock JSON accepts a `renderMode` key. Access the router (policy +
  telemetry) through the new `PluginContext.renderRouter()`.
- **`/mineplus render stats` / `render reset`** — per-mode route counts, degraded
  and unavailable route counts, pack attach failures, and the active policy.
  New `RENDERING.POLICY` config section (`ALLOW_DEGRADE_TO_VIRTUAL`,
  `LOG_ROUTING`, `TRACK_TELEMETRY`).
- **Texel plate-count reductions** — uniform-area coalescing
  (`UNIFORM_AREA_DETECTION`, `UNIFORM_AREA_MIN_SIZE`,
  `UNIFORM_AREA_OKLAB_THRESHOLD`), area-scaled adaptive budgeting
  (`ADAPTIVE_BUDGETING`), single-dominant-color degradation for over-budget faces
  (`BUDGET_FALLBACK_TO_SIMPLE_COLOR`), and identical-face bake reuse
  (`REUSE_SYMMETRIC_FACES`). `/mineplus model info` reports `simplifiedFallbacks`
  and `reusedFaceBakes`.
- **`PACK.LIGHTING`** — `AUTO` (default) lights non-emissive pack models with
  natural world light and applies the model's maximum `light_emission` only to
  emissive models; `NATURAL` / `EMISSIVE` / `FULLBRIGHT` select the other
  behaviors.

#### Changed

- **Pack model lighting** — pack display entities no longer force full
  brightness `(15, 15)`. Under the default `AUTO` policy, non-emissive pack
  models are lit by the world (previously they glowed everywhere); set
  `PACK.LIGHTING: FULLBRIGHT` to restore the old look.
- **Occlusion tests** in texel baking gain a per-cube bounding-box pre-filter
  before the oriented-box test; results are unchanged, only faster.

### Core engine (`Mineplus`) — Combat & mob behavior services

#### Added

- **Core projectile simulation** — `infrastructure.combat.Ballistics` steps every
  live round once per `BALLISTICS.INTERVAL_TICKS`, applying spread, gravity,
  drag, distance falloff, penetration, ricochet, headshot multipliers and the
  profile's own tracer/impact FX through `ParticleFx`; over
  `MAX_ACTIVE_ROUNDS` a shot degrades to a single instant trace (same hit, same
  damage) instead of queueing, counted in `degradedShots()`. Authored through
  `BallisticProfile.builder()` (tolerant particle and sound names) and observed
  through `ProjectileHandler`, whose default methods make a plain gun a
  zero-callback call. Reachable from every context: `PluginContext.ballistics()`.
- **`DamageTransactions`** — the damage-attribution seam for module-computed
  hits (melee, throwables): `apply(victim, amount, attacker)` plus
  `inProgress()`/`isTransaction(event)` so a module's own listener never
  double-counts or zeroes the hit it just made.
- **`HeldItemInput`** — one shared held-item listener set for the whole server:
  modules attach a `HeldItemHandler` (`owns`, `owns(stack, holder)`, `onPress`,
  `onRelease`, `onSwapHands`, `suppressVanillaMelee`, `freezeVanillaInteractions`)
  instead of registering their own `PlayerInteractEvent` handlers. It routes
  presses across both hands, swallows the vanilla swing, and freezes
  place/mine/trade/ride while an owned item is held — per player, so a disabled
  world or opt-out leaves the carrier fully vanilla. `PluginContext.heldItemInput()`.
- **`ItemMagazine` / `ItemState`** — countable item state (rounds) held in memory
  while the stack is in hand and flushed as one silent PDC write when it leaves,
  keyed by a per-stack identity tag so a magazine survives slot re-ordering,
  off-hand hops and re-logs. Rewriting a held stack per shot is what makes the
  client replay the equip animation and receive slot spam 20×/s; the deferred
  model removes that entirely.
- **`MobEditSession` spawn budget** — the world-work grant now carries entity
  spawns as well as block edits (`spawnMob`, `hasSpawnBudget`,
  `openEditSession(editBudget, spawnBudget)`), both clamped to
  `MOB_BEHAVIOR.MAX_BLOCK_EDITS_PER_RUN` / `MAX_ENTITY_SPAWNS_PER_RUN`, so a
  staged build that outlives its evaluation cannot write or populate outside the
  subsystem's limits.
- **Generic sensing conditions** — `BehaviorContext.Conditions.belowHealth`,
  `nearbyEntity(radius, matcher)` and `visibleEntity(radius, matcher)`, so
  module gates compose Core primitives instead of re-scanning entity lists.
- **`WorldTimes`** — one owner for the world's three clocks. `timeOfDay`,
  `isNight`, `isDay`, `dayIndex` and `nightIndex` answer "is it night" the way
  the sky does, so no scenario has to guess whether `getTime()`,
  `getFullTime()` or `getGameTime()` is the clock it wanted.
- **Crew formation points** — `MobCrewService` now owns a crew's *formation
  point* (`rally`, `setRally`) and hands each member the one bounded step it
  should take toward it (`rejoinWaypoint`), with `spread` measuring how far
  the group is scattered. `BehaviorContext` exposes them as `crewRally`,
  `setCrewRally`, `crewRejoin` and `crewSpread` (replacing `crewFocus`).
  A crew now walks as one: the formation point advances along the route only
  once everybody has reached it, and no member is ever handed a target beyond
  its own path range — the two rules that turn "a column of villagers a
  hundred blocks apart" into a march.
- **A shared trigger state** — `HeldItemInput.trigger(player)` hands a module
  the Core's own view of the activation gesture: `held()` (the button is down)
  and `freshPress()` (this activation is a new press, not the client repeating
  the one it already sent). The Core resolves the press stream once, for every
  module: a modern client answers one physical click with up to two packets —
  a click on a block sends "use item on block" and, when the block did not
  consume the interaction, follows it with "use item in hand" — and the two
  must not read as two clicks. A press inside `CLICK_WINDOW_MILLIS` (150 ms) of
  the previous one continues the same click; a press outside it is a new
  gesture. The window is deliberately narrow: it has to cover the round trip
  between the two packets and nothing more, or a fast, deliberate second click
  is swallowed (a 280 rpm weapon needs 214 ms between rounds).

#### Changed

- **Journey night rest is cohesion-first** — `JourneyToStructureAction` walks
  stragglers back to the crew focus *before* halting the crew for the night, and
  `Callbacks.onNight` now returns `boolean`: returning `false` (e.g. the edit
  budget refused a write) retries inside the same night instead of burning it.
- **Activity claims carry a priority** — a claim remembers the priority of the
  profile that took it, and a strictly higher-priority profile whose conditions
  pass may take the entity over (a guardian answers an attack on a villager
  mid-pilgrimage). The challenger still plays the odds gate and keeps the mob
  only by claiming it, so a higher priority never steals a mob on its own, and
  a lower-priority scenario can never interrupt one.
- **Profiles dispatch by priority first** — `MobProfile.priority()` orders the
  profiles of one entity type (higher first, registration order breaks ties),
  and the engine re-sorts on every registration.
- **`JourneyRunner` legs stay inside the path range** — the default leg is 32
  blocks (vanilla navigation resolves roughly three dozen) and the runner's
  internal cooldowns use `World#getGameTime()`, which `/time set` cannot stall.
- **A crew member another scenario took over is not part of the formation** —
  `crewSpread` ignores a member another action has claimed, so a rescued
  villager cannot freeze the march it was walking.
- **A spark channel on the shot profile** — `BallisticProfile.sparkParticle`
  (default `SMALL_FLAME`) adds the burning embers a shot throws off: the muzzle
  gets a spark burst beside the flash, and an impact gets one beside the dust.
  The gun module exposes it per weapon as `fx.spark`.
- **A journey member that fell behind is never lost** — a crew member that
  falls more than `detachRadius` (64 blocks) behind the formation point stops
  chasing it and walks the goal on its own; beyond that distance the formation
  is a guide the member cannot follow, and a pilgrim that keeps chasing it is a
  pilgrim that never arrives. It rejoins the formation when it catches up.
- **A journey's stride is the caller's business** — `JourneyToStructureAction`
  takes a `JourneyRunner.Options` (speed, arrival distance, leg length, the
  repath/progress cooldowns and the stall tolerance), so a scenario that has
  to cross a wasteland in a hurry can march faster without re-implementing the
  runner.

### Core engine (`Mineplus`) — Pack item frames

#### Fixed

- **Framed items never changed pose** — a frame state is written into the
  stack's `custom_model_data`, and on 1.21.4+ the client reads the
  *component's float list*, not the legacy integer. `ItemFrameWriter` now
  writes both representations (the legacy integer for the legacy host-file
  predicates, the float list for the modern `range_dispatch`), and the
  accessor seam is probed against both spellings the API has used: `ItemMeta`
  declares the getter as `int getCustomModelData()` and the setter as
  `void setCustomModelData(Integer)`, and a lookup that asks for the boxed
  return type finds nothing and silently disables the whole pipeline. A server
  that refuses the write is reported once instead of leaving the item on its
  resting pose forever.
- **The pack assets carried a UTF-8 BOM** — the byte order mark is invisible in
  an editor and fatal to the client: the vanilla model loader rejects the file
  and the item renders its bare carrier. `PackAsset.fileBytes()` strips it from
  every asset it serves.
- **A frame could not be told from the state below it** — the generated
  `assets/<ns>/items/<id>.json` now lists the resting model as an explicit
  `range_dispatch` entry (and keeps it as the fallback), so the item shows its
  idle pose instead of the bare material when the stack carries no frame value.

### Modules (`mineplus-gun`, `mineplus-mobfun`)

#### Fixed

- **Gun shots had no feedback or damage** — the shipped module jar predated the
  `BulletSimulator` source, so the build never compiled the firing path: the shot
  consumed a round and updated the HUD while particles, impact and crosshair
  damage silently never ran. Firing is now the Core's `Ballistics`, the input
  seam is the Core's `HeldItemInput`, and ammo is a Core `ItemMagazine`.
- **Pilgrim villagers never reached a distant village** — the module's hand-rolled
  `JourneyAction` commanded movement through an `instanceof Pathfinder` cast that
  is never true for a villager entity, so nothing was ever pathed; and villagers
  camping at night were scattered because each halted where it stood. The
  showcase now runs on the Core's `JourneyToStructureAction` +
  `JourneyRunner`/`MobCrewService`, with the module keeping only the camp and
  celebration content (`PilgrimCamp`).
- **Scenario conflicts** — the four villager profiles (lamplighter, two golem
  builders, pilgrim) could steer the same mob at once. Journeys now hold an
  exclusive anchored activity claim, builders tag themselves so they are never
  marched away, and summoning scenarios are leashed to their own area.
- **The pilgrim camp was never raised** — the fire-site search accepted the
  leader's own cell (a formation point is air on solid ground, which is
  exactly what "buildable" means), found it first and then discarded it, so
  the crew spent every night without a campfire. The search now excludes the
  anchor, and the bed row counts only the crew's own beds (tagged through the
  bed block's persistent data) — a village's beds are no longer mistaken for
  the camp's, which had made the crew lay no beds at all.
- **Guardians could not hold their post** — a builder claimed its mob only
  after the column was finished, so a pilgrim walked the builder away from the
  site while it worked. The builder now claims the mob for the build, and each
  guardian kind keeps its own cooldown so the iron and snow builders no longer
  silence each other.
- **A reload that could load nothing** — the reserve is counted in nuggets
  while the magazine is counted in rounds, so a player holding fewer nuggets
  than one round costs started a reload that ended with an empty magazine, and
  the HUD's `{reserve}` did not match `{mag}`. The reserve is now counted in
  rounds everywhere, a reload that cannot supply one round never starts, and
  the HUD reads one unit.
- **One click fired the gun twice** — the Core's repeat detection was dead
  code (`lastWasRepeat` could never become true), so every activation packet
  read as a fresh press. On a modern client a right-click on a block produces
  two packets, so the weapon fired twice per click, a queued second shot fired
  right behind the first, and a reloading weapon clicked empty while it was
  loading. The Core now folds the two packets of one click into one press
  (see the trigger state above), and a melee swing answers a click once too.
- **A dry click during a reload** — pulling the trigger while the magazine was
  loading reported the weapon as EMPTY, although the magazine is empty
  precisely because the reload is running. A pull during a reload now neither
  fires nor clicks empty.
- **The magazine and the ammo supply were written to a copy** — an inventory
  accessor may hand out a detached stack, and a count written only into that
  copy is silently dropped: the magazine read as full again on the next open
  (so the weapon refused to reload as "already loaded") and the shooter paid
  for rounds he still carried. `ItemMagazine.flush` and the module's ammo
  supply now write the stack back into its slot.
- **A gun left in the inventory on its recoil frame** — the weapon rests on its
  own pose when it leaves the hand and when another gun takes its place, so a
  shooter carrying several guns never sees one of them frozen on its fire pose.
- **Two guns had no recoil at all** — the packs ship no shooting pose, and for
  the western pistol and the lever rifle the authored fire frame was a copy of
  the idle frame, so the shot played its sound and particles over a weapon that
  did not move. The recoil is now authored on top of the imported geometry: the
  pistol's barrel swings up around the frame, the lever rifle's receiver,
  barrel and front sight kick up around the grip.

#### Changed

- **More damage, more particles** — the arsenal was tuned up a little: the
  pistols 9/15 → 10/17, the rifles 11/14/19/20/22 → 13/16/22/23/25, the
  scatter guns 6/4.5 → 7/5 per pellet. Every weapon also gained a `spark`
  channel (`fx.spark`): the Core throws burning embers at the muzzle and at the
  impact, next to the flash, the barrel smoke and the dust.
- **The carrier note in the config was wrong** — it described the four-tick
  use-item repeat, which no modern client sends. The carrier still has to be
  neutral (a bow or a crossbow would put the client into its own "using item"
  state), but the reason is now the client's use state, not a repeat.
- **The necromancer never stopped calling** — the witch summoned on a 25 s
  cooldown with a five-minion cap, so an iron golem walked through the raid
  and killed her before the next wave. The horde is what buys her the time to
  run from a golem she cannot kill: the wave now comes every **3 s** (every
  **1 s** while a golem is swinging at her), three minions per wave, a horde
  cap of **8**, and the profile's own interval dropped from 12 s to 2 s so the
  engine evaluates her twice a second instead of twice a minute.
- **The pilgrims march instead of loitering** — the journey runs on a longer
  leg (32 blocks), recruits from twice the radius (24), gathers tighter (8) and
  walks on a faster stride (1.15×). A member that falls 64 blocks behind stops
  waiting for the formation and walks the village on its own, so one villager
  that cannot keep up no longer holds the whole crew in the wasteland.

#### Added

- Core-surface migration in both modules: `ParticleFx`/`Sounds` for feedback,
  `Conditions` for gates, `openEditSession`/`trySpawn` for world work,
  `journeySnapshot()`/`crewSnapshot()`/`activeActivity()` in `/mobfun status`.

### Texel devtool (`devtools/texel`)

#### Added

- **Texel brightness control** — an explicit `texelBrightness` override (0–15,
  or "meta / off" to let the model's `.meta.json` apply) in the settings panel;
  rides the same user-override-wins-over-meta mechanism as the other fields.
- **Bake history panel** — each successful bake is recorded (model, plate
  count, bake time, effective settings); clicking an entry restores its
  settings and re-bakes, making budget/brightness comparisons one click.
- **Per-face strategy counts in diagnostics** — FULL / CROP_HALF / TILE / TEXEL
  face tallies, mirroring `/mineplus model info`'s strategy reporting.
- **Frame camera button** — re-centers the viewport on the current model.
- **Model list auto-refresh** — added/renamed/deleted `.bbmodel` files in a
  watched root appear in the list without a page reload.

#### Fixed

- **Texture list parity with the bake** — the daemon's texture-path lookup now
  mirrors `TextureImageStore`'s resolution (root-folder lookup, `.mcmeta` and
  namespace stripping) instead of adjacent-only, so a texture that bakes via
  the root folder no longer shows "not found" in the reference viewer.
  `TextureImageStore` gained a read-only `rootFolderFile` accessor for this.

#### Performance

- **Shared wireframe material/geometry** — plate wireframes share one
  `LineBasicMaterial` + one `EdgesGeometry` per bake instead of allocating both
  per plate (hundreds of GPU-side allocations per bake on plate-heavy models).

### Core engine (`Mineplus`) — performance & runtime hardening

#### Added

- **HikariCP connection pooling for SQLite persistence** —
  `SqliteConnectionFactory` now serves connections from a single-connection
  HikariCP pool (WAL journal, `NORMAL` sync, `foreign_keys`, busy timeout as
  datasource properties) instead of opening a raw `DriverManager` connection
  per operation; the pool is closed on shutdown. HikariCP is declared as a
  plugin library (`libraries:` in `plugin.yml`) so the server's library loader
  provides it at runtime — fastutil and sqlite-jdbc already ship with the
  server. Bundled for compilation as `libs/HikariCP-5.1.0.jar` and
  `libs/fastutil-8.5.14.jar`.
- **Folia support for the animation runtime** — on Folia, `ModelAnimationManager`
  ticks through the `GlobalRegionScheduler`; on Paper/Spigot it keeps the
  standard scheduler. `stop()` now cancels the Folia task for real.
- **Viewer culling for animations** — instances with no player inside the
  transport's full-LOD range and looking roughly toward the model (~70° cone)
  skip their per-tick pose push entirely.
- **fastutil collections on the hot paths** — occupancy cells use
  `LongOpenHashSet` (packed 26/26/12-bit X/Z/Y keys, sign-extended on unpack,
  overflow-safe near world borders) and the entity pool's id index uses
  `Int2ObjectOpenHashMap`. Bundled as `libs/fastutil-8.5.14.jar`.

#### Fixed

- **Texel textures missing after load/reload** — instances restored or re-rendered
  immediately after startup or `/mineplus reload models` could spawn before
  their model's async bake finished, rendering without texel textures. Spawn
  paths now wait (bounded, 10 s) for the model's in-flight bake, so texel
  models always render with their bake — baking stays off-thread, but it can
  no longer race the spawn.

#### Changed

- **Texel palette: polished granite removed** — its speckled orange pattern is
  too conspicuous even at 1×1 (66 entries now).
- **Concrete powders are no longer stretchable** — only pure-flat concretes
  (and snow) merge into stretched rectangles; powders, terracottas and stones
  stay 1×1 texels so their grain never smears when scaled.
- **Plate thickness halved (1/512 → 1/1024 block)** — perpendicular plates
  meeting at a shared cube edge now interpenetrate by an imperceptible sliver
  instead of a visible overlap at surface intersections.
- **Texel plates are corner-disjoint** — every texel plate is inset in-plane by
  one plate thickness at each face edge, so perpendicular plates at a shared
  cube edge exactly touch instead of crossing. The fix no longer depends on the
  client honoring ultra-thin geometry: plates cannot interpenetrate even if the
  renderer clamps or quantizes sub-millimeter scales.
- **Texel baking is asynchronous** — `bakeTexelSurfaces` moved off the main
  thread onto a `CompletableFuture`; model registration and reload no longer
  stall on PNG decode + palette matching. Results are guarded by a bake
  generation counter so a bake superseded by a reload/settings change is
  discarded instead of landing stale.
- **Allocation-free occupancy SAT** — `GeometryOccupancyCalculator` reuses
  `ThreadLocal` scratch vectors and per-cube matrices in the rasterization
  loop; cube/world matrices are rewritten in place instead of reallocated.
- **`VirtualBlockManager` state maps are concurrent** — models, meta, source
  files, texel bakes, and active-block lookups use `ConcurrentHashMap` so async
  bakes and the main thread never contend.
- **Animation pose loop is allocation-free** — pose/delta scratch arrays are
  `ThreadLocal` (grown on demand for >256-bone models) instead of per-tick
  allocations.
- **NMS adapter uses `MethodHandle`s** — `ReflectionNmsAdapter` resolves every
  constructor/method once into cached `MethodHandle`s/`Constructor`s; hot paths
  only pay for `invoke`. CraftBukkit class resolution handles both versioned
  and unversioned (Paper 1.20.5+) package layouts, and constructor matching is
  strict on parameter types again.

#### Removed

- **Legacy `AABB` collision fill** — `COLLISION_MODE: AABB` no longer runs the
  whole-model bounding-box fill; the value is kept as a compatibility alias
  resolved through the same per-cube geometry rasterization as `GEOMETRY`.

## [1.4.0]

### Core engine (`Mineplus`) — voxel reconstruction removal

#### Removed

- **Voxel reconstruction pipeline** — the `infrastructure.virtual.voxel` package
  (`RenderStrategy`, `RenderStrategySelector`, `VoxelModelBake`,
  `VoxelRenderingSettings`, `VoxelSurfaceBaker`) is deleted in full. All
  models now unconditionally render through the cube / per-face plate / texel
  surface baking pipeline via `DisplayEmitter.emitCube(...)`; models that
  previously qualified for voxel reconstruction (non-animated, axis-aligned,
  lattice-snapped, texture-backed) keep the legacy pipeline unchanged.
- **`VOXEL_RENDERING` config section** — deleted from `settings.mp.yml`
  template and `ConfigManager`. The `voxelMode`/`maxVoxelDisplays` per-model
  `.meta.json` keys are no longer parsed by `ModelMeta`.
- **`RenderStrategy` / `RenderStrategySelector`** — removed from the `/mineplus
  model info` output and the texel devtool's diagnostics panel.

#### Renamed

- **`VoxelOccupancyCalculator`** → **`GeometryOccupancyCalculator`** — the
  collision cell rasterizer is unchanged in behavior (same `T(anchorOffset)·R·M`
  transform, same per-cube SAT, same cache); only the name is updated to reflect
  that it computes a collision occupancy grid, not a rendering voxel mesh.

## [1.3.0]

Rendering pipeline deep-dive: the texel and voxel bakers rebuilt for resource
efficiency and visual fidelity, plus the wine tasting flight showcase module
content (five texel-baked vinery bottles with `/wine flight`).

### Core engine (`Mineplus`) — texel surface baking

#### Added

- **Enclosed-transparency infill**: transparent texels flood-reached from the
  grid rim are genuine silhouette cutout and stay; enclosed transparent texels
  (atlas padding, anti-aliased speckle) are infilled with the majority palette
  entry of their opaque 4-neighbors — corner/edge holes that previously exposed
  the base display's dominant color disappear while real cutouts survive.
- **Partial-visibility corner rescue**: a texel whose plate center is buried in
  another cube but whose footprint straddles the occluder boundary (label-band
  overhangs) is rescued when any footprint corner probe is visible — boundary
  texels are never silently dropped.
- **Alpha-weighted texel accumulation**: supersamples contribute their color
  weighted by alpha, and a texel covered below half its footprint emits no
  plate — anti-aliased sprite rims no longer drag blended dark colors through
  the cutout threshold as halos.
- **Flipped-UV sampling**: reversed UV windows (`u1 > u2`) now sample mirrored,
  matching vanilla's interpretation, instead of clamping to the window's min
  edge.

#### Changed

- `TexelSampler` rewritten over a bulk-extracted ARGB raster
  (`TextureImageStore.raster()`): the window + in-plane-rotation affine is
  precomputed into six coefficients, and `sample()` is pure primitive
  arithmetic (no allocation, no per-call `BufferedImage.getRGB` color-model
  dispatch) — the per-texel hot loop of both bakers is garbage-free.
- `TexelPalette.match` caches packed-RGB results (bounded, self-clearing) —
  repeat colors collapse the 51-entry redmean scan to one array lookup.
- Occlusion probes reuse scratch vectors across the whole bake loop
  (previously one `Vector3f` per probe per texel).

### Core engine (`Mineplus`) — voxel reconstruction

#### Added

- **XZ greedy rectangle merging**: same-color, same-emission voxels merge into
  maximal XZ rectangles per Y level (the texel merger's algorithm applied to
  the voxel grid); a uniform N×N floor collapses from N runs to one display.
  Extreme-XZ models fall back to X-only runs under a merge-grid guard.

#### Fixed

- **Interior culling order dependence**: culling previously removed voxels
  while iterating the occupancy map, so removing one interior voxel un-occupied
  a face and let adjacent interior voxels escape culling (surfacing as white
  neutral voxels inside otherwise uniform models). Interiorhood is now decided
  against the full occupancy in one pass, then removed in a second.

#### Changed

- Per-cube geometry (inverse matrix, model-space AABB, candidate cell ranges)
  is precomputed once per bake and shared by both rasterization passes;
  axis-aligned cubes (the AUTO-eligible majority) resolve cell spans
  analytically from translation/scale with zero matrix transforms — the
  probed-cell loop allocates nothing per cell.
- `VoxelRun` carries XZ extents (`lengthX`/`widthZ`) instead of a +X length;
  spawn-side scale follows.

### Core engine (`Mineplus`) — spawn path

#### Changed

- `Display.Brightness` instances are cached per emission level instead of
  allocated per spawned display.

### Module content (`MineplusFun`)

#### Added

- **Wine tasting flight** (`com.mineplus.fun.wine`): five texel-baked vinery
  bottles (Strad, Stal, Red, Chenet, Solaris) converted from vanilla
  `java_block` JSON, each with its own 16×16 sprite and `texelMode: AUTO` meta
  (raised plate budgets). `/wine place [variant]|flight|remove|clear|status` —
  the flight lays all five out side by side for bake comparison. All wine metas
  pin `voxelMode: OFF` so the texel-plate showcase is never hijacked by the
  voxel AUTO strategy.

## [1.2.0]

**Blockbench animations now play on vanilla clients.** Clips, bones, and keyframes ride inside the `.bbmodel` — the core samples them server-side and the vanilla client interpolates to its own frame rate. No mods, no resource packs.

### Animation engine

- **Dual-input control:**
  - **Internal (data):** autoplay per multiblock level — `"animations": ["rotate_gear"]` in the level JSON (survives restarts and upgrades) — or per raw model via `.meta.json`.
  - **External (code):** `context.animationApi()` with selectors — trigger one clip, one bone, or the whole model:
    ```java
    anim.triggerAnimation(id, AnimationSelector.animation("recoil"));
    anim.triggerAnimation(id, AnimationSelector.bone("turret"));
    anim.setAnimationEnabled(id, AnimationSelector.bone("wheel"), false);
    ```
- **Bone hierarchy** — animating an outliner group moves all nested children; deltas compose down the tree with Blockbench Euler order. Concurrent clips blend additively.
- **Loop modes** — `once` (returns to rest), `loop`, `hold` (freezes the end frame).
- **Hooks** — `onAnimationStart` / `onAnimationComplete` on `MultiBlockHook`, exception-isolated.
- **Config** — new `ANIMATION` section in `settings.mp.yml` (`ENABLED`, `TICK_INTERVAL_TICKS`, `INTERPOLATION_TICKS`, `AUTOPLAY`), hot-reloadable.
- `/mineplus model info` now reports bones, clips, loop modes, and track counts.

### New reference feature: the Gear

`/gear place` a gear, power it with redstone, and it spins. Place gears next to each other and they chain-react as an interlocking train — phase-synced, and reachability-based, so a ring of gears never self-sustains after you cut the power.

### Module lifecycle refactor (MineplusFun)

- New `ModuleFeature` contract: features are a one-line list entry; bootstrap is exception-isolated per feature.
- **One** coordinated `reloadAll()` per module instead of one per feature — faster startup with every feature you add.

### Fixes

- **False "Insufficient space"** — a model that failed to parse reported an occupancy problem; load failures now say so and point at the server log.
- **Cannon view-clamp exception spam** — removed the impossible per-tick camera clamp (`Player#setRotation` throws on 1.21+); aiming stays bounded by the 60° fire-time cone.

<img width="1020" height="704" alt="image" src="https://github.com/user-attachments/assets/c484b8f9-e08c-45f9-831d-e9fd959f62e7" />

## [1.1.0]

Core engine hardening and a new module toolkit: the architecture pass that made
the engine faster under load, fault-isolated against misbehaving modules, and
removed the boilerplate every module previously duplicated.

### Core engine (`Mineplus`)

#### Added

- **Module toolkit** (`context.moduleSupport()`, `ModuleSupport`):
  - `installDefault(module, resource, target, overwrite)` — installs embedded
    jar resources into the Core's data folder (models overwrite, configs don't).
  - `resolveLooked(player, range, typeId)` — resolves the machine a player is
    looking at (anchor block first, then rendered-model id); replaces every
    module's hand-rolled `findLooked`.
  - `registerCommand(module, label, SubCommand)` — dynamic top-level command
    registration on the server command map; no `plugin.yml` command entries,
    permission enforced by the wrapper.
- **`ModelPoints`** — CENTER-origin pixel→world transform helpers
  (`toWorld`, `toWorldOffset`, `direction`) using the exact math the renderer
  applies, so feature geometry (muzzles, seats, mounts) can never drift from
  the rendered model.
- **`TypedState`** — typed view over persisted `stateData`
  (`getInt/setInt/getLong/getDouble/getBoolean/...`); replaces hand-rolled
  string parsing in module state stores.
- **`Cooldowns`** — self-pruning per-key cooldown map; the standard
  main/off-hand interact-pair dedupe (leak-free, unlike raw maps).
- **`AbstractMachineGui`** — base class for machine GUIs owning all
  interaction guarding: top/bottom raw-slot routing, shift-click
  cancellation, cursor/hotbar-swap/drag validation via `accepts(slot, item)`,
  take-only output slots, filler, and capture-on-next-tick scheduling.
  Subclasses implement only layout, container topology, buttons, and capture.
- **`stagePersist(instanceId)`** (InfrastructureApi) — stages an instance's
  state for the async persistence queue after `stateData` mutations from
  hooks/GUI callbacks.

#### Changed

- **Incremental persistence** — hot lifecycle paths (place/upgrade/remove,
  process advancement) now stage single-instance upserts/deletes instead of
  rewriting every row per mutation; full-replace remains for bulk paths
  (reload, shutdown, migration). A single right-click no longer rewrites the
  whole table.
- **Chunk-aware tick fixed** — `TICK` events and `onTick` hooks are no longer
  dispatched for instances in unloaded chunks (previously only heartbeats and
  deferred renders were skipped, despite the docs claiming a full skip).
- **Heartbeat pass no longer writes** — it only refreshes timestamps; the
  full-dataset restage every 5 seconds (pure write amplification) is gone.
- **`MultiBlockRegistry.getInstances()`** returns a snapshot copy — hooks that
  remove instances mid-tick can no longer cause
  `ConcurrentModificationException` in the tick loop.

#### Fixed

- **Hook exception isolation** — all direct per-type hook dispatches (interact,
  upgrade, break/remove, tick, craft, signal, model reload, process
  start/complete) are wrapped: a throwing module hook is logged and skipped
  instead of aborting the lifecycle operation or killing the tick loop.
- **GUI callback isolation** — a throwing module GUI callback no longer breaks
  the inventory-event pipeline for other modules.
- **GUI session leak on quit** — open sessions are dropped on
  `PlayerQuitEvent` (previously only `InventoryCloseEvent` cleaned up).

### Reference module (`MineplusFun`)

#### Changed

- Both features install resources through `context.moduleSupport()`; the
  duplicated `installDefaultResource` helpers are gone.
- Both GUIs extend `AbstractMachineGui` — all slot/drag/hotbar guard code,
  capture scheduling, filler, and named-item helpers removed (~60% of each
  GUI class was boilerplate).
- Both subcommands resolve targets via `resolveLooked`; the duplicated
  `findLooked` helpers are gone.
- `/juicer` and `/cannon` are registered dynamically via
  `registerCommand` — no `plugin.yml` command entries, no dispatch in the
  plugin main class.
- Cannon: single `isHolding(actor, material)` helper replaces the duplicated
  torch/saddle variants; `Cooldowns` replaces the hand-rolled cooldown map;
  `ModelPoints` replaces the three duplicated CENTER-origin transform copies
  (level-1 muzzle, level-2 muzzle, seat).
- `CannonTntStore` accesses `stateData` through `TypedState`.
- `plugin.yml` keeps only permissions; command declarations removed.

#### Compatibility

Module-facing API is additive only (`moduleSupport()`, `stagePersist`,
`AbstractMachineGui`, `ModelPoints`, `TypedState`, `Cooldowns`). Existing
`InfrastructureGui`/`MultiBlockHook` implementations compile unchanged.
Modules built against 1.0.0 run unchanged, but should migrate to the toolkit
at their leisure. **Core and module must be rebuilt and redeployed together**
with this release.

## [1.0.0] — 2026-08-29

First public release.

### Core engine (`Mineplus`)

#### Added

- **Virtual Blockbench rendering** — single-pass streaming `.bbmodel` importer:
  negative-coordinate geometry, outliner pivot-conjugation transforms, per-cube
  `light_emission`, per-face UV analysis, and per-face material plates for
  mixed-material cubes. Models render as vanilla `BlockDisplay` entities —
  **no client mods, no resource packs**.
- **Geometry-aware collision** — per-cube SAT voxelization with `GEOMETRY`
  (default), `SURFACE` (interior hollowing), and `AABB` (legacy) modes; barrier
  occupancy uses the exact same transform as the visuals, so collision and
  rendering can never drift apart.
- **Rotation snapping** — placements snap to the 24 orientation-preserving axis
  permutations for exact voxel-lattice transforms.
- **Multiblock lifecycle** — create / place / interact / upgrade / tick / remove
  with hooks (`MultiBlockHook`) and lifecycle events for every stage.
- **Timed crafting processes** — restart-safe, chunk-aware timed processes
  (paused in unloaded chunks, resumed on load — vanilla furnace parity), scaled
  by per-level `speed` multipliers.
- **Machine linking & signals** — directed block links, pipe-network style
  auto-linking (`autoLinkNeighbors`), and signal propagation between machines.
- **Three-tier API** — `JsonInfrastructureApi` (config-first),
  `BasicInfrastructureApi` (place/lookup), `InfrastructureApi` (full hooks,
  GUIs, signals, processes).
- **SQLite persistence** — asynchronous write-behind queue
  (`plugins/Mineplus/infrastructure.db`); gameplay never blocks on disk I/O,
  with synchronous flush on shutdown/reload and re-queued retries on failure.
- **Ghost cleanup** — orphaned `BlockDisplay` entities from crashes are
  detected and removed on chunk loads.
- **Texture material resolver** — filename-to-vanilla-material pipeline
  (curated map, direct match, aliases, fuzzy matching) with a JVM-lifetime cache.
- **Admin CLI** — `/mineplus status`, `/mineplus reload [all|models|multiblocks|recipes]`,
  and the `/mineplus model` suite (list, inspect, remove, respawn, setlevel,
  debugspawn) with per-instance diagnostics.
- **Update checker** — optional SpigotMC version check, configured via
  `UPDATE_CHECKER.RESOURCE_ID` in `settings.mp.yml` (disabled by default).
- **bStats metrics** — anonymous usage statistics
  ([bstats.org](https://bstats.org/plugin/bukkit/Mineplus/33702)); opt-out anytime
  via `plugins/bStats/config.yml`.

#### Zero-content policy

The core ships with **no gameplay content** — no default machines, items, or
world injections. Everything in-game comes from your JSON or API calls.

### Reference module (`MineplusFun`)

#### Added

- **Juicer** — recipe-driven machine with a GUI, custom juice items, and a
  level upgrade path; demonstrates the unconditional-GUI interaction pattern.
- **Cannon** — 3×1×1 Blockbench cannon with conditional interaction
  (torch fires, anything else opens the ammunition menu), persistent TNT ammo
  (survives restarts via `stateData`), model-accurate `TNTPrimed` ballistics,
  and rotation-aware placement.
- `/juicer` and `/cannon` admin commands with tab completion.
- `DEVELOPMENT_PROMPT.md` — the canonical module-building guide.

[1.4.0]: https://github.com/Tariux/MinePlus/releases/tag/v1.4.0
[1.1.0]: https://github.com/Tariux/MinePlus/releases/tag/v1.1.0
[1.0.0]: https://github.com/Tariux/MinePlus/releases/tag/v1.0.0
