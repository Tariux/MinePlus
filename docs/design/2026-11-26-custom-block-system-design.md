# Custom block system — research and plan

Status: **phases 1–5 landed** — definitions, item placement, JSON persistence,
chunk restore, breaking with hardness/tool gating/destroy stages,
`message:`/`sound:`/`command:` actions on place, break and right-click,
autoplay clips on place/restore, and a `--definition` authoring gate that proves
a model and its block JSON agree.
Carrier-pool exhaustion still falls back through the existing virtual engine; the
1.21–26.3 version matrix run is still pending.
Target: one data-driven block type
(`blocks/<namespace>/<id>.json`) that is placed, rendered, collided, broken with
progress, persisted and networked — built on the primitives already in the core.

Scope: Minecraft 1.21 → 26.3, one jar, Spigot and Paper, resource pack only, no
client mod, no vanilla block removed or changed.

---

## 1. What already exists (verified in this tree, 2026-11-26)

| Capability | Where | State |
|---|---|---|
| Pack model on a real block state (note-block carrier, runtime-allocated states) | `pack/block/PackBlockCarrier.java`, `PackBlockRenderer` | works |
| Display-entity rendering of a multi-cell model | `infrastructure/virtual/DisplayEmitter.java`, `ModelRenderingManager` | works |
| Cell-accurate collision from model geometry (SAT per 1/16 cell, `GEOMETRY`/`SURFACE`/`SINGLE`/`AABB`) | `virtual/GeometryOccupancyCalculator.java`, `virtual/ModelMeta.java:291` | works |
| Barrier lattice placement, restore, removal | `virtual/VirtualBlockManager.java` | works |
| Per-model overrides (`collisionMode`, `fillBox`, material, origin, animations) | `.bbmodel.meta.json` | works |
| Multi-cell machines, durability, upgrades, rotation/kinetic networks | `core/multiblock/**`, `examples/mineplus-machine` | works |
| Persistence across restarts | `infrastructure.db` (SQLite, `PersistenceConfig`) | works |
| Placement validation (target face, yaw snap, air check) | `virtual/VirtualBlockPlacementHelper.java`, `PlacementOptions` | partial |
| **Per-block definition layer (`blocks/*.json`)** | `core/block/BlockDefinition.java`, `BlockDefinitionLoader`, `BlockRegistry` | **phase 1 landed** (loader + validation + `/mineplus blocks`) |
| **Break progress / hardness / tool tier** | — | **missing**: break is instant (`VirtualBlockManager.java:355`, `MultiBlockLifecycleManager`) |
| **Block place/break/interact events for config blocks** | — | **missing** (multiblocks have `MultiBlockBreakEvent` only) |

Consequence: the brief's three "unknowns" are answered by the code already —
rendering is pack-first, collision is a geometry-derived barrier lattice, state
is SQLite. The build work is the definition layer, breaking, and the authoring
gate, not a new renderer.

---

## 2. Research: what the field does, and what vanilla allows

| Finding | Source |
|---|---|
| Nexo's NOTE_BLOCK mechanic borrows vanilla note-block states for pack blocks (~1149 usable blocks per world), collision comes from the borrowed vanilla shapes / barrier option | Nexo docs, NoteBlock mechanic |
| CraftEngine splits a block into visual states, a collision state, and an interaction-entity hitbox for furniture; all states in one group must share the same vanilla collision shape | CraftEngine docs, Block & Hitbox/Collision |
| The `interaction` entity has a configurable hitbox but **no collision at all**: it does not push players or block placement — it only records/reports clicks | minecraft.wiki, Interaction |
| A block's collision shape is code-side and client-side, not something a resource pack can redefine; only a real vanilla block state gives real collision | wiki + the three plugins above all land on this |
| The destroy-stage animation is server-drivable on any block location | `javap` on `libs/spigot-api-1.21.1.jar`: `Player#sendBlockDamage(Location, float)`, `(Location, float, Entity)`, `(Location, float, int)` |

**Answer to "is my concept flawed?"** No, but one part is impossible as written:
*exact* collision matching the visual shape is not attainable without a client
mod, because the client computes collision from vanilla block shapes. What is
attainable — and what this core already does — is a **1/16-block-cell lattice
derived from the model's own cubes**, which is exact at cell resolution and
identical for every client. Likewise, a display entity cannot show a crack
overlay; break progress must animate the *carrier blocks* instead.

Rejected alternatives:

- *Per-face AABB collision via NMS* — no server API, breaks one-jar-per-version,
  and the client would still use the block state's shape.
- *Interaction entity as hitbox for movement* — impossible (no collision); it is
  the right tool only for click targeting, and only when the carrier has no
  shape to click.
