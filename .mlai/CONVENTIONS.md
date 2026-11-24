# Conventions

## Blockbench plugin: the open project is `Project`, never `Model`
- Blockbench exposes the active project as the global `Project` (mirrored on `Blockbench.Project`, which is `0` when nothing is open). There is no `Model` project global — code gating on `typeof Model !== 'undefined' && Model.mesh` silently no-ops in the real app while passing the mocked test sandbox.
- Anything project-dependent must go through `currentModel()` in `devtools/studio/generic-display-plugin/mineplus_generic_display.js`, which checks `Project` → `Blockbench.Project` → legacy `Model`, requiring a `uuid`.
- Format switches on an open project prefer the native `ModelFormat.convertTo()` (migrates box UV, bone-rig grouping, rotation limits); fall back to `fmt.select()` — never a bare `model.format = fmt`, which leaves the global `Format` (and the Display/Animate tabs) stale.
- The test sandbox (`test/plugin.test.js`) mirrors the real globals: `Project` only, no `Model`.

## Guards (added after a mistake repeated)
- Before finishing, stop the servers and background commands you started; long-lived work goes in a job or the preview.
- Report a test, build or fix as passing only after running it in the same task; quote the command and its result, and write "Not verified:" otherwise.
- Report a test, build or fix as passing only after running it in the same task; quote the command and its result.
