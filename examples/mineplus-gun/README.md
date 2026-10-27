# MineplusGun

A standalone [Mineplus](../../README.md) module that owns the combat/weapons
ecosystem â€” firearms, melee and ammunition â€” as **fully additive**
content. It never overrides a vanilla item, block, texture or sound.

- **[PLAN.md](PLAN.md)** — the design: asset inventory & organization, physics
  model, fire modes, HUD, particles/sounds, the Vault shop, Core enhancements,
  pricing, phasing.
- **[DEVELOPMENT_PROMPT.md](DEVELOPMENT_PROMPT.md)** — the build contract:
  verified Core APIs, exact layout, phased tasks and acceptance criteria.

## Status

v1 implemented: Phase 0 Core readiness (item `display` import/emit, module
`/mineplus` subcommand registration, additive `WEAPON`/`AMMO` categories),
Phase 1 presentation + shop, and Phase 2 shooting runtime — plus the Western
content axis on the Core `ItemFrames` infrastructure: frame-animated weapons,
magazine rounds drawn from the player's gold-nugget supply, multi-pellet
scatter guns and melee.

## Quick facts

| | |
|---|---|
| Plugin id | `MineplusGun` (`depend: [Mineplus]`, `softdepend: [Vault, packetevents]`) |
| Commands | `/mpgun …` and `/mineplus gun …` |
| Item axis | `PackApi.registerItem` (additive `item_model`; legacy `CustomModelData` fallback) |
| Physics | tick-stepped simulated bullets (gravity, drag, penetration, ricochet) |
| Input | the Core's held-item seam: right-click is the only trigger (one click = one shot, whatever the client sends), left-click is inert, swap-hands reloads |
| Economy | Vault; all prices config-driven; admins/bypass-permission never charged |
| Assets | 9 Western frame-animated guns across three source packs (western_1: pistol + rifle, western_2: lever rifle + coach shotgun, western_3/musketmod: flintlock pistol, musket, scoped musket, bayonet musket, blunderbuss), imported via `tools/import-western-models.ps1` |

## Combat scenario (per gun type)

Cadence derives from `fire.rpm` (`⌈1200/rpm⌉` ticks between shots) and
`ammo.reload-ticks`. SEMI/BOLT/PUMP need a genuine re-press — the Core's
trigger state folds the packets of one physical click into a single press, so
a click on a block (which the client answers with two packets) never fires
twice, and a pull that arrives while the weapon is still cycling is queued
and fired as soon as the weapon is ready instead of being dropped. A fully
auto `AUTO` config keeps firing while the Core's trigger is held.

| Weapon | Type / action | Shot latency | Magazine / salvage | Damage* | Feel |
|---|---|---|---|---|---|
| `western_pistol` | PISTOL, SEMI | 5 ticks (0.25 s) | 6 / reload 2.5 s | 10.0 | fast sidearm, spam-clickable |
| `lever_rifle` | RIFLE, SEMI | 10 ticks (0.5 s) | 8 / reload 3 s | 13.0 | steady mid-range pressure |
| `western_rifle` | RIFLE, BOLT | 26 ticks (1.3 s) | 5 / reload 3.25 s | 16.0 (+1 penetration) | deliberate precision, punishes misses |
| `coach_shotgun` | SHOTGUN, PUMP | 16 ticks (0.8 s) | 6 ×4 pellets / reload 4 s | 7.0 ×4 | room-sweeper, falls off hard past 10 blocks |
| `flintlock_pistol` | PISTOL, SEMI | 10 ticks (0.5 s) | 1 / ram 4.5 s | 17.0 | one honest shot, then a long exposed ram |
| `flintlock_musket` | RIFLE, SEMI | 22 ticks (1.1 s) | 1 / ram 5 s | 23.0 (+1 penetration) | battlefield sniper |
| `bayonet_musket` | RIFLE, SEMI | 20 ticks (1.0 s) | 1 / ram 5 s | 22.0 (+1 penetration) | same role, tuned a step closer-in |
| `scope_musket` | RIFLE, BOLT | 24 ticks (1.2 s) | 1 / ram 5.5 s | 25.0 (+2 penetration) | held right-click scopes (spread ×0.35) |
| `blunderbuss` | SHOTGUN, PUMP | 20 ticks (1.0 s) | 2 ×6 pellets / ram 3.5 s | 5.0 ×6 | despair funnel — devastating point-blank only |

