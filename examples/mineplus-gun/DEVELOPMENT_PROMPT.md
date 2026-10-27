# MineplusGun — Implementation Prompt

> **Read [`PLAN.md`](PLAN.md) first.** This file is the build contract: the
> exact layout, the Core surfaces the module is allowed to use, and the
> acceptance criteria. Where the plan and this file disagree, this file wins.
>
> The contract below describes the module **as it is today**: the runtime that
> used to live here moved into the Core, and what stayed here is content.

---

## 0) Mission

Build **`mineplus-gun`**, a standalone Mineplus module plugin that owns the
weapon *content* of the combat ecosystem — the arsenal, its prices, its
presentation and its shop — on top of the Core.

Hard rules:

1. **Never override a vanilla item, block, texture, model or sound.** Additive
   only (`item_model` on custom PDC items; sounds under `mineplusgun:`).
2. **Never re-implement a Core service.** Ballistics, held-item input, the
   magazine, the frame writer, the particle/sound budgets and the damage
   attribution are Core-owned (§2). A module that owns one of them again is a
   bug, not a feature.
3. The module must work with `PACK.ENABLED: false` (items degrade to their
   vanilla backing material) and must not crash without Vault.
4. Content is data-driven: adding a weapon is editing `config.yml`, not code.

---

## 1) The split (what this module owns vs. what the Core owns)

| Concern | Owner | Where |
|---|---|---|
| Round simulation (spread, gravity, drag, falloff, penetration, ricochet, tracers) | **Core** | `context.ballistics()` → `Ballistics` |
| Trigger routing (one click = one press) | **Core** | `context.heldItemInput()` → `HeldItemInput` / `TriggerState` |
| Rounds in the magazine, persisted on the stack | **Core** | `ItemMagazine` |
| Frame state written onto the stack | **Core** | `pack.item.ItemFrameWriter` + `pack.item.ItemFrames` |
| Particle / sound budgets | **Core** | `context.particleFx()`, `Sounds` |
| Damage attribution (never counted twice) | **Core** | `DamageTransactions` |
| Item identity, carrier, display, category, price | **Core parser** | `ContentItems.parse(namespace, id, section)` |
| **Weapon definitions** (ballistics values, fire mode, ammo, fx) | **module** | `config.yml` → `WeaponConfigLoader` |
| **The arsenal shop** (categories, purchase, Vault) | **module** | `shop/ShopGui`, `shop/ShopService` |
| **The ammo supply** (what a round costs in the inventory) | **module** | `runtime/AmmoSupply` |
| **The reload economy** (nuggets → rounds) and the reload gesture | **module** | `runtime/GunRuntime` |
| **The HUD** (action bar / boss bar) | **module** | `hud/AmmoHud` |
| **The weapon's own aim rules** (what may be shot) | **module** | `GunRuntime.GunRounds` → `ProjectileHandler` |

The rule of thumb: *if a second module would want the same behavior, it
belongs in the Core.* Targeting rules are the one place a module genuinely
differs from server to server, so they stay here — everything else is a
service.

---

## 2) Verified Core facts (do not re-derive; these were confirmed)

| Fact | Where |
|---|---|
| Module bootstrap: enable all features → **one** `reloadAll()` → register commands; teardown reverse order | `examples/mineplus-fun/src/main/java/com/mineplus/fun/MineplusFunPlugin.java`, `ModuleFeature.java` |
| Feature contract `id()/onEnable()/onDisable()/command()` | `ModuleFeature.java` |
| Top-level command registration (no `plugin.yml` entry needed) | `ModuleSupport.registerCommand(module, label, subCommand)` |
| Resource install into the **Core** data folder | `ModuleSupport.installDefault(module, classpathResource, dataRelativePath, overwrite)`, `installAll(...)`, `dataFile(...)` |
| Custom item registration | `PackApi.registerItem(PackItemDefinition)` / `registerItem(ContentItem#toPackItem())` |
| Identity + presentation are parsed by the Core | `ContentItems.parse(namespace, id, section)` → `ContentItem` (display, category, carrier, model, price, visibility) |
| Raw asset (sounds) | `PackApi.registerRawAsset(namespace, path, file, …)` — **non-minecraft namespace only** |
| Texture asset | `PackApi.registerTexture(namespace, path, file)` — path is extension-less (`item/<name>`) |
| Item create at runtime | `PackApi.createItem(namespace, id)` |
| A framed item's states | `ItemFrames` (idle / reload / loaded / fire) + `ItemFrameWriter.apply(stack, frameStackValue)` |
| A module may add a `/mineplus` subcommand | `ModuleSupport.registerCoreSubCommand(...)` |

Build (from the repo root):

```bash
npm run build-all          # Core + both example modules, both deployed
```

The module's `build.gradle` uses `compileOnly files("../../build/libs/mineplus-1.3.0.jar")`
plus the `libs/` stub jars. `plugin.yml` declares `depend: [Mineplus]` and
`softdepend: [Vault]`.

---

## 3) Assets