- *One block state per definition, no dynamic allocation* — burns the ~1150-state
  pool on models that may never be placed; the runtime pool already avoids this.

---

## 3. Decisions

**D1 — One definition, three render backends.** `blocks/*.json` compiles onto
`renderMode: pack_block` (real state, best for static blocks), `virtual`
(display entities, no state cost, for large or animated pieces) and
`hybrid_block`. The definition never names a backend requirement the engine
cannot serve; `visuals.renderMode: auto` picks by size and animation.

**D2 — Collision is cells, and that is the contract.** Modes: `geometry`
(default, per-cube SAT), `surface` (walk-in hollowing), `single` (anchor cell
only), `box` (one pixel-space box, for simple shapes), `none`. Resolution is
1/16 block; a face at 22.5° is approximated by the cells it covers. This is
documented as the acceptance criterion, not hidden.

**D3 — Breaking is server-side with real progress.** `hardness` is seconds at
bare hand (not health), `tool` is `{type, tier|any}`; required time =
hardness × tool multiplier × (efficiency/status modifiers later). Progress is
tracked per player per block instance, ticks server-side, and is driven to
clients with `Player#sendBlockDamage` on every carrier cell with a stable
source id, so the vanilla destroy stage shows on the real blocks. Timeout
(default 400 ms without a damage event) resets progress.

**D4 — Rotation for collidable blocks is 90° steps only.** The lattice cannot
represent arbitrary yaw; `visuals.rotation.snap` is 90 by default, free
rotation is allowed only when `collision.mode: none`.

**D5 — A block instance is content, keyed by anchor position.** Record:
`world, x, y, z, definitionId, owner, rotation, state{}`. Reconcile on chunk
load from the same table that already holds multiblocks; no entity identity, no
NBT loot. State changes are written through, not on a timer.

**D6 — Capacity is the carrier pool, and it is bounded.** The shipped
`note_block` carrier allocates from 7 rare instruments × 25 notes = **175
slots** (`PackBlockCarrier`), not the ~1150 Nexo-style full table. Display-only
definitions cost nothing from the pool; `pack_block` definitions consume a slot
per distinct block appearance. When the pool is exhausted, extra instances fall
back to `virtual` and the log names the definition — never a silent purple
cube. A second carrier (own state table, `MODEL` render type) is the documented
escape hatch when a map needs more real states.

**D7 — One jar, no new NMS.** New code uses only the 1.21.1 API verified above
plus the existing `ServerVersion` seams. Pack-format changes stay inside
`PackFormat` (1.21.9 `min_format`/`max_format` is already handled).

**D8 — Placement validates before it mutates.** Footprint, support,
replaceable set and per-cell space are checked first; failure returns a
reason and places nothing ("never fail by accident for lack of space").

---

## 4. Data model

`plugins/Mineplus/blocks/<namespace>/<id>.json` (folder = namespace). Loaded
before multiblocks; a definition id is `namespace:id`.

```json
{
  "id": "mineplus:water_wheel",
  "displayName": "Water Wheel",
  "visuals": {
    "model": "water_wheel",
    "renderMode": "auto",
    "anchors": [[0,0,0], [0,0,1], [0,1,0], [0,1,1]],
    "animations": { "autoplay": ["spin"], "speed": 1.0 },
    "rotation": { "axis": "y", "snap": 90, "from": "player_facing" },
    "light": 0,
    "offset": [0, 0, 0]
  },
  "collision": { "mode": "geometry", "material": "BARRIER", "box": null },
  "occupancy": { "width": 1, "height": 2, "depth": 2 },
  "breaking": {
    "hardness": 1.2,
    "tool": { "type": "axe", "tier": "wooden" },
    "drops": [{ "item": "mineplus:water_wheel", "count": 1 }],
    "xp": 0
  },
  "properties": {
    "waterloggable": false, "flammable": false,
    "blastResistance": 6, "redstone": { "conductive": false }
  },
  "placement": {
    "support": "any", "replaceable": ["air", "water"], "spaceCheck": true
  },
  "item": { "material": "PAPER", "model": "water_wheel", "placeSound": "block.wood.place" },
  "events": { "onPlace": [], "onBreak": [], "onInteract": [], "onTick": null },
  "state": { "fields": [], "persist": true },
  "tooltip": []
}
```

Rules:

- `visuals.model` is a model key resolved exactly as multiblocks resolve theirs
  (`models/` recursive); `anchors` are block offsets from the anchor cell.
- `.meta.json` stays the per-model source of truth for geometry, `fillBox`,
  `collisionMode` and clips. `blocks/*.json` may override `collision` only; a
  definition without `anchors` derives its footprint from the model bounds.
