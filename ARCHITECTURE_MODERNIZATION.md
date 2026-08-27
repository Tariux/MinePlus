# Mineplus Architecture Modernization — Design System & Implementation Guide

## 1. Project Context

You are operating on the **Mineplus** codebase, a Spigot/Paper plugin (Java 21, Gradle, targeting MC 1.21) located at the project root. The plugin provides a data-driven framework for custom multiblock machines, virtual rendered models, recipes, GUIs, and linked block systems.

**Current architecture (as of this prompt):**
- `MineplusPlugin` → `PluginContext` → `InfrastructureEngine` (composition root)
- `InfrastructureEngine` composes: `MultiBlockRegistry`, `MultiBlockLifecycleManager`, `ModelRenderingManager`, `MultiBlockLinkingSystem` + `HookBus`, `RecipeManager`, `UpgradeManager`, `InfrastructureGuiManager`, `MultiBlockStorageEngine`, config loaders, and three-tier API layer.
- Virtual rendering: `.bbmodel` → `BbModelImporter` → `VirtualModel` → `VirtualBlockManager` (spawns `BlockDisplay` entities + barrier blocks).
- **Two persistence systems exist:** active JSON file persistence (`MultiBlockStorageEngine` → `multiblocks.json`) and dormant SQLite persistence (`PersistenceFacade` + `Sqlite*` classes, fully implemented but **never wired** into the engine).
- `VirtualBlockManager` maintains its own independent persistence (`virtual-blocks.json`) and restoration logic, operating autonomously from the multiblock lifecycle.

**Critical architectural deficiencies:**
1. No unified state machine; `MultiBlockInstance` is a passive data record with no lifecycle status.
2. Split-brain state between physical blocks, virtual visuals, and instance registry.
3. No atomic lifecycle binding; render, register, and persist are separate non-atomic steps.
4. No crash-recovery semantics; `restorePersistedInstances()` blindly replays without validating physical world state.
5. `VirtualBlockManager` restores visuals independently, creating orphaned entities.
6. `renderedModelId` (runtime entity UUID) is persisted, but entity UUIDs do not survive server restarts.
7. `HookBus` is synchronous with no error isolation.
8. `MultiBlockLinkingSystem` retains dangling links with no cleanup.
9. Inconsistent error handling (mix of `printStackTrace()`, logger warnings, null returns, empty collections).
10. `Location` used as map key in `VirtualBlockManager` instead of `BlockCoordinate`.

---

## 2. Primary Objective

Your **sole responsibility** is to perform **precise, complete, and consistent code updates** across the entire codebase to implement the architectural modernization plan defined in this document. You must follow the implementation phases sequentially, modify the correct files, and ensure every change is consistent with the existing architecture and coding conventions.

**You MUST NOT:**
- Run any tests, test frameworks, or validation scripts.
- Execute any Gradle tasks (`build`, `compileJava`, `test`, `run`).
- Execute any server scripts, plugin reloads, or runtime validation.
- Modify any files outside the `src/main/java` directory unless explicitly instructed.
- Add comments, documentation, or markdown files to the codebase unless explicitly instructed.
- Propose alternative architectures or "improvements" not specified in this prompt.
- Engage in conversational filler, summaries, or post-action explanations beyond what is necessary to identify the correct files and changes.

Your output must be **granular file edits** using the Edit tool, applied sequentially and correctly. When a phase requires changes across multiple files, batch independent reads in parallel, then apply edits methodically.

---

## 3. Target Architecture

### 3.1 Unified State Model (Enhanced Existing Component)

**Enhance `MultiBlockInstance`** — do not create a parallel state object. Add lifecycle fields directly to the existing record:

- `status` (EntityStatus enum: `CREATED`, `PLACED`, `ACTIVE`, `BROKEN`, `REMOVED`, `CORRUPTED`)
- `lastHeartbeat` (long)
- `lastValidatedAt` (long)
- `modelKey` (String — the `.bbmodel` path/key, replacing `renderedModelId` as the persistent identifier)

