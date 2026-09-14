# MineplusGun — Unified Combat Engine (Design Plan)

> **Status:** planning only. No wiring code exists yet. This document is the
> contract the implementation prompt ([`DEVELOPMENT_PROMPT.md`](DEVELOPMENT_PROMPT.md))
> is built from.
>
> Sibling module: [`examples/mineplus-fun`](../mineplus-fun/README.md). The Gun
> module depends on the [Mineplus Core](../../README.md) exactly like Fun does
> (`depend: [Mineplus]`, APIs through `MineplusPlugin#getPluginContext()`).

---

## 1) Vision

A standalone module, **`mineplus-gun`**, that owns the whole combat/weapons
ecosystem on top of the Core: firearms, melee, grenades, ammunition, and (later)
attachments and animated rigs. It is **fully additive** — it adds custom items
and never replaces a single vanilla item, block, texture or sound.

Two hard promises drive every design decision:

1. **No vanilla override, ever.** Guns are Mineplus items with PDC identity and
   an additive `item_model`; sounds live under our own namespace; the old
   Gunsmith approach (overlaying `bow`/`crossbow`) is **retired**, not extended.
2. **Big-server ready.** Input, ballistics, HUD, particles and the shop must be
   bounded, allocation-light, per-tick-cheap, region-safe where possible, and
   configurable per weapon. Content scales by config, never by code.

---

## 2) Scope

| In v1 | Later phases |
|---|---|
| Firearms (pistols, SMGs, rifles, shotguns, snipers) | Attachments (scopes, grips, mags) |
| Melee (knives; optional STALKER-style set) | Animated rigs (reload/draw) via `AnimationApi` |
| Grenades (thrown, fuse, radius falloff) | Armor/gear, medical, food, currency items |
| Ammunition items + magazine model | Mag-fed reload, per-mag state, ammo crafting |
| Action-bar ammo HUD, particles, no-override sounds | Hit-markers, kill feed, kill rewards |
| Vault shop GUI + permissions + admin bypass | Physical shop block (multiblock) |

---

## 3) Non-negotiables (verified against the Core)

- **Presentation:** custom items via `PackApi.registerItem(PackItemDefinition)`
  (additive `item_model` on 1.21.4+, legacy `CustomModelData` on 1.21–1.21.3).
  Never `registerRawAsset` into the `minecraft` namespace for a vanilla asset
  path. (The retired Gunsmith did exactly that on purpose — do not repeat it.)
- **Sounds:** `sounds.json` under `assets/mineplusgun/`, events played as
  `mineplusgun:<event>`. No `replace: true` on any vanilla event.
- **Identity:** PDC through `ItemRegistry`; gameplay must keep working with
  `PACK.ENABLED: false` (items fall back to their vanilla backing material).
- **World state:** ammo/mag data in item PDC + `stateData` via
  `InfrastructureApi.stagePersist` when relevant; nothing global-mutable.

---

## 4) Asset inventory & organization

### 4.1 Source of truth

All weapon geometry + textures live in `C:\Users\Tariux\Desktop\mineplus-guns`
(19 `.bbmodel` + 16 matching `.png`). The legacy
`temp/mineplus-gun-pack.zip` and `temp/comodare_sample_item/` are **reference
only** in this plan: the zip supplies reusable *sounds*, the comodare tree
supplies *2D item textures* for the STALKER-style melee/consumable sets.

### 4.2 Weapon models (Desktop)

