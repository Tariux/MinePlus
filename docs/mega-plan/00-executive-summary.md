# 00 Executive summary

Mineplus is a 65k-line Java plugin that already delivers the hardest part of the vision — custom blocks, items, mobs and furniture on completely vanilla clients, with an automatic resource pack, machine GUIs and multiblock persistence. What it does not yet have is the two product layers the vision names (machines with energy/logistics, and a network/minigame layer), a stable API for other developers, and any proof that it works outside one developer's machine. Evidence for every claim is in `02-audit-findings.md` (findings F1–F17) and `03-research-benchmark.md`.

## Top 10 risks (highest first)

1. **F2 — No version has been verified on a real server.** `docs/version-compatibility.md:16-17` says so explicitly. Every other claim rests on this not being broken. Fix costs 5–8 days and unblocks everything.
2. **F3 — Possible GUI item theft/duplication** via unhandled `ClickType`s (`AbstractMachineGui.java:101-106`). Exploitable; fix is 2 days.
3. **F5 — Persistence has no schema version** (`SqliteMigrationRunner.java:16-33`). Any storage change after users have worlds is a manual rescue. Fix before the machine layer lands.
4. **F1 — Folia support is claimed but absent**: 17 raw Bukkit scheduler calls and no `folia-supported` flag. Competitors advertise Folia; we must either build it or stop saying it.
5. **F9 — No stable extension surface.** A "framework other developers build on" with zero events and no service registration means every refactor breaks addons.
6. **F10 — 9 test classes, 1,719 lines, no CI** for 64,967 lines of source. Every phase below depends on this changing first.
7. **F4 — Viewer refresh is O(players × instances)** (`DisplayTransport.java:435,446`); a million distance checks per pass at the stated 10k-instance target.
8. **F6 — Machine heartbeat walks every instance** every second (`MultiBlockLifecycleManager.java:455`), which the machine layer will multiply.
9. **F8 — Pack endpoint binds `0.0.0.0` in plain HTTP by default** (`ConfigManager.java:299`) and replaces other plugins' packs.
10. **F11 — Per-tick `getNearbyEntities`/`getOnlinePlayers` scans** in mob and animation paths; unbounded with player count.

## Top 10 opportunities

1. Folia compatibility via the existing `SchedulerCompat` wrapper (F1) — a market requirement, competitors list it.
2. Verified-version smoke CI (F2) — converts the whole compatibility story from hope to fact.
3. Dirty-row SQLite persistence with schema versioning (F5) — prerequisite for machines at scale.
4. Chunk-indexed viewer culling (F4) — the single biggest scaling win; index already exists at `DisplayTransport.java:85`.
5. Versioned API + event surface (F9) — turns "framework" into an actual product.
6. `item_model` data-component path is already correct and future-proof (`PackItemFactory.java:166`) — keep and document it.
7. Blockbench plugin v6.1.0 already covers bones/animations/hitboxes — the pipeline end-to-end exists; hot reload is a small step.
8. Active-set machine ticking (F6 fix) borrowed from Slimefun's coarse tick — enables 100k machines.
9. Cancellable events for place/break/interact (F7 fix) — unlocks WorldGuard/GriefPrevention coexistence and the protection plugin market.
10. Adventure migration (F14) — 259 `ChatColor` uses stand between the codebase and one modern text path.

## Recommended direction

**Stabilize, then engines, then network.** Phase A makes what exists correct and measured (tests + CI, real-server smoke, GUI/persistence safety, scheduler unification). Phase B ships API v1: events, a versioned facade, data-driven definitions with validation. Phase C builds the machine layer (energy, kinetic, logistics) on the fixed foundations, with coarse ticks and network caps sized from Slimefun's published defaults. Phase D adds a minigame/network toolkit as a *separate* jar against the API, with Redis state and an external allocator — orchestration never goes in the core (03 §5). Phase E is SDK, docs and templates. Do not start Phase C or D work before A and B: each is built on top of the previous one's guarantees, and the audit shows the current base cannot carry them (F5, F6, F9, F10).

Top 5 things to do first: smoke CI on real Paper 1.21/26.3 (F2), GUI click hardening (F3), persistence schema versioning (F5), route all 17 scheduler calls through `SchedulerCompat` (F1), chunk-indexed viewer refresh (F4).