**Remove from persistence:** `renderedModelId` (runtime entity UUID). This field may remain on `MultiBlockInstance` as a transient runtime handle but must never be serialized.

**State transition rules (enforced by `MultiBlockLifecycleManager`):**
- `CREATED → PLACED` (on successful render + validation)
- `PLACED → ACTIVE` (on activation event)
- `ACTIVE → BROKEN` (on physical block break or manual removal)
- `BROKEN → REMOVED` (after successful cleanup of visuals + vectors)
- `CREATED/PLACED/ACTIVE → CORRUPTED` (on reconciliation failure)
- `CORRUPTED → REMOVED` (on cleanup)
- Any state → REMOVED` (on forced removal)

**Critical invariant:** Visual elements and vectors may only exist if `status ∈ {PLACED, ACTIVE}`.

### 3.2 Atomic Persistence via SQLite Transactions (Reuse Existing Infrastructure)

**Leverage `PersistenceFacade`** — it already provides transaction support via `SqlitePersistenceTx`. Do not build a custom WAL table.

Persistence protocol:
1. Begin SQLite transaction via `PersistenceFacade.beginTransaction()`.
2. Write full `MultiBlockSnapshot` (extended with new fields) within the transaction.
3. Commit transaction via `PersistenceFacade.commitTransaction()`.
4. On failure, rollback via `PersistenceFacade.rollbackTransaction()`.

SQLite's native `PRAGMA journal_mode=WAL` provides crash-safe write-ahead logging. Enable this in `SqliteConnectionFactory.open()` via `PRAGMA journal_mode=WAL`.

**Extend `MultiBlockSnapshot`** to include:
- `status` (String — EntityStatus name)
- `lastHeartbeat` (long)
- `lastValidatedAt` (long)
- `modelKey` (String)
- Remove `renderedModelId` (or keep as transient, excluded from serialization)

### 3.3 Heartbeat & Reconciliation (Integrated, Not Isolated)

**Heartbeat:** In `MultiBlockLifecycleManager.tick()`, update `lastHeartbeat` for all `ACTIVE` instances. Batch updates and persist via `PersistenceFacade` on a 5-second interval using the existing scheduler.

**Reconciliation:** Integrate into `InfrastructureEngine.initialize()` and `reloadAll()` as a method on `MultiBlockLifecycleManager` (e.g., `reconcile()`), not a new class. The method:
1. Loads all persisted snapshots via `PersistenceFacade`.
2. For each snapshot with `status ∈ {PLACED, ACTIVE}`:
   - Validate physical block exists at `BlockCoordinate`.
   - Validate corresponding multiblock type exists in registry.
   - If invalid → mark `CORRUPTED`.
3. For `CORRUPTED` states: execute cleanup (remove visuals, clear vectors, remove from registry).
4. For valid `ACTIVE` states: re-render model using `modelKey` (generate new runtime UUID), re-register in registry.

### 3.4 VirtualBlockManager Lifecycle Coordination (Enhanced, Not Replaced)

**Enhance `VirtualBlockManager`** with lifecycle awareness rather than removing its autonomy:
- Add `onLifecycleRemove(UUID instanceId)` callback that lifecycle manager invokes.
- Add `onLifecycleRestore(BlockEntityState state)` callback for coordinated restoration.
- `restoreSpawnedModels()` becomes `restoreForState(BlockEntityState state)` — only restores visuals for valid, active states.
- `saveNow()` and `shutdown()` persistence logic is removed entirely; `VirtualBlockManager` must not persist.
- Change `blockToModelMap` key from `Location` to `BlockCoordinate`.
- Fix `onBarrierBreak()` to invoke lifecycle removal.

**`ModelRenderingManager`** validates that `render()` and `swapModel()` succeeded before mutating instance state.

### 3.5 Link Integrity (Enhanced Existing Component)

**Enhance `MultiBlockLinkingSystem`**:
- `sendSignal()` validates target instance exists before propagating.
- Add `cleanupLinksFor(UUID removedId)` method.
- Call from `MultiBlockLifecycleManager.remove()` and `pruneUnknownTypes()`.

---

## 4. Implementation Phases

Execute the following phases **sequentially**. Each phase builds on the previous one. Do not skip phases.

### Phase 0: Unified Persistence Wiring

**Goal:** Wire `InfrastructureEngine` to use the existing SQLite `PersistenceFacade`. Deprecate JSON persistence.

**Actions:**
1. In `InfrastructureEngine.java`:
   - Add `PersistenceFacade` field.
   - Inject via constructor using `PersistenceConfig.defaults(plugin.getDataFolder())` and `plugin.getLogger()`.
   - Call `persistenceFacade.initialize()` in constructor.
   - Replace `MultiBlockStorageEngine` field and all references with `PersistenceFacade`.
   - In `initialize()`: load snapshots via `persistenceFacade.loadAllMultiBlocks()`, convert via `MultiBlockSnapshot.toInstance()`, register them.
   - In `shutdown()`: call `persistenceFacade.shutdown(5000)`.
   - In `reloadAll()`: after reloading configs, enqueue full replace via `persistenceFacade.enqueueFullReplace()` then `flushNow()`.
2. In `PluginContext.java`:
   - Pass `PersistenceFacade` to `InfrastructureEngine` constructor.
3. In `SqliteConnectionFactory.java`:
   - Add `PRAGMA journal_mode=WAL` and `PRAGMA synchronous=NORMAL` to `open()` for crash-safe WAL.
4. Deprecate `MultiBlockStorageEngine.java` by prefixing with `// DEPRECATED` comment; do not delete yet to avoid breaking references during transition.
5. Update `build.gradle` only if SQLite driver JAR is missing from `libs/` (it is already listed).