| # | File stem | Class | Fire mode | PNG |
|---|---|---|---|---|
| 1 | `colt-cobra-gun-03` | Pistol (revolver) | SEMI | ✅ |
| 2 | `colt-heavy-gun-01` | Pistol (heavy) | SEMI | ✅ |
| 3 | `colt-deagle-heavy-gun-02` | Pistol (heavy/Deagle) | SEMI | ✅ |
| 4 | `negav-heavy-gun-01` | Pistol (revolver/heavy) | SEMI | ✅ |
| 5 | `rifle-ak-gun-05` | Rifle (AK) | AUTO | ✅ |
| 6 | `rifle-medium-gun-01` | Rifle (medium) | AUTO | ✅ |
| 7 | `rifle-medium-m4-gun-02` | Rifle (M4) | AUTO | ⚠️ **missing PNG** |
| 8 | `rifle-heavy-scope-gun-02` | Rifle (DMR/scoped) | SEMI | ⚠️ **missing PNG** |
| 9 | `rifle-smg-gun-01` | SMG | AUTO | ✅ |
| 10 | `rifle-smg-gun-04` | SMG | AUTO | ✅ |
| 11 | `rifle-smg-uzi-gun-03` | SMG (Uzi) | AUTO | ✅ |
| 12 | `shutgun-gun-01` | Shotgun | PUMP/BURST | ⚠️ **missing PNG** |
| 13 | `shutgun-gun-old-02` | Shotgun (old) | PUMP | ✅ |
| 14 | `sniper-heavy-gun-01` | Sniper | BOLT | ✅ |
| 15 | `sniper-heavy-gun-02` | Sniper | BOLT | ✅ |
| 16 | `sniper-heavy-awp-gun-03` | Sniper (AWP) | BOLT | ✅ |
| 17 | `knife-01-light` | Melee (light) | MELEE | ✅ |
| 18 | `knife-02-heavy` | Melee (heavy) | MELEE | ✅ |
| 19 | `geanade-red-01` | Grenade (thrown) | THROW | ✅ |

**Asset task A (blocking for 3 models):** `rifle-heavy-scope-gun-02`,
`rifle-medium-m4-gun-02`, `shutgun-gun-01` have **no adjacent PNG**. Every
bbmodel embeds its texture as a Base64 `source`, but the Core's importer
deliberately skips Base64 (dead branch). Export each embedded texture to
`<stem>.png` next to its model, exactly like the other 16.

**Asset task B:** all models carry a `display` block (first-person/third-person
poses) but the Core currently ignores it — see §10 Core enhancements. Without it
every gun renders with the generic item pose.

### 4.3 STALKER-style set from `comodare_sample_item` (2D textures)

These are 16×16 item textures; their meaning is documented by that pack's
`lang/en_us.json`. v1 uses the melee subset; the rest is later-phase.

| Texture (stem) | Concept | Planned use |
|---|---|---|
| `bagnet3` / `bagnet6h3` / `bagnety6h4` / `bagnet_scout*` | Bayonets 6H3/6H4/6H5, scout knife | Melee (bayonet) |
| `siekiera1..11` | Fire axe / ice axe / sapper axe | Melee (axe) |
| `lom1..6` | Crowbar | Melee |
| `sierp1..7` | Sickle | Melee |
| `mlot*` / `mlotek1` | Hammer | Melee |
| `maczeta.i` | Taiga-1 machete | Melee |
| `widly1..11` | Pitchfork | Melee |
| `tonfa1..2` | Tonfa | Melee |
| `gazrurka` | Gas pipe | Melee |
| `kalach*`, `nowykalach*` | AK texture variants | Firearm re-skins (later) |
| `amunicja1..2` | 5.45×39 / 9×18 ammo | Ammunition items |
| `medyka2`, `enchant_golden_apple` | AI-2 medkits | Consumable (later) |
| `chlyb1..2`, `tushonka*`, `makaron1` | Food | Consumable (later) |
| `ruble1` | Soviet rubles | Currency item (later) |
| `chainmail_*`, `iron_*`, `diamond_*`, `netherite_*` | Armor sets | Gear (later) |
| `texture*.png` (noise) | Unidentified / scratch | **Do not ship** — quarantine |

