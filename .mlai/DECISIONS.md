# Decisions

## Model-space coordinates are cube + bone pivot
- `ModelCube` stores from/to relative to its own bone's pivot; `BbClipBaker` adds the bone pivot (`cube.fromX() + pivot[0]`) to reach model space.
- Any code that pools cube geometry across bones (for example `HeldPoseSolver.solve`) must add the bone pivot itself, or bones with off-origin pivots collapse onto the origin and geometry analysis (PCA long-axis, bounds) is wrong.
- Regression: `CustomMobPipelineTest.heldPoseSolverPivots()`.

## Element rotations are snapped to 22.5° for clients older than 1.21.6
- Clients below 1.21.6 reject a whole item model if any element rotation is off the 22.5° steps (or beyond ±45°) and draw the purple/black missing-model cube.
- Blockbench exports and generated in-between frames routinely use free angles (for example -18°, 16.875°).
- `ModelGeometryGuard.enforce(entries, steppedAngles)` therefore snaps angles when the target server is below 1.21.6. On 1.21.6+ angles are left as authored.
- Cost: hinges and in-between frames look slightly coarser on old clients. Do not remove the snap to "fix" that; the model stops rendering.

## Legacy predicate packs animate through `cooldown` overrides
- A server tick is too coarse to walk animation frames, so clips play on the client's clock: one frame-state write plus one item cooldown.
- Modern packs (1.21.4+) express this as a nested `range_dispatch` on `minecraft:cooldown`. Legacy packs express the same thresholds as `cooldown` predicates in the host model's `overrides` (`ItemModelWriter.legacyHostFiles`).
- Overrides must stay sorted ascending by (custom_model_data, cooldown) across the whole host file: vanilla picks the last matching override.
- Modules should ask `PackApi.isAvailable()` to decide whether the client can play a clip. `usesModernItemModels()` is diagnostic only.

## Item display transforms are derived from geometry when not authored
- Static items without an authored `display` and without a parent go through `ItemDisplaySolver.derive` (`pack/item`): wrap one bbox around the covered cubes, center gui/ground/fixed/head on the vanilla center (8,8,8) and grip-align the four hand contexts via `HeldPoseSolver.solveBoxes` (PCA long axis). Authored contexts win per context.
- Animated models: `BbClipBaker.autoHeldPose` solves each unauthored hand context independently (a partial authored display no longer cancels the pass for the untouched contexts).
- Authors can tune the pose in a second file: `"displayFile": "<name>.bbmodel"` in the model meta, or a sibling `<stem>.display.bbmodel` next to the model; its display transforms replace the target's entirely. `.display.bbmodel` sidecars are excluded from model registration by `ModelImportCoordinator`.
- Do not re-add a global "authored anywhere cancels the solve" rule: it is what left partially-authored models floating in the hand.