### Phase 1: State Machine Foundation

**Goal:** Enhance `MultiBlockInstance` with lifecycle status and transition validation.

**Actions:**
1. Create `EntityStatus.java` enum in `infrastructure/core/multiblock/`:
   - Values: `CREATED`, `PLACED`, `ACTIVE`, `BROKEN`, `REMOVED`, `CORRUPTED`.
2. Enhance `MultiBlockInstance.java`:
   - Add `status` (EntityStatus), `lastHeartbeat` (long), `lastValidatedAt` (long), `modelKey` (String) fields.
   - Update constructor and record components accordingly.
   - `renderedModelId` remains as a runtime field but is excluded from `StoredMultiBlock` serialization.
3. Enhance `MultiBlockSnapshot.java`:
   - Add `status`, `lastHeartbeat`, `lastValidatedAt`, `modelKey` fields.
   - Remove `renderedModelId` from serialization (keep in `from()`/`toInstance()` for backward compatibility during migration, but do not persist it).
   - Update `from(MultiBlockInstance)` and `toInstance()` factory methods.
4. Enhance `StoredMultiBlock` record in `MultiBlockStorageEngine.java` (deprecated):
   - Add `status`, `lastHeartbeat`, `modelKey` fields so old JSON files can still be loaded during migration.
5. Create `EntityStateMachine.java` in `infrastructure/core/multiblock/lifecycle/`:
   - Static method `canTransition(EntityStatus from, EntityStatus to)` returning `boolean`.
   - Static method `transition(MultiBlockInstance instance, EntityStatus newStatus)` that validates and mutates, returning `boolean`.
   - Static method `validateInstance(MultiBlockInstance instance, MultiBlockRegistry registry)` that checks type existence and coordinate validity, returning `boolean`.
6. Refactor `MultiBlockLifecycleManager.java`:
   - `create()`: set `status = CREATED`, persist via `PersistenceFacade`.
   - `place()`: validate `CREATED → PLACED`, render, validate success, set `status = ACTIVE`, persist.
   - `upgrade()`: validate `ACTIVE → ACTIVE` (level change), swap model, persist.
   - `remove()`: validate transition to `BROKEN` or `REMOVED`, cleanup, persist.
   - `restorePersistedInstances()`: convert snapshots to instances with status, run reconciliation, render valid states.
   - `tick()`: update `lastHeartbeat` for `ACTIVE` instances.
   - `pruneUnknownTypes()`: clean up dangling links for removed instances.
