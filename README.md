<div align="center">

# ⚙️ Mineplus

### Data-driven machines & custom structures for Minecraft — rendered with **100% vanilla clients**.

[![](https://img.shields.io/badge/Minecraft-1.21%2B-green)](https://www.minecraft.net/)
[![](https://img.shields.io/badge/Server-Paper%20%7C%20Spigot-orange)](https://papermc.io/)
[![](https://img.shields.io/badge/Java-21-blue)](https://adoptium.net/)
[![](https://img.shields.io/badge/version-1.0.0-red)](https://github.com/Tariux/MinePlus/releases)
[![](https://img.shields.io/badge/license-MIT-informational)](LICENSE)
[![](https://img.shields.io/badge/Modrinth-minepluscore-1bd96a)](https://modrinth.com/plugin/minepluscore)
[![](https://img.shields.io/badge/Hangar-MinePlusCore-blue)](https://hangar.papermc.io/Tariux/MinePlusCore)

**Design it in Blockbench → drop it in → it exists in-game. No client mods. No resource packs. No downloads for your players.**

**📥 Download:** [Modrinth](https://modrinth.com/plugin/minepluscore) · [Hangar](https://hangar.papermc.io/Tariux/MinePlusCore) · [GitHub Releases](https://github.com/Tariux/MinePlus/releases)

</div>

---

## 🎯 The Big Idea

Mineplus is a **server-side infrastructure core** for Paper/Spigot 1.21+ that turns Blockbench models (`.bbmodel`) into living, collidable, interactive machines — using nothing but vanilla `BlockDisplay` entities.

What does that mean for your server?

| | Mineplus | Traditional custom-content plugins |
|---|---|---|
| **Player client** | ✅ 100% vanilla | ❌ Often needs mods or resource packs |
| **Extra downloads** | ✅ None. Ever. | ❌ Mod pack / texture pack |
| **Model authoring** | ✅ Blockbench (free, visual) | ❌ Java code or flat item models |
| **Collision** | ✅ Per-cube cell-rasterized barriers | ⚠️ Usually one flat bounding box |
| **Content policy** | ✅ Zero-content core — you decide everything | ⚠️ Opinionated defaults |

**The core ships with zero gameplay content.** No surprise blocks, no test machines, no world edits. Everything in-game exists because *you* defined it — through JSON files, the module API, or both.

---

## ✨ Feature Highlights

- 🧱 **Virtual Blockbench rendering** — a single-pass streaming `.bbmodel` importer with negative-coordinate geometry, outliner pivot transforms, per-cube `light_emission`, and per-face UV analysis that maps textures onto vanilla block materials.
- 🎞️ **Blockbench animations, playable in-game** — clips, bones, and keyframes ride inside the model file; the core samples them server-side and the vanilla client interpolates to its own frame rate. Autoplay via JSON, or drive parts from code with selector-based hooks (trigger `turret`, disable `wheel`, play `recoil`).
- 🎯 **Geometry-aware collision** — per-cube SAT cell rasterization (`GEOMETRY` / `SURFACE` / `AABB` modes) places barrier blocks exactly where your model is solid. Hollow structures stay hollow; empty interiors stay walkable.
- 🧭 **Rotation-perfect placement** — rotations snap to the 24 orientation-preserving axis permutations, so barriers and visuals *never* drift apart, even on rotated placements.
- 🗂️ **Multiblock registry & lifecycle** — create / place / interact / upgrade / tick / break with a full hook and lifecycle-event system.
- ⏱️ **Timed crafting processes** — restart-safe, chunk-aware processes (vanilla furnace parity: paused in unloaded chunks, resumed on load), scaled by per-level `speed` multipliers.
- 🔗 **Linking & signals** — pipe-network style auto-linking (`autoLinkNeighbors`) plus directed signal propagation between machines.
- 💾 **SQLite persistence, off the main thread** — asynchronous write-behind queue; gameplay never blocks on disk I/O.
- 🖥️ **Built-in admin CLI** — inspect, respawn, reload, and diagnose every instance live.

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
5. **That's it** — every player, on every vanilla client, can already see and touch it.

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

`plugins/Mineplus/settings.mp.yml` is auto-generated on first start and controls the rendering engine — collision mode, rotation snapping, per-face rendering, and the anchor convention:

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
| [**Developer API**](docs/developer-api.md) | The three API tiers, hooks, timed processes, auto-linking, runtime internals |
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

- **Persistence** — state lives in `plugins/Mineplus/infrastructure.db`. Gameplay actions stage snapshots in memory; a background task flushes ~once per second and synchronously on shutdown/reload. After a hard crash, at most ~1 second of changes roll back.
- **Chunk awareness** — instances in unloaded chunks are fully skipped (no ticks, hooks, renders, or process advancement) and resume on chunk load — vanilla tile-entity parity.
- **Ghost cleanup** — orphaned `BlockDisplay` entities from crashes are detected and cleaned on chunk load.

---

<div align="center">

**Made with ⚙️ and vanilla redstone by [Tariux](https://github.com/Tariux)** · [tariux@protonmail.com](mailto:tariux@protonmail.com)

*Star the repo if your server gained a cannon today.*

<sub>Anonymous usage statistics are collected via [bStats](https://bstats.org/plugin/bukkit/Mineplus/33702) — disable anytime in `plugins/bStats/config.yml`.</sub>

</div>
