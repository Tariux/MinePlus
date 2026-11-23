# MinePlus Core — Implementation Report

Status of every approved phase, with what shipped where.

## Phase 0 — Foundations — ✅ applied
- **Molang evaluator** (`MolangExpression.java`): sandboxed, allocation-free expression evaluator (`math.*`, `q.anim_time`, `q.life_time`); `Molang.numericConstant` now constant-folds; `Keyframe`, `BbModelImporter` and `AnimationEvaluator` compile and evaluate expression keyframes instead of dropping them.
- **Gradle wrapper** (Gradle 8.10.2) + **CI** (`.github/workflows/ci.yml`).

## Phase 1 — Mob feel — ✅ applied
Ambient/hurt/death sounds, hurt-clip trigger wired into the damage path.

## Phase 5 — Collision cell materials — ✅ applied
Per-cube `collisionMaterial` (slime, honey, cobweb, powder snow, soul sand, bubble column) on the barrier lattice: vanilla physics on custom geometry, free for all entities.

## Phase 6 — Light engine tiers — ✅ applied
`LightTier` hidden-light-source system driven by texel brightness.

## Phase 7 — Unified hit testing — ✅ applied
`HitTestApi` on `PluginContext` / `dev()`: world blocks, virtual-model cells, custom mobs in one ray.

## Phase 8 — Developer API 2.0 — ✅ applied
`DeveloperApi2` facade on `PluginContext.dev()`.

## Phase 9 — PlacementOptions — ✅ applied (this session)
- New `virtual/PlacementOptions.java`: yaw lattice (90/45/22.5, must divide 360), face-normal offset, air-target check; `DEFAULT` + `with*` builders.
- `VirtualBlockPlacementHelper.getPlacementData(player, distance, options)` overload; old signature delegates. `calculateVanillaRotation` snaps to the configured lattice.

## Phase 10 — Mob senses & memory — ✅ applied
`MobMemory` (senses, grudges) exposed via `BehaviorContext.memory()`.

## Phase 11 — Rendering 2.0 — ✅ applied (this session)
`render/RenderDiagnostics.java`: point-in-time census (rigged mobs, virtual-block instances, live `Display` entities, worlds) exposed as `dev().renderSnapshot()`, so modules can watch render budgets instead of guessing.

## Phase 12 — Multiblock 2.0 — ✅ applied (this session)
`core/multiblock/MultiblockApi.java`: machine lookup by location, instance/type listing, process start/cancel/read, and `advanceProcesses(interval)` returning dirty instance ids for event-driven displays. Exposed as `dev().multiblock()`.

## Phase 13 — Docs — ✅ applied (this session)
`docs/developer-api.md` gained the "Developer API 2.0 (`dev()`)" section, the `PlacementOptions` example and the new facade methods.

## Verification
Final gate: `./gradlew clean compileJava testRunners jar` — see the chat message for the run of record.

## Phase 14 — Held-pose solver & hurt feedback — ✅ applied (this session)
Dynamic held-item pose system replacing axis-aligned heuristics:
- `pack/item/HeldPoseSolver.java`: PCA (3x3 covariance + Jacobi eigen-decomposition) over cube corners pooled in **model space** (cube + bone pivot, per `.mlai/DECISIONS.md`), solving both orientation and grip translation for any model shape; per-context Euler for first/third person, left mirrored.
- `BbClipBaker` fills display contexts from geometry only when none are authored; authored contexts win unchanged.
- `MobAnimator.hurtFlinchDegrees()`: procedural 12° half-sine flinch over 0.3 s for rigs without an authored `hurt` clip; `MobRenderEngine` adds a 0.08-block root lift and root-bone tilt, and drops the ground-shadow plate onto the heightmap surface.
- Behaviour preserved exactly: authored display contexts and authored `hurt` clips take precedence over everything derived; models with no dominant axis get no pose (previous behaviour).

## Rollback (Phase 14)
Revert `src/main/java/com/mineplus/pack/item/HeldPoseSolver.java` (delete), and `git checkout` of `BbClipBaker.java`, `MobAnimator.java`, `MobRenderEngine.java` and `CustomMobPipelineTest.java`. No data paths, config keys or resource-pack formats changed, so rollback is a pure code revert; already-baked packs keep working because authored display blocks are untouched.

## Tests (Phase 14)
`CustomMobPipelineTest.heldPoseSolverPivots()`, `.heldPoseSolverGeometry()`, `.bakerAutoHeldPose()`, `.mobAnimatorHurtFlinch()`. Run of record: `./gradlew test --rerun-tasks && ./gradlew testRunners --rerun-tasks` — exit 0, all 9 runners "all checks passed".
