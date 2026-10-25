# MineplusGun

A standalone [Mineplus](../../README.md) module that owns the combat/weapons
ecosystem — firearms, melee, grenades and ammunition — as **fully additive**
content. It never overrides a vanilla item, block, texture or sound.

- **[PLAN.md](PLAN.md)** — the design: asset inventory & organization, physics
  model, fire modes, HUD, particles/sounds, the Vault shop, Core enhancements,
  pricing, phasing.
- **[DEVELOPMENT_PROMPT.md](DEVELOPMENT_PROMPT.md)** — the build contract:
  verified Core APIs, exact layout, phased tasks and acceptance criteria.

## Status

v1 implemented: Phase 0 Core readiness (item `display` import/emit, module
`/mineplus` subcommand registration, additive `WEAPON`/`AMMO` categories),
Phase 1 presentation + shop, Phase 2 shooting runtime, and Phase 3 grenades +
melee. Phase 4+ (magazine items, animated rigs) remains future work.

## Quick facts

| | |
|---|---|
| Plugin id | `MineplusGun` (`depend: [Mineplus]`, `softdepend: [Vault, packetevents]`) |
| Commands | `/mpgun …` and `/mineplus gun …` |
| Item axis | `PackApi.registerItem` (additive `item_model`; legacy `CustomModelData` fallback) |
| Physics | tick-stepped simulated bullets (gravity, drag, penetration, ricochet) |
| Input | Bukkit events: right-click is the only trigger (hold to fire / scope), left-click is inert, swap-hands reloads |
| Economy | Vault; all prices config-driven; admins/bypass-permission never charged |
| Assets | 19 weapon `.bbmodel` + textures from the author's `mineplus-guns` set |

## Controls

| Input | Action |
|---|---|
| **Right-click** | The only action. Fire (hold to keep firing — AUTO stays automatic while held); a scoped weapon scopes while held (spread × `aim.spread-multiplier`, HUD `SCOPE`); melee swings; a grenade is thrown. |
| **Left-click** | Inert. Mining, attacking and the vanilla arm-punch animation are all suppressed. |
| **Swap hands (F)** | Reload from the magazine capacity. |

Left-click is deliberately not a fire button: the client plays the arm-punch
animation locally when a swing happens, and it cannot be suppressed
server-side, so a gun must never be fired from it. Every action lives on
right-click. The runtime never branches on the input source, so a packet-level
adapter drops in without touching weapon logic.

## Carriers (why no gun ever zooms or ignites)

A gun's `backing-material` is only the vanilla carrier the additive
`item_model` replaces — **its vanilla behaviour is not replaced**. That is why
the module refuses unsafe carriers:

- `FLINT_AND_STEEL` would ignite fire on a right-click into a block.
- `SPYGLASS` would zoom the camera on right-click.

Normal firearms (scoped ones included) therefore use `IRON_INGOT` — a material
with no use action at all — and the loader substitutes it automatically for any
unsafe material such as `SPYGLASS` (which would render as a vanilla spyglass and
zoom the camera) or `FLINT_AND_STEEL` (which would ignite fire). A weapon with
`aim.scope: true` toggles a **scope** on right-click: its spread is multiplied
by `aim.spread-multiplier` and the HUD reads `SCOPE`.

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
binaries** and are not committed (see [`.gitignore`](.gitignore)); each asset
folder carries a `PLACEHOLDER.md` describing the expected file names. Drop the
`.bbmodel` + `.png` into `src/main/resources/defaults/models/gun/` and the
`.ogg` into `src/main/resources/defaults/sounds/mineplusgun/` for a build with
real content. Without them the module still enables and the shop works, but
weapons render as their vanilla backing material.

## Configuration

The module keeps its own `plugins/MineplusGun/config.yml` (generated on first
start). Highlights, all editable:

| Section | Key | Meaning |
|---|---|---|
| `settings.hud` | `mode` / `template` / `low-ammo-threshold` | `ACTIONBAR`/`BOSSBAR`/`OFF`, `{weapon} {mag} {reserve} {mode}` |
| `settings` | `disabled-worlds`, `pvp-only`, `block-damage` | gameplay gating |
| `ballistics` | `max-active-bullets`, `max-particles-per-tick` | global budgets; over-budget shots resolve as an instant trace |
| `economy` | `mode`, `price-multiplier`, `price-scale` | `VAULT`/`FREE`, price scaling |
| `shop` | `title`, `rows`, `admin-bypass-payment` | GUI and bypass |
| `weapons.<id>` | `model`, `type`, `backing-material`, `price`, `ammo`, `ballistics`, `fire`, `aim`, `fx`, `grenade`, `melee` | one entry per weapon |

Every value is clamped by `WeaponConfigLoader`; a bad weapon is logged and
skipped, never fatal. `backing-material` is validated against a safe-carrier
allow-list (`IRON_INGOT` is the neutral default) and a scoped weapon is always
carried by `SPYGLASS` — see _Carriers_ above.