7. Refactor `ModelRenderingManager.java`:
   - `render()` returns `null` on failure; caller must handle rollback.
   - `swapModel()` removes old model first, validates new render, updates `instance.renderedModelId()` only on success.

### Phase 2: Crash Recovery & Heartbeat

**Goal:** Implement heartbeat persistence and startup reconciliation.

**Actions:**
1. In `MultiBlockLifecycleManager.java`:
   - Add `heartbeatTaskId` field and schedule a repeating task in `initialize()` (or have `InfrastructureEngine` schedule it) that calls `flushHeartbeats()` every 5 seconds.
   - `flushHeartbeats()` iterates `ACTIVE` instances, updates `lastHeartbeat`, and calls `persistenceFacade.enqueueFullReplace()` with current snapshot list, then `flushNow()`.
2. In `MultiBlockLifecycleManager.java`:
   - Add `reconcile()` method that implements the reconciliation protocol described in Section 3.3.
3. In `InfrastructureEngine.java`:
   - Call `lifecycleManager.reconcile()` at the start of `initialize()` and at the start of `reloadAll()`.
   - Schedule heartbeat flush task alongside the existing tick task.
4. In `MultiBlockLifecycleManager.restorePersistedInstances()`:
   - Load snapshots, convert to instances with status fields.
   - Call `reconcile()` before registering instances.
   - Only re-render instances with `status == ACTIVE` after reconciliation validates them.

### Phase 3: VirtualBlockManager Lifecycle Integration

**Goal:** Make `VirtualBlockManager` lifecycle-coordinated rather than autonomous.

**Actions:**
1. Refactor `VirtualBlockManager.java`:
   - Remove `restoreSpawnedModels()` method body; replace with a lifecycle-aware `restoreForState(BlockCoordinate anchor, String modelKey, Quaternionf rotation)` that only spawns if the corresponding multiblock instance exists and is valid. Call this from `MultiBlockLifecycleManager.restorePersistedInstances()`, not from `loadModels()`.
   - Remove `saveNow()` and `shutdown()` persistence logic entirely.
   - Change `blockToModelMap` from `Map<Location, UUID>` to `Map<BlockCoordinate, UUID>`.
   - Add `boolean exists(UUID instanceId)` method.
   - Add `Set<Location> getBarrierLocations(UUID instanceId)` method.
   - Fix `onBarrierBreak()`: resolve instanceId from `blockToModelMap` using `BlockCoordinate`, then invoke lifecycle removal via a callback or direct manager reference. The listener should be registered in `MineplusPlugin` or `InfrastructureListener` with access to `MultiBlockLifecycleManager`.
2. Refactor `InfrastructureListener.java`:
   - `onBarrierBreak()` already calls `lifecycleManager.remove()` for origin instances; extend it to also handle barrier-block-triggered removals by resolving the multiblock instance via `blockToModelMap` and calling `lifecycleManager.remove()`.
3. Refactor `ModelRenderingManager.java`:
   - `render()` validates world is loaded and model is resolvable before spawning; returns `null` on failure.
   - `swapModel()` calls `remove()` then `render()`, validates both succeed, updates instance.

### Phase 4: Transactional Persistence & Migration

**Goal:** Use SQLite transactions for atomic saves and migrate from JSON.

**Actions:**
1. In `PersistenceFacade.java`:
   - `enqueueFullReplace()` and `flushNow()` already use `SqlitePersistenceTx` with transactions. Ensure `PRAGMA journal_mode=WAL` is set (via `SqliteConnectionFactory`).
   - Add `beginTransaction()`, `commitTransaction()`, `rollbackTransaction()` methods that delegate to `SqlitePersistenceTx`.
