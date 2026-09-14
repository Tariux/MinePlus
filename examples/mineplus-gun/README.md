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
| Input | Bukkit events (press = use; release = stop-use for usable carriers + switch/drop/sneak bounds) |
| Economy | Vault; all prices config-driven; admins/bypass-permission never charged |
| Assets | 19 weapon `.bbmodel` + textures from the author's `mineplus-guns` set |

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
| `weapons.<id>` | `model`, `type`, `backing-material`, `price`, `ammo`, `ballistics`, `fire`, `fx`, `grenade`, `melee` | one entry per weapon |

Every value is clamped by `WeaponConfigLoader`; a bad weapon is logged and
skipped, never fatal. Use `SPYGLASS` as the backing material for automatic
weapons when PacketEvents is absent: it is a usable, non-projectile carrier, so
hold/release is observable (at the cost of the client's scope zoom).
