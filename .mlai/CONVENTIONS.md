# Conventions

## Blockbench plugin: the open project is `Project`, never `Model`
- Blockbench exposes the active project as the global `Project` (mirrored on `Blockbench.Project`, which is `0` when nothing is open). There is no `Model` project global — code gating on `typeof Model !== 'undefined' && Model.mesh` silently no-ops in the real app while passing the mocked test sandbox.
- Anything project-dependent must go through `currentModel()` in `devtools/studio/generic-display-plugin/mineplus_generic_display.js`, which checks `Project` → `Blockbench.Project` → legacy `Model`, requiring a `uuid`.
- Format switches on an open project prefer the native `ModelFormat.convertTo()` (migrates box UV, bone-rig grouping, rotation limits); fall back to `fmt.select()` — never a bare `model.format = fmt`, which leaves the global `Format` (and the Display/Animate tabs) stale.
- The test sandbox (`test/plugin.test.js`) mirrors the real globals: `Project` only, no `Model`.

## Blockbench 5.x dialogs: `dialog.form` is an InputForm, not a field map
- In Blockbench 5.x `dialog.form` is an `InputForm` object (js/interface/form.ts); the raw `{key: value}` result is `form.getResult()`. Reading `form.someField.value` is `undefined` and throws "Cannot read properties of undefined (reading 'value')" in `onButton`.
- Plugin dialogs read values through the `formValues()` helper in `mineplus_generic_display.js` (getResult() with a legacy raw-map fallback). The test sandbox's `Dialog` stub mirrors the InputForm shape.

## Display-mode panel events and repair
- Mode switches dispatch `select_mode` (js/modes.ts:139); `display_mode_entered` is NOT a real Blockbench event. Panel-repair logic hooks `select_mode` and checks `data.mode.id === 'display'`.
- The Display tab's slot list and sliders are filled by `DisplayMode.load(slot)` writing `DisplayMode.vue._data.slot` (js/display_mode/display_mode.js, loadDisp); `DisplayMode.vue` is bound at panel mount from `Interface.Panels.display.inside_vue`. If that chain misses, the panel opens empty — the plugin's `repairDisplayPanel()` re-binds the vue and re-runs `DisplayMode.load()`.

## Web build plugin loading (.bbweb, git-ignored)
- The user opens `.bbweb/index.html` (the production page). It had NO plugin loader — only `index_debug.html` loads the plugin. A plugin "fix" is invisible to the user unless index.html loads it. index.html now has a loader: poll until `Blockbench.setup_successful && Interface.Panels.display`, then `fetch('/mineplus_generic_display.js')` → `new Plugin(...)` → `plugin.loadFromFile({path, name, content}, true)`.
- `plugin.loadFromFile(.., true)` calls `window.confirm()`; headless Playwright auto-dismisses it so onload never runs (looks like success). The index.html loader auto-accepts ONLY around the loadFromFile call (save/restore `window.confirm`), so the factory-reset confirm at index.html:57 stays intact. Do not set a global `window.confirm = () => true` on index.html.
- Plugin load markers to verify in the real app: start-screen New list shows "Mineplus Model", Tools menu shows "Mineplus: Convert to Mineplus Model". Top menu bar points are `li.menu_bar_point`; the Tools menu is `BarMenu('tools', ...)` (js/interface/menu_bar.ts:503).

## Guards (added after a mistake repeated)
- Before finishing, stop the servers and background commands you started; long-lived work goes in a job or the preview.
- Report a test, build or fix as passing only after running it in the same task; quote the command and its result, and write "Not verified:" otherwise.
- Before calling UI work done, render it in the browser at 390px and 1280px and check overflow, clipped text, overlap and contrast.
