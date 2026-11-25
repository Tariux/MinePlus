# 07 Roadmap

Five phases, each shippable on its own. Phase letters match `00-executive-summary.md`. Effort totals assume one developer; halve with two. KPIs are measured with the existing PerfRegistry, `/mineplus perf`, Spark, and the smoke CI from A1.

## Phase A — Stabilize and unblock (≈ 6 weeks)

**Goal:** everything that exists today is correct, safe and measured, on every claimed version.
**Tasks:** A1 real-server smoke CI (Paper 1.21.1, 1.21.11, 26.3; Spigot 26.3; Folia) (F2). A2 GUI click audit + HIGH-priority listener (F3). A3 persistence schema version table + world/chunk columns + dirty writes (F5). A4 route 17 scheduler sites through `SchedulerCompat` region/entity variants; add `folia-supported` last (F1). A5 chunk-indexed viewer refresh and removal (F4). A6 add test repositories to `build.gradle:14-16` for test scope only; first golden-file pack tests; CI runs `./gradlew check` + Blockbench plugin test (F10). A7 throttle per-tick nearby scans (F11). A8 quick wins from `09`.
**Milestones:** smoke CI green on all targets; GUI exploit repro closed; machine set survives world copy; Folia loads and serves one region's instances.
**Acceptance:** zero exceptions in smoke logs across all five server types; every audit finding F1–F12 has a test or a closed repro.
**KPIs:** baseline MSPT recorded per 1k/10k display instances; refresh pass ≤ 5 ms at 100 players × 10k instances (vs ~1,000,000 distance checks today, F4).

## Phase B — Core engine and API v1 (≈ 8 weeks)

**Goal:** third-party developers can build on Mineplus without touching internals.
**Tasks:** B1 data-driven definitions with validation and atomic hot reload (P4). B2 API v1 facade registered in `ServicesManager`, `@since` everywhere (P5). B3 cancellable events for place/break/interact/machine start/finish (P7); remove the op/creative full-access bypass (`MultiBlockLifecycleManager.java:273-275`). B4 active-set machine heartbeat with tick budget (F6). B5 Adventure migration (F14). B6 version/docs drift cleanup (F13, F15, F16).
**Milestones:** one example addon compiled only against the API; reload of a machine definition with zero downtime.
**Acceptance:** adding a machine in examples takes < 50 lines (the stated success criterion); `/mineplus doctor` reports definition errors with file and line.
**KPIs:** time-to-first-machine for a new developer ≤ 30 min from README; heartbeat MSPT flat as total machines grow from 1k to 100k (active set).

## Phase C — Machines and models (≈ 14 weeks)

**Goal:** the Slimefun/Create layer, on fixed foundations.
**Tasks:** C1 energy networks via union-find + topological balancing (P6). C2 kinetic networks (speed/stress). C3 item/fluid logistics. C4 research tree + guide book. C5 animation: state machines, event keyframes, no-viewer skip, LOD (03 §3). C6 bosses: phases, telegraphed attacks. C7 live pack/model hot reload from Blockbench. C8 asset pipeline v2: incremental content-addressed builds, external host, `addResourcePack`, rate limit (F8, P9).
**Milestones:** a 3-machine processing chain (source → transform → sink) running on energy nets at 10k machines with flat MSPT; hot-reload of a model during play.
**Acceptance:** network recomputation on block edit is incremental (no full BFS); pack rebuild after one model change touches only that model's hash.
**KPIs:** ≤ 2 ms/tick machine budget at 100k machines (active set); entities per player ≤ budget from A1 baseline; pack build time sub-linear in model count.

## Phase D — Network and minigame layer (≈ 16 weeks)

**Goal:** Hypixel-style play on the framework, as a separate jar (P10).
**Tasks:** D1 minigame toolkit: game state machine, teams, kits, map regions with markers, per-game scoreboards, spectators. D2 network toolkit: authenticated proxy messaging, Redis pub/sub state, queue, parties, server selector NPCs, stats/leaderboards. D3 instance lifecycle create/ready/drain/destroy with template worlds; allocation external. D4 idempotent cross-server commands; drain-and-restart safe against persistence v2.
**Milestones:** one minigame (e.g. a round-based arena) running two instances behind Velocity with queue and party join; a drained instance loses no machine state.
**Acceptance:** any message arriving over plugin messaging from a non-proxy source is rejected (Velocity rule, 03 §5); instance start-to-ready ≤ 15 s from template.
**KPIs:** queue join-to-game ≤ 30 s; cross-server state convergence ≤ 1 s; zero state loss across 100 drain cycles.

## Phase E — SDK, docs, ecosystem (≈ 8 weeks, overlaps D)

**Tasks:** E1 Gradle plugin + project generator; example addons (one machine, one mob, one minigame). E2 docs site with javadoc from API v1. E3 admin suite: content validator, pack size analyzer, lag-source finder, content hot-patch (06 #26). E4 cosmetics/seasonal events as API showcases.
**Acceptance:** a new developer generates, builds and runs an example addon without reading core source.
**KPIs:** generator-to-running-server ≤ 10 min; docs coverage 100% of public `@since` surface.

Sequencing rule: no phase starts before its dependency phase's acceptance criteria are green (C needs A3/B4; D needs B2/A3; E rides on B2).
