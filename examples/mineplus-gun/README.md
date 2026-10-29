# MineplusGun

A standalone [Mineplus](../../README.md) module that owns the firearms
ecosystem — guns, magazines, scopes and a Vault shop — as **fully additive**
content. It never overrides a vanilla item, block, texture, model or sound.

- **[DEVELOPMENT_PROMPT.md](DEVELOPMENT_PROMPT.md)** — the build contract:
  verified Core APIs, exact layout and acceptance criteria.

## Status

The final gun system. Every piece of the first implementation (bow/crossbow
carriers, left-click fire, per-shot stack writes, hand-rolled frame lists, the
retired nine-gun Western arsenal) is deleted, not deprecated. What is left:

- four shipped guns driven entirely by `config.yml`;
- presentation **discovered from the model files themselves** — no frame list
  to keep in sync;
- right-click to fire, left-click reserved for the scope, F to reload, empty
  guns reload themselves;
- recoil, movement inaccuracy, knockback, pellet scatter, penetration,
  falloff, tracer/particle/sound feedback — all through the Core's ballistics,
  held-item input, magazine, HUD and effect budgets;
- an event-driven runtime: no per-tick player scan, no work while nobody
  shoots.

## Quick facts

| | |
|---|---|
| Plugin id | `MineplusGun` (`depend: [Mineplus]`, `softdepend: [Vault]`) |
| Commands | `/mpgun …` and `/mineplus gun …` |
| Item axis | `PackApi.registerItem` (additive `item_model`; legacy `CustomModelData` fallback) |
| Physics | tick-stepped simulated rounds (gravity, drag, penetration, ricochet, falloff, knockback) |
| Input | the Core's held-item seam: right-click fires, left-click scopes and is otherwise swallowed, swap-hands reloads |
| Presentation | Core `ItemFrames` + `ItemFrameAnimator`, states discovered from `defaults/guns/<id>/models/` |
| Economy | Vault; all prices config-driven; admins/bypass-permission never charged |
| Assets | 4 guns (`pistol`, `shotgun`, `rifle`, `battle_rifle`) with 28 frame models, 4 sprites and 21 sound samples under the `mineplusgun` namespace |

## Controls

| Input | Action |
|---|---|
| **Right-click** | Fire. One press shoots; `AUTO` keeps firing while the Core's trigger reports the hold. |
| **Left-click** | Toggle the scope on weapons with `aim.scope: true`. On every gun the gesture is swallowed: no punch, no mining, no block interaction, no vanilla melee. |
| **F (swap hands)** | Reload. The Core consumes the swap, so the gun never leaves the hand. |
| — | An empty gun starts reloading by itself a few ticks after the last shot, if the reserve can supply a round. |

Minecraft sends no event for an arbitrary key, so the reload key is the
client's "Swap Item With Offhand" binding — point it at F.

Left-click is free of firing on purpose: it is the gesture a scoped weapon
aims with, and the swing stream that feeds it would otherwise double as a fire
button. Everything that shoots lives on right-click.

The scope is an aiming state, not a camera change: `aim.spread-multiplier`
tightens the cone, `aim.move-multiplier` punishes moving while aimed,
`aim.recoil-multiplier` softens recoil, the HUD reads `SCOPE` and a `scope_in`
/ `scope_hold` / `scope_out` frame sequence plays when the gun ships one. A
true client zoom needs a per-version packet (Spigot 1.21.1 exposes no FOV API),
so it is deliberately not faked here.

## Recoil is dispersion

A server cannot rotate a modern client's view, so a shot does not kick the
camera — it kicks the **cone**. Each round adds `accuracy.recoil-per-shot`
degrees to the next shot's spread, capped at `accuracy.recoil-max` and
recovered by `accuracy.recoil-recovery` per tick; movement adds
`accuracy.move` / `.air` / `.sprint` on top. The resulting profile is quantised
to a quarter degree and cached, so a full-auto magazine allocates a handful of
`BallisticProfile`s instead of one per round.

## Frame discovery (the scalable part)

A gun folder is self-describing. The module installs
`defaults/guns/<id>/models/*.json` into the Core's pack staging area
(`plugins/Mineplus/pack/mineplusgun/models/item/<id>/`) and its sprite as
`…/textures/item/<id>.png`; `WeaponFrames` then classifies every file by its
name suffix after `<id>_`:

| Suffix | Role |
|---|---|
| `idle`, `rest`, `base`, `default` | resting pose |
| `primed`, `loaded`, `ready`, `cocked` | pose shown while rounds remain |
| `loading_N`, `reload_N`, `load_N` | reload clip, sorted numerically and retimed to `ammo.reload-ticks` |
| `fire_N`, `shoot_N`, `shot_N` | shot pose |
| `scope_in`, `scope_hold`, `scope_out` (also `aim_`/`zoom_` spellings) | scope sequence |
| `cycle`, `bolt`, `pump`, `rack`, `rechamber` | post-shot action cycle |
| `equip`, `draw`, `raise` | draw pose |

