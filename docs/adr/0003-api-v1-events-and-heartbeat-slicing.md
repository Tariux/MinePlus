# ADR 0003: API v1 events, spawn-area permission, and heartbeat time-slicing

Date: 2026-11-25
Status: Accepted (implemented in this increment; see docs/mega-plan/05 §P3, §P7, 07 Phase B)

## Context

The mega plan (docs/mega-plan/02-audit-findings.md F3/F9, 05-architecture-proposals.md P3/P7, 07-roadmap.md Phase B) calls for a public API v1: cancellable Bukkit events for multiblock lifecycle, removal of the implicit op/creative spawn-area bypass, and a heartbeat pass that stays within tick budget at the 100k-instance scale target. Constraints: the codebase has no Bukkit mock (tests run headless, 9 runners in src/test/java/com/mineplus/test), and dispatch must never throw on servers without a registered plugin manager.

## Decision

1. **Events are plain Bukkit events dispatched through a single guarded entry point** (`ApiEvents.dispatch`, src/main/java/com/mineplus/infrastructure/api/event/ApiEvents.java). It calls `Bukkit.getPluginManager().callEvent` and swallows only `IllegalStateException`/`IllegalArgumentException` (headless/test servers), returning the event so callers read cancellation in one expression.
   - Alternative rejected: per-manager try/catch around each `callEvent` — duplicates the guard in five call sites and risks inconsistent handling.
   - Alternative rejected: a custom event bus for API v1 — third-party plugins expect Bukkit `HandlerList` semantics; a parallel bus would split listeners.

2. **Five API events, `@since 1.3.0`**: `MultiBlockPlaceEvent` (cancellable), `MultiBlockBreakEvent` (cancellable, `destroy` flag distinguishes physical break from admin delete), `MultiBlockInteractEvent` (cancellable, cancel makes the click fall through), `MachineProcessStartEvent` (cancellable, `start()` returns false), `MachineProcessFinishEvent` (informational — output is already produced; prevention happens at start).
   - Alternative rejected: making finish cancellable — it would lie (recipe already consumed inputs) and force rollback logic into every module hook.

3. **Spawn-area clearing requires the explicit permission `mineplus.multiblock.clearspawn`** (op by default) instead of an implicit `isOp() || CREATIVE` bypass (MultiBlockLifecycleManager.place). Creative players without the node now fail the empty-area check like anyone else — a creative build inside a protected region must not silently clear it.
   - Migration: servers that relied on creative bypass grant the node in their permission plugin. Rollback: revert the two-line condition.

4. **Heartbeat sweep is time-sliced with a rotating cursor** (2 ms budget, `HEARTBEAT_BUDGET_NANOS`), iterating a snapshot array so concurrent registration cannot cause skipping; the slice duration feeds `PerfRegistry.recordHeartbeatSlice`. With 100k instances a full per-tick sweep would exceed budget; slicing trades one-tick freshness for a bounded pass — acceptable because heartbeat staleness of a few ticks has no gameplay effect.
   - Alternative rejected: per-instance round-robin map — more state, no better bound.

## Consequences

- Third-party plugins can cancel place/break/interact/process-start via standard Bukkit listeners.
- Cancelled break aborts before any state transition, so no partial teardown.
- New permission node needs documenting in docs/config-reference.md at the next docs pass.
- Events fire only when a Bukkit server is registered; headless tests keep passing (verified: `./gradlew check` BUILD SUCCESSFUL, 9/9 runners).

## Riskiest assumption to prove first

That guarded dispatch does not hide real errors in test environments: spike S-next should assert that a throwing listener surfaces its exception through the plugin manager on a mocked server, not through `ApiEvents`.
