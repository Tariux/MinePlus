# MineplusGun — Implementation Prompt

> **Read [`PLAN.md`](PLAN.md) first.** This file is the build contract: exact
> layout, verified Core APIs, phased tasks, and acceptance criteria. Follow it
> literally; where the plan and this file disagree, this file wins.

---

## 0) Mission

Build **`mineplus-gun`**, a standalone Mineplus module plugin that owns the
combat/weapons ecosystem (firearms, melee, grenades, ammo) on top of the Core.

Hard rules:

1. **Never override a vanilla item, block, texture, model or sound.** Additive
   only (`item_model` on custom PDC items; sounds under `mineplusgun:`).
2. **Never edit the Core's game logic.** The only Core changes allowed are the
   three additive enhancements in §2.
3. The module must work with `PACK.ENABLED: false` (items degrade to their vanilla
   backing material) and must not crash without Vault / PacketEvents.
4. Content is data-driven: adding a weapon is editing `config.yml`, not code.

---

## 1) Verified Core facts (do not re-derive; these were confirmed)

| Fact | Where |
|---|---|
| Module bootstrap: enable all features → **one** `reloadAll()` → register commands; teardown reverse order | `examples/mineplus-fun/src/main/java/com/mineplus/fun/MineplusFunPlugin.java`, `ModuleFeature.java` |
| Feature contract `id()/onEnable()/onDisable()/command()` | `ModuleFeature.java` |
| Top-level command registration (no `plugin.yml` entry needed) | `ModuleSupport.registerCommand(module, label, subCommand)` (`ModuleSupport.java:139`) |
| Resource install into the **Core** data folder | `ModuleSupport.installDefault(module, classpathResource, dataRelativePath, overwrite)` |
| Custom item registration | `PackApi.registerItem(PackItemDefinition)` |
| Item builder | `PackItemDefinition.builder(namespace, id, Material, modelKey)` + `.displayName()/.category()/.descriptionLines()` |
| `ItemCategory` values today | `UTILITY, TOOL, BLOCK_ITEM, MACHINE_COMPONENT, CATALYST` (no WEAPON) |
| Raw asset (sounds/lang) | `PackApi.registerRawAsset(namespace, path, file, …)` — **non-minecraft namespace only** |
| Item create at runtime | `PackApi.createItem(namespace, id)` |
| Persist instance state | `InfrastructureApi.stagePersist(instanceId)` |
| Model key resolution for multiblocks | `<typeId>_lvl_<level>` (not needed in v1; guns are items) |
| **Item `display` transforms are NOT imported or emitted** | `BbModelImporter` ignores `display`; `ModelJsonWriter.writeInternal(..., block=false)` always emits `vanillaDisplayTransforms()` (`ModelJsonWriter.java:121-126`) |
| **Modules cannot register `/mineplus` subcommands** | `CommandRouter` is private in `MineplusPlugin`; only `ModuleSupport.registerCommand` exists |

Build:

```bash
# Core (from repo root) — produces build/libs/mineplus-1.3.0.jar
gradle jar --offline

# Module (from examples/mineplus-gun)
gradle compileJava --offline
```

The module's `build.gradle` mirrors `examples/mineplus-fun/build.gradle`:
`compileOnly files("../../build/libs/mineplus-1.3.0.jar")` plus the same
`libs/` stub jars. `plugin.yml` must declare `depend: [Mineplus]` and
`softdepend: [Vault, packetevents]`.

---

## 2) Phase 0 — Core readiness (do these first, in the Core)

### 2.1 Import & emit item `display` transforms

- `BbModelImporter`: parse the bbmodel `display` object into a new immutable
  value on `VirtualModel` (context name → `{rotation[3], translation[3], scale[3]}`).
  Skip nothing else; keep the single streaming pass and the no-Base64 behavior.
- `ModelJsonWriter`: for **item** models (`block=false`), emit the authored
  `display` when present; otherwise keep the current `vanillaDisplayTransforms()`
  fallback. Block models (`block=true`) stay unchanged.
- `ModelMeta`: optional `display` override per model (used when the bbmodel has
  none, or to correct one) — merge precedence: meta override > bbmodel > vanilla.

**Acceptance:** recompiling a weapon model emits its authored
`firstperson_righthand` (etc.); existing models without `display` produce
byte-identical output to today; `gradle jar --offline` succeeds.

### 2.2 Module → Core subcommand registration

- Add `ModuleSupport.registerCoreSubCommand(SubCommand)` (or equivalent on
  `PluginContext`) that registers the subcommand on the Core's `CommandRouter`,
  enforcing `SubCommand.permission()`.
- Do not rename or remove any existing method.

**Acceptance:** a module can add `gun` so `/mineplus gun …` works, and
`/mineplus` help lists it.

### 2.3 Additive item categories

- Add `WEAPON` (and optionally `AMMO`) constants to `ItemCategory`. No existing
  constant changes its name or order-sensitive value.

---

## 3) Asset tasks

