# Decisions

## Model-space coordinates are cube + bone pivot
- `ModelCube` stores from/to relative to its own bone's pivot; `BbClipBaker` adds the bone pivot (`cube.fromX() + pivot[0]`) to reach model space.
- Any code that pools cube geometry across bones must add the bone pivot itself, or bones with off-origin pivots collapse onto the origin.
- Regression: `CustomMobPipelineTest.heldPoseSolverPivots()` (removed with the solver; the pivot rule still holds for the baker).

## Element rotations are snapped to 22.5° for clients older than 1.21.6
- Clients below 1.21.6 reject a whole item model if any element rotation is off the 22.5° steps (or beyond ±45°) and draw the missing-model cube.
- `ModelGeometryGuard.enforce(entries, steppedAngles)` snaps angles when the target server is below 1.21.6. On 1.21.6+ angles are left as authored. Do not remove the snap to "fix" coarse poses; the model stops rendering.

## Authored display is the only display (workaround layer removed, 2026-11-24)
- `HeldPoseSolver`, `ItemDisplaySolver`, `HeldOrientationFix`, `BbClipBaker.autoHeldPose`, `Style.HeldCorrection` and the gun module's `pack.held-yaw` / `held-y-lift` / `held-yaw-overrides` are deleted. A model's held/GUI/ground pose comes only from the `display` block the author set (bbmodel or meta; meta wins per context).
- `ModelJsonWriter` emits only authored contexts; missing contexts get vanilla defaults (directly or via the item parent). `ModelDisplay.Transform` clamps rotation ±180°, translation ±80 px, scale 0.01–100, NaN→0 — one malformed field cannot make the client reject the model.
- Migration for models that relied on auto-pose: author the display block (plugin `devtools/studio/generic-display-plugin/`, mapping table `docs/display-slot-mapping.md`). Gun bake `BAKE_VERSION` bumped to 7 so stale frames are swept.
