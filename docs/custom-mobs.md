# Custom Mobs

Mineplus custom mobs add *new* mobs to a server without touching vanilla ones.
A custom mob is a real vanilla base entity (`PIG`, `ZOMBIE`, `VILLAGER`, …) that
the Core gives a Mineplus identity, an optional visual model, attribute
overrides and a behavior profile of its own. Vanilla AI is cleared, so the mob
does exactly what the plugin says and nothing else.

Two content phases use the same API:

| Phase | Model | What changes on the client |
|---|---|---|
| Texture-extended mob (Evil Pig) | `CustomMobModel.vanilla(type, png)` | the vanilla geometry with a replacement texture |
| Fully custom mob | `CustomMobModel.builder(...)` / `builderFor(...)` | arbitrary parts, texture, independent part animation |

The first phase requires no model authoring at all. The second uses the same
compiler with author-supplied parts.

---

## The Evil Pig (first phase)

`examples/mineplus-mobfun` registers the Evil Pig as a complete working
example:

```java
CustomMobDefinition evilPig = CustomMobDefinition.builder(
                "mobfun:evil_pig", EntityType.PIG,
                CustomMobModel.vanilla(EntityType.PIG, texturePng))
        .displayName("Evil Pig")
        .showName(true)
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
else was declared.

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
| `hideBaseWhenRigged(boolean)` | `true` | Hide the vanilla base entity once a rig covers it |
| `canPickupItems(boolean)` | `false` | Allow item pickup |
| `visual(VisualMode)` | `AUTO` | `BASE` never rigs (vanilla look), `AUTO` rigs when the pack can present it, `RIG` insists and logs loudly when it cannot |
| `attributes(CustomMobAttributes)` | none | Attribute overrides |
| `behavior(Consumer<MobProfile.Builder>)` | idle | The mob's entire behavior |
| `inheritGoalsFrom(EntityType)` | none | Keep the vanilla goals of the base type (the donor must be the base type) instead of isolating |
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

---

## What the Core guarantees

### No vanilla AI, on every path

On spawn and on every chunk restore the Core clears the entity's goal and
target selectors (`MobAiIsolation.isolate`) while leaving AI enabled. The
navigator still ticks the mob's own vanilla walk, so movement looks and behaves
like a vanilla mob, but no vanilla goal or target decides anything.

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

An authored Blockbench entity file is imported instead of re-typed.
`BedrockMobModel` reads the `.bbmodel` and takes the part layout as an explicit
mapping, because a model file names elements, not animatable parts:

```java
CustomMobModel model = BedrockMobModel.parse(bbmodelBytes)
        .textureName("soldier_villager")
        .part("head", MobPart.Role.HEAD, 0, 24, 0, "head_geo", "nose_geo", "helmet_cap")
        .part("body", MobPart.Role.BODY, 0, 12, 0, "body_geo", "skirt_geo")
        .part("right_leg", MobPart.Role.LEG_BACK_RIGHT, -2, 12, 0, "leg_r", "knee_r")
        .build(texturePng);
```

Pivots are the model's own (y up, feet at 0) and are converted to the rig's
convention; `inflate` and the authored per-face UV windows are preserved, so a
model painted in Blockbench needs no UV re-derivation. Elements an animation
cannot reach (or a malformed element with no box) are simply never claimed.
`examples/mineplus-mobfun` registers the Villager Soldier
(`mobfun:villager_soldier`) this way.

Part coordinates are vanilla entity-model pixels: `y` grows downward from
`modelHeightPx` (24 for a standard quadruped), the model front is `-z`, and
pivots are absolute. `MobPart.Role` tells the rig which vanilla pose applies —
`HEAD` tracks the entity's look direction, `LEG_*` swings with movement, `BODY`
and `STATIC` stay put — so a rigged mob animates without a custom animation
pipeline.

A file whose outliner is already the rig is imported whole instead:
`partsFromOutliner()` makes every group a bone (authored pivot, rest rotation
and parent), the elements directly inside it its geometry, and
`animationClips()` attaches the file's clips by bone name. `animation.idle` and
`animation.walk` are blended by movement speed, measured in blocks per second
from the entity's own velocity: full walk weight at the model's
`walkSpeedReference` (2 blocks/s by default, overridable in
`mobs/<id>.model.json`), with the walk clock and the procedural stride phase
scaled from the same value. An `animation.attack` clip is a one-shot overlay the
Core plays whenever the mob lands a melee hit (a module can also trigger it
through `MobRigRenderer.playAttack`). `examples/mineplus-mobfun`
uses this path for the Villager Soldier (`mobfun:villager_soldier`) and the
Woodland Trickster (`mobfun:woodland_trickster`, idle + walk + attack).

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
- `BoneRigRenderer` applies each piece's offset in the pose pass, so the
  pieces reassemble into the authored shape and rotate as one bone.

`MobRigRenderer` hides the base entity and presents each part (or piece) as an
`ItemDisplay` whose `item_model` component points at that model. The rig is
placed with `R_y(180° − bodyYaw + yawOffset)` — authored models face `-z`, so
this frame turns the model's front to the entity's own facing — and the head
bone additionally follows the entity's head yaw and look pitch. A model
authored facing another axis corrects itself with
`yawOffsetDegrees(...)` instead of code. All of this rides the existing pack
delivery — players already receiving the Core pack see the mob with no extra
download.

### Degradation

If the pack subsystem is unavailable, disabled, or the server has no
`item_model` display support, no rig is created and the base entity stays
visible. Identity, AI isolation, attributes, persistence and behavior are
unaffected, so a custom mob is always playable and testable without the visual
layer.

### Cost controls (`CUSTOM_MOBS`)

| Key | Default | Effect |
|---|---|---|
| `ENABLED` | `true` | Subsystem master switch |
| `RENDERING` | `true` | Create visual rigs at all |
| `RENDER_INTERVAL_TICKS` | `1` | Server ticks between rig pose pushes |
| `RENDER_INTERPOLATION_TICKS` | `1` | Client-side easing window per pose push; keep equal to the interval or the client lags/snaps |
| `RENDER_DISTANCE` | `48` | Blocks beyond which a rig stops receiving poses |
| `MAX_RIGGED_PER_WORLD` | `96` | Hard ceiling of rigged mobs per world |
| `REMOVE_UNKNOWN_ON_LOAD` | `true` | Remove custom-tagged entities whose definition is gone |

The rig pose task is lazy: it starts with the first attached rig and cancels
itself when the last one detaches.

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
- Creative and spectator players are never targeted.
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
/mobfun despawn              # remove every mob spawned through the test bench
```

`forceFire` dispatches by identity, so the report describes the custom profile
even though the entity's `EntityType` is `PIG` or `VILLAGER`. A failure means the
profile or its conditions blocked the action — never that the wrong profile ran.

---

## Checklist for a new custom mob

1. Decide the base type — it must be a vanilla `Mob` (pathfinding).
2. Add the texture PNG as a jar resource (or generate it), keeping the vanilla
   sheet layout if you use `CustomMobModel.vanilla`.
3. Build the `CustomMobDefinition`: id, base type, model, attributes, behavior.
4. Register it on module enable (`context.customMobs().register(this, def)`),
   unregister on disable.
5. Optionally expose a spawn command or spawner hook.
6. Verify with `/mobfun spawn`-style force-fire that the action list is what you
   expect, and that no vanilla behavior shows through.