1. Copy all 19 `.bbmodel` (+ `.png`) from `C:\Users\Tariux\Desktop\mineplus-guns`
   into `examples/mineplus-gun/src/main/resources/defaults/models/gun/`.
2. **Export the embedded texture** for the three models lacking a PNG:
   `rifle-heavy-scope-gun-02`, `rifle-medium-m4-gun-02`, `shutgun-gun-01`
   (each has a Base64 `source`; the Core skips Base64). Write `<stem>.png` next
   to each. Verify with `/mineplus model info <key>` that texture resolution is
   not a fallback.
3. Reusable sounds from `temp/mineplus-gun-pack.zip` (`item/crossbow/shoot*.ogg`,
   `loading_*.ogg`, `custom/gun_bow.ogg`) are **renamed and re-homed** under
   `defaults/sounds/mineplusgun/`, with an additive `sounds.json`. Do **not**
   carry over the zip's `replace: true` vanilla entries.
4. Ambiguous comodare textures go to `defaults/textures/_unmapped/` untouched;
   mapped melee/ammo textures are copied only when their item is actually added.

---

## 4) Module skeleton & config

### 4.1 `plugin.yml`

```yaml
name: MineplusGun
version: ${version}
main: com.mineplus.gun.MineplusGunPlugin
api-version: '1.21'
depend: [Mineplus]
softdepend: [Vault, packetevents]
commands:
  mpgun:
    description: MineplusGun combat administration
    usage: /mpgun <shop|give|hud|reload|stats|debug>
permissions:
  mineplusgun.shop.use:   { description: Open the weapon shop, default: true }
  mineplusgun.shop.buy.*: { description: Purchase weapons, default: true }
  mineplusgun.admin:      { description: Gun administration, default: op }
  mineplusgun.bypass.cost:{ description: Skip shop payment, default: op }
  mineplusgun.hud:        { description: Toggle the ammo HUD, default: true }
```

### 4.2 `config.yml` (shipped defaults — every field editable)

```yaml
settings:
  language: en
  hud:
    mode: ACTIONBAR        # ACTIONBAR | BOSSBAR | OFF
    template: "&e{weapon} &7| &f{mag}&7/&f{reserve} &7| &b{mode}"
    low-ammo-threshold: 3
  disabled-worlds: []
  pvp-only: false
  block-damage: true       # grenade terrain damage

ballistics:
  max-active-bullets: 96
  max-particles-per-tick: 240

economy:
  mode: VAULT              # VAULT | FREE
  price-multiplier: 1.0
  price-scale: 1

shop:
  title: "Weapon Store"
  rows: 6
  admin-bypass-payment: true

weapons:
  rifle_ak:
    display-name: "AK Rifle"
    model: gun/rifle-ak-gun-05
    category: RIFLE
    # Carrier material only (the item_model draws the weapon). Neutral by default
    # so no side effect occurs; use SPYGLASS only for a pure-Bukkit hold/release
    # carrier when PacketEvents is unavailable (accepts the scope zoom).
    backing-material: FLINT_AND_STEEL
    price: 2700
    ammo:
      ammo-type: 545
      magazine: 30
      reload-ticks: 40
    ballistics:
      damage: 7.0
      headshot-multiplier: 2.0
      velocity: 3.2
      gravity: 0.03
      drag: 0.01
      penetration: 1
      ricochet: 0.0
      range: 120
      spread-degrees: 1.2
    fire:
      mode: AUTO
      rpm: 600
    fx:
      muzzle: SMOKE
      tracer: CRIT
      sound-fire: "mineplusgun:rifle_fire"
```

`WeaponConfigLoader` must: reject unknown `fire.mode`, clamp `rpm` to
`[30, 1200]`, clamp `velocity/gravity/drag/penetration/ricochet` to sane
ranges, default every missing field, and log (not throw) on a bad weapon so one
typo never disables the module.

---

## 5) Phase 1 — Presentation & shop

- Install models/meta (`overwrite=true`), load them via the Core, then register
  every weapon with `PackApi.registerItem(...)` using `modelKey` = the installed
  stem (e.g. `gun/rifle-ak-gun-05`).
- `WeaponItemFactory` returns `PackApi.createItem(ns, id)` and stamps the runtime
  PDC block (`mpgun:weapon`, `mpgun:ammo`) via `ItemMeta`.
