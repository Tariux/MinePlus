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
| `attributes(CustomMobAttributes)` | none | Attribute overrides |
| `behavior(Consumer<MobProfile.Builder>)` | idle | The mob's entire behavior |
| `spawnConfigurator(Consumer<LivingEntity>)` | none | Last-word setup after identity/attributes |

Base types must be pathfinding `Mob`s — the Core navigates them with their own
vanilla walk, which needs the vanilla AI to stay enabled (see below).

### Attributes

`CustomMobAttributes` only states differences from the base entity:
`maxHealth`, `movementSpeed`, `attackDamage`, `followRange`, `armor`,
`knockbackResistance`. Unset fields keep the vanilla value. Health is applied as
a new maximum and filled on a fresh spawn; on a chunk restore the saved health
is clamped to the maximum instead of being healed back to full.

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

Part coordinates are vanilla entity-model pixels: `y` grows downward from
`modelHeightPx` (24 for a standard quadruped), the model front is `-z`, and
pivots are absolute. `MobPart.Role` tells the rig which vanilla pose applies —
`HEAD` tracks the entity's look direction, `LEG_*` swings with movement, `BODY`
and `STATIC` stay put — so a rigged mob animates without a custom animation
pipeline.

### How a model reaches the client

The Core compiles the model into pack assets:

1. the texture PNG under `textures/item/entity/<path>/texture.png`;
2. one item model per part under `models/item/entity/<path>/<part>.json`,
   with vanilla box-UV windows converted to element face windows (the same
   cross layout Blockbench derives);
3. one item definition per part under `items/entity/<path>/<part>.json`.

`MobRigRenderer` hides the base entity and presents each part as an
`ItemDisplay` whose `item_model` component points at that part's model. Displays
are positioned with the entity's body yaw (and look pitch for the head), so the
rig follows the mob. All of this rides the existing pack delivery — players
already receiving the Core pack see the mob with no extra download.

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
| `RENDER_INTERVAL_TICKS` | `2` | Server ticks between rig pose pushes |
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
/mobfun status              # registered showcases, including custom mobs
/mobfun evilpig on|off      # register/unregister the Evil Pig
/mobfun spawn evilpig       # spawn a custom mob in front of you and force-fire it
/mobfun fire evilpig force  # force the nearest one, ignoring profile conditions
/mobfun despawn             # remove every mob spawned through the test bench
```

`forceFire` dispatches by identity, so the report describes the custom profile
even though the entity's `EntityType` is `PIG`. A failure means the profile or
its conditions blocked the action — never that the wrong profile ran.

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