> **Open item:** the `textureNNN.png` family and a few stems (`baniak*`,
> `miotla1`, `latarnik1`, `manierka2`, `papiersy*`, `pasnosny1`, `staza2`,
> `wata2`, `szlug1`, `junglestyle`, `medyka2`) are ambiguous. They stay in a
> `_unmapped/` quarantine folder until the author confirms their identity.

### 4.4 Installed layout (Core data folder)

The module installs its content through
`context.moduleSupport().installDefault(plugin, classpathResource, dataPath, overwrite)`
— models and per-model meta with `overwrite=true`, configs with `overwrite=false`:

```
plugins/Mineplus/
├── models/gun/<stem>.bbmodel            ← 19 weapon models
├── models/gun/<stem>.png                ← adjacent textures (texel + pack)
├── models/gun/<stem>.meta.json          ← per-model originBrightness/texel opt-ins
└── (the module keeps its own config at plugins/MineplusGun/config.yml)
```

Module jar resources mirror this:

```
examples/mineplus-gun/src/main/resources/
├── plugin.yml
├── config.yml                           ← shipped defaults (weapons/prices/shop)
├── defaults/models/gun/…                ← models + png + meta
├── defaults/lang/…                      ← message files
└── defaults/sounds/…                    ← .ogg (renamed, non-vanilla namespace)
```

---

## 5) Architecture

### 5.1 Module skeleton (mirrors `mineplus-fun`)

```
com.mineplus.gun
├── MineplusGunPlugin                 ← bootstrap: enable → install/register → ONE reloadAll → register commands
├── ModuleFeature (copy of fun's contract)
├── command/
│   ├── GunRootSubCommand            ← "/mpgun …"  (own top-level command)
│   └── gun/…                        ← "/mineplus gun …" (registered on the Core router, see §10)
├── weapon/
│   ├── WeaponType                   ← enum: PISTOL, SMG, RIFLE, SHOTGUN, SNIPER, MELEE, GRENADE
│   ├── WeaponDefinition             ← immutable, parsed from config.yml
│   ├── WeaponRegistry               ← id → definition; reloadable
│   └── WeaponItemFactory            ← definition → ItemStack (PackApi.createItem + PDC runtime block)
├── runtime/
│   ├── GunRuntime                   ← per-player session; fire-flow state machine
│   ├── FireMode                     ← SEMI | AUTO | BURST | PUMP | BOLT | MELEE | THROW
│   ├── AmmoModel                    ← magazine state in PDC (mag-ready for phase 4)
│   ├── Ballistics                   ← tick-stepped simulation (§6)
│   └── GrenadeRuntime               ← fuse + radius falloff (§7)
├── input/
│   ├── InputAdapter (interface)     ← press/release/aim/switch signals
│   ├── PacketInputAdapter           ← PacketEvents (soft-depend)
│   └── BukkitInputAdapter           ← fallback (interact + swing-timeout heuristic)
├── hud/
│   ├── AmmoHud                      ← action bar (§8)
│   └── HudText                      ← template + colors, per-player toggle
├── fx/
│   ├── ParticleFx                   ← muzzle/tracer/impact (pooled, budget-capped)
│   └── SoundFx                      ← mineplusgun:<event> playback
├── shop/
│   ├── ShopGui                      ← InventoryHolder GUI, categories + full list
│   ├── ShopService                  ← price lookup, purchase validation, delivery
│   └── EconomyBridge                ← Vault (soft-depend) + admin bypass
└── config/
    ├── GunConfig                    ← settings/economy/shop/weapons
    └── WeaponConfigLoader           ← validates and clamps every field
```

### 5.2 Feature decomposition (ModuleFeature instances)

| Feature id | Owns |
|---|---|
| `weapons` | Model/texture/meta install, `PackItemDefinition` registration, `WeaponRegistry` |
| `combat` | Input adapters, `GunRuntime`, ballistics, damage, grenades |
| `hud` | Action-bar ammo display |
| `shop` | Vault economy, shop GUI, purchase flow |