- `ShopGui`: an `InventoryHolder` GUI (not the Core's multiblock GUI), category
  rows + a full list; clicking a weapon:
  - if buyer has `mineplusgun.bypass.cost` (or `shop.admin-bypass-payment` and
    they are op) → **skip balance check entirely**, deliver.
  - else if `economy.mode == VAULT` and Vault present → `has` then `withdraw`,
    deliver; on failure message and deliver nothing.
  - else (FREE / no Vault) → deliver.
- Delivery: inventory first, overflow dropped at the player's feet.

**Acceptance:** `/mpgun shop` opens; a normal player with Vault balance buys and
is charged; an admin is never charged; a no-Vault server still delivers; with
`PACK.ENABLED: false` the GUI still works and items are their backing material.

---

## 6) Phase 2 — Shooting runtime

- `InputAdapter` selected at enable: `PacketInputAdapter` when PacketEvents is
  present, else `BukkitInputAdapter`. Weapon logic never branches on the adapter.
- `GunRuntime`: one repeating task per active shooter drives AUTO/BURST; SEMI/
  PUMP/BOLT are event-driven. `rpm` → interval `max(1, ceil(1200/rpm))` ticks.
- `Ballistics`: implement the tick-stepped model from `PLAN.md §6` exactly —
  drag → gravity → step → block/entity ray → nearest hit → penetration/ricochet
  → damage. Respect `range`, `max-active-bullets`, and resolve overflow as an
  instant trace.
- `AmmoModel`: `mpgun:ammo` PDC; spend one per shot; dry-fire on empty; `reload`
  restores `magazine` from reserve over `reload-ticks`, abortable.
- `AmmoHud`: action bar per `settings.hud.template`; low-ammo color; reload state.
- `ParticleFx`/`SoundFx`: muzzle/tracer/impact within `max-particles-per-tick`;
  all sounds via `mineplusgun:` events.

**Acceptance:** each fire mode behaves per config; no bullet is spawned as an
entity; TPS stays flat with `max-active-bullets` shooters at 1200 rpm; HUD shows
correct counts; empty guns dry-fire; reload cannot dupe ammo across item
switch/death.

---

## 7) Phase 3 — Grenades & melee

- `THROW`: on use, consume one, launch a simulated grenade (gravity + bounce,
  reuse the ballistics stepper), pin sound, fuse countdown, then explosion.
- Explosion: radius + line-of-sight falloff damage, optional block damage gated
  by `settings.block-damage`/world allow-list, smoke ring particles.
- `MELEE`: swing → arc sweep (short ray + cone), `damage`, optional knockback;
  no ammo, cooldown by `fire.rpm`. Grenades and melee use the same item factory.

**Acceptance:** grenade detonates after the configured fuse, damage falls off
with distance and respects walls; melee hits entities in the arc only; both
respect disabled worlds.

---

## 8) Phase 4+ — Ammo economy & rigs (do not start until 1–3 are accepted)

- Ammo and magazine items (`registerItem`), reload action consuming a mag item,
  reserve/mag persistence in PDC.
- Animated draw/reload via `AnimationApi` once the Core's display/anim pipeline
  supports it; keep the `InputAdapter`/runtime seam so no weapon logic changes.

---

## 9) Commands

| Command | Permission | Notes |
|---|---|---|
| `/mpgun shop` · `/mineplus gun shop` | `mineplusgun.shop.use` | GUI |
| `/mpgun give <weapon> [player]` | `mineplusgun.admin` | grants item + full mag |
| `/mpgun hud` | `mineplusgun.hud` | toggle action bar |
| `/mpgun reload` | `mineplusgun.admin` | reload `config.yml` + registry |
| `/mpgun stats` | `mineplusgun.admin` | bullet/task/fx counters |
| `/mpgun debug <hitbox\|trace>` | `mineplusgun.admin` | ballistics diagnostics |

Register `/mpgun` via `ModuleSupport.registerCommand(plugin, "mpgun", …)` and
`/mineplus gun` via the new `registerCoreSubCommand` from §2.2. Both route to the
same subcommand tree; the permission check is enforced by the wrapper.

---

## 10) Anti-patterns (reject on sight)

- ❌ `registerRawAsset("minecraft", "items/bow.json", …)` or any vanilla path.
- ❌ `sounds.json` with `"replace": true` on a vanilla event.
- ❌ Spawning real projectile/arrow entities for bullets.
- ❌ One repeating task per bullet or per weapon; one task per shooter only.
- ❌ Hard-coded prices, damage, rpm, or colors — everything is config.
- ❌ Branching weapon logic on `packApi().isAvailable()` for gameplay (identity
  and firing must work without the pack).
- ❌ Editing Core game logic outside the three §2 enhancements.
- ❌ Charging an admin / bypass-permission holder in the shop.

---

## 11) Definition of done (v1)

1. `gradle jar --offline` (Core) and `gradle compileJava --offline` (module) both
   succeed with no errors.
2. All 19 weapons registered and visible with correct models in-game; the three
   exported textures resolve (no fallback) in `/mineplus model info`.
3. Zero vanilla overrides: a diff of the generated pack shows only
   `assets/mineplusgun/**` additions.
4. Shop: normal player charged via Vault, admin never charged, works without
   Vault, works with `PACK.ENABLED: false`.
5. Fire modes, ballistics, HUD, grenades, and melee meet the phase acceptance
   criteria.
6. `README.md` for the module + a short `config-reference` section are written;
   the Core docs' `migration-guide` notes the Gunsmith → Gun module transition.

---

*Companion design document: [`PLAN.md`](PLAN.md).*