Everything the module ships is a **pack asset**; nothing vanilla is touched.

| Folder in the jar | Installed to | Registered as |
|---|---|---|
| `defaults/frames/<weapon-id>/<stem>.json` | `pack/mineplusgun/models/item/<weapon-id>/` | `mineplusgun:item/<weapon-id>/<stem>` |
| `defaults/frames/textures/<weapon>.png` | `pack/mineplusgun/textures/item/` | `mineplusgun:item/<texture-stem>` |
| `defaults/sounds/mineplusgun/*.ogg` | `pack/mineplusgun/sounds/` | `mineplusgun:sounds/<file>` |
| `defaults/sounds/sounds.json` | `pack/mineplusgun/sounds.json` | `mineplusgun:sounds.json` |
| `defaults/models/gun/<stem>.bbmodel` (+ `.png`, `.meta.json`) | `models/gun/<stem>/` | the Core's virtual-model folder |

The frame models are the **imported pack frames** — the guns' own `idle`,
`loading_N` and `primed` geometry, imported verbatim from the source packs by
`tools/import-western-models.ps1`. The `fire` pose is *authored on top of*
them and is deliberately not produced by the import, so re-importing never
overwrites it.

Frame naming is fixed by the Core's `ItemFrames` math: state `0` is idle,
`1..N` are the reload frames in order, `N+1` is the loaded state and `N+2` is
the fire state. `fireState()` returns `reloadReferences.size() + (loaded != null ? 2 : 1)`,
so a weapon without a `loaded` stem still has a fire state.

---

## 4) Configuration (shipped defaults — every field editable)

```yaml
settings:
  language: en
  hud:
    mode: ACTIONBAR            # ACTIONBAR | BOSSBAR | OFF
    template: "&e{weapon} &7| &f{mag}&7/&f{reserve} &7| &b{mode}"
    low-ammo-threshold: 2
  disabled-worlds: []          # gameplay (firing/shop use) disabled in these worlds
  pvp-only: false              # only damage players, never other living entities

ammo:
  material: GOLD_NUGGET        # what one round costs in the inventory
  items-per-round: 1           # items consumed per round

economy:
  mode: VAULT                  # VAULT | FREE
  price-multiplier: 1.0
  price-scale: 1

shop:
  title: "Frontier Gunsmith"
  rows: 6
  admin-bypass-payment: true

weapons:
  western_pistol:
    display-name: "&fWestern Pistol"
    type: PISTOL               # PISTOL | SMG | RIFLE | SHOTGUN | SNIPER | MELEE
    backing-material: IRON_INGOT
    price: 500
    frames:
      idle: idle
      reload: [reload0, reload1, reload2, reload3, reload4]
      loaded: loaded
      fire: fire
      texture: western_pistol.png
    ammo: { magazine: 6, reload-ticks: 50 }
    ballistics: { damage: 10.0, headshot-multiplier: 2.0, velocity: 3.2, gravity: 0.03, drag: 0.01, range: 70, spread-degrees: 1.2, falloff-start: 30, falloff-per-block: 0.03 }
    fire: { mode: SEMI, rpm: 280 }
    fx: { muzzle: SMOKE, flash: FLAME, smoke: SMOKE, spark: SMALL_FLAME, tracer: CRIT, impact: CRIT, impact-smoke: CLOUD, sound-fire: "mineplusgun:shot" }
```

`WeaponConfigLoader` must: reject an unknown `fire.mode`, clamp `rpm` to
`[30, 1200]`, clamp velocity/gravity/drag/penetration/ricochet to sane
ranges, default every missing field, and **log (not throw)** on a bad weapon
so one typo never disables the module.

### 4.1 The carrier (`backing-material`)

The carrier is the vanilla item the item model replaces, and it must be
**neutral** — no vanilla use action. A bow, a crossbow or food would put the
client into its own "using item" state, the client would then stop sending
further use packets, and the Core would read the press as a single shot
instead of a hold. An ingot or a stick keeps the vanilla interaction inert,
so the Core owns the whole trigger stream.

The Core resolves that stream **once for every module**: a modern client
answers a click on a block with two packets (use-on-block, then use-in-hand)
and the Core folds them into one press. A weapon never fires twice for one
click.

---

## 5) Presentation & shop

- Install the frame models, textures and sounds into the Core's data folder
  and register them as pack assets (`WeaponFeature`).
- Register every weapon with `context.packApi().registerItem(...)`.
- `ShopGui`: an `InventoryHolder` GUI, category rows + a full list. Clicking a
  weapon:
  - if the buyer has `mineplusgun.bypass.cost` (or `shop.admin-bypass-payment`
    and they are op) → **skip the balance check entirely**, deliver;
  - else if `economy.mode == VAULT` and Vault is present → `has` then
    `withdraw`, deliver; on failure, message and deliver nothing;
  - else (FREE / no Vault) → deliver.
- Delivery: inventory first, overflow dropped at the player's feet.

**Acceptance:** `/mpgun shop` opens; a normal player with Vault balance buys
and is charged; an admin is never charged; a no-Vault server still delivers;
with `PACK.ENABLED: false` the GUI still works and the items are their
backing material.