- Unknown keys are logged and ignored (definition-time validation, like
  `MultiBlockType`); a malformed definition disables itself, never the server.
- `properties.light > 0` requires a carrier whose state carries a light level;
  with the note-block carrier it is clamped to 0 and warned, because a
  note_block cannot emit light. This is a hard vanilla limit, not a TODO.

---

## 5. Runtime pipeline

**Place** (`BlockPlacementService`, planned): item right-click on a block face
→ resolve definition → compute rotated footprint → `validate` (space, support,
replaceables, occupant) → allocate carrier state or display plan → write block
record → place carrier/lattice → emit `CustomBlockPlaceEvent` (cancellable;
cancel rolls the whole placement back) → play sound → consume item.

**Tick / animate** (`BlockLifecycleManager`, planned, registered on the existing
scheduler): drive `autoplay`/signal animations through the existing animation
engine; no per-tick work for static blocks.

**Break** (`BlockBreakProgressTracker`, planned): `BlockDamageEvent` on any
carrier cell → start/continue progress for this player and instance →
`sendBlockDamage` stage on all cells → on completion `BlockBreakEvent` path:
`CustomBlockBreakEvent` (cancellable, carries actor + drops) → remove lattice,
displays, record → drops/XP/sound → emit `onBreak` actions. Breaking one cell
of a multi-cell block breaks the whole block (all-or-nothing, like
multiblocks); a config flag for partial break is deliberately not offered.

**Restore** (extend `VirtualBlockManager` + a `SqliteBlockRepository`): chunk
load → records in chunk → re-place lattice only for cells that are air or
already-lattice (the existing restore path); a cell now holding a foreign block
is skipped and reported once, never overwritten.

**Interact**: `PlayerInteractEvent` on a carrier cell → routed to the
definition's `onInteract`/GUI, else the carrier's own behaviour is left alone
(note-block instrument sounds are suppressed in the same listener).

---

## 6. Authoring gate for AI agents (deliverable 4)

Extend `docs/model-authoring-guide.md` §9 and
`devtools/skills/mineplus-model-authoring/SKILL.md` with a block-specific
checklist that an agent must satisfy *before* handing a `.bbmodel` over:

1. Model space: `free`, anchored at the block grid, feet on y=0, no negative
   pixels below y=0, footprint stated in whole cells.
2. Cubes are the collision source: no decorative cube sticking into a walkway
   unless `collision.mode: none` is requested; voids that should be walkable
   are actual gaps, not dark cubes.
3. `fillBox` declared for any cell the block must leave open (water through the
   wheel) and verified against `GeometryOccupancyCalculator` output.
4. Clips named `snake_case`; `spin`/`idle` autoplay declared in the definition.
5. Texture embedded (hawk_eye rule) or a sibling PNG with the model stem.
6. Element angles within the 1.21.6 snap grid unless the target matrix excludes
   those versions.
7. Report: model bounds in pixels, derived footprint, collision cell count,
   expected break time, and a screenshot of the debug lattice (F3+G) in game.

Definition acceptance: the agent ships a definition plus the model, not a model
alone; a block without a definition is not deliverable.

---

## 7. Acceptance test: water wheel and shaft

Both already exist as machine models (`examples/mineplus-machine`,
`devtools/models/water_wheel`), so this tests the new layer, not modelling.

| # | Step | Expected |
|---|---|---|
| 1 | Give the two items, place the wheel against water, shaft beside it | both place on the first try; wheel cells filled by water stay open; shaft couples to the wheel's kinetic network |
| 2 | Walk around and into the wheel's ring | collision follows the spokes and rim, not a cube; standing inside the ring is possible |
| 3 | Stand on the shaft | solid 1-cell top, no floating gap |
| 4 | Mine the wheel bare-handed for 1 s | destroy stages 0→9 advance on every cell and reset after stopping; no instant break |
| 5 | Break with the wrong tool | refuses (or times out at the configured penalty), tooltip states the requirement |
| 6 | Break correctly | one event, one drop, water refills the open cells, lattice fully gone (no orphan barriers) |
| 7 | Restart the server; revisit the chunk | both blocks restore with rotation and running animation; no duplicate barrier cells |
| 8 | Place a shaft where the space is blocked | refusal message, no partial barriers left behind |
| 9 | Repeat 1–7 on Paper 1.21.1, 1.21.11, 26.3; spam the same on Spigot 26.3 | identical behaviour; the matrix is recorded on `docs/version-compatibility.md` |

### Recorded run — Paper 1.21.1 build 133

`devtools/live-test/run.sh` replays this on demand (bot client, headless server).