2. In `MultiBlockLifecycleManager.java`:
   - Replace all `storage.saveAsync()` calls with `persistenceFacade.enqueueFullReplace()` + `flushNow()`.
3. Migration path:
   - In `InfrastructureEngine.initialize()`, after wiring `PersistenceFacade`, check if `multiblocks.json` exists.
   - If it exists, load it via a temporary `MultiBlockStorageEngine` instance, convert each `StoredMultiBlock` to `MultiBlockSnapshot`, enqueue to `PersistenceFacade`, flush, then delete or archive the JSON file.
   - This ensures backward compatibility for existing server installations.
4. Remove `MultiBlockStorageEngine.java` after migration is complete and all references are gone.

### Phase 5: HookBus Hardening & Link Integrity

**Goal:** Isolate listener failures and clean up dangling links.

**Actions:**
1. Refactor `HookBus.java`:
   - Wrap each `listener.accept(event)` in try-catch.
   - Log failures via `plugin.getLogger().log(Level.WARNING, ...)` without stopping subsequent listeners.
   - Add overloaded `registerLifecycleListener(Consumer<MultiBlockLifecycleEvent> listener, MultiBlockLifecycleEventType... types)` for type-filtered registration.
2. Refactor `MultiBlockLinkingSystem.java`:
   - `sendSignal()` validates `registry.getInstance(targetId) != null` before propagating.
   - Add `cleanupLinksFor(UUID removedId)` that iterates all instances and removes `removedId` from their `linkedBlocks` sets.
   - Call from `MultiBlockLifecycleManager.remove()` and `pruneUnknownTypes()`.

### Phase 6: Code Quality & Defensive Fixes

**Goal:** Address critical code quality issues without changing architecture.

**Actions:**
1. Create `StringNormalizer.java` in `infrastructure/core/util/`:
   - `static String normalize(String value)` — lowercase, trim, null-safe.
   - Replace duplicate `normalize()` methods across `MultiBlockRegistry`, `CommandRouter`, `RecipeManager`, `MultiBlockConfigLoader`, `InfrastructureGuiManager`.
2. In `VirtualBlockManager.java`:
   - Replace `Location` keys in `blockToModelMap` with `BlockCoordinate`.
   - Fix `onBarrierBreak()` to use `BlockCoordinate.from(block.getLocation())`.
   - Replace `exception.printStackTrace()` with `plugin.getLogger().log(Level.WARNING, ...)`.
3. In `MultiBlockRegistry.java`:
   - Document that all access must occur on the main server thread. Add `synchronized` to `clearTypes()`, `registerType()`, `registerHookOverride()`, `addInstance()`, `removeInstance()`, `clearInstances()` as a defensive measure against future async access.
4. In `MultiBlockLifecycleManager.java`:
   - Replace `findByRenderedModelId()` stream scan with a `Map<UUID, UUID>` (`renderedModelId → instanceId`) maintained on `addInstance()` and `removeInstance()`.
5. Establish error handling convention:
   - All internal methods log via `plugin.getLogger()`.
   - Public API methods return `Optional<T>` or throw documented exceptions; never return `null` silently without log.
   - Replace all `printStackTrace()` with proper logging.

---

## 5. Execution Rules

### 5.1 Component Reuse & Enhancement Protocol

- **Enhance existing components before creating new ones.** If a new concept can be expressed by extending an existing class/record, do so.
- **Avoid parallel state objects.** State must live on `MultiBlockInstance` and `MultiBlockSnapshot`, not in new parallel records.
- **Reuse existing persistence infrastructure.** `PersistenceFacade`, `SqliteConnectionFactory`, `SqlitePersistenceTx`, and `MultiBlockSnapshot` are the foundation. Extend them; do not replace them.
- **Reuse existing lifecycle events.** `MultiBlockLifecycleEventType` already covers the needed transitions. Add `CORRUPTED` if needed, but do not duplicate event types.