*Base damage; headshots multiply by `ballistics.headshot-multiplier` and every
gun carries distance falloff from `falloff-start` at `falloff-per-block`
(bounded by `min-damage-fraction`).

Feedback stack on every shot, all of it Core-owned (`fx:` per weapon): the
muzzle **flash**, the burning **sparks** it throws off, the barrel **smoke**
and the lingering **muzzle cloud**, the **tracer** puffs along the flight path
(every 5 blocks), the impact burst and its dust, a per-shot pitch-varied
report, and a hit-marker tick to the shooter (higher-pitched on headshots).
Fired rounds draw from the gold-nugget reserve on reload; a reserve that cannot
supply a single round flashes `NO AMMUNITION` on the HUD and the reload never
starts. A pull while the weapon is loading neither fires nor clicks empty —
the magazine is empty precisely because the reload is running.

The HUD reads `{mag}` in **rounds** (in the magazine) and `{reserve}` in
**rounds** (still loadable from the reserve), so `{mag}/{reserve}` is one
coherent fraction whatever `ammo.items-per-round` costs.

## Controls

| Input | Action |
|---|---|
| **Right-click** | The only action. Fire (hold to keep firing â€” AUTO stays automatic while held); a scoped weapon scopes while held (spread Ã— `aim.spread-multiplier`, HUD `SCOPE`); melee swings. |
| **Left-click** | Inert. Mining, attacking and the vanilla arm-punch animation are all suppressed. |
| **Swap hands (F)** | Reload â€” the magazine rounds are drawn from the player's nugget supply (`ammo.material`, `ammo.items-per-round`; a supply too small for one round means no reload). |

Left-click is deliberately not a fire button: the client plays the arm-punch
animation locally when a swing happens, and it cannot be suppressed
server-side, so a gun must never be fired from it. Every action lives on
right-click. The runtime never branches on the input source, so a packet-level
adapter drops in without touching weapon logic.

## Carriers (the neutral hook)

A gun's `backing-material` is the vanilla carrier the additive model replaces.
The arsenal uses **IRON_INGOT** — a *neutral* carrier, an item that does
nothing on its own. That is the requirement: a carrier with a vanilla use
action (a bow, a crossbow, food) puts the client into its own "using item"
state, the client then stops sending further use packets, and the Core's
trigger never sees the hold. A neutral carrier keeps the vanilla interaction
inert, so the Core owns the whole trigger stream — whatever the client sends
for one click, the Core resolves it into exactly one press.

The module keeps the vanilla carrier inert for its weapons — every use path
(interact, entity interact, attack) is cancelled before the vanilla behaviour
can do anything. Unsafe carriers (e.g. `SPYGLASS`, which zooms) are
substituted with a neutral carrier by the Core content parser.

The load animation is **not** the client's draw gesture: it is the Core's own
frame pipeline (below), so the poses play on the module's schedule and the
weapon works the same on a client that never applies the pack.

## Build

```bash
# from the repo root — builds the Core, then this module, then deploys both
npm run build-all

# or just this module (Core jar must already exist in build/libs/)
npm run build-mineplus-gun

# direct Gradle (from this folder), after `gradle jar` in the repo root
gradle build --offline
```

Requires `build/libs/mineplus-1.3.0.jar` (produced by the Core build).

## Assets

The weapon models, textures and sound samples are **local-only authoring
binaries** and are not committed (see [`.gitignore`](.gitignore)). The frames
and textures come from the author's three Western packs at
`C:\Users\Tariux\Desktop\gunpacks` (`western_1`, `western_2`, `western_3`;
the `modern_1` pack is ignored); each pack folder carries the guns' raw model
JSONs and per-gun textures. Re-run the import any time the packs change:

```powershell
powershell -File tools/import-western-models.ps1
```

The import brings in the pack's own frames (`idle`, `reload*`, `loaded`) and
the per-weapon texture; the `fire` pose is authored on top of them and is
left untouched by the import.

Drop the `.ogg` into `src/main/resources/defaults/sounds/mineplusgun/` for a
build with real sound content. Without them the module still enables and the
shop works, but weapons play no custom sounds.

### The arsenal

