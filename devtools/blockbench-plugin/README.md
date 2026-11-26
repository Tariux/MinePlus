# Mineplus Generic Display (Blockbench plugin) — v6.2.0

Author, animate and validate Mine Plus models: edit a model's `display`
block, animate it in Blockbench's Animate tab, and save in the Mineplus-native
`.mpmodel` format (or plain `.bbmodel` — both stay fully loadable). Every step
logs to the browser console and to the in-app log panel, so a failed step or a
failed save is always traceable.

## Tools menu

All Mineplus entries live under **Tools** (four of them: Convert to Mineplus
Model, Open & Convert Old Model…, Copy Display Settings, Paste Display
Settings).

| Tool | What it does |
|---|---|
| Mineplus: Toggle Tools | show/hide all other Mineplus tools |
| Mineplus: Convert to Mineplus Model | migrate the open model (old bbmodel, java_block, free, …) to the mineplus format; cubes, bones, textures, display and animations are all kept |
| Mineplus: Open & Migrate Old Model… | pick a `.bbmodel` / `.json` / `.mpmodel` file, open it and convert it in one step |
| Mineplus: Copy Display Settings | copy the open model's complete Display tab settings (every slot: rotation, translation, scale, pivots, mirror) to memory + clipboard, as marked JSON |
| Mineplus: Paste Display Settings | apply the copied settings to the open model slot by slot through the built-in `DisplaySlot.extend()` path — values land exactly as copied, with undo (Ctrl+Z) |

(The simplified v6 plugin has only the four tools above; the v5.x extras —
Display Editor, validators, template tools, reports, exports, log panel,
Studio — were removed on purpose.)

## Opening old models / animations

Opening any `.bbmodel`, `.json` or `.mpmodel` (via File > Open Model or the
plugin's open tools) auto-switches the project to the mineplus format after
load, so **both** the Animate tab and the display editor are available — no
manual step. If a load ever comes in without them (log says so), run
**Mineplus: Convert to Mineplus Model**: it only changes the project's format
stamp and never touches cubes, bones, textures, the display block or
animations; it verifies afterwards that no content count dropped and logs the
before/after numbers.

## Getting both tabs (Display + Animate)

The Animate and Display tabs follow Blockbench's *global* format
(`Format.animation_mode` / `Format.display_mode`). Old `.bbmodel` files load
in the generic "free" format, which sets `animation_mode` but **not**
`display_mode` — that is exactly the "Animate works, Display never appears"
bug. v5.0 fixes it for good:

- After every load the plugin auto-applies the mineplus format (which sets
  both flags) and re-selects the *global* format (`Format.select()`, with
  codec and direct fallbacks, all logged).
- After every conversion the plugin **verifies** the global format actually
  landed on mineplus with both tab flags on; if the native
  `ModelFormat.convertTo()` did not land, it falls back to select()
  automatically and the log names the exact step.

If anything still fails, the `[Mineplus]` log tells you which step; run
**Mineplus: Convert to Mineplus Model** to retry — it only changes the
project's format stamp and never touches cubes, bones, textures, the display
block or animations, and it verifies afterwards that no content count dropped
(logs the before/after numbers).

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
toggle, the format registration and the old-model conversion. The sandbox uses
Blockbench's real globals — the open project is `Project` (`Blockbench.Project`),
there is no `Model` global — which is exactly what v4.1 got wrong: every
convert/display tool required a nonexistent `Model` global and silently did
nothing on old models. Conversion prefers Blockbench's native
`ModelFormat.convertTo()` (it also migrates box UV, bone-rig grouping and
rotation limits), with the plugin's own select-based path as fallback.

```
node test/plugin.test.js
```

## Logging

Every registration, conversion, save, validation and hook logs with the
`[Mineplus]` prefix (console + Show Log panel). Reload the plugin to re-run
onload with fresh logs; each onload step is isolated, so one failed step
cannot stop the others from registering.
