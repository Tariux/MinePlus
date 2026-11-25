# Decisions

## Display defaults via Blockbench's built-in item preset (v6.1.0, 2026-11-25)
- The Java-item default display values are NOT hardcoded in the plugin. `applyJavaItemDisplayDefaults()` finds the fixed `item` entry in Blockbench's own `display_presets` (js/display_mode/display_mode.js) and calls `DisplayMode.applyPreset(preset, true)` — the exact mechanism of the default Java Block/Item template.
- It only runs when `Project.display_settings` is empty/absent, so authored display data is never overwritten.
- Order inside applyMineplusFormat success path: format switch → applyJavaItemDisplayDefaults() → repairDisplayPanel().
- The plugin copy the web app loads is `.bbweb/mineplus_generic_display.js`; it must be re-copied after every source change (git-ignored folder, md5 must match the tracked source).

## Mineplus Studio reverted (v5.0, 2026-11-24)
- The "Mineplus: Studio" tool (custom launcher that entered display mode and forwarded panels) was deleted — designers found it confusing; the plugin offers no Studio entry. Do not reintroduce it.
- Tab visibility rule (verified in Blockbench source): the Animate/Display tabs follow the page-scope global `Format.animation_mode` / `Format.display_mode`. The generic "free" format (js/formats/generic.ts) sets `animation_mode` but NOT `display_mode` — old bbmodel files therefore show Animate but never Display until converted.
- `applyMineplusFormat` must always try native `fmt.convertTo()` (no `Blockbench.Format` precondition — the old guard skipped it on fresh state), then verify the global `Format` landed on mineplus with both flags; fall back to `fmt.select()` if not, logging each step.

## Plugin simplified to two tools (v6.0.0, 2026-11-24)
- User directive: the plugin does ONE job — convert (or create) a model so the Animate and Display tabs both work, using only Blockbench's default UI. All v5.x extras were deleted on purpose and must NOT come back: display editor dialog, Copy Display, Validate Display/Model, Template Settings, Check Template, Save as .mpmodel/.bbmodel actions, Open Generic dialog, Fix Duplicates, Model Report, Show Log, Toggle Tools, and the `mineplus` ModelProject metadata stamp. test/plugin.test.js pins each removal.
- Kept surface: registerFormat (`mineplus_model` .mpmodel, animation_mode + display_mode, bound to Codecs.project), Convert to Mineplus Model, Open & Convert Old Model, auto-apply on load_project/project_loaded/select_project, and repairDisplayPanel (enter display mode, bind vue, load slot) on select_mode.
- Display adjustments are Blockbench's default Display panel with vanilla defaults — the plugin never writes display values itself.