Bootstrap order: install & register **all** features, then **one** coordinated
`reloadAll()`, then register `/mineplus gun` + `/mpgun`. Teardown in reverse.

---

## 6) Ballistics — tick-stepped simulation (v1)

Chosen model: a **server-side simulated projectile**, not an instant hitscan and
not a spawned entity. One `Bullet` marches a small step per tick until it hits,
expires, or leaves range.

```
per tick:
  age += 1
  velocity *= (1 - drag)                    // linear drag per tick
  velocity.y -= gravity                     // blocks/tick²
  step = velocity * stepScale
  ray = world.rayTraceBlocks(eye→step)      // block hit
  ents = world.rayTraceEntities(eye→step)   // living hit (hitbox sweep)
  resolve nearest hit
  on entity hit  → damage, penetration check, stop or continue
  on block hit   → penetration/ricochet, chips + sound, stop or continue
  else advance position
```

Per-weapon ballistics config (all clamped by the loader):

| Field | Meaning |
|---|---|
| `velocity` | blocks/tick (converted to a per-tick step; e.g. 3.0 = 60 blocks/s) |
| `gravity` | downward acceleration per tick (0 = laser-flat pistol, ~0.05 = rifle drop) |
| `drag` | velocity multiplier lost per tick (0–1) |
| `penetration` | how many entities/blocks a round may pass through, with damage falloff |
| `ricochet` | chance (0–1) and max bounces off a block face |
| `range` | hard cap in blocks |
| `spreadDegrees` | base cone; grows with movement/sustained fire |
| `headshotMultiplier` | extra damage on head hitbox |
| `falloff` | damage multiplier per block beyond a start distance |

**Determinism & cost:** one ray trace pair per bullet per tick, capped by a
global `ballistics.max-active-bullets`; a bullet that would exceed the budget is
resolved as an instant trace (graceful degradation, never unbounded).

---

## 7) Fire flow & ammunition

- **Fire modes:** `SEMI` (one shot per press), `AUTO` (continuous while held,
  gated to `rpm`), `BURST` (n rounds per press at `rpm`), `PUMP`/`BOLT` (a
  cycle cooldown between shots), `MELEE` (arc sweep, no ammo), `THROW` (grenade).
- **`rpm` → interval ticks:** `ceil(1200 / rpm)`, minimum 1 tick. A single
  per-player repeating task drives all AUTO weapons (one task per shooter, not
  per bullet).
- **Ammo model (mag-ready):** the magazine size and current rounds live in the
  item's PDC (`mpgun:ammo`, `mpgun:mag`). v1 refills from the shop/command; phase
  4 inserts magazine *items* and a reload action. Reserve ammo and reload time
  are optional per weapon.
- **Reload:** `reloadTicks` gate; the HUD shows a reload bar; cancelling
  (switching item/dying) aborts cleanly and never dups ammo.
- **Dry fire:** a distinct click sound + HUD flash, never a wasted round.

### Input resolution (best-feel, future-proof)

`InputAdapter` abstracts *press / release / aim / weapon-switch*:

- `PacketInputAdapter` (when PacketEvents is present): true press/release via the
  use-item packets and swing/attack packets; reliable AUTO hold and clean release.
  Registered as a **soft-depend** — absence is not an error.
- `BukkitInputAdapter` (always available): `PlayerInteractEvent` for press; for
  release it listens to `PlayerStopUsingItemEvent`, which **only fires when the
  underlying item is "usable" (has a use duration)**. A neutral backing material
  (no use duration) therefore yields no release event, so the adapter additionally
  treats item-switch / drop / sneak / quit as hard stops and falls back to a
  swing-timeout heuristic for AUTO.

  > **Backing-material trade-off.** The vanilla material behind a gun is a
  > *carrier*, not its look (the additive `item_model` draws the model). If a
  > true press/release is wanted **without PacketEvents**, base AUTO weapons on a
  > usable, non-projectile carrier — in practice `SPYGLASS` (hold = using; release
  > fires the stop event) — accepting the client-side scope zoom as a side effect
  > (or as a deliberate ADS feature for scoped weapons). Default to a neutral
  > carrier (`FLINT_AND_STEEL`) when PacketEvents is present, so no zoom occurs.

