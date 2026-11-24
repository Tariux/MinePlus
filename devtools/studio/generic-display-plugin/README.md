# mineplus_generic_display (Blockbench plugin) — v2

Author and validate a model's `display` block, and save models in the
Mineplus-native `.mpmodel` format. Every step logs to the browser console
(`[Mineplus]` prefix) and to an in-app log panel, so a missing menu entry or a
failed save is always traceable.

## Install

1. Copy this folder (or a zip of it) into your Blockbench plugins directory:
   - Windows: `%appdata%/../.blockbench/plugins/`
   - macOS: `~/.blockbench/plugins/`
   - Linux: `~/.blockbench/plugins/`
2. Restart Blockbench, open **File › Plugins**, enable
   **Mineplus Generic Display**.
3. Open **Tools** — the Mineplus entries appear at the bottom of the menu
   (they are added with `MenuBar.addAction(action, 'tools')`, which is the
   only way an action lands in that menu). If anything is missing, open
   **Tools › Mineplus: Show Log** — every registration step is logged there.

## Tools

| Menu entry | What it does |
|---|---|
| Mineplus Display Editor | Edit rotation / translation / scale per vanilla context (`firstperson_righthand`, `gui`, `ground`, `head`, …). Values are clamped to the server's validated ranges on write. |
| Mineplus: Copy Display | Copy the current `display` block as pretty JSON (paste into a meta file or issue). |
| Mineplus: Validate Display | Report NaN / out-of-range / collapsing-scale values before they reach the bake. |
| Mineplus: Save as .mpmodel | Save the open model in the Mineplus format (below); validates first. |
| Mineplus: Show Log | In-app panel with the last 400 log lines. |

## Event hooks (all logged, exception-safe)

- `display_mode_entered` / `display_mode_exited` — logs when you enter
  Blockbench's display editor and dumps the display block on exit.
- `project_saved` — runs **Validate Display** automatically after every save.
- `oninstall` / `onenabled` / `ondisabled` / `onunload` lifecycle logging.

## The .mpmodel format

Registered as the Blockbench format **Mineplus Model** (`mineplus_model`,
extension `mpmodel`, visible in File › New and File › Save As). An `.mpmodel`
file is a standard bbmodel JSON plus an optional top-level metadata block:

```json
{
  "meta": { "model_format": "mineplus_model", ... },
  "mineplus": {
    "format": 1,
    "plugin": "mineplus_generic_display"
  },
  "elements": [ ... ],
  "display": { ... }
}
```

Full compatibility: the Mineplus server plugin's streaming importer
(`BbModelImporter`) skips unknown keys, so `.mpmodel` is parsed by exactly the
same code path as `.bbmodel` — the coordinator accepts either extension for
models, sidecars (`.display.mpmodel`, `.anim.mpmodel`) and meta-declared
files. Save as `.mpmodel` when the model is Mineplus-only; keep `.bbmodel`
when you also want vanilla Blockbench round-trips.

## Server side

`ModelImportCoordinator` / `VirtualBlockManager` accept `.mpmodel` everywhere
`.bbmodel` was accepted: model files, display sidecars, animation sidecars and
`displayFile` / `animationFile` meta declarations (a bare name in a meta still
defaults to `.bbmodel`).
