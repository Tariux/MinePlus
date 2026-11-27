# 04 Gap analysis

Impact 1–5 (5 = product-defining). Effort S ≤ 3d, M ≤ 10d, L ≤ 30d, XL > 30d. "Current state" evidence lives in `02-audit-findings.md` (F-ids) and `01-architecture-map.md`; "Best-in-class" evidence in `03-research-benchmark.md` (§ numbers).

| Capability | Current state | Best-in-class reference | Gap | Impact | Effort | Risk | Dependencies |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Block rendering | Display entities + interaction entities, virtual manager, texel baking | Nexo/CraftEngine hybrid: display for shape + block states for volume/collision (03 §2) | No block-state fallback for volume; refresh O(players×instances) (F4) | 5 | M | Packet volume at scale | F4 fix first |
| Furniture | Same display path, hitbox via interaction entities | Nexo furniture with seat/offset config | No seat/sitting, no protection hooks (F7) | 4 | M | Dupes with other plugins | Events (API v1) |
| Mobs / AI | YAML mobs, goal engine on Bukkit scheduler, per-mob render engine | BetterModel/ModelEngine: packet-only bones, no-viewer skip (03 §3) | No no-viewer skip; per-tick nearby scans (F11); no boss phases | 5 | L | Thread safety on Folia | Scheduler unification |
| Animation | Keyframe sampling, global animation manager, walk-every-instance | BetterModel 3.7.0 async transform + skip | No state machines/blend trees, no event keyframes | 4 | L | Version-coupled NMS | NMS smoke test |
| Armor / tools | Not present (zero-hit searches for armor trim/model recipes) | ItemsAdder armor sets + durability | Whole feature missing | 2 | M | Pack bloat | Pack pipeline |
| Recipes / crafting | Vanilla recipes only | Slimefun research-gated crafting | No custom recipes/crafting system | 4 | L | — | Data-driven defs (05) |
| GUIs | Machine GUI framework, drag handling, no action audit (F3) | Reactive menu libraries | Exploit surface; no pagination/animation API | 4 | S | **Exploitable now** | F3 fix |
| Energy networks | Absent | Slimefun energy nets; Create stress graph (03 §4) | Whole layer missing | 5 | XL | Tick cost | F6 fix, spatial index |
| Kinetic networks | Absent | Create: speed/stress propagation over shaft graph (03 §4) | Missing; server-side analog must be designed | 4 | XL | Design risk | Energy nets |
| Logistics (item/fluid) | Absent | Slimefun cargo nodes | Missing | 4 | XL | Bandwidth | Energy nets |
| Multiblocks | Present: lifecycle manager, storage, GUI | Slimefun multiblocks + Create contraptions | Whole-server scans (F6); no moving contraptions | 4 | M | MSPT spike | F6 fix |
| Research tree / guide book | Absent | Slimefun guide | Missing | 3 | L | — | Recipes, GUIs |
| World generation | Absent | Data-driven registries via Paper Lifecycle API (03 §6, UNVERIFIED) | No ores/structures/biomes | 2 | L | Registry freeze timing | Paper repo decision (Q5) |
| Loot and drops | Vanilla only | Custom loot tables | Missing | 3 | M | — | Events |
| Sounds / particles | `infrastructure.fx` basics | Competitor event systems | No event-keyframe sound/particle hooks | 3 | S | — | Animation |
| HUD / dialogs | `infrastructure.hud` + bossbar basics | Custom fonts/glyph menus, vanilla dialogs | No font glyph UI, no dialog forms | 3 | M | Client version drift | Pack pipeline |
| Resource pack ops | Self-host HTTP, single pack, hash versioning, overlays (03 §1) | CraftEngine multi-host, incremental builds (03 §2) | No external host, no incremental, no merge (F8) | 4 | M | Default bind risk (F8) | — |
| Persistence | SQLite, JSON payload, no version, pool 1 (F5) | Versioned schema, chunk-attached, write-behind | Migration path absent; rewrite-all saves | 5 | M | **Data loss on upgrade** | F5 fix |
| Networking | Zero code | Velocity + Redis + allocator (03 §5) | Whole layer missing | 5 | XL | Scope creep | API v1 |
| Minigame lifecycle | Absent | Hypixel-style instance managers (03 §5) | Missing | 5 | XL | — | Networking |
| Party / matchmaking | Absent | Queue/party services (BlueDragon, 03 §5) | Missing | 3 | L | — | Networking |
| Stats / leaderboards | Absent | Holographic leaderboards | Missing | 3 | M | — | Persistence v2 |
| Admin tooling | `/mineplus` status/reload/model/pack/render/perf | Content validator, lag finder | No doctor command, no pack analyzer | 3 | S | — | Perf registry |
| Observability | PerfRegistry exists | Spark markers, metrics | No export, no structured logs | 3 | M | — | — |
| Docs | Good topical docs; version drift (F13) | Docs site + javadoc | No API javadoc (no stable API) | 3 | M | — | API v1 |
| DX (add machine in <50 lines) | Examples exist but reach into internals (F9) | Annotation/DSL registration | Facade size; no templates/SDK | 5 | L | — | API v1, data-driven defs |
| Tests / CI | 9 classes, no CI, empty repositories block (F10) | MockBukkit + run-paper smoke CI (03 §6) | Everything | 5 | M | — | Q5 decision |
| Folia | Claimed, not real (F1) | Competitors list Folia support | 17 raw scheduler calls | 4 | L | Concurrency bugs | SchedulerCompat variants |

### 2026-11-26: custom blocks, collision and breaking

The gap is narrower than the original write-up assumed. Rendering (pack block
carrier + display entities) and geometry-derived collision already exist, so
the missing pieces are the definition layer (`blocks/*.json`), break progress,
and a per-block event surface. Cell resolution (1/16 block) is the hard limit
of a vanilla server; see `docs/design/2026-11-26-custom-block-system-design.md`
for the decisions, rejected alternatives and the phased plan.
