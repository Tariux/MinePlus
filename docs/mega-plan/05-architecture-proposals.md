# 05 Architecture proposals

Each proposal: design, trade-offs, rejected alternatives, migration and rollback. Ordered by dependency, not importance.

## P1. Scheduler unification and Folia-first concurrency (adopts F1 fix)

**Design:** extend `util/SchedulerCompat` (`:30,50`) with `region(Location, Runnable)` and `entity(Entity, Runnable)` variants; every scheduled task declares the region or entity it touches. Convert the 17 raw call sites one module at a time. Shared maps (`VirtualBlockManager` instance maps, `DisplayTransport.byChunk`) get a single owning thread or become `ConcurrentHashMap` with immutable value records. Only then add `folia-supported: true` to `plugin.yml`.
**Trade-offs:** wrapping costs nothing at runtime on Folia (Folia handles Paper schedulers internally, 03 §1) and removes the 17-site tail risk. Cost is discipline, not performance.
**Rejected:** actor/message-passing framework across regions (full isolation) — right long-term shape, but it rewrites every module at once; incremental conversion is reversible per commit.
**Migration:** one module per PR, each green under the existing smoke job. **Rollback:** stop converting; wrapper is a pass-through on Paper.

## P2. Spatial index and budgeted ticking (adopts F4, F6 fixes)

**Design:** every display instance and machine registers its chunk key (`DisplayTransport.java:85` index already exists). Player refresh iterates only chunks within view distance. Machine heartbeat moves from "walk all instances every 20 ticks" (`MultiBlockLifecycleManager.java:455,569-576`) to an active set (loaded chunk ∧ has work), time-sliced under a per-tick budget (target: ≤ 2 ms/tick, measured via the existing PerfRegistry).
**Complexity:** refresh O(view chunks × instances/chunk) instead of O(players × instances). Heartbeat O(active) instead of O(total).
**Rejected:** octree/hash-grid — chunk granularity matches Minecraft's own load model; finer spatial structures add complexity without benefit here. ECS archetype storage (P3) is the deeper fix for tick cost and is rejected separately below.

## P3. ECS / archetype storage for machines and mobs

**Decision: reject for now.** The value is cache-friendly ticking of millions of homogeneous entities. Mineplus's realistic scale (machines in the thousands to low hundreds of thousands, 03 §4 estimates) is servable by the active set in P2 plus plain Java records. An ECS would force a rewrite of behavior code across `mob`, `core` and `virtual` with no measurable win at these counts, and there are no tests to catch the rewrite (F10). Revisit only if a benchmark shows the P2 path missing MSPT budgets at 100k machines.

## P4. Data-driven definitions with validation and hot reload

**Design:** machine/mob/block YAML today is scattered across loaders with ad-hoc parsing. Introduce one `DefinitionLoader` per content type: parse → validate (schema, unknown-key rejection, range checks) → build an **immutable** runtime record → registry. Registry has bootstrap → register → freeze → run phases mirroring Paper's Lifecycle API pattern (03 §6, UNVERIFIED details). Reload = build a full second registry set, then atomically swap references; nothing mutates in place.
**Trade-offs:** more structure than today's direct YAML→object mapping, but it gives hot reload, editor autocomplete (shipped schema), and `/mineplus doctor` validation output essentially for free.
**Rejected:** annotation scanning and DI containers (03 §6) — runtime magic, poor debuggability, and the actual DX problem is `PluginContext`'s seven overloads (`PluginContext.java:113-217`), fixed by P5.
**Migration:** one content type at a time behind the facade; old loaders delegate until converted. **Rollback:** each type can fall back to its old loader.

## P5. API v1: versioned facade, services, events

**Design:** a small `mineplus-api` interface set (blocks, items, mobs, machines, pack) with `@since` on everything, registered in Bukkit's `ServicesManager` and obtained by addons via `getService`. Cancellable events: custom block place/break/interact, machine start/finish, mob spawn — fired from the existing lifecycle and virtual managers (P7's protection hooks consume the same events). Keep the internal `PluginContext` for the core; the API layer delegates to it.
**Trade-offs:** freezes a surface before all internals settle; mitigate by shipping only what examples demonstrate (03 §6: examples are the real spec) and marking the rest internal.
**Rejected:** keeping `PluginContext` public as-is — seven bootstrap overloads and direct engine references mean every refactor is a breaking change (F9).

## P6. Network graphs for energy / kinetic / logistics

**Design (for Phase C):** each network is a connected component maintained by incremental union-find on block place/break (amortized near-O(α) per edit, no full rebuild). Sources and consumers are nodes; each tick the **active** nodes (P2) are balanced: sum supply, sum demand, propagate results downstream in topological order — Create's speed/stress model flattened to a server-side equivalent (03 §4). Recompute only dirty components. Solve is pure computation over plain data → safe to run off-thread, results applied on the owning region thread (P1).
**Rejected:** per-tick BFS over all machines (Slimefun's older behavior, documented as a pitfall in 03 §4); full flow-solver per tick (overkill for boolean/level networks).
**Rollback:** union-find state is rebuildable from the persisted block set at any time.

## P7. Protection and event compliance

**Design:** every world edit caused by Mineplus goes through one `WorldEditService` that fires a cancellable event per batch (`VirtualBlockManager.java:257-271` today bypasses everything, F7). Player-caused edits additionally call `BlockBreakEvent` so WorldGuard/GriefPrevention rules apply. Remove the null/op/creative full-access shortcut at `MultiBlockLifecycleManager.java:273-275` in favor of explicit permission checks.
**Rollback:** a config flag restores direct-set behavior per edit type.

## P8. Persistence v2

**Design:** `schema_version` table + numbered migrations (F5); world/x/z/chunk columns with an index; dirty-row writes instead of `replaceAll` (`SqliteMultiBlockRepository.java:53`); optional async snapshot before destructive migration. Binary or PDC chunk-attached storage is the end state; SQL remains the network/player tier.
**Rollback:** migrations are one-way by design; the pre-migration snapshot is the rollback.

## P9. Asset pipeline v2

**Design:** content-addressed pack builds (hash per model → incremental rebuild), build off-thread (already async), overlays per client version (exists, `PackFormat.java:101,110`), pluggable host: self-host + external upload + `addResourcePack` instead of `setResourcePack` (F8). Rate-limit the endpoint per address.
**Rejected:** core shaders, packet interception by default — both rejected on fragility grounds in 03 §7 and already optional in the repo.

## P10. Network / minigame layer (Phase D shape)

**Design:** separate `mineplus-minigames` jar against API v1. Proxy plugin (Velocity) owns queue/party/routing; game servers report state and accept drain over authenticated plugin messaging (Velocity's warning is a hard rule, 03 §5) via Redis pub/sub; allocation stays external (existing orchestrator or Agones-style), never in the plugin. Instances are Paper servers with template worlds.
**Rejected:** Minestom instance tier — would need a second implementation of all rendering/mob code (03 §5); orchestration inside the jar (03 §7 "Avoid").