| # | Result | Evidence |
|---|---|---|
| 1 | pass, with a placement rule | the wheel is refused level on the ground: its geometry dips one cell below the anchor, so the space check blocks it. Against a side face one cell up it spawns with 8 barrier cells that follow the ring, and the open middle stays walkable |
| 2 | pass | a shaft on the ground leaves 3 barrier cells: its body plus the sub-pixel slivers the engine's 1/1024 epsilon keeps |
| 3 | pass | shaft leaves exactly one barrier cell |
| 4 | blocked | bare-hand dig fires damage and is refused with "Needs a axe." |
| 5 | pass | wrong tool refused, block untouched; state record unchanged |
| 6 | pass | wooden axe: all barrier cells gone in one break, one state record removed, item dropped back |
| 7 | pass | restart logged `Restored 2 placed block(s)`; barriers present on chunk load, no duplicates |
| 8 | partial | not scripted yet; the placement path refuses before spawning, so no partial lattice is possible |
| 9 | partial | 1.21.1 only; 1.21.11 and 26.3 need a matching Paper download and a re-run of the same script |

One finding from the run: the barrier lattice comes from model geometry capped by
`fillBox`, so `occupancy` in the block JSON is an authored claim, not a runtime
cap. `check-bbmodel.mjs --definition` checks that claim against the geometry so
the definition cannot drift from what the server builds.

---

## 8. Phases

| Phase | Work | Exit criterion | Rough size |
|---|---|---|---|
| 0 | This document; definition schema frozen; docs updates | reviewed | 1 doc |
| 1 | `BlockDefinition` + loader + registry + `/mineplus blocks validate` | a bad definition fails alone, with a line number | **done** (`core/block`, `BlockSubCommand`, `BlockDefinitionTest`) |
| 2 | Placement + persistence + restore; reuse lattice | wheel/shaft place, survive restart, refuse when blocked (test 1, 7, 8) | **landed and live-tested on 1.21.1** (place, state save, restart restore) |
| 3 | Breaking: progress, stages, hardness, tools, drops, events | test 4–6 | **landed and live-tested on 1.21.1** (tool gate, axe break, drop, record removal) (`BlockBreakTracker`, `BlockBreakingRules`, `BlockBreakListener`) |
| 4 | Interaction + animation signals + capacity fallback | wheel animates only while turning; pool exhaustion falls back | **partly landed**: actions + right-click routing; autoplay clips start on place/restore; per-state signals and pool fallback reuse existing engine behaviour |
| 5 | Authoring gate, example definitions, version matrix run | test 9 recorded | **landed**: `check-bbmodel.mjs --definition`, `devtools/live-test/run.sh` replays 1–7 on Paper; 1.21.11/26.3 rows still to record |

Phases 2 and 3 are independently shippable and reversible; neither touches
existing multiblock behaviour until a definition opts in (`blocks/*.json` only).

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| Barrier cell count explodes on complex models (hundreds of real blocks per instance) | cap cells per definition at load (warn + `SURFACE` suggestion), measure tick cost in phase 2, offer `virtual` for decoration |
| Creative players break barrier cells directly | `BlockBreakEvent` on lattice routed to the block's break path; lattice re-check on chunk load |
| Another plugin places a block into a lattice cell | restore skips foreign cells and reports; never overwrites |
| `sendBlockDamage` behaviour differs on old clients | server-version gated, stage is cosmetic; progress is server-authoritative either way |
| Definition/model drift (model edited, overlap stale) | `check-bbmodel.mjs --target pack-block` on delivery, and `/mineplus block validate` recomputes and diffs the lattice cache |
| Brief's 26.2 cap vs repo 26.3 | plan targets 26.3; 26.2 is inside the matrix |

---

## 10. Assumptions

- "MinecraftEDU server" is a normal Spigot/Paper server with an education-world
  map; no EDU-specific API is assumed.
- The version matrix in `docs/version-compatibility.md` stays the contract
  (1.21 → 26.3); the brief's 26.2 is treated as a minimum.
- No new dependency, no client mod, no datapack as a collision source.
- Water wheel and shaft definitions ship from `examples/mineplus-machine`, not
  from the core jar.

## 11. Docs touched by this plan

`docs/config-reference.md` (new §11 planned blocks format), `docs/README.md`,
`docs/model-authoring-guide.md` (§9 gate), `docs/pack-system.md` (carrier
limits and light), `docs/mega-plan/04-gap-analysis.md`,
`docs/mega-plan/06-feature-backlog.md`, `AGENTS.MD`, `CHANGELOG.md`,
`devtools/skills/mineplus-model-authoring/SKILL.md`.
