# mineplus_generic_display (Blockbench plugin)

Edits the `display` block of a `.bbmodel` directly, context by context, so
held/GUI/ground poses are authored rather than derived. The plugin writes the
values into the model file; the Mineplus pack compiler copies them verbatim
into the resource pack (see `docs/display-slot-mapping.md`).

## Install

1. Copy this folder (or a zip of it) into your Blockbench plugins directory:
   - Windows: `%appdata%/../.blockbench/plugins/`
   - macOS: `~/.blockbench/plugins/`
   - Linux: `~/.blockbench/plugins/`
2. Restart Blockbench, open **File › Plugins**, enable
   **Mineplus Generic Display**.
3. Open a `.bbmodel`, then run **Tools › Mineplus Display Editor**.

## Use

- Pick a context (`firstperson_righthand`, `thirdperson_lefthand`, `head`,
  `gui`, `ground`, `fixed`, …) and edit rotation / translation / scale
  numerically, or nudge with the buttons.
- **Apply** writes the value into the model's `display` object immediately
  (visible in the bbmodel JSON — no hidden state).
- **Clear context** removes that context from the `display` block so vanilla
  defaults apply again.
- Values outside the validated ranges (rotation ±180°, translation ±80,
  scale 0.01–100) are clamped on write to match the bake-time validation.

## Source

`mineplus_generic_display.js` is a single-file Blockbench plugin, no build
step required.