### 5.2 File Modification Protocol

- **Always read the target file before editing.** Use the Read tool to inspect the current content.
- Use the Edit tool for precise string replacements. Do NOT use Write tool to overwrite entire files unless the file is being created new.
- When creating new files, use the Write tool with the complete file content.
- Maintain existing code style: 4-space indentation, Java 21 features (records, var, switch expressions), existing naming conventions.
- Preserve all existing imports; add new imports only as needed.
- Do NOT rename existing classes or methods unless explicitly required by the phase.
- Do NOT change package structures.

### 5.3 Granularity Requirements

For each phase, provide **step-by-step file edits**. Do not batch unrelated changes into a single edit. Each Edit tool call should target a specific, logical change.

When a phase requires changes across multiple files:
1. Read all affected files in parallel.
2. Apply edits in dependency order (e.g., create `EntityStatus` enum before enhancing `MultiBlockInstance` which uses it).
3. Verify each edit is syntactically valid by inspecting surrounding context.

### 5.4 Consistency Requirements

- All persistence flows through `PersistenceFacade` after Phase 0; JSON persistence is deprecated and removed after Phase 4.
- All instance restoration flows through `reconcile()` after Phase 2.
- All visual operations for lifecycle-managed entities go through `ModelRenderingManager` after Phase 3; `VirtualBlockManager` has no autonomous restoration.
- `renderedModelId` is never persisted after Phase 1; only `modelKey` is persisted.
- All lifecycle transitions validate via `EntityStateMachine` after Phase 1.

### 5.5 What You MUST NOT Do

- **Do not run tests.** The project has no test suite configured; do not create one.
- **Do not run Gradle.** Do not execute `./gradlew build`, `compileJava`, or any other task.
- **Do not start a server.** Do not attempt to load the plugin in any runtime environment.
- **Do not validate behavior.** Your job is structural code modification, not functional verification.
- **Do not add logging beyond what is necessary for error reporting.** Do not add debug logs, metrics, or observability code unless specified in a phase.
- **Do not modify `build.gradle`, `settings.gradle`, `plugin.yml`, or any non-Java files** unless explicitly required by a phase.

---

## 6. Completion Criteria

You have completed the task when:

1. **Phase 0 complete:** `InfrastructureEngine` uses `PersistenceFacade` (SQLite) with WAL enabled. JSON persistence classes are deprecated.
2. **Phase 1 complete:** `MultiBlockInstance` has `status`, `lastHeartbeat`, `modelKey` fields. `EntityStatus` enum exists. `EntityStateMachine` validates transitions. `MultiBlockLifecycleManager` methods use state machine before operating.
3. **Phase 2 complete:** Heartbeat task updates `lastHeartbeat` and flushes every 5 seconds. `reconcile()` runs on startup and reload. Corrupted states are cleaned up before visuals are restored.
4. **Phase 3 complete:** `VirtualBlockManager` has no persistence or autonomous restoration. Barrier breaks trigger lifecycle removal via `InfrastructureListener`. `ModelRenderingManager` validates all render operations.
5. **Phase 4 complete:** SQLite WAL mode enabled. All persistence uses `PersistenceFacade` transactions. JSON migration path exists. `MultiBlockStorageEngine` is removed.
6. **Phase 5 complete:** `HookBus` isolates listener failures. Dangling links are cleaned up on instance removal and type pruning.
7. **Phase 6 complete:** Code quality fixes applied (normalization utility, Location→BlockCoordinate, error handling consistency, O(1) renderedModelId lookup).

**Final state:** The codebase follows a unified state machine architecture built on enhanced existing components. Persistence is atomic via SQLite transactions. Visual entities are lifecycle-coordinated with no orphan possibility. Crash recovery is handled through reconciliation and heartbeat validation. All changes are minimal, surgical, and consistent with the existing codebase style.

Begin with Phase 0. Read the relevant files, apply the edits, and proceed sequentially through each phase until all completion criteria are met.
