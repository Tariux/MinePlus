# Custom Mobs

Mineplus custom mobs add *new* mobs to a server without touching vanilla ones.
A custom mob is a real vanilla base entity (`PIG`, `ZOMBIE`, `IRON_GOLEM`,
`VILLAGER`, …) that the Core gives a Mineplus identity, an optional visual model
and attribute overrides. Its **mind** is then the author's choice: either the
Core clears the vanilla goals and steers the mob through its behavior profile,
or `vanillaAi()` leaves the goals alone and lets the server's own engine run it
([Whose mind runs the mob?](#whose-mind-runs-the-mob)). Either way the entity
stays a genuine, hittable, pushable mob — the plugin only dresses it up.

Two content phases use the same API:

| Phase | Model | What changes on the client |
|---|---|---|
| Texture-extended mob (Evil Pig) | `MobRenderSpec` `RETEXTURE_VANILLA` (or `CustomMobModel.vanilla(type, png)`) | the vanilla geometry with a replacement texture |
| Fully custom mob | `MobRenderSpec` with a `.bbmodel` / Java model file, or `CustomMobModel.builder(...)` / `builderFor(...)` | the authored bone tree, texture and clips, as displays over an invisible base |

The first phase requires no model authoring at all. The second is the same
compiler with author-supplied geometry: a Blockbench file is read whole and
needs no Java-side part table, a hand-authored `CustomMobModel` does.

---

## The Evil Pig (first phase)

`examples/mineplus-mobfun` registers the Evil Pig as a complete working
example — a Core-steered mob, because no vanilla pig hunts anyone:

```java
CustomMobDefinition evilPig = CustomMobDefinition.builder("mobfun:evil_pig", EntityType.PIG)
        .displayName("Evil Pig")
        .showName(true)
        .render(MobRenderSpec.builder(MobRenderSpec.RenderType.RETEXTURE_VANILLA, "evil_pig.png")
                .textureBytes(() -> evilPigTexture)
                .build())
        .attributes(CustomMobAttributes.builder()
                .maxHealth(12.0)
                .movementSpeed(0.28)
                .attackDamage(4.0)
                .followRange(24.0)
                .knockbackResistance(0.1)
                .build())
        .behavior(profile -> profile
                .priority(BehaviorPriority.EVIL_PIG)
                .action(AttackNearestAction.builder()
                        .searchRadius(18.0)
                        .reach(2.5)
                        .speed(1.2)
                        .damage(4.0)
                        .cooldownTicks(20L)
                        .knockback(0.4)
                        .build(), 1.0))
        .build();

context.customMobs().register(this, evilPig);
```

The texture is a standard 64×32 pig sheet, embedded as a jar resource
(`mobs/evil_pig.png`) and shipped to clients through the Core's resource pack.
The mob attacks players and villagers; it does nothing else, because nothing
else was declared — its vanilla goals were cleared. The Villager Soldier and the
Woodland Trickster in the same module are the other half of the axis: `.bbmodel`
mobs on an `IRON_GOLEM` and a `ZOMBIE` base with `vanillaAi()`, so the server
runs them and the plugin only poses their rigs.

Test it in game:

```
/mobfun evilpig on
/mobfun spawn evilpig
```

`/mobfun spawn` places a real custom mob in front of you, force-fires its
profile once and prints the per-action result. `/mobfun despawn` removes the
test mobs; `/mobfun evilpig off` unregisters the definition and its live mobs.

---

## Registering a mob

```java
CustomMobApi mobs = context.customMobs();

mobs.register(plugin, definition);              // main thread only
LivingEntity mob = mobs.spawn(location, "mymod:my_mob");
```

### Identity

The id is `namespace:path`, both segments `[a-z0-9_-]`. The namespace is also
the resource-pack namespace, and the path is the asset folder
(`assets/<namespace>/textures/item/entity/<path>/…`), so two modules can never
collide on an id.

Registration is owner-scoped:

- the same owner re-registering the same definition is a no-op;
- a different owner claiming an existing id throws `IllegalStateException`;
- `unregister(owner, id)` removes the definition, its behavior profile and its
  live mobs;
- `unregisterAll(owner)` is the module-disable path.

### Definition options

| Builder call | Default | Effect |
|---|---|---|
| `displayName(String)` | prettified path | Custom name |
| `showName(boolean)` | `false` | Float the name above the mob |
| `persistent(boolean)` | `true` | Survive chunk unloads and restarts |
| `hideBaseWhenRigged(boolean)` | `true` | Make the vanilla base invisible once a rig covers it |
| `canPickupItems(boolean)` | `false` | Allow item pickup |
| `visual(VisualMode)` | `AUTO` | `BASE` never rigs (vanilla look), `AUTO` rigs when the pack can present it, `RIG` insists and logs loudly when it cannot |
| `attributes(CustomMobAttributes)` | none | Attribute overrides |
| `render(MobRenderSpec)` | none | Presentation: type, model/texture bytes, scale, yaw offset, hitbox, hurt feedback, shadow, clip aliases, hand items |
| `behavior(Consumer<MobProfile.Builder>)` | see below | The mob's behavior, run by the Core's behavior engine |
| `vanillaAi()` | off | **The game engine keeps the mob's mind**: no isolation, no profile, no scan cost — see [Whose mind runs the mob?](#whose-mind-runs-the-mob) |
| `inheritGoalsFrom(EntityType)` | none | The explicit form of the same idea (the donor must be the base type; a mismatch is ignored and the mob stays isolated) |
| `spawnConfigurator(Consumer<LivingEntity>)` | none | Last-word setup after identity/attributes |

Base types must be pathfinding `Mob`s — the Core navigates them with their own
vanilla walk, which needs the vanilla AI to stay enabled (see below). Because
clearing the goals leaves nothing else to turn the body, every path request
also points the vanilla look control at the destination: the mob faces where it
is walking instead of sliding along its frozen spawn direction, and the rig's
yaw frame follows that body yaw. A vanilla path request is refused while the
entity is airborne or not yet on the ground, so the engine's periodic re-path
is what starts a walk whose first request arrived during the spawn fall.

`AUTO` is the right default: a retexture of a vanilla model *is* a rig on a
1.21.1 client, because the only way to replace a mob's look server-side is to
hide the entity and present its parts. Use `BASE` only for a mob that is meant
to keep its vanilla skin (a pure behavior showcase), and `RIG` when a missing
rig is a bug you want reported rather than absorbed.

### Attributes

`CustomMobAttributes` only states differences from the base entity:
`maxHealth`, `movementSpeed`, `attackDamage`, `followRange`, `armor`,
`knockbackResistance`. Unset fields keep the vanilla value. Health is applied as
a new maximum and filled on a fresh spawn; on a chunk restore the saved health
is clamped to the maximum instead of being healed back to full.

A mob that should move like something other than its base type starts from that
type's real values instead of guessed ones:

```java
CustomMobAttributes.builder()
        .fromTemplate(EntityType.IRON_GOLEM)   // read once from a throwaway donor
        .maxHealth(60.0).movementSpeed(0.3).attackDamage(6.0)
        .followRange(28.0).armor(10.0).knockbackResistance(0.3)
        .build();
```

`fromTemplate` spawns one donor entity, reads its attributes, removes it and
caches the template for the JVM, so a villager-based soldier walks like a golem
instead of like a villager. Only the fields you do not override come from the
template, and `overrideCount()` reports how many explicit overrides the mob
carries.

### Behavior

`behavior(...)` receives a `MobProfile.Builder` already bound to the base type
**and the custom id**, so actions, priorities, conditions and pipelines are
exactly the [Mob Behavior API](developer-api.md#mob-behavior-api). A custom mob
without a behavior consumer gets an idle profile and stands still — the correct
default once vanilla AI is gone.

That last sentence does not apply to a `vanillaAi()` definition: it registers
**no profile at all**, because the server's own AI already moves it and an idle
profile would only cost a scan slot.

---

## Whose mind runs the mob?

A custom mob has two possible minds, and picking the right one is the single
biggest quality decision in this axis.

| | Core AI (the default) | `vanillaAi()` |
|---|---|---|
| Who pathfinds, walks, attacks, recoils, dies | the plugin's behavior engine, on its own tick budget | the server's own mob AI, on its own tick |
| Client motion | interpolated only as far as the pose push allows | interpolated natively, like any real mob |
| Behavior-engine cost per mob | one profile slot in every scan pass | zero (no profile is registered) |
| What you author | a `BehaviorAction` (or a bundled one such as `AttackNearestAction`) | nothing — you pick a base type whose AI already fits |
| Attack clip on the rig | the action calls `playAttack` | the engine's own melee swing fires it (`EntityDamageByEntityEvent`) |

**Use `vanillaAi()` whenever the content is "a real mob wearing a custom
face".** A soldier that should guard like a golem is an `IRON_GOLEM`; a
creature that should hunt players is a `ZOMBIE`. A mob can only keep the goals
of the type it actually is, so the base entity *is* the AI choice:

```java
CustomMobDefinition.builder("mobfun:villager_soldier", EntityType.IRON_GOLEM)
        .visual(CustomMobDefinition.VisualMode.RIG)
        .vanillaAi()                        // golem AI: patrol, hunt monsters, protect
        .render(MobRenderSpec.builder(MobRenderSpec.RenderType.BBMODEL,
                        "soldier_villager.bbmodel")
                .modelBytes(() -> loadResource("mobs/soldier_villager.bbmodel"))
                .textureBytes(() -> loadResource("mobs/soldier_villager.png"))
                .hitbox(0.7, 2.1)           // the visible model, not the golem's box
                .build())
        .attributes(CustomMobAttributes.builder()
                .fromTemplate(EntityType.IRON_GOLEM)
                .maxHealth(60.0).armor(10.0).build())
        .build();                           // note: no behavior(...) at all
```

Everything else still applies: identity, PDC tag, persistence, attributes,
name, rig, clip blending, hurt feedback and the shadow plate. Only the mind
changes hands.

**Use Core AI when no vanilla mob does the job.** A pig that hunts players
(`mobfun:evil_pig`) has no vanilla equivalent — a real pig wanders and flees —
so it stays isolated and steered by its profile. The same goes for anything
long-range or scripted: journeys, crews, pipelines and village raids are
`BehaviorAction`/`MobPipeline` content and always have been.

A `spawnConfigurator` is still the place for base-type quirks that would spoil
the fiction. The Woodland Trickster is a zombie, and a zombie burns at sunrise,
so it asks for the Paper toggle through a probe (Spigot has no such method) and
falls back to ambient fire resistance:

```java
.spawnConfigurator(entity -> {
    try {
        entity.getClass().getMethod("setShouldBurnInDay", boolean.class)
                .invoke(entity, false);
        return;
    } catch (ReflectiveOperationException absent) {
        entity.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,
                PotionEffect.INFINITE_DURATION, 0, false, false));
    }
})
```

In YAML the same choice is one key (`ai: vanilla` — see
[the loader's contract](#the-yaml-definition-mobsyml) and
`src/main/resources/mobs/examples/example_vanilla_ai_mob.yml`).

---

## The YAML definition (`mobs/*.yml`)

An operator can add a mob without writing Java: every top-level `.yml` in
`plugins/Mineplus/mobs/` is one definition, read by `MobYamlLoader` on start and
on `/mineplus mob reload`. The jar ships `mobs/examples/` (not scanned — the
loader only reads the folder's top level) with one worked example of each mind:
`example_evil_pig.yml` (Core-steered retexture) and
`example_vanilla_ai_mob.yml` (engine-owned bbmodel mob).

```yaml
id: forest_stalker          # defaults to the file name; a bare id gets mineplus:
base-entity: ZOMBIE         # required, and must be a pathfinding Mob
ai: vanilla                 # vanilla | core (default) — whose mind runs it
display-name: "Forest Stalker"
show-name: true
persistent: true

attributes:                 # only what differs from the base entity
  max-health: 30.0
  movement-speed: 0.32
  attack-damage: 4.0
  follow-range: 24.0
  armor: 2.0

render:
  type: bbmodel             # bbmodel | retexture_vanilla | java_json | none
  file: forest_stalker.bbmodel      # relative to mobs/
  texture: forest_stalker.png       # relative to mobs/
  scale: 1.0
  yaw-offset: 0             # 180 when the model was authored backwards
  fallback: base_entity     # or generic_cube
  hurt-feedback: particles  # particles | flash | none
  shadow: true              # true | a radius in blocks | false
  # hand-items:             # bone -> vanilla material or pack item id
  #   weapon_r: IRON_SWORD
  # animations:             # state -> clip alias
  #   attack: animation.pounce

hitbox:                     # blocks, feet-anchored (see the note below)
  width: 0.6
  height: 1.9
```

Rules worth knowing:

- Model and texture paths are relative to `mobs/`, so copy the `.bbmodel` and
  its PNG next to the YAML.
- A file that cannot be parsed, names an unknown `base-entity`, or points at a
  missing model/texture is **skipped with a logged reason**; a broken YAML never
  registers a half-described mob, and never takes the others down with it.
- Legacy key spellings (`base:`, top-level `render-type:` / `model:` /
  `texture:` / `scale:`, `hand_items:`, `attributes.health`) still parse and are
  reported once per file with the new spelling.
- `hitbox:` is applied through `Entity#setSize`, which is a Paper API. On plain
  Spigot the method does not exist, the box stays the base type's own, and the
  Core logs it once under `ADDITIONAL_DEBUG_LOGS` — a mob remains fully
  hittable either way, only its box differs from the drawn one.
- `/mineplus mob inspect <id>` prints the parsed result: base, mind owner,
  render source, hitbox, hurt feedback, shadow, clip aliases and every bone.

---

## What the Core guarantees

### When the Core owns the mind: no vanilla AI, on every path

Unless the definition asked for `vanillaAi()`, on spawn and on every chunk
restore the Core clears the entity's goal and target selectors
(`MobAiIsolation.isolate`) while leaving AI enabled. The navigator still ticks
the mob's own vanilla walk, so movement looks and behaves like a vanilla mob,
but no vanilla goal or target decides anything. A `vanillaAi()` mob is skipped
by all three paths — spawn, chunk restore and the periodic re-assert sweep —
and reports `vanilla goals kept` as its isolation result, which is the intended
state rather than a failed clear.

The navigation, look, Brain and selector handles are resolved against the
**live handle's runtime class**, never the declared return type of
`getHandle()`. CraftBukkit is free to declare that bridge loosely —
`CraftVillager.getHandle()` returns `Entity` — and a plan built from the
declared type finds no navigation, look control or Brain at all: the villager
keeps its vanilla brain and cannot be steered. Resolving from the live handle
makes the guarantee hold for goal-driven and Brain-driven mobs alike.

Brain-driven mobs (villagers, piglins, axolotls) run their behaviors out of a
`Brain`, not the goal selectors, so isolation clears the Brain's **activities**
— the active-behavior map and the behavior/task collections, all keyed by the
vanilla `Activity` enum. It never touches the Brain's memory or sensor
*registries*: `Brain.getMemory` throws
`IllegalStateException: Unregistered memory fetched: minecraft:last_slept` for a
type it no longer has registered, and that exception then fires on every
neighbour that reads the mob — an iron golem's village stroll goal asks each
villager whether it wants a golem, so one over-eager clear spammed a stack trace
per tick for every golem in the village. The containers to clear are classified
once at runtime from a live Brain (which keys are `Activity` enums), so the rule
holds on reobfuscated mappings, and clearing a memory *value* is fine while
emptying the *registration* is not.

Isolation is a claim the Core must be able to prove, so it is never silent:

- `MobAiIsolation.Result` / `CustomMobApi.isolationLabel(entity)` report what was
  actually cleared (selectors, Brain activities, both, or nothing);
- `CustomMobApi.rigFailure(id)` reports why a mob renders as its base instead of
  its rig, and a definition that asked for `VisualMode.RIG` logs a warning once
  with that reason;
- if the Brain's activity containers cannot be identified, the Core says so
  ("its vanilla behaviors stay enabled") rather than leaving a half-isolated,
  corrupted mob.
- isolation is re-asserted periodically, not only on spawn and chunk load, so a
  mob that a plugin or a vanilla event hands back to its AI gets re-isolated.

A mob that genuinely needs its vanilla brain — a villager that must keep trading
or breeding — asks for it explicitly with `inheritGoalsFrom(baseType)`, which
skips the clear and reports "on donor AI" in every status line. That is a
decision, not an accident, and the status output shows which it is.

### Identity survives restarts

The custom id is stored in the entity's persistent data (`custom_mob_id`), with
a scoreboard tag for cheap discovery and an in-memory `UUID → id` cache for the
engine's hot path. On chunk load the Core re-applies identity, attributes and
isolation, then re-attaches the visual rig. Death and removal forget the
entity.

A definition registered *after* entities already exist picks up its loaded
mobs on the first registration sweep — so a server restart never leaves a
custom mob as a vanilla-looking impostor.

### Unknown ids are contained

A tagged entity whose definition is not registered is still isolated (vanilla
AI never takes it back), and — once at least one definition exists — removed on
chunk load under `CUSTOM_MOBS.REMOVE_UNKNOWN_ON_LOAD`. Unknown ids are never
silently converted into vanilla mobs.

### Vanilla mobs are untouched

Profiles are dispatched by custom id through `MobIdentityResolver`:

- a vanilla pig keeps its vanilla AI and never matches a custom profile;
- a custom pig never matches a `MobProfile.builder(EntityType.PIG)` profile;
- `forceFire` resolves identity the same way, so the test bench cannot cross
  the boundary either.

---

## Rendering

### Texture-extended models

`CustomMobModel.vanilla(baseType, png)` reuses the published vanilla model
descriptor for that entity type. Parts are the vanilla boxes (head, body, legs)
with vanilla pivots and roles; only the texture differs. The hitbox is the
vanilla one, so gameplay and pathing are unchanged.

On a server that supports data-driven variants (1.21.5+) this render type ships
a generated texture plus a variant registry entry, and each spawned mob is
given that variant reflectively. On an older server that channel does not exist,
so the Core presents the same texture as a part rig built from the vanilla
descriptor instead — the mob then wears its texture on `ItemDisplay` parts, with
head look and procedural limb swing like any other rig. `/mobfun rig <id>`
prints which of the two the definition actually got.

### Fully custom models

```java
CustomMobModel model = CustomMobModel.builder(64, 64, texturePng)
        .modelHeightPx(24)
        .textureName("texture")
        .part(MobPart.builder("head", MobPart.Role.HEAD)
                .pivot(0, 24, 0)
                .box(MobBox.builder(0, 0)          // textureU, textureV
                        .from(-4, 24, -8)          // min corner, model pixels
                        .to(4, 32, 0)              // max corner
                        .build())
                .build())
        .part(/* body, legs, … */)
        .build();
```

Or start from the vanilla descriptor and modify it:

```java
CustomMobModel model = CustomMobModel.builderFor(EntityType.PIG, texturePng)
        .part(myExtraPart)
        .build();
```

An authored Blockbench file is imported whole instead of re-typed. A definition
points at it through its render spec, and the Core reads the file's own outliner
as the rig — no part mapping is authored in Java:

```java
CustomMobDefinition.builder("mobfun:villager_soldier", EntityType.IRON_GOLEM)
        .visual(CustomMobDefinition.VisualMode.RIG)
        .vanillaAi()                        // the golem's own AI runs it
        .render(MobRenderSpec.builder(MobRenderSpec.RenderType.BBMODEL,
                        "soldier_villager.bbmodel")
                .modelBytes(() -> loadResource("mobs/soldier_villager.bbmodel"))
                .textureBytes(() -> loadResource("mobs/soldier_villager.png"))
                .hitbox(0.7, 2.1)
                .build())
        .build();
```

`BbModelParser` understands both the classic and the 5.0 `.bbmodel` shapes (in
5.0 the geometry lives in the root `groups` array and the outliner only nests
and names it; animation tracks arrive as a `keyframes` array rather than the
named channel objects). Every group becomes a bone with its authored pivot,
rest rotation and parent, and every element is claimed by its innermost group,
so a model painted and rigged in Blockbench needs no UV re-derivation and no
Java-side part table. Elements an animation cannot reach, or a malformed
element with no box, are simply never claimed. An element rotated on more than
one axis is baked into synthetic sub-bones, because an `ItemDisplay` pose is a
single matrix and the reader must not silently drop the rotation.

Part coordinates are vanilla entity-model pixels for a hand-authored
`CustomMobModel`: `y` grows downward from `modelHeightPx` (24 for a standard
quadruped), the model front is `-z`, and pivots are absolute. `MobPart.Role`
tells the rig which vanilla pose applies — `HEAD` tracks the entity's look
direction, `LEG_*` swings with movement, `BODY` and `STATIC` stay put — so a
rigged mob animates without a custom animation pipeline.

Clips bind by name, not by index: `MobAnimator` picks the authored clip whose
name contains the state word (`idle`/`stand`/`breath`, `walk`, `run`/`sprint`,
`attack`/`swing`/`melee`, `hurt`/`hit`/`damage`, `death`/`die`), or the alias a
definition declares with `MobRenderSpec.animation(state, clipName)`. A missing
clip falls back along run → walk → idle → procedural, so a model with only an
idle clip still breathes and still swings its legs while moving. The state is
chosen from the mob's own measured speed (blocks per tick between pose pushes):
above 0.015 it walks, above 0.16 it runs, and the transitions cross-fade over
0.15 s. `animation.attack` is a one-shot *overlay*: the Core plays it the moment
the mob swings — a Core-steered action calls `MobRenderEngine.playAttack` when
it swings, and for a `vanillaAi()` mob the engine's own melee hit
(`EntityDamageByEntityEvent` with the mob as damager) is what fires it, so the
clip lands on the swing rather than only on the damage — or a module through
`CustomMobApi.playAttack`/`playAnimation` for its own timing. When it ends the
bones go back to walk/idle. For a `vanillaAi()` mob the measured speed that
chooses idle/walk/run is the distance the *server* actually moved the entity, so
the rig's gait follows the engine's stride instead of a plugin's intention.
`examples/mineplus-mobfun` uses this path for the Villager Soldier
(`mobfun:villager_soldier`, iron-golem base) and the Woodland Trickster
(`mobfun:woodland_trickster`, zombie base, idle + walk + attack).

### How a model reaches the client

The Core compiles the model into pack assets:

1. the texture PNG under `textures/item/entity/<path>/texture.png`;
2. one item model per part under `models/item/entity/<path>/<part>.json`,
   with vanilla box-UV windows converted to element face windows (the same
   cross layout Blockbench derives) — or, for an imported model, its authored
   per-face windows copied unchanged;
3. one item definition per part under `items/entity/<path>/<part>.json`.

**The client element window.** An item model is rendered inside a fixed
−16..32 px box; an element outside it is clamped and collapses into a
missing-texture cube. Every part is therefore compiled to fit, whatever the
author drew:

- the writer keeps the vanilla `+8` item anchor and only records the extra
  pixel shift a part needs (usually none); a part that overshoots by a couple
  of pixels is shifted inside the window instead of being re-authored;
- a part that still spans more than 48 px on an axis is sliced into
  deterministic pieces (`<part>_p2`, `<part>_p3`, …), each its own item model
  and `ItemDisplay` at its own offset. Oversized weapons and long tails are
  normal content, not a modelling error;
- the pose pass applies each piece's offset under the same bone matrix, so the
  pieces reassemble into the authored shape and rotate as one bone.

`MobRenderEngine` makes the base entity invisible and presents each part (or
piece) as an `ItemDisplay` whose `item_model` component points at that model (see
[A rigged mob is still a real entity](#a-rigged-mob-is-still-a-real-entity)). The
rig is placed with `R_y(180° + yawOffset − heading)` — authored models face
`-z`, and in this frame the model's front lands exactly on the direction
`(-sin heading, 0, cos heading)`. `heading` is the direction the mob actually
walks: every pose pass measures the entity's own position delta and steers the
rig toward it, easing over the shortest arc, and falls back to the server body
yaw while the mob stands still (a vanilla mob keeps turning its body toward
whatever it last looked at, which is not a walk direction). The head bone
additionally follows the entity's head yaw and look pitch, measured against the
rig frame rather than the server body yaw so the two cannot add up. A model
authored facing another axis corrects itself with
`yawOffsetDegrees(...)` (or `render.yaw-offset` in a mob YAML) instead of code,
and `/mobfun rig <id> yaw-offset <deg|auto>` probes a value live before it is
authored. All of this rides the existing pack delivery — players already
receiving the Core pack see the mob with no extra download.

### A rigged mob is still a real entity

Hiding the mob's own model must not hide the mob. The base entity is therefore
made **invisible** (`LivingEntity#setInvisible`), never removed from the client:
its hitbox, collision, push-back, targeting, sounds, damage state, knockback and
death all stay exactly as the server computed them, and only the model is
transparent. Removing the entity from the client instead — the obvious shortcut —
silently costs all of that: players cannot look at or hit the mob, a gun shot
passes through it, nothing pushes it, and it has no hurt tint or collapse to
show.

Because the rig *draws* the mob, the three things a real entity normally
communicates through its own model are restored by the render engine instead:

| Need | What happens |
|---|---|
| "I hit it" | `MobRenderSpec.HurtFeedback.PARTICLES` (default) — a `CRIT` burst at the mob's chest on every hit, throttled to one per four pose passes and emitted through the shared `ParticleFx` budget. `FLASH` instead reveals the real model for 8 ticks so the vanilla red tint plays; `NONE` stays quiet. Either way the rig also plays its authored `hurt` clip when one exists. |
| "It is standing here" | a generated ground-shadow plate (`render/MobShadowAssets`, one `ItemDisplay` under the mob, `shade:false` + `blend:true`), sized from the hitbox width or from `render.shadow: <blocks>`; `shadow: false` (or `CUSTOM_MOBS.SHADOWS: false`) removes it. Needs the pack, like every other display. |
| "Its body is this big" | `MobRenderSpec.hitbox(width, height)` / `hitbox:` in YAML, applied through Paper's `Entity#setSize` where it exists (probed once; plain Spigot keeps the base type's box and logs it). |

Death is handled the same way: a rig with an authored `death` clip plays it as a
one-shot and the session tears down when the clip ends; a rig without one
releases its displays so the **engine's own** collapse, smoke and drop play
instead, held for `DEATH_HOLD_SECONDS`. A mob never vanishes mid-death.

Every one of those is per-viewer correct: a player whose client has not accepted
the resource pack cannot receive the rig's displays at all, so while any nearby
viewer is in that state the base model stays visible rather than leaving an
invisible hitbox nobody can see. `/mobfun rig <id>` prints `base hidden` /
`base visible` per mob, and `/mineplus mob inspect <id>` prints the hurt, shadow
and hitbox the definition asked for.

### Degradation

If the pack subsystem is unavailable, disabled, or the server has no
`item_model` display support, no rig is created and the base entity stays
visible. Identity, AI isolation, attributes, persistence and behavior are
unaffected, so a custom mob is always playable and testable without the visual
layer.

### Cost controls (`CUSTOM_MOBS`)

The base entity of a custom mob costs exactly what the vanilla mob it wraps
costs — that part of the lag budget is the server's, and a `vanillaAi()` mob
adds nothing to it at all. What the plugin adds is the **rig**: one
`ItemDisplay` (or more, for a multi-piece bone) per bone, posed on a schedule.
Every knob below bounds that.

| Key | Default | Effect |
|---|---|---|
| `ENABLED` | `true` | Subsystem master switch |
| `RENDERING` | `true` | Create visual rigs at all |
| `RENDER_INTERVAL_TICKS` | `1` | Server ticks between rig pose pushes (the pose loop is scheduled at this cadence, and the animation clocks and measured walk speed scale with it) |
| `RENDER_INTERPOLATION_TICKS` | `1` | Client-side easing window per pose push; the engine raises it to the interval when it is shorter, so a slowed loop still glides |
| `RENDER_DISTANCE` | `48` | Blocks beyond which a rig is **released**: its despawn, the base entity shows again, and the rig is rebuilt when a viewer returns (8 blocks of hysteresis keep a mob walking the border from thrashing) |
| `RENDER_NEAR_DISTANCE` | `20` | Blocks inside which a rig poses at full rate; beyond it, at a quarter cadence with the interpolation window widened to match, so the motion stays smooth at a fraction of the packets |
| `MAX_RIGGED_PER_WORLD` | `48` | Hard ceiling of rigged mobs per world; the rest keep their base entity and log the reason once |
| `SHADOWS` | `true` | Give a rigged mob its generated ground-shadow plate |
| `REMOVE_UNKNOWN_ON_LOAD` | `true` | Remove custom-tagged entities whose definition is gone |

On top of the settings, the pose pass itself is written to be free when nothing
changed:

- **one viewer snapshot per pass.** Every rig used to walk the online-player
  list and allocate distance checks of its own; a crowd made that a
  mob-times-player loop per tick. The list and each viewer's pack state are now
  gathered once per pass and read by every rig without allocating.
- **per-display dirty check.** Each display remembers the position and the 16
  matrix floats it was last sent (`PoseSlot`); a pose that moved neither (by
  more than 1.5 mm, or 0.0008 of a matrix unit) pushes no packet at all. A mob
  standing idle therefore costs nearly nothing, which is the common case in a
  herd or a patrol.
- **staggered wake-ups.** Each session starts its pose cadence offset by its own
  id hash, so a hundred quarter-rate rigs do not all push on the same tick.
- **`ParticleFx` budgets** for hurt feedback, and **hysteresis** on release.

Read the live cost with `/mineplus mob cost` (posed rigs, displays, how many are
at quarter rate or released, cadence and cap), and per definition with
`/mineplus mob validate`. Raising `RENDER_INTERVAL_TICKS` or lowering
`RENDER_NEAR_DISTANCE` is the cheap knob for a world with hundreds of rigged
mobs; giving a heavy mob `ai: vanilla` is the one that removes the rig's work
from the behavior engine entirely.

---

## Bundled action: `AttackNearestAction`

The Evil Pig's entire behavior, usable by any profile:

```java
AttackNearestAction.builder()
        .searchRadius(18)       // blocks (1–64)
        .reach(2.5)             // strike distance (0.5–6)
        .speed(1.2)             // navigation speed (0.2–3)
        .damage(4)              // (0.5–100)
        .cooldownTicks(20)      // between strikes
        .knockback(0.4)         // horizontal knockback (0–3)
        .target(e -> e instanceof Player || e instanceof Villager)  // default
        .build();
```

Behavior details:

- Target acquisition is sticky for 60 ticks, so a mob does not flicker between
  two nearby players; the target is re-scanned when it dies, leaves range or
  the stickiness expires.
- Creative and spectator players are hunted like anyone else — the mob walks up
  and swings at them — but are never damaged or pushed. Excluding those game
  modes from targeting entirely would leave a hostile mob standing perfectly
  still next to the one player testing it, which reads as "this mob has no AI".
- Navigation is throttled (re-path every 10 ticks) and only engages outside
  strike reach; inside reach the mob stops and swings.
- Damage goes through `DamageTransactions.apply`, so a module listener that
  suppresses its own damage cannot accidentally suppress mob damage.
- A line-of-sight check runs before the strike; the mob does not hit through
  walls.

---

## Testing a custom mob

```
/mobfun status               # registered showcases, including custom mobs
/mobfun evilpig on|off       # register/unregister the Evil Pig
/mobfun spawn evilpig        # spawn a custom mob in front of you and force-fire it
/mobfun fire evilpig force   # force the nearest one, ignoring profile conditions
/mobfun spawn soldier        # the imported Villager Soldier (mobfun:villager_soldier)
/mobfun spawn trickster      # the Woodland Trickster (mobfun:woodland_trickster)
/mobfun rig soldier          # what the definition asks for vs what the Core presents
/mobfun rig trickster attack # play the authored attack clip on the nearest one
/mobfun rig soldier yaw-offset 180   # flip a backwards-authored model, live
/mobfun despawn              # remove every mob spawned through the test bench
/mineplus mob cost           # how many rigs are posed, quarter-rated or released
```

`forceFire` dispatches by identity, so the report describes the custom profile
even though the entity's `EntityType` is `PIG`, `ZOMBIE` or `IRON_GOLEM`. A
failure means the profile or its conditions blocked the action — never that the
wrong profile ran.

A `vanillaAi()` showcase has no profile to force, so `/mobfun spawn soldier` and
`/mobfun fire soldier` say so instead of printing an empty action list: the
engine owns its mind, and the test to run is to stand in front of it and watch it
path, hunt, recoil and die. `/mobfun rig soldier` remains the diagnostic that
describes the visual half — and its `Definition:` line states which mind the
definition asked for, so a mob that "does nothing" is instantly separated into
"the profile is blocked" versus "the engine's AI is busy or has nothing to
target".

---

## Checklist for a new custom mob

1. Decide the mind first. Is this "a real mob wearing a custom face"? Then pick
   the base type whose vanilla AI already does the job and call `vanillaAi()` —
   movement, attack timing, recoil and death come out smooth because the server
   produces them, and the behavior engine never scans the mob. If no vanilla mob
   does it (a hunting pig, a pilgrim, a raid leader), leave the default and
   author the profile. Either way the base type must be a pathfinding `Mob`.
2. Add the texture PNG as a jar resource (or generate it), keeping the vanilla
   sheet layout if you use `CustomMobModel.vanilla`. A `.bbmodel` needs no
   layout rule — its outliner becomes the rig and its clips bind by name.
3. Build the `CustomMobDefinition`: id, base type, render spec (or model),
   attributes, behavior or `vanillaAi()`.
4. Describe the *drawn* body, not the base type's: `render(...).hitbox(w, h)`
   when the model is smaller or larger than the vanilla silhouette, and leave
   `hurt-feedback` and `shadow` at their defaults unless the mob needs the real
   model to flash instead of a spark burst.
5. Register it on module enable (`context.customMobs().register(this, def)`),
   unregister on disable.
6. Optionally expose a spawn command or spawner hook.
7. Verify with `/mobfun spawn`-style force-fire that the action list is what you
   expect (for a Core-steered mob), or that it behaves like its base type and
   takes damage, knockback and a killing blow like one (for `vanillaAi()`).
8. Then read the rig itself with `/mobfun rig <id>`: it prints what the
   definition asks for, what the Core actually presented, each live mob's
   isolation result, its pose source and its rig heading beside the server body
   yaw, and whether its base model is hidden. "It looks like a plain villager"
   has several different causes — pack not delivered, model failed to parse, rig
   cap, render toggle, isolation failure — and this separates them in one
   command. A backwards-facing model shows as a rig heading that disagrees with
   the walk direction on every line; correct it with `/mobfun rig <id>
   yaw-offset 180`, and if that fixes it, author the value into the definition.
9. With a crowd of them, check `/mineplus mob cost`: posed rigs, display
   entities, how many are quarter-rated or released. If it is heavy, tune
   `RENDER_NEAR_DISTANCE` / `RENDER_INTERVAL_TICKS` / `MAX_RIGGED_PER_WORLD` —
   or move the mob to `vanillaAi()`, which is both cheaper and smoother.
