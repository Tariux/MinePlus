# 06 Feature backlog

Scored by (Impact × Confidence) / Effort, 1–10 (impact 1–5, confidence 0.2–1.0, effort in person-days). Confidence reflects how certain we are the feature works with vanilla clients at the target versions; evidence links go to `02`, `03`, `04`, `05`.

| # | Feature | Player value | Dev value | Vanilla feasibility | Impact | Conf | Effort (d) | Score |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Real-server smoke CI, all claimed versions (F2) | indirect (stability) | high | certain | 5 | 1.0 | 6 | **0.83** |
| 2 | GUI click hardening: action audit, priorities (F3) | high (no theft/dupes) | med | certain | 5 | 1.0 | 2 | **2.50** |
| 3 | Persistence schema versioning + dirty writes (F5) | indirect | high | certain | 5 | 1.0 | 10 | **0.50** |
| 4 | Folia via SchedulerCompat variants (F1) | med | high | certain (04 §1) | 4 | 0.9 | 15 | **0.24** |
| 5 | Chunk-indexed viewer culling (F4) | high at scale | med | certain | 5 | 0.9 | 3 | **1.50** |
| 6 | Active-set machine heartbeat (F6) | high at scale | high | certain | 5 | 0.9 | 8 | **0.56** |
| 7 | API v1: services + cancellable events (F9, P5) | med (protection interop) | **high** | certain | 5 | 0.9 | 20 | **0.23** |
| 8 | Protection-plugin compliance (F7, P7) | high on shared servers | med | certain | 4 | 0.9 | 5 | **0.72** |
| 9 | Pack hardening: bind, rate limit, addResourcePack (F8) | med | med | certain | 3 | 1.0 | 5 | **0.60** |
| 10 | Data-driven definitions + hot reload (P4) | med | **high** | certain | 5 | 0.8 | 15 | **0.27** |
| 11 | **Energy networks** (Slimefun-style, union-find, P6) | high | high | certain (server-side) | 5 | 0.8 | 40 | **0.10** |
| 12 | **Kinetic networks** (Create-style speed/stress, P6) | high | high | feasible | 4 | 0.7 | 40 | **0.07** |
| 13 | **Logistics: item/fluid cargo** | high | high | feasible | 4 | 0.7 | 40 | **0.07** |
| 14 | Research tree + guide book | med | med | certain | 3 | 0.8 | 20 | **0.12** |
| 15 | No-viewer animation skip + LOD (03 §3) | high at scale | med | certain | 4 | 0.9 | 6 | **0.60** |
| 16 | Nearby-player scan throttle (F11) | med | low | certain | 3 | 0.8 | 5 | **0.48** |
| 17 | In-game **live pack/model hot reload** from Blockbench | high (creator joy) | high | certain | 4 | 0.8 | 10 | **0.32** |
| 18 | Animated bosses: phases, telegraphed attacks | high | med | feasible | 4 | 0.7 | 30 | **0.09** |
| 19 | Minigame toolkit: state machine, teams, maps, scoreboards | high | high | certain | 5 | 0.8 | 50 | **0.08** |
| 20 | Network toolkit: queue, parties, server selector, stats (03 §5 shape) | high | high | certain | 5 | 0.7 | 60 | **0.06** |
| 21 | Custom HUD / glyph menus / dialogs | med | med | version-sensitive | 3 | 0.6 | 20 | **0.09** |
| 22 | World-gen content via data-driven registries | med | med | UNVERIFIED API stability (03 §1) | 2 | 0.5 | 30 | **0.03** |
| 23 | Moving contraptions (conveyor belts, mechanical crafters) | very high | high | hard: collision + interpolation | 5 | 0.5 | 80 | **0.03** |
| 24 | Replays / spectator systems | med | low | feasible | 2 | 0.5 | 30 | **0.03** |
| 25 | Public SDK: Gradle plugin, templates, docs site | low (players) / **high** (devs) | certain | 4 | 0.7 | 30 | **0.09** |
| 26 | Admin suite: content validator, pack analyzer, lag finder | med | high | certain | 3 | 0.9 | 12 | **0.23** |
| 27 | Cosmetics system + seasonal events | med | med | certain | 3 | 0.7 | 25 | **0.08** |
| 28 | Custom world structures/dimensions | med | low | hard | 2 | 0.4 | 60 | **0.01** |

Read with `04-gap-analysis.md`: items 1–10 are Phase A/B work; 11–14 Phase C; 15–18 Phase C/E; 19–20 Phase D; the rest opportunistic. The scoring confirms the ordering: the cheap safety/correctness items (2, 5, 8, 9, 16) dominate, and the two headline ambitions (kinetics, minigames) only score well once the infrastructure under them exists.

## Added 2026-11-26: custom block definition layer (B1)

Plan: `docs/design/2026-11-26-custom-block-system-design.md`. Same scoring
formula as the table above.

| # | Feature | Player value | Dev value | Vanilla feasibility | Impact | Conf | Effort (d) | Score |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 25 | Block definitions + placement + persistence (`blocks/*.json`) | high (server owners build blocks) | high | certain (on existing lattice) | 5 | 0.9 | 12 | **0.38** |
| 26 | Break progress, hardness, tool tiers, block events | high (parity feel) | high | certain (`sendBlockDamage`) | 5 | 0.8 | 6 | **0.67** |
| 27 | Block authoring gate for AI/Blockbench deliveries | high (quality floor) | high | certain (docs + validator) | 4 | 0.9 | 3 | **1.20** |
