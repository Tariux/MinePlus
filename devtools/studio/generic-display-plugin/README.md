# mineplus_generic_display (Blockbench plugin) — v4.0

Author, animate and validate Mine Plus models: edit a model's `display`
block, animate it in Blockbench's Animate tab, and save in the Mineplus-native
`.mpmodel` format (or plain `.bbmodel` — both stay fully loadable). Every step
logs to the browser console and to the in-app log panel, so a failed step or a
failed save is always traceable.

## Tools menu

All Mineplus entries live under **Tools**. **Mineplus: Toggle Tools** shows or
hides every other Mineplus entry (the state is remembered across sessions, and
the toggle itself stays visible so you can always switch back). Useful when a
designer is working in a plain Blockbench model and does not want the extra
menu noise — toggle Mineplus tools on whenever display/animation work starts.

| Tool | What it does |
|---|---|
| Mineplus: Toggle Tools | show/hide all other Mineplus tools |
| Mineplus: Convert to Mineplus Model | migrate the open model (old bbmodel, java_block, free, …) to the mineplus format; cubes, bones, textures, display and animations are all kept |
| Mineplus: Open & Migrate Old Model… | pick a `.bbmodel` / `.json` / `.mpmodel` file, open it and convert it in one step |
| Mineplus Display Editor | edit rotation/translation/scale per display context |
| Mineplus: Copy Display | copy the display block as JSON |
| Mineplus: Validate Display | display-only validation (clamps match the server) |
| Mineplus: Validate Model | full compatibility check: budgets, 22.5° angles, textures, animations |
| Mineplus: Template Settings | edit the `mineplus` metadata stamp |
| Mineplus: Check Template | is this file detected as a Mine Plus template? |
| Mineplus: Save as .mpmodel | save in the Mineplus format |
| Mineplus: Export as .bbmodel | save a plain bbmodel (also fully loadable) |
| Mineplus: Open Generic / Java Item Model | start a vanilla Java item project or open any model file |
| Mineplus: Studio (Display + Animate) | one click: enables both tabs, enters display mode, brings the default Display + Animations panels forward |
| Mineplus: Fix Duplicates | remove UUID-duplicated cubes/groups, rename same-name elements |
| Mineplus: Model Report | cubes/bones/textures/animations summary |
| Mineplus: Show Log | in-app log panel (last 400 lines) |

## Opening old models / animations

Opening any `.bbmodel`, `.json` or `.mpmodel` (via File > Open Model or the
plugin's open tools) auto-switches the project to the mineplus format after
load, so **both** the Animate tab and the display editor are available — no
manual step. If a load ever comes in without them (log says so), run
**Mineplus: Convert to Mineplus Model**: it only changes the project's format
stamp and never touches cubes, bones, textures, the display block or
animations; it verifies afterwards that no content count dropped and logs the
before/after numbers.

## Mineplus Studio

**Tools > Mineplus: Studio (Display + Animate)** does not build any custom
GUI — it wires up Blockbench's own tools in one click:

- makes sure the project is on the mineplus format, so **both** the Display
  and Animate tabs exist,
- enters **display mode**, so the viewport previews the authored transforms
  the way Minecraft renders the item,
- brings the default **Display panel** (and the **Animations** panel) forward
  in the sidebar.

From there you edit display transforms with the standard Display panel and
create/play animations with the standard Animate timeline — exactly the tools
you already know, nothing new to learn.

Opening old animated models: since v4.0 the auto-apply after load also
re-selects the *global* format (via `Format.select()`, with codec and direct
fallbacks, all logged) — the Display/Animate tabs follow the global format, so
reopened models now always get both tabs, even when `model.format` was already
mineplus. If anything still fails the log names the exact step.

## The `.mpmodel` format

- A `.bbmodel` file (zipped project JSON) with extension `.mpmodel`, plus the
  top-level `mineplus` metadata stamp — registered as a real Blockbench format
  bound to the built-in project codec, so File > Open/Save handles it natively.
- Saves/loads animations and display settings exactly like bbmodel does.
- The server side (`BbModelImporter`, `MineplusTemplate`) reads both
  `.bbmodel` and `.mpmodel`.

## Event hooks (all logged, exception-safe)

- `display_mode_entered` / `display_mode_exited` — log the display block
- `project_saved` — validate the display after each save
- `merge_project`, `edit_texture` — debug trail for the reported
  texture-change duplication: both log cube/bone counts, so a log shows
  exactly which step introduced copies
- `load_project` / `project_loaded` — restore the template stamp, log
  animation/display counts, auto-apply the mineplus format

## Install

Copy `mineplus_generic_display.js` into your Blockbench plugins folder —
`~/.blockbench/plugins/` on Linux/macOS or
`%appdata%/../.blockbench/plugins/` on Windows — restart Blockbench, enable
**Mineplus Generic Display** under File > Plugins, then use the **Tools** menu.

## Tests

Headless checks (no Blockbench needed) live in `test/plugin.test.js`; they load
the real plugin in a sandboxed stub of the Blockbench API and exercise the
toggle, the format registration and the old-model conversion:

```
node test/plugin.test.js
```

## Logging

Every registration, conversion, save, validation and hook logs with the
`[Mineplus]` prefix (console + Show Log panel). Reload the plugin to re-run
onload with fresh logs; each onload step is isolated, so one failed step
cannot stop the others from registering.