| Weapon id | Source pack | Type | Action | Frames |
|---|---|---|---|---|
| `western_pistol` | western_1 | PISTOL | SEMI, 6 rounds | idle + reload ×5 + loaded + fire |
| `western_rifle` | western_1 | RIFLE | BOLT, 5 rounds | idle + reload ×6 + loaded + fire |
| `lever_rifle` | western_2 | RIFLE | SEMI, 8 rounds | idle + reload ×5 + loaded + fire |
| `coach_shotgun` | western_2 | SHOTGUN | PUMP, 6×4 pellets | idle + reload ×8 + loaded + fire |
| `flintlock_pistol` | western_3 | PISTOL | SEMI, 1 round | idle + loaded + fire |
| `flintlock_musket` | western_3 | RIFLE | SEMI, 1 round | idle + loaded + fire |
| `bayonet_musket` | western_3 | RIFLE | SEMI, 1 round | idle + loaded + fire |
| `scope_musket` | western_3 | RIFLE | BOLT, 1 round, scoped | idle + loaded + fire |
| `blunderbuss` | western_3 | SHOTGUN | PUMP, 2×6 pellets | idle + loaded + fire |

The reload frames are the pack's own animation, imported verbatim; the `fire`
frame is the recoil pose the shot plays for its few ticks. Six guns inherit
their fire pose from the pack (the hammer falls, the muzzle drops). Two had no
such pose in the pack at all, so the recoil is authored on top of the imported
geometry: the pistol's barrel swings up around the frame, the lever rifle's
receiver, barrel and front sight kick up around the grip.

Every gun renders through the Core's frame pipeline: one `custom_model_data`
value per state, identical on modern (`item_model` `range_dispatch`) and
legacy (pre-1.21.4 host-file predicate) servers, so frame animation works
everywhere the pack renders at all. The state the client renders is the
number the Core writes on the stack — the modern dispatch reads the
`custom_model_data` component's float list and the legacy representation
reads the integer, and the Core writes both, so one value drives the pose
on either server.

### Retired v0 arsenal

The first implementation shipped different weapon ids (`colt_peacemaker`,
`rifle_boltaction`, `knife_bowie`, …). Stacks stamped with those ids are
retired: on enable and on every join the module exchanges each one for the
fresh stack of its replacement (conserving the stack's uid and live rounds) —
the Bowie knife is removed. The registry also still resolves the retired ids
as aliases, so a held old gun fires immediately even before the swap.

## Configuration

The module keeps its own `plugins/MineplusGun/config.yml` (generated on first
start). Highlights, all editable:

| Section | Key | Meaning |
|---|---|---|
| `settings.hud` | `mode` / `template` / `low-ammo-threshold` | `ACTIONBAR`/`BOSSBAR`/`OFF`, `{weapon} {mag} {reserve} {mode}` |
| `settings` | `language`, `disabled-worlds`, `pvp-only` | gameplay gating (a disabled world's guns behave as plain vanilla carriers) |
| _not here_ | round budget, particle budgets | owned by the **Core** (`BALLISTICS.*` and `PARTICLE_FX.*` in `plugins/Mineplus/settings.mp.yml`), metered across every module that fires; over-budget shots resolve as an instant trace |
| `economy` | `mode`, `price-multiplier`, `price-scale` | `VAULT`/`FREE`, price scaling |
| `shop` | `title`, `rows`, `admin-bypass-payment` | GUI and bypass |
| `weapons.<id>` | `model`, `type`, `backing-material`, `price`, `ammo`, `ballistics`, `fire`, `aim`, `fx`, `grenade`, `melee` | one entry per weapon |
| `weapons.<id>.fx` | `flash`, `spark`, `smoke`, `muzzle`, `tracer`, `impact`, `impact-smoke`, `sound-fire` | the Core's shot effect stack — every name is a vanilla particle, an unknown one degrades to the default instead of silencing the weapon |
| `weapons.<id>.frames` | `idle`, `reload`, `loaded`, `fire`, `texture` | the pack models the weapon renders; the stems resolve under `defaults/frames/<weapon-id>/` |

Every value is clamped by `WeaponConfigLoader`; a bad weapon is logged and
skipped, never fatal. `backing-material` is validated against a safe-carrier
allow-list (`IRON_INGOT` is the neutral default) and a scoped weapon is always
carried by `SPYGLASS` — see _Carriers_ above.