The runtime never branches on the concrete adapter, so a future animated-rig
input source drops in without touching weapon logic.

---

## 8) HUD, particles, sounds

- **Ammo HUD:** the bottom-of-screen text area (`Player#sendActionBar`, Adventure
  if available). Template from config, e.g.
  `"⟦ {weapon} ⟧ {mag}/{reserve}   ·   {mode}"`, with low-ammo/empty colors and a
  `reload` state. Per-player toggle (`/mpgun hud`). Never a boss bar by default
  (config option to switch).
- **Particles:** muzzle flash at the barrel tip, a *sparse* tracer along the
  resolved path (one puff per N blocks, config-tunable), impact puff colored by
  surface, and a grenade smoke ring on detonation. All counts are per-weapon
  config values and clamped; a global `fx.max-particles-per-tick` budget degrades
  gracefully rather than spiking TPS.
- **Sounds:** shipped `.ogg` under `assets/mineplusgun/` with a `sounds.json`
  registering **additive** events (`mineplusgun:pistol_fire`, `…:reload`,
  `…:grenade_pin`, …). Distance attenuation via location sounds. Vanilla
  `entity.arrow.shoot` and crossbow sounds are **never** replaced.

---

## 9) Shop & economy

- **Command form:** `/mpgun shop` (and `/mineplus gun shop`) opens a GUI listing
  **all** items, grouped by category (Pistols, SMGs, Rifles, Shotguns, Snipers,
  Melee, Grenades, Ammo). Configurable title/rows; icons are the real weapon items.
- **Economy:** **Vault** (soft-depend). Absence of Vault, or a category with
  `free: true`, disables payment.
- **Access control via standard permissions** (works with LuckPerms etc.):
  - `mineplusgun.shop.use` — open the shop (default: all players)
  - `mineplusgun.shop.buy.<category|weaponId>` — purchase permission per group
  - `mineplusgun.admin` — admin commands (`give`, `reload`, `debug`, `stats`)
  - `mineplusgun.bypass.cost` — **skip payment entirely** (admin/staff)
- **Admin bypass (required):** if the buyer has `mineplusgun.bypass.cost` (or is
  an admin with the default node), the purchase path **skips balance checks and
  withdrawals completely** and delivers instantly. Otherwise the price is checked
  and withdrawn atomically (`Economy#has` then `withdrawPlayer`; on failure, no
  item is given and the player is told why).
- **Prices:** defaults are Counter-Strike-flavoured (see §11), scaled by
  `economy.price-multiplier` and rounded by `economy.price-scale`. Every price is
  editable per weapon in `config.yml`; a weapon can be free, hidden, or
  permission-gated.
- **Delivery:** purchased items go to the inventory, overflowing to a drop at the
  player's feet (never silently lost); optional `economy.refund-on-full: false`.

---

## 10) Required Core enhancements (approved)

Three small, additive Core changes make the module possible without hacks.

1. **Import & emit item `display` transforms.**
   - *Gap:* `BbModelImporter` ignores the bbmodel `display` block; `ModelJsonWriter.writeInternal(..., block=false)` always emits hardcoded `vanillaDisplayTransforms()` (`ModelJsonWriter.java:121-126`). So every gun loses its authored first-person pose.
   - *Change:* capture `display` into `VirtualModel` (context → rotation/translation/scale); emit it for item models when present (fall back to the current vanilla defaults otherwise); allow a per-model `.meta.json` `display` override for models lacking one.