**Every one of them is optional.** The shipped packs only author `idle`,
`primed` and `loading_N`, so these four guns reload with animation and hold
their resting pose while firing and scoping — and they still fire, reload,
scope and sell. A folder with a single `idle` model is a valid gun. A gun with
no models at all is a valid gun on its vanilla carrier. Adding a weapon is a
folder plus a config entry; no code, no frame list, no per-gun pose authoring.

An explicit `frames:` block in the config overrides discovery for that weapon,
so a server owner can retime or re-skin a gun without rebuilding the jar.

## The arsenal

Cadence is `⌈1200 / fire.rpm⌉` ticks; `PUMP`/`BOLT` wait for
`fire.cycle-ticks` instead when it is larger.

| Weapon | Type / action | Magazine / reload | Damage | Frames shipped |
|---|---|---|---|---|
| `pistol` | PISTOL, SEMI, 320 rpm | 12 / 36 t | 7.5 | idle, primed, 5 × loading |
| `shotgun` | SHOTGUN, PUMP, 70 rpm, cycle 14 t | 4 ×8 pellets / 60 t | 5.0 ×8 | idle, primed, 8 × loading |
| `rifle` | RIFLE, BOLT, 40 rpm, cycle 20 t, scoped | 5 / 44 t | 18.0 (+1 penetration) | idle, primed, 5 × loading |
| `battle_rifle` | RIFLE, AUTO, 420 rpm, scoped | 20 / 48 t | 9.5 | idle, primed, 6 × loading |

Each round carries per-weapon velocity, gravity, drag, range, spread,
falloff, headshot multiplier, penetration, ricochet and **knockback**, plus
its own effect stack (`fx:`): muzzle smoke, flash, sparks, tracer puffs,
impact burst and dust, a per-gun report from the module's own sounds and a
pitch-varied hit-marker tick for the shooter (higher on headshots). Rounds are
metered by the Core's `BALLISTICS.MAX_ACTIVE_ROUNDS` (an over-budget shot
resolves as one instant trace instead of queueing) and the `PARTICLE_FX`
budgets, which every module on the server shares.

## Magazines and ammunition

The magazine is the Core's `ItemMagazine`: the live round count rides in memory
while the gun is in hand and is flushed as one silent PDC write the moment it
leaves the hand (slot change, drop, quit, world change, module disable). Stacks
are matched by a per-stack identity tag, so two pistols of the same id are two
guns with two magazines — the runtime re-opens a session when the stack in hand
changes, never spends another stack's rounds.

Reloading draws rounds from the player's inventory: `ammo.items-per-round`
items of `ammo.material` (gold nuggets) per round. A reserve that cannot supply
one whole round never starts a reload and flashes `NO AMMUNITION`; the HUD
counts `{reserve}` in rounds, so `{mag}/{reserve}` is one coherent fraction.

## Carriers

Every gun's `backing-material` is **IRON_INGOT** — a neutral carrier with no
vanilla use action. That is the requirement, not a taste: a carrier with its
own use behaviour (bow, crossbow, food, spyglass) puts the client into a
"using item" state, the client stops repeating the use packet, and the Core's
trigger never sees the hold. A neutral carrier keeps the whole trigger stream
server-side, so the Core can fold the two packets of one click into one press
and report a genuine hold.

## Build

```bash
# from the repo root — Core, then this module, then deploy both
npm run build-all
npm run build-gun            # Core + this module

# direct Gradle (from this folder), after `gradle jar` in the repo root
gradle build --offline
```

Requires `build/libs/mineplus-1.3.0.jar` (the Core build). The module jar
carries `defaults/guns/` and `defaults/sounds/`; the retired
`defaults/frames/` import is excluded from the jar and kept only as reference
art.

`npm run pack` (repository root) builds the same gun assets into
`dist/mineplus-resource-pack.zip` for manual distribution. The complete pack is
always the Core's artifact: `/mineplus pack recompile` and
`plugins/Mineplus/packs/mp-<hash>.zip`, which adds the generated item
definitions and legacy host files.

## Assets

`src/main/resources/defaults/guns/<id>/` holds the models and sprite each gun
renders; `defaults/sounds/` holds `sounds.json` and the samples. The sources
are the author's pack library (`western_1`/`western_2` gun packs); the `.ogg`
samples are local-only binaries (see the module `.gitignore` and the folder's
`PLACEHOLDER.md`), so a fresh clone enables and shoots silently rather than
failing.