---

## 6) The fire flow (the part this module still owns)

- **Trigger.** Right-click is the one and only trigger (hold for `AUTO`);
  left-click is inert; swapping hands reloads. The press itself arrives
  through the Core's `HeldItemInput`, which resolves the click into a press
  and a hold. `TriggerState.freshPress()` tells the module whether this is a
  new gesture (fire once) or a continuation (keep an automatic weapon
  firing); a melee swing answers a click **once**.
- **Cadence.** `rpm` → interval `max(1, ceil(1200/rpm))` ticks. `AUTO` keeps
  firing while the trigger is held (capped per hold by
  `fire.max-hold-ticks`), `BURST` fires `fire.burst` rounds, `SEMI`/`PUMP`/
  `BOLT` are one round per press. A press that arrives while the weapon is
  still cycling is **queued**, never dropped.
- **Magazine.** The Core's `ItemMagazine` counts the rounds in memory while
  the stack is held and flushes one silent write when it leaves the hand.
  The module reads `rounds()` and spends one per shot.
- **Reload.** Swap hands (or pull the trigger on an empty weapon) starts a
  reload: it consumes `ammo.items-per-round` items of `ammo.material` per
  round from the shooter's inventory, fills the magazine, and plays the
  reload frames. A reload that cannot supply **one whole round** never
  starts — the HUD says so instead of playing a reload that ends empty. The
  reserve is counted in **rounds** everywhere, like the magazine.
- **Effects.** The whole shot effect (flash, spark, smoke, muzzle, tracer,
  impact, impact-smoke) is one Core `BallisticProfile`; the module only names
  the particles. An unknown particle name degrades to the default instead of
  silencing the weapon.
- **Damage.** The Core's `Ballistics` applies the damage; the module's own
  melee arc uses `DamageTransactions.apply(victim, amount, attacker)` so the
  same swing is never counted twice.
- **Frames.** The reload plays the reload frames across the reload duration
  (elapsed fraction, each pose held long enough to be legible), the loaded
  pose shows while rounds remain, the fire pose shows for a few ticks after
  the shot, and the idle pose is written onto the stack when the weapon
  leaves the hand. A frame is written only when it actually changes, and
  the stack is written back into the slot so the change reaches the client.

**Acceptance:** each fire mode behaves per config; one click is one shot;
the weapon fires while held in `AUTO`; a press during the cycle is not lost;
the HUD shows correct counts; an empty gun clicks instead of firing; a reload
cannot dupe ammo across an item switch or a death.

---

## 7) Commands

| Command | Permission | Notes |
|---|---|---|
| `/mpgun shop` · `/mineplus gun shop` | `mineplusgun.shop.use` | GUI |
| `/mpgun give <weapon> [player]` | `mineplusgun.admin` | grants the item with a full magazine |
| `/mpgun ammo [player] [amount]` | `mineplusgun.admin` | tops the ammo supply up (default 32) |
| `/mpgun hud` | `mineplusgun.hud` | toggle the ammo HUD |
| `/mpgun reload` | `mineplusgun.admin` | reload `config.yml` + the registry |
| `/mpgun stats` | `mineplusgun.admin` | weapon count, ballistics telemetry, particle budget |
| `/mpgun debug <hitbox\|trace>` | `mineplusgun.admin` | ballistics diagnostics |

Both labels route to the same `GunSubCommand` instance shape; each action
checks its own permission.

---

## 8) Anti-patterns (reject on sight)

- ❌ `registerRawAsset("minecraft", "items/bow.json", …)` or any vanilla path.
- ❌ `sounds.json` with `"replace": true` on a vanilla event.
- ❌ Spawning real projectile/arrow entities for bullets.
- ❌ One repeating task per bullet or per weapon; one task for the whole
  module, one session per shooter.
- ❌ Rewriting the held stack per shot — it replays the equip animation and
  spams slot packets; the Core's magazine keeps the count in memory.
- ❌ Hard-coded prices, damage, rpm, or colors — everything is config.
- ❌ Branching weapon logic on `packApi().isAvailable()` for gameplay
  (identity and firing must work without the pack).
- ❌ Editing Core game logic from this module.
- ❌ Charging an admin / bypass-permission holder in the shop.
- ❌ A non-neutral carrier (bow, crossbow, food) — it breaks the trigger hold.

---

## 9) Definition of done

1. `npm run build-all` succeeds with no errors.
2. Every configured weapon is registered and visible with its model; the
   frame models and textures resolve (no missing-model fallback).
3. Zero vanilla overrides: a diff of the generated pack shows only
   `assets/mineplusgun/**` additions.
4. Shop: a normal player is charged via Vault, an admin never is, it works
   without Vault, and it works with `PACK.ENABLED: false`.
5. Fire modes, the reload economy, the frames and the HUD behave per §6.
6. `README.md` for the module is in sync with the shipped config.

---

*Companion design document: [`PLAN.md`](PLAN.md).*
