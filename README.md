<div align="center">

# ⚙️ Mineplus

### Data-driven machines, items & mobs for Minecraft — **no client mods**, rendered through a self-delivered resource pack.

[![](https://img.shields.io/badge/Minecraft-1.21%2B-green)](https://www.minecraft.net/)
[![](https://img.shields.io/badge/Server-Paper%20%7C%20Spigot-orange)](https://papermc.io/)
[![](https://img.shields.io/badge/Java-21-blue)](https://adoptium.net/)
[![](https://img.shields.io/badge/version-1.3.0-red)](https://github.com/Tariux/MinePlus/releases)
[![](https://img.shields.io/badge/license-MIT-informational)](LICENSE)
[![](https://img.shields.io/badge/Modrinth-minepluscore-1bd96a)](https://modrinth.com/plugin/minepluscore)
[![](https://img.shields.io/badge/Hangar-MinePlusCore-blue)](https://hangar.papermc.io/Tariux/MinePlusCore)

**Design it in Blockbench → drop it in → it exists in-game. No client mods, ever.** Full-fidelity models, textures, animations and custom items ride through a **resource pack the plugin compiles, hosts and delivers itself**: hash-named artifacts, a built-in HTTP endpoint, per-player URL resolution, automatic push to online players, one accept prompt — never a manual download. And for content that prefers it (or a player who declines the pack), the built-in **virtual/texel axis** renders models out of vanilla `BlockDisplay` entities with per-pixel texel baking — 100% vanilla clients, zero pack, automatic fallback in one place.

**📥 Download:** [Modrinth](https://modrinth.com/plugin/minepluscore) · [Hangar](https://hangar.papermc.io/Tariux/MinePlusCore) · [GitHub Releases](https://github.com/Tariux/MinePlus/releases)

</div>

---

## 🎯 The Big Idea

Mineplus is a **server-side infrastructure core** for Paper/Spigot 1.21+ that turns Blockbench models (`.bbmodel`) into living, collidable, interactive machines, custom items, animated mobs and more. It renders through **two axes you choose per model**, and neither requires a client mod:

- 📦 **Resource-pack axis (primary)** — the model compiles into the pack at its authored fidelity: real textures, real UVs, animations baked into item frames, oversized blocks window-fitted exactly. The pack is generated, hosted and auto-delivered by the plugin; players accept once.
- 🧊 **Virtual / texel axis (built-in option)** — the model is mosaic'd out of vanilla `BlockDisplay` entities, with per-pixel texel baking reconstructing real textures from a 66-entry vanilla palette. No pack involved — and it is also the automatic fallback whenever pack rendering is unavailable or declined, so content never breaks, only degrades.

What does that mean for your server?

| | Mineplus | Traditional custom-content plugins |
|---|---|---|
| **Player client** | ✅ No mods; vanilla clients work everywhere | ❌ Often needs mods |
| **Resource pack** | ✅ Generated & auto-delivered by the plugin — one accept prompt, never a manual download | ❌ Manual texture-pack download and install |
| **Degradation** | ✅ Pack declined → the built-in texel/virtual render takes over in one place | ❌ Broken or invisible content |
| **Model authoring** | ✅ Blockbench (free, visual) | ❌ Java code or flat item models |
| **Collision** | ✅ Per-cube cell-rasterized barriers | ⚠️ Usually one flat bounding box |
| **Content policy** | ✅ Zero-content core — you decide everything | ⚠️ Opinionated defaults |

**The core ships with zero gameplay content.** No surprise blocks, no test machines, no world edits. Everything in-game exists because *you* defined it — through JSON files, the module API, or both.

---

## ✨ Feature Highlights

- 🧱 **Virtual Blockbench rendering** — a single-pass streaming `.bbmodel` importer with negative-coordinate geometry, outliner pivot transforms, per-cube `light_emission`, and per-face UV analysis that maps textures onto vanilla block materials.
- 📦 **Self-hosted resource packs** — when a model wants full-fidelity rendering, the core compiles a deterministic hash-named pack, serves it from a built-in endpoint (or your static URL), auto-pushes it to online players and tracks per-player accept/decline; declined players see the vanilla/texel fallback instead of broken content.
- 🔫 **Advanced item models, any Blockbench project** — multi-texture, any-resolution projects compile correctly (UVs scaled to the vanilla window, every texture bound), and a derived item that only re-skins a vanilla item (an emerald sword from the diamond sword) costs a single PNG via vanilla-parent inheritance.
- 🎬 **Animations beside the model + declarative triggers** — keep all animation work in a separate `.anim.bbmodel` beside the model (merged by bone name), and let modules fire authored event names (`triggerEvent`) that a `.meta.json` trigger map wires to clips — no clip names in code.
- 📦 **Pack blocks of any size, exactly authored** — models wider than one block are window-fitted with one uniform transform the renderer inverts at display time, so a 4-block alchemy table renders 1:1 with its virtual twin and its collision lattice.
- 🎞️ **Blockbench animations, playable in-game** — clips, bones, and keyframes ride inside the model file; the core samples them server-side (Folia's global scheduler or the standard scheduler, with viewer culling so off-screen models cost nothing) and the vanilla client interpolates to its own frame rate. Autoplay via JSON, or drive parts from code with selector-based hooks (trigger `turret`, disable `wheel`, play `recoil`).
- 🎯 **Geometry-aware collision** — per-cube SAT cell rasterization (`GEOMETRY` / `SURFACE` / `AABB` modes) places barrier blocks exactly where your model is solid, with an allocation-free hot loop. Hollow structures stay hollow; empty interiors stay walkable.
- 🧭 **Rotation-perfect placement** — rotations snap to the 24 orientation-preserving axis permutations, so barriers and visuals *never* drift apart, even on rotated placements.
- 🗂️ **Multiblock registry & lifecycle** — create / place / interact / upgrade / tick / break with a full hook and lifecycle-event system.
- ⏱️ **Timed crafting processes** — restart-safe, chunk-aware processes (vanilla furnace parity: paused in unloaded chunks, resumed on load), scaled by per-level `speed` multipliers.
- 🔗 **Linking & signals** — pipe-network style auto-linking (`autoLinkNeighbors`) plus directed signal propagation between machines.
- 💾 **SQLite persistence, off the main thread** — HikariCP-pooled connections with an asynchronous write-behind queue; gameplay never blocks on disk I/O.
- 🔫 **Shared combat services** — a Core-side projectile simulation (spread, drop, drag, falloff, penetration, ricochet, headshots) with budgeted particles and sound resolution, a held-item input seam that routes presses and freezes vanilla interactions for the items a module owns, and a deferred magazine model so a gun never rewrites the held stack mid-fire. Modules ship weapon definitions, not physics loops.
- 🐄 **Budgeted mob behavior engine** — per-entity-type profiles of interval-gated actions, arbitrated by anchored activity claims so scenarios can't fight over one mob or wander outside their area; ground-probed navigation, leg-by-leg journeys with stall recovery, crew cohesion, and hard per-run caps on time, block edits and spawns. Lazy: it costs nothing until a module registers a profile.
- 🖥️ **Built-in admin CLI** — inspect, respawn, reload, and diagnose every instance live.
- 🧰 **Pure-Java toolkit subsystems** — research trees with budget-gated unlocks, a minigame state machine + map/instance toolkit with a Bukkit adapter, a cross-server message bus (Redis pub/sub over plain sockets or a file spool — no client jar), energy/kinetic grid networks with post-tick balancing, health-threshold boss-fight phases, isolated hot-reload steps and a dependency-ordered asset pipeline. See the [toolkit docs](docs/developer-api.md#toolkit-subsystems-pure-java).

---

## 🚀 Quick Start

1. **Install** — drop `Mineplus.jar` into `plugins/` and restart. Paper/Spigot 1.21+, Java 21.
2. **Add a model** — export a model from Blockbench as `.bbmodel` into `plugins/Mineplus/models/`.
3. **Define the machine** — drop a JSON file into `plugins/Mineplus/multiblocks/`:

   ```json
   {
     "id": "my_machine",
     "name": "My Machine",
     "levels": {
       "1": { "model": "models/my_machine.bbmodel" }
     }
   }
   ```

4. **Reload & spawn** — `/mineplus reload all`, then place it via the API, a module, or `/mineplus model debugspawn <modelKey>`.
5. **That's it** — every player sees and touches it: pack-rendered content arrives as one automatic, server-sent pack prompt, and players who decline the pack fall back to the built-in virtual render.

> 📖 **Full walkthroughs:** [`docs/extension-workflows.md`](docs/extension-workflows.md) • **Sample files:** [`examples/README.md`](examples/README.md)

---

## 🎬 Showcase — the Cannon

Everything below is a **module feature**, built entirely outside the core with JSON + ~600 lines of Java. *Demo video and screenshots live on the [Modrinth](https://modrinth.com/plugin/minepluscore) and [Hangar](https://hangar.papermc.io/Tariux/MinePlusCore) pages.*

| Interaction | Result |
|---|---|
| **Right-click with TNT** | Opens the ammunition menu — one slot, one stack |
| **Right-click with a torch** | Fires! One TNT consumed, launched ~20 blocks along the barrel with a ballistic arc |
| **Break it** | Your leftover TNT drops back out — nothing is ever silently destroyed |

▸ **Explore the code:** [`examples/mineplus-fun`](examples/mineplus-fun/README.md) — hook, GUI, ballistics, placement rotation, and the [module development guide](examples/mineplus-fun/DEVELOPMENT_PROMPT.md).
▸ **More complete modules:** [`examples/mineplus-gun`](examples/mineplus-gun/README.md) — firearms, melee and reload animation on the Core's combat services; [`examples/mineplus-mobfun`](examples/mineplus-mobfun/README.md) — nine smart mobs, golem builders and pilgrim villagers on the Core's mob behavior engine.

---

## 🧱 Three Ways to Use Mineplus

| Tier | Audience | Tool | You write |
|---|---|---|---|
| **1 — JSON** | Server operators | `JsonInfrastructureApi` | Configs only — models, multiblocks & recipes in JSON |
| **2 — Basic** | Light add-ons | `BasicInfrastructureApi` | A few lines of Java for place/remove/lookup |
| **3 — Advanced** | Framework devs | `InfrastructureApi` | Full hooks, GUIs, signals, links & processes |

The tiers stack: a JSON-defined machine can be driven by a Tier-3 module (see [`examples/mineplus-fun`](examples/mineplus-fun/README.md) — a complete Juicer **and** a torch-fired Cannon built entirely outside the core).

---

## 🖥️ Admin Commands

| Command | Permission | Description |
|---|---|---|
| `/mineplus status` | `mineplus.admin.status` | Runtime overview: types, instances, processes |
| `/mineplus reload [all\|models\|multiblocks\|recipes]` | `mineplus.admin.reload` | Hot-reload content without a restart |
| `/mineplus model list [limit]` | `mineplus.admin.model` | List active instances |
| `/mineplus model inspect [look\|uuid]` | `mineplus.admin.model` | Full diagnostics: cubes, textures, occupancy |
| `/mineplus model remove [look\|uuid]` | `mineplus.admin.model` | Remove an instance |
| `/mineplus model respawn [look\|uuid]` | `mineplus.admin.model` | Respawn an instance's rendering |
| `/mineplus model setlevel <look\|uuid> <level>` | `mineplus.admin.model` | Force an instance's level |
| `/mineplus model debugspawn <modelKey>` | `mineplus.admin.model` | Spawn a raw model on the looked-at face |

---

## ⚙️ Configuration

`plugins/Mineplus/settings.mp.yml` is auto-generated on first start and controls the rendering engine plus the runtime services — the excerpt below covers collision mode, rotation snapping, per-face rendering, and the anchor convention; pack delivery, ballistics, particle budgets and the mob behavior engine are in the [configuration reference](docs/config-reference.md#settingsmpyml-engine--runtime-services):

```yaml
VIRTUAL_RENDERING:
  COLLISION_MODE: GEOMETRY        # AABB | GEOMETRY | SURFACE
  COLLISION_EPSILON: 0.001
  COLLISION_NON_AIR_POLICY: SKIP  # SKIP | STRICT
  ROTATION_SNAP: true
  ROTATION_SNAP_THRESHOLD_DEGREES: 5
  PER_FACE_RENDERING: true
  ORIGIN_MODE: AUTO               # AUTO | CENTER | GRID

ANIMATION:
  ENABLED: true
  TICK_INTERVAL_TICKS: 1          # server pushes per tick; client interpolates to its own FPS
  INTERPOLATION_TICKS: 1
  AUTOPLAY: true

ADDITIONAL_DEBUG_LOGS: false
```

> 📖 **Every key documented:** [`docs/config-reference.md`](docs/config-reference.md)

---

## 📚 Documentation

| Doc | What's inside |
|---|---|
| [**Configuration Reference**](docs/config-reference.md) | Multiblock & recipe JSON schemas, `settings.mp.yml`, model meta overrides, the complete texture-name catalog, Blockbench authoring guide |
| [**Developer API**](docs/developer-api.md) | The three API tiers, hooks, timed processes, auto-linking, runtime internals, plus the toolkit subsystems (research, minigames, messaging, grids, bosses, hot reload, asset pipeline) |
| [**Extension Workflows**](docs/extension-workflows.md) | Step-by-step paths for JSON-only servers, basic add-ons, and full framework modules |
| [**Examples**](examples/README.md) | Ready-to-copy JSON machines, Java snippets, and hybrid patterns |
| [**MineplusFun Module**](examples/mineplus-fun/README.md) | Complete reference plugin: Juicer + Cannon, built fully outside the core |
| [**Module Development Prompt**](examples/mineplus-fun/DEVELOPMENT_PROMPT.md) | The context guide for building new modules on the core |

---

## 🏛️ Architecture at a Glance

```
MineplusPlugin
 └─ ConfigManager ─────────── settings.mp.yml
 └─ VirtualBlockManager ───── .bbmodel → BlockDisplay pipeline
     └─ PluginContext
         └─ InfrastructureEngine
             ├─ MultiBlockRegistry            (types + instances)
             ├─ MultiBlockLifecycleManager    (create/place/interact/upgrade/remove/tick)
             ├─ ModelRenderingManager         (level → model spawn/swap/remove)
             ├─ MultiBlockLinkingSystem       (links + signals)
             ├─ MachineProcessManager         (timed crafting, restart-safe)
             ├─ RecipeManager / UpgradeManager / GuiManager
             └─ PersistenceFacade             (SQLite, async write-behind)
```

- **Persistence** — state lives in `plugins/Mineplus/infrastructure.db` behind a HikariCP-pooled connection. Gameplay actions stage snapshots in memory; a background task flushes ~once per second and synchronously on shutdown/reload. After a hard crash, at most ~1 second of changes roll back.
- **Chunk awareness** — instances in unloaded chunks are fully skipped (no ticks, hooks, renders, or process advancement) and resume on chunk load — vanilla tile-entity parity.
- **Ghost cleanup** — orphaned `BlockDisplay` entities from crashes are detected and cleaned on chunk load.

---

<div align="center">

**Made with ⚙️ and vanilla redstone by [Tariux](https://github.com/Tariux)** · [tariux@protonmail.com](mailto:tariux@protonmail.com)

*Star the repo if your server gained a cannon today.*

<sub>Anonymous usage statistics are collected via [bStats](https://bstats.org/plugin/bukkit/Mineplus/33702) — disable anytime in `plugins/bStats/config.yml`.</sub>

</div>