Events the runtime plays, all additive in the `mineplusgun` namespace:
`pistol_shot`, `shotgun_shot`, `rifle_shot`, `battle_rifle_shot`,
`reload_begin`, `reload_mid`, `reload_done`, `empty` (plus `shot`/`heavy`/
`reload` alternates). An unknown custom event falls back to the vanilla sound
named beside it, never to a thrown exception.

## Configuration

The module keeps `plugins/MineplusGun/config.yml` (generated on first start).
The shipped file is the documented schema — every key has a typed default in
`WeaponConfigLoader`, is clamped, and a bad weapon is logged and skipped
instead of breaking the module.

| Section | Key | Meaning |
|---|---|---|
| `settings.hud` | `mode`, `template`, `low-ammo-threshold` | `ACTIONBAR`/`BOSSBAR`/`OFF`, template over `{mag} {reserve}` (`{weapon}`, `{mode}` available); `RELOADING`/`SCOPE` appended while true |
| `settings` | `language`, `disabled-worlds`, `pvp-only` | gameplay gating (a disabled world's gun behaves as a plain carrier) and the player-only damage filter |
| _not here_ | round and particle budgets | owned by the **Core** (`BALLISTICS.*`, `PARTICLE_FX.*` in `plugins/Mineplus/settings.mp.yml`) and shared across every module |
| `ammo` | `material`, `items-per-round` | the reload reserve |
| `economy` | `mode`, `price-multiplier`, `price-scale` | `VAULT`/`FREE` and price formatting |
| `shop` | `title`, `rows`, `admin-bypass-payment` | GUI and bypass |
| `weapons.<id>` | identity via the Core content parser: `display-name`, `category`, `backing-material`, `price`, optional `frames` | the item, not the gameplay |
| `weapons.<id>.ammo` | `magazine`, `reload-ticks`, `type` | `magazine: 0` means an ammoless weapon (melee-shaped) |
| `weapons.<id>.fire` | `mode` (`SEMI`/`AUTO`/`BURST`/`PUMP`/`BOLT`), `rpm`, `cycle-ticks`, `pellets`, `burst` | the action |
| `weapons.<id>.ballistics` | `damage`, `velocity`, `gravity`, `drag`, `range`, `spread-degrees`, `falloff-*`, `headshot-multiplier`, `penetration`, `ricochet`, `knockback`, `hitbox-expansion`, `tracer-spacing` | one round's physics |
| `weapons.<id>.accuracy` | `move`, `air`, `sprint`, `recoil-per-shot`, `recoil-recovery`, `recoil-max` | the dispersion model |
| `weapons.<id>.aim` | `scope`, `spread-multiplier`, `move-multiplier`, `recoil-multiplier` | the left-click scope state |
| `weapons.<id>.animation` | `reload`, `cycle`, `fire`, `scope-in`, `scope-hold`, `scope-out`, `equip` | clip names to play; an unknown name simply plays nothing |
| `weapons.<id>.fx` | particles and `sound-*` names | the Core's effect stack; unknown names degrade, never throw |

## Commands

`/mpgun shop` · `/mpgun hud` · `/mpgun give <weapon> [player]` ·
`/mpgun ammo [player] [amount]` · `/mpgun reload` · `/mpgun stats` ·
`/mpgun debug <hitbox|trace>` (the last five need `mineplusgun.admin`).
`/mineplus gun …` is the same tree. `/mpgun reload` re-installs assets,
re-discovers frames, re-registers items, recompiles the pack and refreshes
every live session.

## Module layout

| Package | Owns |
|---|---|
| `MineplusGunPlugin` | bootstrap: install assets → register assets → parse config → build services → start features → one `reloadAll()` → register commands |
| `config` | `GunConfig` + `WeaponConfigLoader` (tolerant parse, clamps, type defaults) |
| `weapon` | `WeaponDefinition` (gameplay record + compiled ballistic profiles), `WeaponFrames` (file-name discovery), `WeaponAssets` (jar → staging → pack registration), `WeaponItemFactory`, `WeaponRegistry` |
| `runtime` | `WeaponRuntime` (sessions, firing, reloading, poses, HUD), `FireMode`, `AmmoSupply` |
| `feature` | `WeaponFeature` (items + reload), `CombatFeature` (runtime + held-item handler + stack-change listeners), `HudFeature`, `ShopFeature` |
| `hud`, `shop`, `command` | the module's adapters over the Core `HudService`, the Vault bridge and the shared command tree |

Nothing here simulates a bullet, routes a click, stores a magazine, meters a
particle or draws a bar: those are Core services, and the module is the content
that uses them.

## Retired

`tools/import-western-frames.js` and `tools/import-western-models.ps1` belong to
the previous nine-gun Western arsenal (`defaults/frames/`), which the discovery
pipeline replaced. They are kept as authoring references only — no build step
runs them, and `defaults/frames/` never reaches the jar.