2. **Expose `/mineplus` subcommand registration to modules.**
   - *Gap:* the Core's `CommandRouter` is private to `MineplusPlugin`; modules can only register their own top-level labels via `ModuleSupport.registerCommand` (`ModuleSupport.java:139`).
   - *Change:* add `ModuleSupport.registerCoreSubCommand(SubCommand)` (or `PluginContext` equivalent) so a module can add `gun` under `/mineplus`, with its permission enforced by the Core router.

3. **Optional metadata:** add `ItemCategory.WEAPON` and `AMMO` (purely additive
   enum constants) so shop grouping and item metadata read naturally instead of
   overloading `TOOL`.

None of these change existing behavior when unused.

---

## 11) Default pricing (Counter-Strike flavour)

The economy unit is 1 Vault unit per 1 CS dollar, scaled by
`economy.price-multiplier` (default `1.0`).

| Weapon | Concept | Suggested price |
|---|---|---|
| `pistol_cobra` | Colt Cobra | 600 |
| `pistol_heavy` | Colt heavy | 700 |
| `pistol_deagle` | Desert Eagle | 700 |
| `pistol_negav` | Heavy revolver | 600 |
| `smg_mp` | SMG | 1250 |
| `smg_uzi` | Uzi | 1050 |
| `rifle_ak` | AK | 2700 |
| `rifle_m4` | M4 | 3100 |
| `rifle_medium` | Medium rifle | 2500 |
| `rifle_dmr` | Scoped rifle | 4200 |
| `shotgun_old` | Old shotgun | 1100 |
| `shotgun_pump` | Shotgun | 1700 |
| `sniper_heavy` | Sniper | 4750 |
| `sniper_awp` | AWP | 4750 |
| `knife_light` | Light knife | 200 |
| `knife_heavy` | Heavy knife | 350 |
| `grenade_red` | Grenade | 300 |
| `ammo_9x18` / `ammo_545` | Ammo bundle | 30 / 60 |

Every value is a config default, not a constant.

---

## 12) Scaling & robustness rules

- One repeating task per *active shooter*, not per weapon or per bullet.
- Bullets and particles obey global caps; over-budget work degrades to the cheap
  path (instant trace / skip tracer).
- No world mutation except grenade explosions, which honor
  `settings.block-damage` and (optionally) a region/world allow-list.
- All per-player state keyed by UUID in concurrent maps; cleared on quit.
- Every gameplay path null-guards a disabled pack subsystem: with
  `PACK.ENABLED: false` the module still fires, shops, and shows the HUD (items
  render as their vanilla backing material).
- Folia: avoid cross-region world writes; simulation runs on the shooter's
  region; document any unsupported grenade world edits.

---

## 13) Phasing

| Phase | Deliverable |
|---|---|
| **0 — Core readiness** | Display-transform import/emit; module core-subcommand registration; asset export for the 3 missing PNGs |
| **1 — Presentation** | Models/meta installed, weapon items registered (all 19), shop GUI + Vault + permissions + admin bypass |
| **2 — Shooting** | Input adapters, FireMode state machine, tick-stepped ballistics, ammo PDC, HUD, particles/sounds |
| **3 — Grenades & melee** | Thrown grenade (fuse/falloff/blocks), melee arc, STALKER melee set |
| **4 — Ammo economy** | Ammo items, magazine items, reload action, crafting/reload UI |
| **5 — Rigs** | Animated draw/reload via `AnimationApi`; attachments |

---

## 14) Open items to confirm during implementation

1. Export the three missing textures (Asset task A).
2. Confirm identity of the quarantined ambiguous textures (§4.3).
3. Confirm per-weapon damage/velocity defaults with the author after first
   in-game calibration.
4. Decide whether a physical shop block joins v1 or stays a later phase.

---

*Plan authored for MineplusGun. Companion implementation prompt:*
[`DEVELOPMENT_PROMPT.md`](DEVELOPMENT_PROMPT.md).
